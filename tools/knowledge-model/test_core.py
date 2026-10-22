"""Synthetic, CPU-only invariants. Run: python -m unittest discover -s tools/knowledge-model."""
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from data import build_samples, evaluation_cases, extract_facts, gate, prepare_payload, score_answer, summarize_eval
from engine import Config, Engine
from store import ProcessLock, Store


class CharacterTokenizer:
    eos_token_id = 1

    def __call__(self, text, add_special_tokens=False):
        return {"input_ids": [ord(c) for c in text]}

    def apply_chat_template(self, messages, tokenize=True, add_generation_prompt=False):
        text = "".join(f"<{m['role']}>" + m["content"] for m in messages)
        text += "<assistant>" if add_generation_prompt else "<end>"
        return [ord(c) for c in text]


class StoreTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.store = Store(Path(self.directory.name))

    def tearDown(self):
        self.directory.cleanup()

    def payload(self, text="澄岚站的校验码是 松柏47。", source="sample", revision="1"):
        return prepare_payload({"source_id": source, "title": "合成资料", "text": text,
                                "revision": revision})

    def test_concurrent_deduplication(self):
        with ThreadPoolExecutor(max_workers=6) as pool:
            results = list(pool.map(lambda _: self.store.enqueue(self.payload()), range(12)))
        self.assertEqual(1, len({result[0]["id"] for result in results}))
        self.assertEqual(1, sum(not result[1] for result in results))

    def test_claim_is_serialized(self):
        self.store.enqueue(self.payload())
        with ThreadPoolExecutor(max_workers=6) as pool:
            claims = list(pool.map(lambda _: self.store.claim(), range(6)))
        self.assertEqual(1, sum(claim is not None for claim in claims))

    def test_revision_snapshot_and_atomic_publication(self):
        first, _ = self.store.enqueue(self.payload())
        next_, _ = self.store.enqueue(self.payload("澄岚站的校验码是 青鹭63。", revision="2"))
        self.assertIn("松柏47", self.store.corpus(first["seq"])[0]["payload"]["text"])
        self.assertIn("青鹭63", self.store.corpus(next_["seq"])[0]["payload"]["text"])
        self.store.finish(first["id"], "published", {"version": "v1"}, publish={"version": "v1"})
        self.store.finish(next_["id"], "rejected", {"version": "v2"})
        self.assertEqual("v1", self.store.active()["version"])
        self.assertNotIn("payload", self.store.get(first["id"]))

    def test_restart_recovers_and_retry_rejects_superseded_revision(self):
        first, _ = self.store.enqueue(self.payload())
        self.store.claim()
        self.store.recover()
        self.assertEqual("queued", self.store.get(first["id"])["status"])
        self.store.finish(first["id"], "failed", error="synthetic failure")
        self.store.enqueue(self.payload("澄岚站的校验码是 青鹭63。", revision="2"))
        with self.assertRaises(ValueError):
            self.store.retry(first["id"])

    def test_process_exclusion(self):
        lock = ProcessLock(self.store.root).acquire()
        try:
            with self.assertRaises(RuntimeError):
                ProcessLock(self.store.root).acquire()
        finally:
            lock.release()
        ProcessLock(self.store.root).acquire().release()

    def test_retry_requeues_after_published_documents_and_freezes_cutoff(self):
        first, _ = self.store.enqueue(self.payload(source="station"))
        self.store.claim()
        self.store.finish(first["id"], "failed", error="synthetic first attempt failure")
        second, _ = self.store.enqueue(self.payload("琥珀桥的登记号是 星沙82。", source="bridge"))
        claimed_second = self.store.claim()
        self.assertEqual(second["id"], claimed_second["id"])
        self.store.finish(second["id"], "published", publish={"version": "bridge-v1"})
        retry = self.store.retry(first["id"])
        self.assertGreater(retry["seq"], second["seq"])
        self.assertEqual(2, retry["attempt"])
        later, _ = self.store.enqueue(self.payload("紫汀仓库的通行词是 云舟29。", source="later"))
        self.assertGreater(later["seq"], retry["seq"])
        claimed_retry = self.store.claim()
        corpus = self.store.corpus(claimed_retry["seq"])
        self.assertEqual({"station", "bridge"}, {d["source_id"] for d in corpus})
        cases = evaluation_cases(corpus, "station")
        self.assertEqual(["bridge"], [c["source_id"] for c in cases if c["group"] == "old"])
        self.assertEqual("bridge-v1", self.store.active()["version"])

    def test_reupload_historical_content_is_new_revision_but_current_retry_is_duplicate(self):
        first, _ = self.store.enqueue(self.payload(revision="1"))
        second, _ = self.store.enqueue(self.payload("澄岚站的校验码是 青鹭63。", revision="2"))
        reverted, duplicate = self.store.enqueue(self.payload(revision="3"))
        self.assertFalse(duplicate)
        self.assertNotEqual(first["id"], reverted["id"])
        self.assertGreater(reverted["seq"], second["seq"])
        retried, duplicate = self.store.enqueue(self.payload(revision="3"))
        self.assertTrue(duplicate)
        self.assertEqual(reverted["id"], retried["id"])
        self.assertEqual("3", self.store.corpus(reverted["seq"])[0]["payload"]["revision"])

    def test_runtime_status_counts_all_jobs_beyond_recent_page(self):
        from main import Runtime
        for index in range(125):
            self.store.enqueue(self.payload(source=f"synthetic-{index}"))
        self.store.claim()
        self.store.claim()
        failed = self.store.claim()
        self.store.finish(failed["id"], "failed", error="synthetic failure")
        self.assertEqual({"queued": 122, "running": 2, "failed": 1}, self.store.status_counts())
        self.assertEqual(100, len(self.store.jobs(100)))
        status = Runtime(self.store.root, Config()).status()
        self.assertEqual(122, status["queued_jobs"])
        self.assertEqual(2, status["running_jobs"])


