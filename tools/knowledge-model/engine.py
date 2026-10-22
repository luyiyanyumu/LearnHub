"""Real CUDA QLoRA training and parameter-only inference; no retrieval or cloud APIs."""
from __future__ import annotations

import gc
import json
import os
import random
import time
from dataclasses import asdict, dataclass
from pathlib import Path

from data import (SYSTEM, build_samples, evaluation_cases, gate, is_abstention,
                  refusal_cases, score_answer, summarize_eval)


@dataclass
class Config:
    model_path: str = os.environ.get("KNOWLEDGE_MODEL_BASE_MODEL", "Qwen/Qwen2.5-1.5B-Instruct")
    max_length: int = 512
    max_steps: int = 80
    accumulation: int = 4
    learning_rate: float = 0.0003
    lora_rank: int = 16
    max_samples: int = 256
    replay_samples: int = 128
    eval_max_tokens: int = 48
    new_accuracy_min: float = 0.8
    old_drop_max: float = 0.1
    old_accuracy_min: float = 0.0
    unknown_accuracy_min: float = 0.9
    known_abstention_max: float = 0.1
    seed: int = 42


def write_json(path, value):
    path = Path(path)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(path)


def attempt_directory(root, job):
    directory = Path(root) / "versions" / job["id"]
    return directory / f"attempt-{job['attempt']}" if job.get("attempt", 1) > 1 else directory


