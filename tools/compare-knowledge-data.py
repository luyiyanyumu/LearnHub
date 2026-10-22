"""Controlled local data-only A/B experiment. Never creates a Store or publishes.

Private inputs and outputs belong under ignored workspace/output. Prepare reads
development cases only; evaluate-only opens its cases after both arms complete.
"""
from __future__ import annotations

import argparse
from dataclasses import asdict, fields
import hashlib
import json
from pathlib import Path
import socket
import sqlite3
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
LIB = ROOT / "tools" / "knowledge-model"
sys.path.insert(0, str(LIB))
from data import SYSTEM, build_samples, is_abstention, normalize, refusal_cases, summarize_eval
from engine import Config, Engine, write_json
from store import ProcessLock


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                    separators=(",", ":")).encode()).hexdigest()


def file_hash(path):
    hasher = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(block)
    return hasher.hexdigest()


def tree_hash(path):
    path = Path(path)
    if not path.is_dir():
        raise ValueError("A local model/adapter directory is required")
    return {str(item.relative_to(path)): file_hash(item)
            for item in sorted(path.rglob("*")) if item.is_file()}


def private_path(path):
    resolved = Path(path).resolve()
    if not resolved.is_relative_to((ROOT / "output").resolve()):
        raise ValueError("Private experiment files must remain inside workspace/output")
    return resolved


def runtime_snapshot(runtime):
    """Hash logical values; WAL/SHM bytes can change for read-only connections."""
    path = Path(runtime) / "state.sqlite3"
    if not path.is_file():
        raise ValueError("Original runtime database is missing")
    connection = sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True)
    try:
        settings = connection.execute("SELECT key,value FROM settings ORDER BY key").fetchall()
        jobs = connection.execute("SELECT * FROM jobs ORDER BY seq").fetchall()
        active = next((value for key, value in settings if key == "active"), None)
        return {"active_value_sha256": digest(active), "settings_sha256": digest(settings),
                "jobs_sha256": digest(jobs), "job_count": len(jobs)}
    finally:
        connection.close()


def invariant_corpus(corpus):
    copied = json.loads(json.dumps(corpus))
    for doc in copied:
        payload = doc["payload"]
        for fact in payload.get("facts", []):
            fact.pop("training_questions", None)
        if payload.get("abstention") is not None:
            payload["abstention"].pop("training_questions", None)
    return copied


def load_cases(path):
    value = read(path)
    cases = value["cases"] if isinstance(value, dict) else value
    if not isinstance(cases, list) or not cases:
        raise ValueError("Cases must be a nonempty list")
    identities = set()
    for case in cases:
        identity = tuple(case[key] for key in ("source_id", "fact_id", "question"))
        if identity in identities or case["group"] not in ("new", "old", "unknown"):
            raise ValueError("Duplicate case identity or invalid case group")
        identities.add(identity)
        if (case["group"] == "unknown") != is_abstention(case["answer"]):
            raise ValueError("Unknown/known group contradicts answer")
        if "response" in case or "correct" in case:
            raise ValueError("Case inputs must not contain model responses or scores")
    return cases


def assert_isolated(corpus, cases):
    questions = [question for doc in corpus for fact in doc["payload"].get("facts", [])
                 for question in fact["training_questions"]]
    questions += [case["question"] for case in refusal_cases(corpus, "train")]
    normalized_train = [normalize(question) for question in questions]
    raw = [normalize(doc["payload"]["text"]) for doc in corpus]
    for case in cases:
        evaluation = normalize(case["question"])
        if not evaluation or any(evaluation in train or train in evaluation for train in normalized_train):
            raise ValueError("Evaluation question overlaps a training question")
        if any(evaluation in text for text in raw):
            raise ValueError("Evaluation question occurs in raw training text")


def sample_budget(samples, coverage):
    positive = coverage["abstention_training"]["positive_qa"]
    unknown = coverage["selected_samples"]["unknown"]
    return {"positive_qa": positive, "unknown_qa": unknown,
            "raw_clm": len(samples) - positive - unknown,
            "new": coverage["selected_samples"]["new"],
            "old": coverage["selected_samples"]["old"], "total": len(samples)}