class EvaluationTests(unittest.TestCase):
    def test_training_masks_questions_and_replays_old_examples(self):
        def doc(source, text):
            return {"payload": prepare_payload({"source_id": source, "title": "合成", "text": text})}

        corpus = [doc("old", "琥珀桥的登记号是 星沙82。"), doc("new", "澄岚站的校验码是 松柏47。")]
        samples, coverage = build_samples(CharacterTokenizer(), corpus, "new", max_length=512)
        self.assertGreater(coverage["selected_samples"]["old"], 0)
        self.assertGreater(coverage["selected_samples"]["new"], 0)
        qa = [sample for sample in samples if -100 in sample["labels"]]
        self.assertTrue(qa)
        held_out = [question for document in corpus for fact in document["payload"]["facts"]
                    for question in fact["eval_questions"]]
        for sample in qa:
            full_text = "".join(chr(i) for i in sample["input_ids"])
            self.assertTrue(all(question not in full_text for question in held_out))
            first_target = next(i for i, label in enumerate(sample["labels"]) if label != -100)
            self.assertIn("<assistant>", full_text[:first_target])
            self.assertNotIn("<user>", full_text[first_target:])

    def test_raw_faq_cannot_train_normalized_held_out_question(self):
        payload = prepare_payload({"source_id": "faq", "title": "合成问答",
            "text": "资料中记载的澄岚站校验码具体是什么 ?\n答案：松柏47。\n\n合成站点使用松柏47作为校验码。",
            "facts": [{"answer": "松柏47", "training_questions": ["查询澄岚站校验码。"],
                       "eval_questions": ["资料中记载的澄岚站校验码具体是什么？"]}]})
        samples, coverage = build_samples(CharacterTokenizer(), [{"payload": payload}], "faq")
        self.assertEqual(1, coverage["raw_segments_removed_for_evaluation_isolation"])
        for sample in samples:
            raw = "".join(chr(i) for i in sample["input_ids"])
            self.assertNotIn("资料中记载的澄岚站校验码具体是什么", raw)
        self.assertTrue(any("松柏47" in "".join(chr(i) for i in s["input_ids"]) for s in samples))

    def test_normalized_training_eval_question_overlap_is_rejected(self):
        with self.assertRaises(ValueError):
            prepare_payload({"source_id": "normalized", "title": "合成", "text": "合成内容",
                "facts": [{"answer": "松柏47", "training_questions": ["校验码是什么?"],
                           "eval_questions": ["校验码是什么？"]}]})

    def test_ordinary_text_has_no_answer_leakage_or_question_overlap(self):
        payload = prepare_payload({"source_id": "synthetic", "title": "合成资料",
            "text": "澄岚站的校验码是 松柏47。\n琥珀桥的登记号=星沙82"})
        self.assertEqual(2, len(payload["facts"]))
        for fact in payload["facts"]:
            self.assertFalse(set(fact["training_questions"]) & set(fact["eval_questions"]))
            self.assertTrue(all(fact["answer"] not in q for q in fact["eval_questions"]))
        cases = evaluation_cases([{"payload": payload}], "synthetic")
        self.assertEqual(2, len([case for case in cases if case["group"] == "new"]))
        self.assertTrue(any(case["group"] == "unknown" for case in cases))

    def test_no_facts_is_uncovered(self):
        self.assertEqual([], extract_facts("这是一段没有明确键值事实的合成材料。"))
        empty = summarize_eval([])
        self.assertIsNone(empty["new"]["accuracy"])
        self.assertEqual("unvalidated", gate(empty, empty)[0])

    def test_rejects_answer_in_eval_question(self):
        with self.assertRaises(ValueError):
            prepare_payload({"source_id": "synthetic", "title": "合成资料", "text": "sample",
                "facts": [{"answer": "松柏47", "training_questions": ["校验码是什么？"],
                           "eval_questions": ["校验码是不是松柏47？"]}]})

    def test_exact_match_does_not_count_negated_or_evasive_answer(self):
        self.assertTrue(score_answer("松柏47。", "松柏47"))
        self.assertFalse(score_answer("不是松柏47", "松柏47"))
        self.assertFalse(score_answer("不知道，也许是松柏47", "松柏47"))

    def test_new_failure_and_old_forgetting_block_release(self):
        def summary(new, old):
            return {"new": {"accuracy": new}, "old": {"accuracy": old},
                    "unknown": {"accuracy": 1, "total": 16},
                    "known_abstention": {"total": 4, "count": 0, "rate": 0}}
        baseline = summary(0, 1)
        self.assertEqual("rejected", gate(baseline, summary(0.5, 1))[0])
        self.assertEqual("rejected", gate(baseline, summary(1, 0.5))[0])
        self.assertEqual("published", gate(baseline, summary(1, 1))[0])


