"""CPU-only synthetic checks for offline refusal data and publication safeguards."""
import unittest

from data import (build_samples, entity_contrast_cases, evaluation_cases, gate, is_abstention, normalize, normalize_answer,
                  prepare_payload, refusal_cases, score_answer, summarize_eval)


class CharacterTokenizer:
    eos_token_id = 1

    def __call__(self, text, add_special_tokens=False):
        return {"input_ids": [ord(c) for c in text]}

    def apply_chat_template(self, messages, tokenize=True, add_generation_prompt=False):
        text = "".join(f"<{m['role']}>" + m["content"] for m in messages)
        text += "<assistant>" if add_generation_prompt else "<end>"
        return [ord(c) for c in text]


def doc(source="new", text="澄岚站的校验码是 松柏47。", facts=None):
    payload = {"source_id": source, "title": "合成资料", "text": text}
    if facts is not None:
        payload["facts"] = facts
    return {"payload": prepare_payload(payload)}


def evaluation(corpus, new_source, answer):
    rows = []
    for case in evaluation_cases(corpus, new_source):
        response = answer(case)
        rows.append({**case, "response": response,
                     "correct": score_answer(response, case["answer"], case.get("aliases", []))})
    return summarize_eval(rows)


class RefusalBankTests(unittest.TestCase):
    def setUp(self):
        self.corpus = [doc(), doc("old", "琥珀桥的登记号是 星沙82。")]

    def test_split_banks_are_stable_and_original_probe_is_held_out(self):
        train = refusal_cases(self.corpus, "train")
        held_out = refusal_cases(self.corpus, "eval")
        self.assertEqual(train, refusal_cases(self.corpus, "train"))
        self.assertEqual(held_out, refusal_cases(self.corpus, "eval"))
        self.assertTrue(32 <= len(train) <= 48)
        self.assertTrue(16 <= len(held_out) <= 20)
        self.assertFalse({c["entity"] for c in train if c["kind"] != "synthetic_missing_field"} &
                         {c["entity"] for c in held_out if c["kind"] != "synthetic_missing_field"})
        self.assertFalse({normalize(c["question"]) for c in train} &
                         {normalize(c["question"]) for c in held_out})
        self.assertFalse(any("岚缺XQ901" in c["question"] for c in train))
        self.assertIn("岚缺XQ901的校验码是什么？", [c["question"] for c in held_out])
        self.assertTrue(all(c["group"] == "unknown" and c["answer"] == "不知道" for c in train + held_out))
        self.assertIn("forced_guess", {c["kind"] for c in train})
        self.assertIn("unknown_entity", {c["kind"] for c in train})

    def test_later_upload_never_moves_held_out_entities_into_training(self):
        original_eval = {case["entity"] for case in refusal_cases(self.corpus, "eval")
                         if case["kind"] != "synthetic_missing_field"}
        later = self.corpus + [doc("later", "栖羽站的进度是 三成。")]
        self.assertFalse(original_eval & {case["entity"] for case in refusal_cases(later, "train")})
        for split in ("train", "eval"):
            previous = {case["entity"] for case in refusal_cases(self.corpus, split)
                        if case["kind"] in ("unknown_entity", "forced_guess")}
            current = {case["entity"] for case in refusal_cases(later, split)
                       if case["kind"] in ("unknown_entity", "forced_guess")}
            self.assertEqual(previous, current)

    def test_corpus_collisions_are_removed_even_without_fact_extraction(self):
        collision = doc("collision", "镜河站参与这次活动。岚缺XQ901也在其中。")
        train = refusal_cases(self.corpus + [collision], "train")
        held_out = refusal_cases(self.corpus + [collision], "eval")
        self.assertNotIn("镜河站", {case["entity"] for case in train})
        self.assertNotIn("岚缺XQ901", {case["entity"] for case in held_out})

    def test_known_entity_missing_fields_are_random_names_not_natural_omissions(self):
        all_cases = refusal_cases(self.corpus, "train") + refusal_cases(self.corpus, "eval")
        cases = [case for case in all_cases if case["kind"] == "synthetic_missing_field"]
        self.assertTrue(cases)
        for case in cases:
            self.assertTrue(case["attribute"].startswith("离线登记键"))
            self.assertNotIn(case["attribute"], "".join(d["payload"]["text"] for d in self.corpus))
        chosen = cases[0]
        updated = self.corpus + [doc("field", f"{chosen['entity']}使用{chosen['attribute']}作为索引。")]
        self.assertFalse(any(case["entity"] == chosen["entity"] and
                             case["attribute"] == chosen["attribute"] for split in ("train", "eval")
                             for case in refusal_cases(updated, split)))

    def test_old_manual_payload_without_subject_still_has_safe_unknowns(self):
        legacy = doc("legacy", "合成站点校验码记录为松柏47。", [{"answer": "松柏47",
            "training_questions": ["给出合成站点校验码。"], "eval_questions": ["合成站点应使用哪一校验码？"]}])
        self.assertGreaterEqual(len(refusal_cases([legacy], "train")), 32)
        self.assertFalse(any(case["kind"] == "synthetic_missing_field"
                             for case in refusal_cases([legacy], "train")))

    def test_known_entities_have_permanently_separate_train_eval_fields(self):
        train = {case["entity"]: case for case in refusal_cases(self.corpus, "train")
                 if case["kind"] == "synthetic_missing_field"}
        held_out = {case["entity"]: case for case in refusal_cases(self.corpus, "eval")
                    if case["kind"] == "synthetic_missing_field"}
        self.assertEqual({"澄岚站", "琥珀桥"}, set(train))
        self.assertEqual(set(train), set(held_out))
        for entity in train:
            self.assertNotEqual(train[entity]["attribute"], held_out[entity]["attribute"])
            self.assertNotIn(held_out[entity]["attribute"], train[entity]["question"])
        self.assertEqual("离线登记键3DE01B0C的配置值", held_out["琥珀桥"]["attribute"])
        self.assertEqual("关于琥珀桥，其离线登记键3DE01B0C的配置值应填什么内容？", held_out["琥珀桥"]["question"])