def prepare(args, tokenizer=None):
    output = private_path(args.output_dir)
    if output.exists() and any(output.iterdir()):
        raise ValueError("Prepare requires a new or empty output directory")
    inputs = {name: private_path(getattr(args, name))
              for name in ("a_corpus", "b_corpus", "config", "cases")}
    config_value = read(inputs["config"])
    if set(config_value) != {field.name for field in fields(Config)}:
        raise ValueError("Config must contain the complete frozen Config dictionary")
    config = Config(**config_value)
    if not Path(config.model_path).is_dir():
        raise ValueError("Use the existing local base model directory")
    corpora = {arm: read(inputs[f"{arm}_corpus"]) for arm in ("a", "b")}
    if invariant_corpus(corpora["a"]) != invariant_corpus(corpora["b"]):
        raise ValueError("Only positive and reviewed-negative training questions may differ")
    cases = load_cases(inputs["cases"])
    for corpus in corpora.values():
        if args.current_source not in {doc["payload"]["source_id"] for doc in corpus}:
            raise ValueError("Current source missing from corpus")
        assert_isolated(corpus, cases)
    if tokenizer is None:
        from transformers import AutoTokenizer
        tokenizer = AutoTokenizer.from_pretrained(config.model_path,
            local_files_only=True, trust_remote_code=False)
    built = {}
    for arm, corpus in corpora.items():
        samples, coverage = build_samples(tokenizer, corpus, args.current_source,
            max_length=config.max_length, max_samples=config.max_samples,
            replay_samples=config.replay_samples, seed=config.seed)
        built[arm] = {"samples": samples, "coverage": coverage,
                      "budget": sample_budget(samples, coverage)}
    if built["a"]["budget"] != built["b"]["budget"]:
        raise ValueError("A/B sample budgets differ: " + json.dumps(
            {arm: value["budget"] for arm, value in built.items()}))
    raw_samples = lambda values: sorted(digest(sample) for sample in values
                                       if sample["input_ids"] == sample["labels"])
    if raw_samples(built["a"]["samples"]) != raw_samples(built["b"]["samples"]):
        raise ValueError("Raw CLM samples changed between arms")
    parent = private_path(args.parent_adapter)
    runtime = private_path(args.runtime)
    if not (parent / "adapter_config.json").is_file() or not (parent / "adapter_model.safetensors").is_file():
        raise ValueError("Saved parent adapter is incomplete")
    output.mkdir(parents=True, exist_ok=True)
    for arm, value in built.items():
        directory = output / arm
        directory.mkdir()
        write_json(directory / "samples.json", value["samples"])
        write_json(directory / "coverage.json", value["coverage"])
    write_json(output / "development-cases.json", cases)
    manifest = {"schema": 1, "status": "prepared", "created_at": time.time(),
        "config": asdict(config), "current_source": args.current_source,
        "parent_version": args.parent_version, "parent_adapter": str(parent),
        "parent_hashes": tree_hash(parent), "model_hashes": tree_hash(config.model_path),
        "system_sha256": digest(SYSTEM), "runtime": str(runtime),
        "runtime_before": runtime_snapshot(runtime), "service_port": args.service_port,
        "input_paths": {name: str(path) for name, path in inputs.items()},
        "input_hashes": {name: file_hash(path) for name, path in inputs.items()},
        "source_hashes": {name: file_hash(LIB / name) for name in ("data.py", "engine.py", "store.py")},
        "runner_sha256": file_hash(__file__), "development_cases_sha256": digest(cases),
        "arms": {arm: {"budget": value["budget"], "samples_sha256": digest(value["samples"]),
            "selected_tokens": value["coverage"]["selected_tokens"]} for arm, value in built.items()},
        "published": False, "blind_used_for_training_or_selection": False,
        "selection_policy": "Both independent arms are evaluated, regardless of development scores"}
    write_json(output / "protocol.json", manifest)
    print(json.dumps({"status": "prepared", "budget": built["a"]["budget"],
                      "output_dir": str(output)}), flush=True)
    return manifest


