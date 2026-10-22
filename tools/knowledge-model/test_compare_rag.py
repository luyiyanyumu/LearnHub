"""Synthetic CPU-only checks for frozen, inference-only RAG comparison."""
import argparse
from dataclasses import asdict
import importlib.util
import math
from pathlib import Path
import socket
import tempfile
import unittest
from unittest.mock import patch

from engine import Config
from store import Store

SCRIPT = Path(__file__).resolve().parents[1] / "compare-knowledge-rag.py"
SPEC = importlib.util.spec_from_file_location("compare_knowledge_rag", SCRIPT)
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


class Tokenizer:
    is_fast = True
    def __call__(self, text, add_special_tokens=True, return_offsets_mapping=False):
        value = {"input_ids": list(range(len(text)))}
        if return_offsets_mapping:
            value["offset_mapping"] = [(i, i + 1) for i in range(len(text))]
        return value

    def apply_chat_template(self, messages, tokenize=False, add_generation_prompt=True):
        return "\n".join(row["role"] + ":" + row["content"] for row in messages) + "\nassistant:"


class FakeEngine:
    loads = []
    def __init__(self, store, config):
        assert store is None
        self.config = config
        self.tokenizer = Tokenizer()

    def _load(self, adapter=None, trainable=False):
        if trainable:
            raise AssertionError("Inference runner requested training")
        self.loads.append(adapter)

    def _answer(self, question, max_tokens=48):
        assert max_tokens == 48
        return "青绿" if "颜色" in question else "不知道"

    def unload(self):
        pass


def fake_infer(engine, action):
    return action(), {"generation_elapsed_ms": 1.0, "answer_elapsed_ms": 2.0}