class RefusalScoringTests(unittest.TestCase):
    def test_numeric_punctuation_cannot_collapse_different_answers(self):
        for answer, expected in (("1.2", "12"), ("-5", "5"), ("+5", "5"),
                                 ("50%", "50"), ("50‰", "50"), ("1/2", "12"),
                                 ("12:30", "1230"), ("1,000", "1000"), (".5", "5"),
                                 ("1e-3", "1e3"), ("0012", "12")):
            with self.subTest(answer=answer, expected=expected):
                self.assertFalse(score_answer(answer, expected))
        self.assertTrue(score_answer("1,000", "1000", aliases=["1,000"]))

    def test_answer_formatting_ignores_sentence_punctuation_and_case(self):
        for answer, expected in (("1.2。", "1.2"), ("1.2.", "1.2"), ("-5！", "-5"),
                                 ("50％。", "50%"), ("1 / 2。", "1/2"), ("12：30", "12:30"),
                                 ("松柏47。", "松柏47"), ("（AbC_47）！", "abc47"),
                                 ("50，", "50"), ("−5", "-5")):
            with self.subTest(answer=answer, expected=expected):
                self.assertTrue(score_answer(answer, expected))
        self.assertEqual("1.2", normalize_answer("１．２。"))
        self.assertEqual("12", normalize("1.2"), "Question/bank normalizer must remain unchanged")
        self.assertEqual("numeric_safe_normalized_exact_answer", summarize_eval([])["metric"])

    def test_only_short_exact_refusal_counts(self):
        self.assertTrue(is_abstention("不知道。"))
        for answer in ("不知道，可能是松柏47", "我不知道", "不是不知道", "不知道这个值但猜星沙82", "松柏47"):
            self.assertFalse(is_abstention(answer), answer)

    def test_unknowns_are_not_counted_as_known_factual_accuracy(self):
        corpus = [doc()]
        summary = evaluation(corpus, "new", lambda case: case["answer"])
        self.assertEqual(1, summary["all"]["total"])
        self.assertEqual(1, summary["new"]["total"])
        self.assertGreaterEqual(summary["unknown"]["total"], 16)
        self.assertEqual(0, summary["known_abstention"]["count"])
        self.assertEqual("published", gate(summary, summary)[0])

    def test_always_refusing_cannot_publish(self):
        corpus = [doc(), doc("old", "琥珀桥的登记号是 星沙82。")]
        baseline = evaluation(corpus, "new", lambda case: case["answer"])
        candidate = evaluation(corpus, "new", lambda case: "不知道")
        self.assertEqual(1, candidate["unknown"]["accuracy"])
        self.assertEqual(1, candidate["known_abstention"]["rate"])
        self.assertEqual("rejected", gate(baseline, candidate)[0])

    def test_unknown_hallucinations_block_release_despite_correct_known_facts(self):
        corpus = [doc()]
        baseline = evaluation(corpus, "new", lambda case: case["answer"])
        candidate = evaluation(corpus, "new", lambda case: "松柏47" if case["group"] == "unknown" else case["answer"])
        self.assertEqual(1, candidate["new"]["accuracy"])
        self.assertEqual(0, candidate["unknown"]["accuracy"])
        status, reasons = gate(baseline, candidate)
        self.assertEqual("rejected", status)
        self.assertTrue(any("Unknown" in reason for reason in reasons))

    def test_kind_failure_blocks_release_even_when_overall_unknown_passes(self):
        rows = [{"group": "new", "response": "松柏47", "correct": True}]
        rows += [{"group": "unknown", "kind": "unknown_entity", "response": "不知道", "correct": True}
                 for _ in range(20)]
        rows += [{"group": "unknown", "kind": "reviewed_absence",
                  "response": "不知道" if index < 3 else "松柏47", "correct": index < 3}
                 for index in range(4)]
        summary = summarize_eval(rows)
        self.assertGreater(summary["unknown"]["accuracy"], 0.9)
        self.assertEqual(0.75, summary["unknown_by_kind"]["reviewed_absence"]["accuracy"])
        self.assertNotIn("forced_guess", summary["unknown_by_kind"])
        status, reasons = gate(summary, summary)
        self.assertEqual("rejected", status)
        self.assertTrue(any("reviewed_absence" in reason for reason in reasons))

    def test_unmeasured_kind_is_not_reported_as_zero_accuracy(self):
        summary = summarize_eval([])
        self.assertEqual({}, summary["unknown_by_kind"])

    def test_missing_unknown_measurement_or_new_facts_cannot_publish(self):
        corpus = [doc()]
        summary = evaluation(corpus, "new", lambda case: case["answer"])
        missing = dict(summary)
        missing.pop("unknown")
        self.assertEqual("unvalidated", gate(summary, missing)[0])
        no_new = evaluation(corpus, "not-present", lambda case: case["answer"])
        self.assertEqual("unvalidated", gate(summary, no_new)[0])

    def test_absolute_old_accuracy_floor_catches_unchanged_existing_errors(self):
        corpus = [doc(), doc("old", "琥珀桥的登记号是 星沙82。")]
        summary = evaluation(corpus, "new", lambda case: case["answer"])
        summary["old"]["accuracy"] = 0.9
        self.assertEqual("published", gate(summary, summary, old_drop_max=0)[0])
        status, reasons = gate(summary, summary, old_drop_max=0, old_min=1)
        self.assertEqual("rejected", status)
        self.assertTrue(any("Old fact accuracy below 100%" in reason for reason in reasons))
        no_old = evaluation([doc()], "new", lambda case: case["answer"])
        self.assertIsNone(no_old["old"]["accuracy"])
        self.assertEqual("published", gate(no_old, no_old, old_min=1)[0])