def verify_frozen(output, protocol):
    if file_hash(__file__) != protocol["runner_sha256"]:
        raise ValueError("Runner changed after preparation")
    for name, expected in protocol["source_hashes"].items():
        if file_hash(LIB / name) != expected:
            raise ValueError("Training source changed after preparation")
    for name, path in protocol["input_paths"].items():
        if file_hash(path) != protocol["input_hashes"][name]:
            raise ValueError("Frozen experiment input changed")
    if tree_hash(protocol["parent_adapter"]) != protocol["parent_hashes"]:
        raise ValueError("Parent adapter changed")
    if tree_hash(protocol["config"]["model_path"]) != protocol["model_hashes"]:
        raise ValueError("Base model changed")
    if digest(SYSTEM) != protocol["system_sha256"]:
        raise ValueError("System instruction changed")
    if digest(read(output / "development-cases.json")) != protocol["development_cases_sha256"]:
        raise ValueError("Development cases changed")
    for arm in ("a", "b"):
        if digest(read(output / arm / "samples.json")) != protocol["arms"][arm]["samples_sha256"]:
            raise ValueError("Tokenized training samples changed")
    if runtime_snapshot(protocol["runtime"]) != protocol["runtime_before"]:
        raise ValueError("Original runtime logical state changed")


def assert_service_stopped(port):
    with socket.socket() as connection:
        connection.settimeout(0.5)
        if connection.connect_ex(("127.0.0.1", port)) == 0:
            raise RuntimeError("Stop the 8766 knowledge-model service before the GPU experiment")


class DiskEngine(Engine):
    def __setattr__(self, name, value):
        super().__setattr__(name, value)
        if name == "progress" and getattr(self, "progress_path", None):
            write_json(self.progress_path, value)


def transitions(baseline, candidate):
    before = baseline["cases"]
    after = candidate["cases"]
    identity = lambda row: tuple(row[key] for key in ("source_id", "fact_id", "question"))
    if [identity(row) for row in before] != [identity(row) for row in after]:
        raise ValueError("Evaluation identities changed")
    result = {}
    for cohort in sorted({row.get("cohort", row["group"]) for row in before}):
        pairs = [(left, right) for left, right in zip(before, after)
                 if left.get("cohort", left["group"]) == cohort]
        result[cohort] = {"total": len(pairs),
            "baseline_correct": sum(left["correct"] for left, _ in pairs),
            "candidate_correct": sum(right["correct"] for _, right in pairs),
            "retained_correct": sum(left["correct"] and right["correct"] for left, right in pairs),
            "lost_correct": sum(left["correct"] and not right["correct"] for left, right in pairs),
            "gained_correct": sum(not left["correct"] and right["correct"] for left, right in pairs),
            "still_wrong": sum(not left["correct"] and not right["correct"] for left, right in pairs),
            "candidate_abstentions": sum(is_abstention(right["response"]) for _, right in pairs),
            "candidate_wrong_concrete_answers": sum(not right["correct"] and
                not is_abstention(right["response"]) for _, right in pairs)}
    return result


def run(args, engine_factory=DiskEngine):
    output = private_path(args.output_dir)
    protocol = read(output / "protocol.json")
    if protocol["status"] != "prepared":
        raise ValueError("Run requires an unexecuted prepared experiment")
    assert_service_stopped(protocol["service_port"])
    lock = ProcessLock(protocol["runtime"]).acquire()
    engine = None
    try:
        assert_service_stopped(protocol["service_port"])
        verify_frozen(output, protocol)
        protocol["status"] = "running"
        write_json(output / "protocol.json", protocol)
        config = Config(**protocol["config"])
        engine = engine_factory(None, config)
        cases = read(output / "development-cases.json")
        engine.progress_path = output / "progress.json"
        engine._load(protocol["parent_adapter"], trainable=False)
        baseline = engine._evaluate(cases, "development_parent")
        write_json(output / "parent-development.json", baseline)
        for arm in ("a", "b"):
            directory = output / arm
            engine.progress_path = directory / "progress.json"
            # Each arm starts from the same frozen parent, never the other arm.
            engine._load(protocol["parent_adapter"], trainable=True)
            samples = read(directory / "samples.json")
            if digest(samples) != protocol["arms"][arm]["samples_sha256"]:
                raise ValueError("Tokenized training samples changed before training")
            training = engine._train(samples, directory, "data-ab-" + arm)
            adapter = str((directory / "adapter").resolve())
            engine._load(adapter, trainable=False)
            evaluation = engine._evaluate(cases, "development_" + arm)
            write_json(directory / "development.json", evaluation)
            result = {"status": "completed", "parent_version": protocol["parent_version"],
                "parent_adapter": protocol["parent_adapter"], "adapter": adapter,
                "adapter_hashes": tree_hash(adapter), "training": training,
                "summary": evaluation["summary"], "transitions": transitions(baseline, evaluation),
                "reloaded_from_disk": True, "published": False}
            write_json(directory / "result.json", result)
            protocol["arms"][arm]["status"] = "completed"
            write_json(output / "protocol.json", protocol)
            print(json.dumps({"arm": arm, "status": "completed", "summary": result["summary"]}), flush=True)
        protocol["status"] = "completed"
        write_json(output / "protocol.json", protocol)
    except Exception:
        protocol["status"] = "failed"
        write_json(output / "protocol.json", protocol)
        raise
    finally:
        if engine is not None:
            engine.unload()
        try:
            after = runtime_snapshot(protocol["runtime"])
            write_json(output / "runtime-verification.json", {"before": protocol["runtime_before"],
                "after": after, "unchanged": after == protocol["runtime_before"]})
            if after != protocol["runtime_before"]:
                raise RuntimeError("Original runtime logical state changed")
        finally:
            lock.release()


