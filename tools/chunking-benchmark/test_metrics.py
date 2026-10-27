import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("chunk_benchmark", Path(__file__).with_name("run.py"))
benchmark = importlib.util.module_from_spec(spec)
spec.loader.exec_module(benchmark)


class EvidenceScoringTest(unittest.TestCase):
    def fixture(self):
        evidence = "完整答案证据必须包含条件甲条件乙以及适用范围，标题命中不能替代正文证据。"
        doc = {"id": "A", "title": "目标标题", "text": evidence, "protected": []}
        question = {"id": "q1", "category": "合成", "doc_id": "A", "evidence": [{"text": evidence}]}
        return {"documents": [doc], "questions": [question]}, evidence

    def test_correct_title_without_body_evidence_is_not_a_hit(self):
        suite, evidence = self.fixture()
        suite["documents"].append({"id": "B", "title": "目标标题", "text": "这个正文讨论另一件事。", "protected": []})
        exported = {"documents": [{"id": "B", "pieces": [{"heading": evidence, "text": "这个正文讨论另一件事。"}]},
                                  {"id": "A", "pieces": [{"heading": "", "text": evidence}]}]}
        metrics, _ = benchmark.score(suite, exported, [[1, 0], [0, 1]], [[1, 0]], 2400)
        self.assertEqual(metrics["evidence_hit_at_1"], 0)
        self.assertEqual(metrics["mrr"], .5)

    def test_complete_coverage_can_be_assembled_from_two_chunks(self):
        suite, evidence = self.fixture()
        midpoint = len(evidence) // 2
        exported = {"documents": [{"id": "A", "pieces": [{"heading": "", "text": evidence[:midpoint]},
                                                                       {"heading": "", "text": evidence[midpoint:]}]}]}
        metrics, _ = benchmark.score(suite, exported, [[1, 0], [.9, .1]], [[1, 0]], 2400)
        self.assertEqual(metrics["complete_evidence_budget"], 1)
        self.assertEqual(metrics["mean_evidence_character_coverage"], 1)

    def test_budget_may_not_truncate_a_protected_block_to_claim_coverage(self):
        suite, evidence = self.fixture()
        exported = {"documents": [{"id": "A", "pieces": [{"heading": "", "text": evidence}]}]}
        metrics, rows = benchmark.score(suite, exported, [[1, 0]], [[1, 0]], len(evidence) - 1)
        self.assertEqual(metrics["complete_evidence_budget"], 0)
        self.assertTrue(rows[0]["blocked"])
        self.assertEqual(rows[0]["context_chars"], 0)


if __name__ == "__main__":
    unittest.main()