class RefusalSamplingTests(unittest.TestCase):
    def test_small_corpus_has_diverse_refusals_with_balanced_qa_and_replay(self):
        corpus = [doc(), doc("old", "琥珀桥的登记号是 星沙82。")]
        samples, coverage = build_samples(CharacterTokenizer(), corpus, "new")
        details = coverage["abstention_training"]
        self.assertLessEqual(len(samples), 256)
        self.assertGreater(coverage["selected_samples"]["new"], 0)
        self.assertGreater(coverage["selected_samples"]["old"], 0)
        self.assertGreaterEqual(coverage["selected_samples"]["unknown"], 16)
        self.assertEqual(12, details["unique_positive_qa"])
        self.assertGreater(details["repeated_positive_qa"], 0)
        self.assertTrue(0.25 <= details["unknown_qa_ratio"] <= 0.35)
        self.assertFalse(coverage["limited_coverage"])
        self.assertTrue(all(set(sample) == {"input_ids", "labels"} for sample in samples))

    def test_small_budget_preserves_both_positive_groups_and_reports_coverage(self):
        corpus = [doc(), doc("old", "琥珀桥的登记号是 星沙82。")]
        samples, coverage = build_samples(CharacterTokenizer(), corpus, "new", max_samples=12)
        self.assertLessEqual(len(samples), 12)
        self.assertGreater(coverage["selected_samples"]["new"], 0)
        self.assertGreater(coverage["selected_samples"]["old"], 0)
        self.assertGreater(coverage["selected_samples"]["unknown"], 0)
        self.assertTrue(coverage["limited_coverage"])

    def test_negative_evaluation_question_cannot_leak_through_raw_or_positive_qa(self):
        reserved = "岚缺XQ901的校验码是什么？"
        payload = doc("faq", reserved + "\n这句不能训练。\n\n澄岚站记录的校验码为松柏47。", [{
            "answer": "松柏47", "training_questions": [reserved, "请给出澄岚站校验码。"],
            "eval_questions": ["澄岚站具体使用哪个校验码？"]}])
        samples, coverage = build_samples(CharacterTokenizer(), [payload], "faq")
        self.assertEqual(1, coverage["raw_segments_removed_for_evaluation_isolation"])
        for sample in samples:
            text = "".join(chr(i) for i in sample["input_ids"])
            self.assertNotIn(normalize(reserved), normalize(text))


