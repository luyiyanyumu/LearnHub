"""Synthetic CPU tests for the controlled data-only experiment runner."""
import argparse
from dataclasses import asdict
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from engine import Config
from store import Store

SCRIPT = Path(__file__).resolve().parents[1] / "compare-knowledge-data.py"
spec = importlib.util.spec_from_file_location("compare_knowledge_data", SCRIPT)
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class FakeEngine:
    loads = []
    trained = []

    def __init__(self, store, config):
        assert store is None
        self.config = config

    def _load(self, adapter, trainable=False):
        self.loads.append((adapter, trainable))

    def _train(self, samples, directory, job_id):
        self.trained.append(samples)
        directory.joinpath("adapter").mkdir()
        directory.joinpath("adapter", "weights.synthetic").write_text("synthetic")
        return {"steps": self.config.max_steps}

    def _evaluate(self, cases, phase):
        rows = [{**case, "response": case["answer"], "correct": True} for case in cases]
        return {"summary": runner.summarize_eval(rows), "cases": rows}

    def unload(self):
        pass


class ComparisonTests(unittest.TestCase):
    def setUp(self):
        root = runner.ROOT / "output"
        root.mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="compare-cpu-", dir=root)
        self.root = Path(self.temporary.name)
        self.runtime = self.root / "runtime"
        self.store = Store(self.runtime)
        self.parent = self.root / "parent"
        self.parent.mkdir()
        for name in ("adapter_config.json", "adapter_model.safetensors"):
            (self.parent / name).write_text("synthetic")
        self.model = self.root / "model"
        self.model.mkdir()
        (self.model / "config.json").write_text("{}")
        self.corpus = [{"source_id": "synthetic", "payload": {
            "source_id": "synthetic", "title": "合成资料", "text": "合成站的颜色是青绿。",
            "facts": [{"fact_id": "fact", "subject": "合成站的颜色", "answer": "青绿",
                "training_questions": ["请报告合成站的颜色。"],
                "eval_questions": ["档案记载的合成站颜色为何？"]}],
            "abstention": {"training_questions": ["合成站的开通日是哪天？"],
                           "eval_questions": ["合成站的维护单位是哪家？"]}}}]
        self.cases = [{"source_id": "synthetic", "fact_id": "fact", "group": "new",
                       "question": "档案记载的合成站颜色为何？", "answer": "青绿"},
                      {"source_id": "synthetic", "fact_id": "unknown", "group": "unknown",
                       "cohort": "old_refusal", "question": "合成站的维护单位是哪家？", "answer": "不知道"}]
        self.paths = {name: self.root / (name + ".json") for name in ("a_corpus", "b_corpus", "cases", "config")}
        for name in ("a_corpus", "b_corpus"):
            runner.write_json(self.paths[name], self.corpus)
        runner.write_json(self.paths["cases"], self.cases)
        runner.write_json(self.paths["config"], asdict(Config(model_path=str(self.model), max_steps=1)))
        self.args = argparse.Namespace(**self.paths, runtime=self.runtime,
            parent_adapter=self.parent, parent_version="parent-synthetic",
            current_source="synthetic", service_port=8766, output_dir=self.root / "runs")
        FakeEngine.loads = []
        FakeEngine.trained = []

    def tearDown(self):
        self.temporary.cleanup()

    def samples(self, tokenizer, corpus, current_source, **kwargs):
        marker = ord(corpus[0]["payload"]["facts"][0]["training_questions"][0][0])
        samples = [{"input_ids": [marker, 5], "labels": [-100, 5]},
                   {"input_ids": [3, 4], "labels": [3, 4]},
                   {"input_ids": [6, 7], "labels": [-100, 7]}]
        return samples, {"abstention_training": {"positive_qa": 1},
                         "selected_samples": {"unknown": 1, "new": 2, "old": 0},
                         "selected_tokens": 6}

    def prepare(self):
        with patch.object(runner, "build_samples", side_effect=self.samples):
            return runner.prepare(self.args, tokenizer=object())

    def test_whitelist_rejects_answer_text_and_eval_changes(self):
        for path in (("text",), ("facts", 0, "answer"), ("facts", 0, "eval_questions")):
            changed = json.loads(json.dumps(self.corpus))
            target = changed[0]["payload"]
            for part in path[:-1]:
                target = target[part]
            target[path[-1]] = "changed"
            self.assertNotEqual(runner.invariant_corpus(self.corpus), runner.invariant_corpus(changed))
        changed = json.loads(json.dumps(self.corpus))
        changed[0]["payload"]["facts"][0]["training_questions"] = ["新问法"]
        changed[0]["payload"]["abstention"]["training_questions"] = ["新负问法"]
        self.assertEqual(runner.invariant_corpus(self.corpus), runner.invariant_corpus(changed))

    def test_budget_difference_rejected_before_any_training(self):
        original = self.samples(None, self.corpus, "synthetic")
        changed = json.loads(json.dumps(original))
        changed[1]["abstention_training"]["positive_qa"] = 2
        with patch.object(runner, "build_samples", side_effect=[original, changed]):
            with self.assertRaisesRegex(ValueError, "budgets differ"):
                runner.prepare(self.args, tokenizer=object())
        self.assertEqual([], FakeEngine.trained)

    def test_same_parent_independent_arms_no_publish_then_readonly_audit(self):
        self.prepare()
        before = runner.runtime_snapshot(self.runtime)
        with patch.object(runner, "assert_service_stopped"):
            runner.run(argparse.Namespace(output_dir=self.args.output_dir), engine_factory=FakeEngine)
        trainable_loads = [adapter for adapter, trainable in FakeEngine.loads if trainable]
        self.assertEqual([str(self.parent), str(self.parent)], trainable_loads)
        self.assertEqual(2, len(FakeEngine.trained))
        self.assertEqual(before, runner.runtime_snapshot(self.runtime))
        self.assertIsNone(self.store.active())
        blind = self.root / "blind.json"
        runner.write_json(blind, self.cases)
        args = argparse.Namespace(output_dir=self.args.output_dir, cases=blind, label="post-training")
        with patch.object(runner, "assert_service_stopped"):
            report = runner.evaluate_only(args, engine_factory=FakeEngine)
        self.assertEqual(2, len(FakeEngine.trained), "Audit must never train")
        self.assertEqual({"parent", "a", "b"}, set(report["models"]))
        self.assertFalse(report["blind_used_for_training_or_selection"])
        self.assertEqual(1, report["transitions"]["a"]["old_refusal"]["retained_correct"])
        self.assertEqual(before, runner.runtime_snapshot(self.runtime))

    def test_incomplete_arms_do_not_open_blind_file(self):
        self.prepare()
        missing = self.root / "blind-must-not-open.json"
        with self.assertRaisesRegex(ValueError, "Both training arms"):
            runner.evaluate_only(argparse.Namespace(output_dir=self.args.output_dir,
                cases=missing, label="blind"), engine_factory=FakeEngine)
        self.assertEqual([], FakeEngine.loads)

    def test_frozen_token_samples_and_input_changes_rejected(self):
        protocol = self.prepare()
        sample_path = self.args.output_dir / "a" / "samples.json"
        saved = runner.read(sample_path)
        saved[0]["input_ids"][0] += 1
        runner.write_json(sample_path, saved)
        with self.assertRaisesRegex(ValueError, "Tokenized training samples changed"):
            runner.verify_frozen(self.args.output_dir, protocol)

    def test_runtime_logical_mutation_detected(self):
        protocol = self.prepare()
        with self.store.connect() as connection:
            connection.execute("INSERT INTO settings(key,value) VALUES('active',?)", ('{"version":"changed"}',))
        with self.assertRaisesRegex(ValueError, "runtime logical state changed"):
            runner.verify_frozen(self.args.output_dir, protocol)

    def test_case_training_overlap_rejected(self):
        changed = [{**self.cases[0], "question": "请报告合成站的颜色。"}]
        with self.assertRaisesRegex(ValueError, "overlaps"):
            runner.assert_isolated(self.corpus, changed)


if __name__ == "__main__":
    unittest.main()