class Engine:
    def __init__(self, store, config):
        self.store, self.config = store, config
        self.model = self.tokenizer = None
        self.loaded_version = None
        self.progress = {"phase": "idle"}

    def unload(self):
        self.model = self.tokenizer = None
        self.loaded_version = None
        gc.collect()
        import sys
        if "torch" in sys.modules:
            torch = sys.modules["torch"]
            if torch.cuda.is_available():
                torch.cuda.empty_cache()

    def _load(self, adapter=None, trainable=False):
        import torch
        from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig
        from peft import LoraConfig, PeftModel, get_peft_model, prepare_model_for_kbit_training

        if not torch.cuda.is_available():
            raise RuntimeError("CUDA PyTorch is required for this QLoRA validation; CPU fallback is disabled")
        self.unload()
        torch.manual_seed(self.config.seed)
        random.seed(self.config.seed)
        dtype = torch.bfloat16 if torch.cuda.is_bf16_supported() else torch.float16
        # A local model directory never falls back to a cloud inference provider.
        source = self.config.model_path
        local_only = Path(source).is_dir()
        self.tokenizer = AutoTokenizer.from_pretrained(source, local_files_only=local_only,
                                                       trust_remote_code=False)
        if self.tokenizer.pad_token_id is None:
            self.tokenizer.pad_token = self.tokenizer.eos_token
        self.model = AutoModelForCausalLM.from_pretrained(source, trust_remote_code=False,
            local_files_only=local_only, device_map={"": 0}, torch_dtype=dtype,
            quantization_config=BitsAndBytesConfig(load_in_4bit=True,
                bnb_4bit_quant_type="nf4", bnb_4bit_use_double_quant=True,
                bnb_4bit_compute_dtype=dtype), attn_implementation="sdpa")
        if trainable:
            self.model = prepare_model_for_kbit_training(self.model,
                use_gradient_checkpointing=True,
                gradient_checkpointing_kwargs={"use_reentrant": False})
        if adapter:
            self.model = PeftModel.from_pretrained(self.model, adapter,
                                                  is_trainable=trainable)
        elif trainable:
            self.model = get_peft_model(self.model, LoraConfig(
                r=self.config.lora_rank, lora_alpha=self.config.lora_rank * 2,
                lora_dropout=0.0, bias="none", task_type="CAUSAL_LM",
                target_modules=["q_proj", "k_proj", "v_proj", "o_proj",
                                "gate_proj", "up_proj", "down_proj"]))
        self.model.config.use_cache = not trainable
        self.model.eval()

    def _answer(self, question, max_tokens=48, system=SYSTEM):
        import torch
        prompt = self.tokenizer.apply_chat_template([
            {"role": "system", "content": system},
            {"role": "user", "content": question}], tokenize=False, add_generation_prompt=True)
        inputs = self.tokenizer(prompt, return_tensors="pt").to("cuda")
        if inputs["input_ids"].shape[1] > self.config.max_length * 2:
            raise ValueError("Question is too long for this local validation model")
        self.model.eval()
        with torch.inference_mode():
            output = self.model.generate(**inputs, max_new_tokens=max_tokens,
                do_sample=False, use_cache=True, pad_token_id=self.tokenizer.pad_token_id,
                eos_token_id=self.tokenizer.eos_token_id)
        answer = self.tokenizer.decode(output[0, inputs["input_ids"].shape[1]:],
                                       skip_special_tokens=True).strip()
        return answer

    def query(self, question, max_tokens=128):
        active = self.store.active()
        version = active["version"] if active else "base"
        if self.model is None or self.loaded_version != version:
            self.progress = {"phase": "loading_inference", "version": version}
            self._load(active["adapter_path"] if active else None)
            self.loaded_version = version
        try:
            answer = self._answer(question, max_tokens)
            return {"answer": answer, "abstained": is_abstention(answer),
                    "abstention_validated": bool(active and active.get("abstention_policy") == "v1"),
                    "model_version": version, "knowledge_source": "model_parameters",
                    "retrieval_used": False}
        finally:
            self.progress = {"phase": "idle"}

    def _evaluate(self, cases, phase):
        rows = []
        self.progress = {"phase": phase, "completed": 0, "total": len(cases)}
        for case in cases:
            answer = self._answer(case["question"], self.config.eval_max_tokens)
            rows.append({**case, "response": answer,
                "correct": score_answer(answer, case["answer"], case.get("aliases", []))})
            self.progress["completed"] = len(rows)
        return {"summary": summarize_eval(rows), "cases": rows}

    def _train(self, samples, directory, job_id):
        """Train one adapter and save it; keep no optimizer or loss graph afterward."""
        import torch
        config = self.config
        self.model.train()
        self.model.config.use_cache = False
        parameters = [p for p in self.model.parameters() if p.requires_grad]
        if not parameters:
            raise RuntimeError("No trainable adapter parameters")
        optimizer = torch.optim.AdamW(parameters, lr=config.learning_rate, weight_decay=0.01)
        use_fp16 = not torch.cuda.is_bf16_supported()
        scaler = torch.amp.GradScaler("cuda", enabled=use_fp16)
        dtype = torch.float16 if use_fp16 else torch.bfloat16
        optimizer.zero_grad(set_to_none=True)
        losses = []
        randomizer = random.Random(config.seed)
        order = list(range(len(samples)))
        randomizer.shuffle(order)
        cursor = 0
        torch.cuda.reset_peak_memory_stats()
        train_started = time.perf_counter()
        for step in range(config.max_steps):
            step_loss = 0.0
            for _ in range(config.accumulation):
                if cursor >= len(order):
                    randomizer.shuffle(order)
                    cursor = 0
                sample = samples[order[cursor]]
                cursor += 1
                batch = {key: torch.tensor([value], dtype=torch.long, device="cuda")
                         for key, value in sample.items()}
                batch["attention_mask"] = torch.ones_like(batch["input_ids"])
                with torch.autocast("cuda", dtype=dtype):
                    loss = self.model(**batch).loss / config.accumulation
                if not torch.isfinite(loss):
                    raise RuntimeError("Non-finite training loss; candidate will not be published")
                scaler.scale(loss).backward()
                step_loss += loss.item()
            scaler.unscale_(optimizer)
            torch.nn.utils.clip_grad_norm_(parameters, 1.0)
            scaler.step(optimizer)
            scaler.update()
            optimizer.zero_grad(set_to_none=True)
            losses.append(step_loss)
            self.progress = {"phase": "training", "job_id": job_id,
                "step": step + 1, "max_steps": config.max_steps, "loss": step_loss}
            if step == 0 or (step + 1) % 10 == 0:
                write_json(directory / "progress.json", self.progress)
                print(json.dumps(self.progress), flush=True)
        training_seconds = time.perf_counter() - train_started
        peak_vram = torch.cuda.max_memory_allocated() / 1024 ** 3
        del optimizer, scaler, parameters, loss, batch
        gc.collect()
        torch.cuda.empty_cache()
        adapter_path = directory / "adapter"
        self.model.save_pretrained(adapter_path, safe_serialization=True)
        self.tokenizer.save_pretrained(adapter_path)
        return {"training_seconds": training_seconds, "peak_allocated_vram_gib": peak_vram,
                "steps": config.max_steps, "loss_first": losses[0], "loss_last": losses[-1]}

    def run(self, job):
        """Candidate artifacts and evaluations are durable before atomic publication."""
        config = self.config
        corpus = self.store.corpus(job["seq"])
        active = self.store.active()
        if active and active.get("base_model") != config.model_path:
            raise ValueError("Active adapter belongs to another base model; use a separate data directory")
        directory = attempt_directory(self.store.root, job)
        directory.mkdir(parents=True, exist_ok=True)
        write_json(directory / "training-corpus.json", corpus)
        write_json(directory / "config.json", asdict(config))
        cases = evaluation_cases(corpus, job["source_id"])
        write_json(directory / "abstention-bank.json", {
            "training": refusal_cases(corpus, "train"),
            "evaluation": [case for case in cases if case["group"] == "unknown"],
            "limits": "Held-out probes measure a finite knowledge boundary; not a universal guarantee."})
        tested_sources = {case["source_id"] for case in cases if case["group"] in ("new", "old")}
        source_coverage = {"source_count": len(corpus), "tested_source_count": len(tested_sources),
            "untested_source_ids": [doc["source_id"] for doc in corpus
                                    if doc["source_id"] not in tested_sources]}
        started = time.perf_counter()
        self.progress = {"phase": "loading_baseline", "job_id": job["id"]}
        try:
            parent_adapter = active["adapter_path"] if active else None
            self._load(parent_adapter, trainable=False)
            baseline = self._evaluate(cases, "baseline_evaluation")
            baseline["summary"]["document_coverage"] = source_coverage
            write_json(directory / "baseline-evaluation.json", baseline)
            self.progress = {"phase": "loading_training", "job_id": job["id"]}
            self._load(parent_adapter, trainable=True)
            samples, coverage = build_samples(self.tokenizer, corpus, job["source_id"],
                max_length=config.max_length, max_samples=config.max_samples,
                replay_samples=config.replay_samples, seed=config.seed)
            coverage["documents"] = len(corpus)
            coverage["documents_with_testable_facts"] = sum(
                bool(doc["payload"].get("facts")) for doc in corpus)
            coverage["untested_source_ids"] = source_coverage["untested_source_ids"]
            coverage["evaluation_scope"] = job["payload"].get("evaluation_scope", "explicit_statements_only")
            write_json(directory / "coverage.json", coverage)
            training = self._train(samples, directory, job["id"])
            adapter_path = directory / "adapter"
            self.progress = {"phase": "loading_candidate", "job_id": job["id"]}
            # _load releases the previous instance first. The artifact is evaluated
            # with exactly the non-trainable load path later used by query().
            self._load(str(adapter_path.resolve()), trainable=False)
            artifact_verification = {"reloaded_from_disk": True,
                "adapter_path": str(adapter_path.resolve()), "trainable": False,
                "load_path": "same_as_query", "baseline_load_path": "same_as_query"}
            write_json(directory / "artifact-verification.json", artifact_verification)
            candidate = self._evaluate(cases, "candidate_evaluation")
            candidate["summary"]["document_coverage"] = source_coverage
            write_json(directory / "candidate-evaluation.json", candidate)
            status, reasons = gate(baseline["summary"], candidate["summary"],
                config.new_accuracy_min, config.old_drop_max,
                config.unknown_accuracy_min, config.known_abstention_max, config.old_accuracy_min)
            version = job["id"] + (f"-attempt-{job['attempt']}" if job.get("attempt", 1) > 1 else "")
            result = {"version": version, "attempt": job.get("attempt", 1),
                "parent_version": active["version"] if active else "base",
                "base_model": config.model_path, "adapter_path": str(adapter_path.resolve()),
                "status": status, "reasons": reasons, "baseline": baseline["summary"],
                "candidate": candidate["summary"], "coverage": coverage,
                "abstention_policy": "v1",
                "thresholds": {"new_accuracy_min": config.new_accuracy_min,
                    "old_drop_max": config.old_drop_max,
                    "old_accuracy_min": config.old_accuracy_min,
                    "unknown_accuracy_min": config.unknown_accuracy_min,
                    "known_abstention_max": config.known_abstention_max},
                **training, "elapsed_seconds": time.perf_counter() - started,
                "artifact_verification": artifact_verification,
                "evaluation_report": str((directory / "candidate-evaluation.json").resolve()),
                "limits": ["Scores cover only the explicitly tested facts.",
                    "Parameter training does not guarantee complete or accurate recall.",
                    "No retrieval or document context is supplied to evaluation or inference.",
                    "Abstention scores do not guarantee rejection of every unsupported question.",
                    "Publishing does not validate retention for sources listed in untested_source_ids."]}
            write_json(directory / "result.json", result)
            self.store.finish(job["id"], status, result, publish=result if status == "published" else None)
            return result
        finally:
            self.unload()
            self.progress = {"phase": "idle"}