class ReviewedAbsenceTests(unittest.TestCase):
    def reviewed(self, source="reviewed", training=None, evaluation=None, text=None):
        return {"payload": prepare_payload({"source_id": source, "title": "合成审核资料",
            "text": text or "澄岚站的校验码是 松柏47。",
            "abstention": {"training_questions": training or ["澄岚站的维修负责人是谁？"],
                           "eval_questions": evaluation or ["澄岚站的维护单位具体是哪家？"]}})}

    def test_reviewed_unknowns_are_separate_from_known_accuracy(self):
        corpus = [self.reviewed()]
        cases = evaluation_cases(corpus, "reviewed")
        reviewed = [case for case in cases if case["origin"] == "locally_reviewed_absence"]
        self.assertEqual(1, len(reviewed))
        self.assertEqual("unknown", reviewed[0]["group"])
        self.assertEqual("reviewed_absence", reviewed[0]["kind"])
        summary = evaluation(corpus, "reviewed", lambda case: case["answer"])
        self.assertEqual(1, summary["new"]["total"])
        self.assertEqual(1, summary["all"]["total"])
        self.assertGreaterEqual(summary["unknown"]["total"], 17)

    def test_reviewed_training_examples_survive_synthetic_sampling(self):
        corpus = [self.reviewed(training=["澄岚站的维修负责人是谁？", "澄岚站的值班室电话是多少？",
                                         "澄岚站哪天完成竣工验收？"])]
        samples, coverage = build_samples(CharacterTokenizer(), corpus, "reviewed")
        self.assertGreaterEqual(coverage["abstention_training"]["selected_kinds"]["reviewed_absence"], 3)
        texts = ["".join(chr(value) for value in sample["input_ids"]) for sample in samples]
        for question in corpus[0]["payload"]["abstention"]["training_questions"]:
            self.assertTrue(any(question in text for text in texts))
        self.assertIn("reviewed-v5", coverage["abstention_training"]["bank_version"])

    def test_each_reviewed_field_is_covered_before_additional_variants(self):
        questions = [f"澄岚站维修分组编号{index}的负责人是谁？" for index in range(5)]
        corpus = [self.reviewed(training=questions)]
        bank = [case for case in refusal_cases(corpus, "train") if case["kind"] == "reviewed_absence"]
        self.assertEqual(20, len(bank))
        self.assertEqual(5, len({case["base_case_id"] for case in bank}))
        for case_id in {case["base_case_id"] for case in bank}:
            self.assertEqual(4, len([case for case in bank if case["base_case_id"] == case_id]))
        samples, coverage = build_samples(CharacterTokenizer(), corpus, "reviewed")
        details = coverage["abstention_training"]
        self.assertEqual(5, details["reviewed_fields_available"])
        self.assertEqual(5, details["reviewed_fields_selected"])
        self.assertEqual([], details["reviewed_unselected_base_case_ids"])
        self.assertEqual(20, details["reviewed_variants_available"])
        self.assertGreaterEqual(details["reviewed_variants_selected"], details["reviewed_fields_selected"])
        for kind in ("unknown_entity", "forced_guess", "synthetic_missing_field"):
            self.assertGreater(details["selected_kinds"][kind], 0)
        self.assertEqual(len(samples), sum(coverage["selected_samples"].values()))

    def test_reviewed_wrappers_do_not_modify_eval_bank_or_train_eval_questions(self):
        training = "澄岚站的维修负责人是谁？"
        held_out = "澄岚站的维护单位具体是哪家？"
        corpus = [self.reviewed(training=[training], evaluation=[held_out])]
        evaluated = [case for case in refusal_cases(corpus, "eval") if case["kind"] == "reviewed_absence"]
        self.assertEqual([held_out], [case["question"] for case in evaluated])
        self.assertNotIn("base_case_id", evaluated[0])
        self.assertNotIn("variant_index", evaluated[0])
        second = self.reviewed("other", training=["琥珀桥的建造商是谁？"], evaluation=[training],
                               text="琥珀桥的登记号是 星沙82。")
        samples, _ = build_samples(CharacterTokenizer(), corpus + [second], "other")
        for sample in samples:
            text = "".join(chr(value) for value in sample["input_ids"])
            self.assertNotIn(normalize(training), normalize(text))

    def test_cross_source_manual_eval_filters_manual_train_and_raw_faq(self):
        held_out = "澄岚站的维修负责人是谁？"
        first = self.reviewed()
        second = self.reviewed("other", training=["琥珀桥的建造商是谁？"], evaluation=[held_out],
            text=held_out + "\n这段只用于隔离测试。\n\n琥珀桥的登记号是 星沙82。")
        samples, coverage = build_samples(CharacterTokenizer(), [first, second], "other")
        self.assertEqual(1, coverage["raw_segments_removed_for_evaluation_isolation"])
        for sample in samples:
            text = "".join(chr(value) for value in sample["input_ids"])
            self.assertNotIn(normalize(held_out), normalize(text))

    def test_cross_source_known_question_cannot_be_trained_as_unknown(self):
        known = doc("known")
        question = known["payload"]["facts"][0]["training_questions"][0]
        manual = self.reviewed("other", training=[question],
            text="琥珀桥的登记号是 星沙82。")
        bank = refusal_cases([known, manual], "train")
        self.assertFalse(any(case["question"] == question for case in bank))

    def test_annotations_reject_invalid_types_empty_or_duplicate_questions(self):
        variants = [[], {}, {"training_questions": [], "eval_questions": ["某事项？"]},
            {"training_questions": [None], "eval_questions": ["某事项？"]},
            {"training_questions": ["？"], "eval_questions": ["某事项？"]},
            {"training_questions": ["谁负责?", "谁负责？"], "eval_questions": ["某事项？"]},
            {"training_questions": ["谁负责？"], "eval_questions": ["谁负责 ?"]},
            {"training_questions": ["澄岚站的校验码是什么？"], "eval_questions": ["谁负责？"]}]
        for value in variants:
            with self.subTest(value=value), self.assertRaises(ValueError):
                prepare_payload({"source_id": "bad", "title": "合成", "text": "澄岚站的校验码是 松柏47。",
                                 "abstention": value})

    def test_auto_unknown_answer_is_skipped_but_manual_known_target_is_rejected(self):
        prepared = prepare_payload({"source_id": "ordinary", "title": "合成", "text": "我的答案是 不知道。",
                                    "abstention": None})
        self.assertEqual([], prepared["facts"])
        self.assertEqual("unvalidated", gate(summarize_eval([]), summarize_eval([]))[0])
        with self.assertRaisesRegex(ValueError, "Known facts"):
            prepare_payload({"source_id": "manual", "title": "合成", "text": "合成记录",
                "facts": [{"answer": "不知道", "training_questions": ["记录为何？"],
                           "eval_questions": ["记录的具体内容？"]}]})

    def test_reserved_eval_entity_never_gets_a_training_missing_field(self):
        corpus = [doc(text="岚缺XQ901的校验码是 松柏47。")]
        self.assertFalse(any("岚缺XQ901" in case["question"] for case in refusal_cases(corpus, "train")))