class RagComparisonTests(unittest.TestCase):
    def setUp(self):
        output = runner.ROOT / "output"
        output.mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="rag-synthetic-", dir=output)
        self.root = Path(self.temporary.name)
        self.private = patch.object(runner, "PRIVATE_ROOT", self.root)
        self.private.start()
        self.runtime = self.root / "runtime"
        self.store = Store(self.runtime)
        self.model = self.root / "model"
        self.model.mkdir()
        (self.model / "config.json").write_text("{}", encoding="utf-8")
        self.adapter = self.root / "adapter"
        self.adapter.mkdir()
        (self.adapter / "weights.synthetic").write_text("synthetic", encoding="utf-8")
        self.prior = self.root / "prior"
        (self.prior / "b").mkdir(parents=True)
        self.corpus_path = self.root / "corpus.json"
        self.documents_path = self.root / "documents.json"
        self.cases_path = self.root / "cases-input.json"
        self.cases = [
            {"source_id": "real-validation:synthetic", "fact_id": "color", "group": "new",
             "source_phase": "new", "cohort": "new_blind_known", "question": "合成站颜色？",
             "answer": "青绿", "aliases": []},
            {"source_id": "real-validation:synthetic", "fact_id": "missing", "group": "unknown",
             "source_phase": "old", "cohort": "new_blind_natural_unknown", "question": "合成站维护者？",
             "answer": "不知道"},
            {"source_id": "synthetic:original", "fact_id": "old", "group": "unknown",
             "cohort": "old_refusal", "question": "合成站最早列车？", "answer": "不知道"}]
        self.corpus = [{"payload": {"source_id": "real-validation:synthetic", "title": "不应进入索引的摘要",
            "text": "不应进入索引的增强文本", "facts": [{"answer": "唯一秘密答案", "training_questions": ["秘密训练题"]}]}}]
        self.documents = [{"source_id": "real-validation:synthetic", "title": "合成档案", "text": "合成站的颜色是青绿。"}]
        runner.write_json(self.corpus_path, self.corpus)
        runner.write_json(self.documents_path, self.documents)
        runner.write_json(self.cases_path, self.cases)
        self.previous = {"config": asdict(Config(model_path=str(self.model), eval_max_tokens=48, seed=42))}
        self.result = {"adapter": str(self.adapter), "adapter_hashes": runner.tree_hash(self.adapter)}
        runner.write_json(self.prior / "protocol.json", self.previous)
        runner.write_json(self.prior / "b" / "result.json", self.result)
        self.prior_b = [{**row, "response": "青绿" if row["group"] != "unknown" else "不知道", "correct": True}
                        for row in self.cases]
        self.audit = self.prior / "post-training-evaluation.json"
        runner.write_json(self.audit, {"cases_sha256": runner.digest(self.cases), "models": {"b": {"cases": self.prior_b}}})
        self.args = argparse.Namespace(output_dir=self.root / "run", corpus=self.corpus_path,
            documents=self.documents_path, cases=self.cases_path, prior_run=self.prior,
            runtime=self.runtime, service_port=8766, embedding_runner=runner.ROOT / "tools" / "mmr-embed.py")
        self.prior_guard = patch.object(runner.AB, "verify_frozen")
        self.prior_guard.start()
        FakeEngine.loads = []

    def tearDown(self):
        self.prior_guard.stop()
        self.private.stop()
        self.temporary.cleanup()

    def prepare(self):
        with patch.object(runner, "prior_inputs", return_value=(self.prior, self.previous, self.result,
                self.corpus_path, self.cases_path, self.audit)):
            return runner.prepare(self.args, tokenizer=Tokenizer())

    def vectors(self, name, target):
        source = self.args.output_dir / name
        docs = runner.read(source)["documents"]
        vector = [1.0] + [0.0] * 1023
        value = {"status": "completed", "inputHash": runner.file_hash(source), "model": "bge-m3",
            "modelDigest": runner.EMBED_DIGEST, "dimension": 1024, "normalized": True,
            "options": {"num_thread": 1, "num_gpu": 0}, "documentCount": len(docs), "completed": len(docs),
            "embeddingRunnerSha256": runner.file_hash(self.args.embedding_runner),
            "cacheHelperSha256": runner.file_hash(runner.ROOT / "tools" / "mmr-embed.py"),
            "vectors": [{"id": doc["id"], "vector": vector} for doc in docs]}
        runner.write_json(self.args.output_dir / target, value)
        return value

    def retrieve(self):
        self.prepare()
        self.vectors("embedding-documents.json", "doc-vectors.json")
        self.vectors("embedding-queries.json", "query-vectors.json")
        return runner.retrieve(self.args, tokenizer=Tokenizer())

    def test_index_whitelist_separate_clean_text_and_offsets(self):
        self.prepare()
        chunks = runner.read(self.args.output_dir / "chunks.json")
        encoded = __import__("json").dumps(chunks, ensure_ascii=False)
        for forbidden in ("唯一秘密答案", "秘密训练题", "增强文本", "不应进入索引"):
            self.assertNotIn(forbidden, encoded)
        self.assertEqual(self.documents[0]["text"], chunks[0]["text"])
        self.assertEqual(runner.text_id(chunks[0]["embedding_text"]), chunks[0]["id"])
        with self.assertRaisesRegex(ValueError, "only"):
            runner.index_chunks([{**self.documents[0], "facts": []}], Tokenizer())
        long = [{**self.documents[0], "text": "甲" * 500}]
        rows, _ = runner.index_chunks(long, Tokenizer())
        self.assertEqual([0, 208, 416], [row["token_start"] for chunk in rows for row in chunk["occurrences"]])

    def test_queries_contain_questions_only_and_are_deduplicated(self):
        self.prepare()
        values = runner.read(self.args.output_dir / "embedding-queries.json")["documents"]
        self.assertEqual([row["question"] for row in self.cases], [row["text"] for row in values])
        self.assertTrue(all(set(row) == {"id", "text"} for row in values))

    def test_frozen_file_and_weights_guards(self):
        protocol = self.prepare()
        runner.verify_frozen(self.args.output_dir, protocol)
        (self.adapter / "weights.synthetic").write_text("changed", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "adapter"):
            runner.verify_frozen(self.args.output_dir, protocol)

    def test_input_artifact_guard(self):
        protocol = self.prepare()
        runner.write_json(self.args.output_dir / "chunks.json", [])
        with self.assertRaisesRegex(ValueError, "artifact"):
            runner.verify_frozen(self.args.output_dir, protocol)

    def test_vector_cache_rejects_wrong_hash_nonfinite_norm_dimension_and_order(self):
        protocol = self.prepare()
        good = self.vectors("embedding-documents.json", "doc-vectors.json")
        source = self.args.output_dir / "embedding-documents.json"
        target = self.args.output_dir / "doc-vectors.json"
        self.assertEqual(1, len(runner.vector_cache(target, source, protocol)))
        for change in ("hash", "dimension", "nan", "norm", "order", "digest", "options", "runner", "helper"):
            value = __import__("copy").deepcopy(good)
            if change == "hash": value["inputHash"] = "changed"
            if change == "dimension": value["dimension"] = 8
            if change == "nan": value["vectors"][0]["vector"][0] = math.nan
            if change == "norm": value["vectors"][0]["vector"][0] = 2.0
            if change == "order": value["vectors"][0]["id"] = "changed"
            if change == "digest": value["modelDigest"] = "0" * 64
            if change == "options": value["options"]["num_gpu"] = 1
            if change == "runner": value["embeddingRunnerSha256"] = "changed"
            if change == "helper": value["cacheHelperSha256"] = "changed"
            runner.write_json(target, value)
            with self.assertRaises(ValueError): runner.vector_cache(target, source, protocol)

    def test_cosine_negative_unclipped_and_stable_ties(self):
        chunks = [{"id": key} for key in ("b", "c", "a")]
        vectors = {"a": [1, 0], "b": [1, 0], "c": [-1, 0]}
        ranked = runner.ranked_chunks([1, 0], chunks, vectors)
        self.assertEqual(["a", "b", "c"], [row["id"] for row in ranked])
        self.assertEqual(-1, ranked[-1]["score"])

    def test_prompt_budgets_drop_tail_and_forbid_question_truncation(self):
        rows = [{"id": str(i), "title": "合成", "text": "文" * 500} for i in range(4)]
        value = runner.budget_context("合成问题", rows, Tokenizer())
        self.assertLessEqual(value["context_tokens"], 1536)
        self.assertEqual(["3"], value["dropped_chunk_ids"])
        self.assertNotIn("answer", value["user"])
        self.assertNotIn("aliases", value["user"])
        with self.assertRaisesRegex(ValueError, "truncation"):
            runner.budget_context("问" * 3000, [], Tokenizer())

    def test_inference_three_arms_no_training_or_runtime_mutation_and_anonymous_analysis(self):
        retrievals = self.retrieve()
        self.assertNotIn("唯一秘密答案", retrievals[0]["user"])
        before = runner.runtime_snapshot(self.runtime)
        with patch.object(runner, "assert_service_stopped"):
            report = runner.run(self.args, engine_factory=FakeEngine, infer=fake_infer,
                                rag_infer=lambda engine, user: engine._answer(user))
        self.assertEqual([str(self.adapter), str(self.adapter), None], FakeEngine.loads)
        self.assertEqual(before, runner.runtime_snapshot(self.runtime))
        self.assertIsNone(self.store.active())
        self.assertEqual(ARMS := set(runner.ARMS), set(report["models"]))
        self.assertTrue(report["models"]["closed_b"]["summary"]["closed_book"])
        self.assertFalse(report["models"]["rag_b"]["summary"]["closed_book"])
        summary = runner.analyze(self.args)
        raw = __import__("json").dumps(summary, ensure_ascii=False)
        for secret in ("青绿", "合成站", "维护者", "real-validation:synthetic"):
            self.assertNotIn(secret, raw)
        self.assertEqual(3, summary["closed_b_replay"]["exact_response_matches"])
        self.assertEqual(1, summary["models"]["rag_b"]["real_known"]["correct"])
        with self.assertRaisesRegex(ValueError, "unexecuted"):
            runner.run(self.args, engine_factory=FakeEngine)

    def test_scores_and_case_alignment_are_checked(self):
        self.retrieve()
        with patch.object(runner, "assert_service_stopped"):
            report = runner.run(self.args, engine_factory=FakeEngine, infer=fake_infer,
                                rag_infer=lambda engine, user: engine._answer(user))
        report["models"]["rag_b"]["cases"][0]["correct"] = False
        with self.assertRaisesRegex(ValueError, "score"):
            runner.analysis(report, self.cases, self.prior_b)

    def test_service_occupied_is_rejected_before_model_loading(self):
        self.retrieve()
        with socket.socket() as server:
            server.bind(("127.0.0.1", 0))
            server.listen()
            port = server.getsockname()[1]
            protocol = runner.read(self.args.output_dir / "protocol.json")
            protocol["service_port"] = port
            runner.write_json(self.args.output_dir / "protocol.json", protocol)
            with self.assertRaisesRegex(RuntimeError, "Stop"):
                runner.run(self.args, engine_factory=FakeEngine)
        self.assertEqual([], FakeEngine.loads)


if __name__ == "__main__":
    unittest.main()