class ArtifactLifecycleTests(unittest.TestCase):
    def exercise(self, fail_reload=False):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        store = Store(Path(directory.name))
        old, _ = store.enqueue(prepare_payload({"source_id": "old", "title": "合成旧资料",
                                               "text": "琥珀桥的登记号是 星沙82。"}))
        parent_path = store.root / "parent-adapter"
        parent_path.mkdir()
        (parent_path / "saved.marker").write_text("synthetic", encoding="utf-8")
        parent = {"version": "parent", "base_model": "fake-base", "adapter_path": str(parent_path)}
        store.finish(old["id"], "published", publish=parent)
        store.enqueue(prepare_payload({"source_id": "new", "title": "合成新资料",
                                       "text": "澄岚站的校验码是 松柏47。"}))
        job = store.claim()
        events = []

        class LifecycleEngine(Engine):
            def unload(self):
                if self.model is not None:
                    events.append(("unload", self.mode))
                self.model = self.tokenizer = None

            def _load(self, adapter=None, trainable=False):
                self.unload()
                events.append(("load", trainable, adapter))
                if adapter:
                    if not (Path(adapter) / "saved.marker").exists():
                        raise AssertionError("Evaluation must load a saved artifact")
                    if adapter != str(parent_path) and fail_reload:
                        raise RuntimeError("Synthetic artifact reload failure")
                self.mode = trainable
                self.model, self.tokenizer = object(), CharacterTokenizer()

            def _train(self, samples, directory, job_id):
                self_outer.assertTrue(self.mode)
                events.append(("save",))
                adapter = directory / "adapter"
                adapter.mkdir()
                (adapter / "saved.marker").write_text("synthetic", encoding="utf-8")
                return {"training_seconds": 0, "peak_allocated_vram_gib": 0, "steps": 1,
                        "loss_first": 1, "loss_last": 0.5}

            def _evaluate(self, cases, phase):
                self_outer.assertFalse(self.mode, "Scoring must use the real inference load mode")
                events.append(("evaluate", phase))
                rows = [{**case, "correct": phase == "candidate_evaluation" or case["group"] == "old",
                         "response": case["answer"]} for case in cases]
                return {"summary": summarize_eval(rows), "cases": rows}

        self_outer = self
        engine = LifecycleEngine(store, Config(model_path="fake-base", max_steps=1))
        return engine, store, job, parent, events

    def test_candidate_gate_loads_saved_nontrainable_artifact_and_releases_each_instance(self):
        engine, store, job, parent, events = self.exercise()
        result = engine.run(job)
        self.assertEqual("published", result["status"])
        self.assertEqual("parent", result["parent_version"])
        self.assertTrue(result["artifact_verification"]["reloaded_from_disk"])
        self.assertEqual([False, True, False], [event[1] for event in events if event[0] == "load"])
        self.assertEqual([False, True, False], [event[1] for event in events if event[0] == "unload"])
        save_index = next(i for i, event in enumerate(events) if event[0] == "save")
        candidate_index = next(i for i, event in enumerate(events)
                               if event == ("evaluate", "candidate_evaluation"))
        self.assertTrue(any(event[0] == "load" and event[1] is False
                            for event in events[save_index + 1:candidate_index]))
        self.assertIsNone(engine.model)
        self.assertNotEqual(parent["version"], store.active()["version"])

    def test_failed_artifact_reload_cannot_publish_or_modify_parent(self):
        engine, store, job, parent, events = self.exercise(fail_reload=True)
        with self.assertRaisesRegex(RuntimeError, "artifact reload failure"):
            engine.run(job)
        self.assertEqual(parent, store.active())
        self.assertFalse(any(event == ("evaluate", "candidate_evaluation") for event in events))
        self.assertIsNone(engine.model)


if __name__ == "__main__":
    unittest.main()