class EntityContrastTests(unittest.TestCase):
    def bound_doc(self):
        return doc("benchmark", "青河基准的严格协议F1分数是 7.4。", [{
            "entity": "青河基准", "subject": "青河基准的严格协议F1分数", "answer": "7.4",
            "training_questions": ["在青河基准上使用严格协议时，F1分数是多少？"],
            "eval_questions": ["严格协议条件下，青河基准记载了哪个F1数值？"]}])

    def test_contrast_changes_only_bound_entity_and_is_permanently_train_only(self):
        corpus = [self.bound_doc()]
        before_eval = refusal_cases(corpus, "eval")
        cases, stats = entity_contrast_cases(corpus)
        self.assertEqual(1, stats["available"])
        self.assertEqual(0, stats["skipped"])
        case = cases[0]
        self.assertEqual("unknown", case["group"])
        self.assertEqual("不知道", case["answer"])
        self.assertEqual("entity_contrast", case["kind"])
        self.assertEqual("train", case["split"])
        original = corpus[0]["payload"]["facts"][0]["training_questions"][0]
        self.assertEqual(original.replace("青河基准", case["entity"], 1), case["question"])
        self.assertNotIn("青河基准", case["question"])
        self.assertNotIn(case["entity"], corpus[0]["payload"]["text"])
        self.assertFalse(any(marker in case["entity"].lower() for marker in
                             ("训练", "对照", "未知", "虚构", "train", "contrast", "unknown")))
        later = corpus + [doc("later", "栖羽站的进度是 三成。")]
        self.assertEqual(case, entity_contrast_cases(later)[0][0])
        self.assertEqual(before_eval, refusal_cases(corpus, "eval"))
        self.assertFalse(any(row["kind"] == "entity_contrast" for row in before_eval))
        self.assertFalse(any(case["entity"] in row["question"] for row in before_eval))

    def test_ambiguous_bindings_and_replacement_collisions_are_reported(self):
        valid = self.bound_doc()
        replacement = entity_contrast_cases([valid])[0][0]["entity"]
        collision = doc("collision", replacement + "列在这里作为冲突记录。")
        cases, stats = entity_contrast_cases([valid, collision])
        self.assertEqual([], cases)
        self.assertEqual(1, stats["skipped_by_reason"]["replacement_entity_in_corpus"])
        ambiguous = doc("ambiguous", "青河基准的分数是 7.4。", [{"entity": "青河基准", "answer": "7.4",
            "training_questions": ["青河基准和青河基准组合后的分数是多少？"],
            "eval_questions": ["该基准记录的具体分数？"]}])
        cases, stats = entity_contrast_cases([ambiguous])
        self.assertEqual([], cases)
        self.assertEqual(1, stats["skipped_by_reason"]["no_unambiguous_training_question"])

    def test_contrast_question_is_filtered_if_it_collides_with_global_eval(self):
        corpus = [self.bound_doc()]
        contrast = entity_contrast_cases(corpus)[0][0]["question"]
        other = {"payload": prepare_payload({"source_id": "other", "title": "合成审核",
            "text": "这一段没有可提取的明确键值。",
            "abstention": {"training_questions": ["负责人应填哪位？"], "eval_questions": [contrast]}})}
        cases, stats = entity_contrast_cases(corpus + [other])
        # The exact entity is now explicitly present in corpus annotations, so
        # absence cannot be assumed even before the global wording check.
        self.assertEqual([], cases)
        self.assertEqual(1, stats["skipped"])
        samples, _ = build_samples(CharacterTokenizer(), corpus + [other], "benchmark")
        self.assertFalse(any(normalize(contrast) in normalize("".join(chr(value) for value in sample["input_ids"]))
                             for sample in samples))

    def test_priorities_cover_fields_kinds_and_contrasts_without_losing_positive_qa(self):
        training = [f"澄岚站维修分组编号{index}的负责人是谁？" for index in range(10)]
        payload = prepare_payload({"source_id": "reviewed", "title": "合成审核",
            "text": "澄岚站的校验码是 松柏47。",
            "abstention": {"training_questions": training, "eval_questions": ["澄岚站的维护单位具体是哪家？"]}})
        corpus = [self.bound_doc(), {"payload": payload}] + [doc(f"extra-{index}",
            f"合成站点{index}的编号是 标记{index:02d}。") for index in range(20)]
        samples, coverage = build_samples(CharacterTokenizer(), corpus, "reviewed")
        details = coverage["abstention_training"]
        self.assertEqual(10, details["reviewed_fields_selected"])
        self.assertEqual(details["entity_contrast"]["available"], details["entity_contrast"]["selected"])
        for kind in ("reviewed_absence", "unknown_entity", "forced_guess", "synthetic_missing_field", "entity_contrast"):
            self.assertGreater(details["selected_kinds"][kind], 0)
        for kind, quota in (("unknown_entity", 4), ("forced_guess", 4), ("synthetic_missing_field", 2)):
            self.assertGreaterEqual(details["selected_kinds"][kind], quota)
        self.assertEqual(127, details["unique_positive_qa"])
        self.assertTrue(0.25 <= details["unknown_qa_ratio"] <= 0.35)
        self.assertLessEqual(len(samples), 256)
        small, limited = build_samples(CharacterTokenizer(), corpus, "reviewed", max_samples=12)
        self.assertLessEqual(len(small), 12)
        self.assertTrue(limited["abstention_training"]["limited_coverage"])


if __name__ == "__main__":
    unittest.main()