def evaluate_only(args, engine_factory=DiskEngine):
    output = private_path(args.output_dir)
    protocol = read(output / "protocol.json")
    # Do not even open independent test questions before both training arms finish.
    if protocol["status"] != "completed" or any(
            protocol["arms"][arm].get("status") != "completed" for arm in ("a", "b")):
        raise ValueError("Both training arms must complete before independent evaluation")
    if not args.label.replace("-", "").replace("_", "").isalnum():
        raise ValueError("Use a short alphanumeric evaluation label")
    destination = output / (args.label + "-evaluation.json")
    cases_path = private_path(args.cases)
    if destination.exists() or destination == cases_path or destination.with_suffix(".json.tmp") == cases_path:
        raise ValueError("Evaluation output exists or would overwrite the input")
    assert_service_stopped(protocol["service_port"])
    lock = ProcessLock(protocol["runtime"]).acquire()
    engine = None
    try:
        assert_service_stopped(protocol["service_port"])
        verify_frozen(output, protocol)
        adapters = {"parent": protocol["parent_adapter"]}
        for arm in ("a", "b"):
            result = read(output / arm / "result.json")
            adapters[arm] = result["adapter"]
            if tree_hash(result["adapter"]) != result["adapter_hashes"]:
                raise ValueError("Trained adapter changed before independent evaluation")
        cases = load_cases(cases_path)
        report = {"label": args.label, "cases_sha256": digest(cases),
            "case_file_sha256": file_hash(cases_path), "case_count": len(cases),
            "retrieval_used": False, "published": False,
            "blind_used_for_training_or_selection": False, "models": {}}
        engine = engine_factory(None, Config(**protocol["config"]))
        engine.progress_path = output / (args.label + "-progress.json")
        for name, adapter in adapters.items():
            engine._load(adapter, trainable=False)
            report["models"][name] = engine._evaluate(cases, args.label + "_" + name)
            write_json(destination, report)
            print(json.dumps({"model": name, "label": args.label,
                              "summary": report["models"][name]["summary"]}), flush=True)
        report["transitions"] = {arm: transitions(report["models"]["parent"], report["models"][arm])
                                 for arm in ("a", "b")}
        report["status"] = "completed"
        write_json(destination, report)
        return report
    finally:
        if engine is not None:
            engine.unload()
        try:
            if runtime_snapshot(protocol["runtime"]) != protocol["runtime_before"]:
                raise RuntimeError("Original runtime logical state changed")
        finally:
            lock.release()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="action", required=True)
    preparation = subparsers.add_parser("prepare")
    for name in ("a-corpus", "b-corpus", "config", "cases", "runtime", "parent-adapter"):
        preparation.add_argument("--" + name, type=Path, required=True)
    preparation.add_argument("--parent-version", required=True)
    preparation.add_argument("--current-source", required=True)
    preparation.add_argument("--service-port", type=int, default=8766)
    execution = subparsers.add_parser("run")
    evaluation = subparsers.add_parser("evaluate-only")
    evaluation.add_argument("--cases", type=Path, required=True)
    evaluation.add_argument("--label", required=True)
    for child in (preparation, execution, evaluation):
        child.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        {"prepare": prepare, "run": run, "evaluate-only": evaluate_only}[args.action](args)
    except Exception as error:
        # No source text, question, expected answer or raw model answer in logs.
        print(json.dumps({"status": "error", "error_type": type(error).__name__,
                          "message": str(error)}, ensure_ascii=False), file=sys.stderr, flush=True)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
