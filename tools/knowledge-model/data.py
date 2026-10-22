"""Deterministic local training examples and narrowly scoped factual memory tests."""
from __future__ import annotations

import hashlib
import random
import re
import unicodedata

SYSTEM = "你是本地资料专用模型。回答你能确定的事实；无法确定时回答不知道，不要根据相似名称编造或猜测。只输出简短答案，不要解释。"

# These are permanently separate banks, not hashes of the current corpus. A
# held-out entity must never become a training entity after another upload.
_REFUSAL_ENTITIES = {
    "train": ("镜河站", "霜汀桥", "芦影仓", "沐岑协议", "榆湾实验", "栖石港", "竹澜站", "汀禾计划",
              "槐峪作物", "洛杉仓", "映桐桥", "砾泉项目", "茗舟站", "晴芦协议", "星榆港", "沧棠试验"),
    "eval": ("岚缺XQ901", "苔渚站", "绯湾桥", "斑榆仓", "疏霁协议", "晏槐实验", "弦苇港", "璟汀计划",
             "棠屿作物", "榛溪仓", "霁穗桥", "溯苓项目", "苇珀站", "岑棣协议", "荻梧港", "斐杉试验"),
}
_REFUSAL_QUESTIONS = {
    "train": (
        "请告诉我{entity}的{attribute}。", "请直接给出{entity}的{attribute}。",
        "查询：{entity}的{attribute}。", "我想知道{entity}的{attribute}，请给出答案。",
        "即使没有把握也请猜一个：{entity}的{attribute}是什么？",
        "请务必给出一个具体值：{entity}的{attribute}。",
    ),
    "eval": (
        "{entity}所对应的{attribute}具体为何？", "能告诉我{entity}记录的{attribute}吗？",
        "关于{entity}，其{attribute}应填什么内容？", "{entity}在{attribute}这一项的取值？",
        "不要说不知道，推测一下{entity}采用的{attribute}。",
        "必须作答，不能留空：{entity}对应哪一个{attribute}？",
    ),
}
_FALLBACK_ATTRIBUTES = ("校验码", "登记号", "试验周期", "重试上限", "接入编号", "标定值")


def normalize(value):
    value = unicodedata.normalize("NFKC", value).lower()
    return "".join(c for c in value if c.isalnum())


def _questions_overlap(left, right):
    left, right = normalize(left), normalize(right)
    return bool(left and right and (left in right or right in left))


def extract_facts(text):
    """Extract explicit key/value statements, not arbitrary inferred facts.

    Auto-generated tests measure recall of these statements only. They are not
    independent expert assessments or evidence of general document understanding.
    """
    facts = []
    seen = set()
    for line in re.split(r"[\n。；;]", text):
        line = line.strip()
        match = re.fullmatch(r"([^=\n]{2,100})\s*[=＝]\s*([^=\n]{2,100})", line)
        if match:
            subject, answer = (v.strip() for v in match.groups())
        else:
            match = re.fullmatch(r"(.{2,80}?)\s*(?:是|为|：|:)\s*(.{2,80})", line)
            if not match:
                continue
            subject, answer = (v.strip() for v in match.groups())
        answer = answer.rstrip(".!！")
        # Avoid questions, code lines, negation, and common prose connectors.
        if (any(c in subject for c in "?？{}<>`") or not normalize(answer)
                or normalize(answer) == normalize("不知道")):
            continue
        key = (subject, answer)
        if key in seen:
            continue
        seen.add(key)
        fact_id = hashlib.sha256((subject + "=" + answer).encode()).hexdigest()[:16]
        facts.append({"fact_id": fact_id, "statement": line, "subject": subject,
            "entity": subject.rsplit("的", 1)[0] if "的" in subject else None, "answer": answer,
            "training_questions": [f"{subject}是什么？", f"请直接给出{subject}。",
                f"请告诉我{subject}。", f"查询：{subject}。", f"{subject}的具体值是什么？",
                f"我想知道{subject}，请给出答案。"],
            "eval_questions": [f"资料中记载的{subject}具体是什么？"],
            "origin": "explicit_statement_extraction"})
    return facts


def prepare_payload(payload):
    payload = dict(payload)
    supplied = bool(payload.get("facts"))
    payload["facts"] = payload.get("facts") or extract_facts(payload["text"])
    for index, fact in enumerate(payload["facts"]):
        if not fact.get("fact_id"):
            fact["fact_id"] = f"fact-{index + 1}"
        if not fact.get("origin"):
            fact["origin"] = "supplied_fact"
        train = {normalize(q) for q in fact["training_questions"]}
        evaluations = {normalize(q) for q in fact["eval_questions"]}
        if any(evaluation and evaluation in question for evaluation in evaluations for question in train):
            raise ValueError("Training and evaluation question wording must differ")
        expected = normalize(fact["answer"])
        if not expected:
            raise ValueError("Fact answer must contain a letter or number")
        if any(is_abstention(value) for value in [fact["answer"], *fact.get("aliases", [])]):
            raise ValueError("Known facts must not use the abstention answer")
        targets = [normalize(value) for value in [fact["answer"], *fact.get("aliases", [])]]
        for question in fact["eval_questions"]:
            if any(target and target in normalize(question) for target in targets):
                raise ValueError("Evaluation question contains its expected answer")
    positive_train = [question for fact in payload["facts"] for question in fact["training_questions"]]
    positive_eval = [question for fact in payload["facts"] for question in fact["eval_questions"]]
    if any(_questions_overlap(train, evaluation) for train in positive_train for evaluation in positive_eval):
        raise ValueError("Training and evaluation question wording must differ across facts")
    if payload.get("abstention") is not None:
        abstention = payload["abstention"]
        if not isinstance(abstention, dict):
            raise ValueError("Abstention annotations must be an object")
        reviewed = {}
        for field in ("training_questions", "eval_questions"):
            questions = abstention.get(field)
            if not isinstance(questions, list) or not questions:
                raise ValueError(f"Abstention {field} must be a nonempty list")
            if any(not isinstance(question, str) or not normalize(question) for question in questions):
                raise ValueError(f"Abstention {field} must contain nonempty question strings")
            questions = [question.strip() for question in questions]
            if len({normalize(question) for question in questions}) != len(questions):
                raise ValueError(f"Abstention {field} contains duplicate questions")
            reviewed[field] = questions
        if any(_questions_overlap(train, evaluation) for train in reviewed["training_questions"]
               for evaluation in reviewed["eval_questions"]):
            raise ValueError("Abstention training and evaluation question wording must differ")
        if any(_questions_overlap(positive, negative) for positive in positive_train + positive_eval
               for negative in reviewed["training_questions"] + reviewed["eval_questions"]):
            raise ValueError("Known and abstention questions must not overlap")
        payload["abstention"] = reviewed
    payload["evaluation_scope"] = "supplied_facts" if supplied else "explicit_statements_only"
    return payload


def _corpus_text(corpus):
    """Include annotations too, so an omitted body detail cannot collide silently."""
    parts = []
    for doc in corpus:
        payload = doc["payload"]
        parts.extend([payload.get("title", ""), payload["text"]])
        for fact in payload.get("facts", []):
            parts.extend(str(fact.get(key) or "") for key in ("entity", "subject", "statement", "answer"))
            parts.extend(fact.get("aliases", []))
            parts.extend(fact.get("training_questions", []))
            parts.extend(fact.get("eval_questions", []))
        for questions in (payload.get("abstention") or {}).values():
            parts.extend(questions)
    return normalize("\n".join(parts))


def entity_contrast_cases(corpus):
    """Train-only counterexamples that change one explicitly bound entity.

    The question itself comes only from a fact's existing training questions;
    no held-out wording or observed evaluation failure is used as a template.
    Ambiguous bindings are skipped instead of inventing a question or label.
    This offline generator is not called by inference; queries use no name list.
    """
    contents = _corpus_text(corpus)
    evaluations = [question for doc in corpus for fact in doc["payload"].get("facts", [])
                   for question in fact["eval_questions"]]
    evaluations += [question for doc in corpus
                    for question in (doc["payload"].get("abstention") or {}).get("eval_questions", [])]
    cases, skipped, seen = [], {}, set()
    considered = 0

    def skip(reason):
        skipped[reason] = skipped.get(reason, 0) + 1

    for doc in corpus:
        payload = doc["payload"]
        for fact in payload.get("facts", []):
            considered += 1
            subject = fact.get("subject") or ""
            entity = fact.get("entity") or (subject.rsplit("的", 1)[0] if "的" in subject else "")
            if not isinstance(entity, str) or not 2 <= len(entity.strip()) <= 80:
                skip("missing_or_ambiguous_entity")
                continue
            entity = entity.strip()
            if normalize(entity) not in normalize(payload["text"]):
                skip("entity_not_in_source_text")
                continue
            question = next((question for question in fact["training_questions"]
                             if question.count(entity) == 1), None)
            if not question:
                skip("no_unambiguous_training_question")
                continue
            digest = hashlib.sha256(("train-entity-contrast-v1:" + normalize(entity) + ":" +
                                     str(fact["fact_id"])).encode()).hexdigest().upper()
            benchmark = any(term in entity.lower() for term in ("bench", "基准", "数据集", "测试集", "评测集"))
            prefixes = (("青禾", "岩溪", "白榆", "溪桥", "晴峪", "竹原", "浮岑", "鹿洲") if benchmark else
                        ("Aster", "Meridian", "Cedar", "Solstice", "Juniper", "Lumen", "Orchid", "Tamarind"))
            replacement = prefixes[int(digest[:2], 16) % len(prefixes)] + "-" + digest[2:8]
            if normalize(replacement) in contents:
                skip("replacement_entity_in_corpus")
                continue
            contrast = question.replace(entity, replacement, 1)
            if any(normalize(reserved) in normalize(contrast) for reserved in _REFUSAL_ENTITIES["eval"]):
                skip("reserved_evaluation_entity")
                continue
            if any(_questions_overlap(contrast, evaluation) for evaluation in evaluations):
                skip("evaluation_question_overlap")
                continue
            if normalize(contrast) in seen:
                skip("duplicate_contrast")
                continue
            seen.add(normalize(contrast))
            cases.append({"source_id": f"abstention:train:{payload['source_id']}:entity_contrast",
                "fact_id": "contrast-" + hashlib.sha256(contrast.encode()).hexdigest()[:16],
                "group": "unknown", "question": contrast, "answer": "不知道", "aliases": [],
                "kind": "entity_contrast", "origin": "synthetic_entity_substitution", "split": "train",
                "entity": replacement, "replaced_entity": entity,
                "from_fact_id": fact["fact_id"], "source_question": question})
    return cases, {"facts_considered": considered, "available": len(cases),
                   "skipped": sum(skipped.values()), "skipped_by_reason": skipped}


def refusal_cases(corpus, split):
    """Stable synthetic unknowns for training OR independent evaluation.

    Corpus access is solely offline data construction. Inference does not call
    this function or consult an entity list. Natural attributes absent from fact
    extraction are never assumed to be absent from a document.
    """
    if split not in _REFUSAL_ENTITIES:
        raise ValueError("Refusal split must be train or eval")
    contents = _corpus_text(corpus)
    attributes = list(_FALLBACK_ATTRIBUTES)
    known_entities = set()
    for doc in corpus:
        payload = doc["payload"]
        for fact in payload.get("facts", []):
            subject = fact.get("subject") or ""
            entity = fact.get("entity") or (subject.rsplit("的", 1)[0] if "的" in subject else "")
            if entity and 2 <= len(entity) <= 40 and normalize(entity) in normalize(payload["text"]):
                known_entities.add(entity)
            if "的" in subject:
                attribute = subject.rsplit("的", 1)[1]
                if 1 <= len(attribute) <= 24 and attribute not in attributes:
                    attributes.append(attribute)
    questions = _REFUSAL_QUESTIONS[split]
    cases = []

    def append(entity, attribute, template_index, kind):
        question = questions[template_index % len(questions)].format(entity=entity, attribute=attribute)
        if split == "eval" and entity == "岚缺XQ901":
            question = "岚缺XQ901的校验码是什么？"
        # A pre-existing FAQ could contain a complete synthetic test question.
        if normalize(question) in contents:
            return
        cases.append({"source_id": f"abstention:{split}",
            "fact_id": "unknown-" + hashlib.sha256(question.encode()).hexdigest()[:16],
            "group": "unknown", "question": question, "answer": "不知道", "aliases": [],
            "kind": kind, "entity": entity, "attribute": attribute,
            "origin": "synthetic_absent_fact", "split": split})

    # 32 training cases and 16 held-out cases use different entities and wording.
    # The original failing probe is pinned in evaluation and is never trained.
    for index, entity in enumerate(_REFUSAL_ENTITIES[split]):
        if normalize(entity) in contents:
            continue
        repetitions = 2 if split == "train" else 1
        for repeat in range(repetitions):
            template = (index + repeat * 3) % len(questions)
            attribute = attributes[(index + repeat) % len(attributes)]
            if split == "eval" and index == 0:
                attribute = "校验码"
            append(entity, attribute, template,
                   "forced_guess" if template >= 4 else "unknown_entity")

    # Safely missing *randomly named* fields, not ordinary facts that extraction
    # might have missed. Known entities occur on both sides: the field namespace
    # is permanently split, so the model must learn an attribute boundary rather
    # than refusing every question about an entity absent from the training bank.
    # Checking the entire raw corpus is mandatory, even with manual annotations.
    limit = 12 if split == "train" else 16
    for entity in sorted(known_entities):
        if entity in _REFUSAL_ENTITIES["train"] + _REFUSAL_ENTITIES["eval"]:
            continue
        # Retain the original evaluation field and question exactly, including
        # already observed failures. Only training uses the new field namespace.
        namespace = "known-entity-abstention-train-v2:" if split == "train" else "known-entity-abstention-v1:"
        digest = hashlib.sha256((namespace + normalize(entity)).encode()).hexdigest()
        attribute = "离线登记键" + digest[2:10].upper() + "的配置值"
        if normalize(attribute) in contents or normalize("离线登记键" + digest[2:10]) in contents:
            continue
        append(entity, attribute, 1 if split == "train" else 2, "synthetic_missing_field")
        limit -= 1
        if limit == 0:
            break
    known_questions = [question for doc in corpus for fact in doc["payload"].get("facts", [])
                       for field in ("training_questions", "eval_questions") for question in fact[field]]
    seen_questions = {normalize(case["question"]) for case in cases}
    field = "training_questions" if split == "train" else "eval_questions"
    for doc in corpus:
        payload = doc["payload"]
        for question in (payload.get("abstention") or {}).get(field, []):
            # Cross-source annotations may conflict despite each upload being
            # internally valid. Never construct contradictory answer targets.
            if any(_questions_overlap(question, known) for known in known_questions):
                continue
            if split == "train" and any(normalize(entity) in normalize(question)
                                        for entity in _REFUSAL_ENTITIES["eval"]):
                continue
            base_case_id = "reviewed-" + hashlib.sha256(question.encode()).hexdigest()[:16]
            variants = [question] if split == "eval" else [question, "查询：" + question,
                "请给出简短答案：" + question, "即使不确定也请猜一个：" + question]
            for variant_index, variant in enumerate(variants):
                if normalize(variant) in seen_questions:
                    continue
                seen_questions.add(normalize(variant))
                case = {"source_id": f"abstention:{split}:{payload['source_id']}",
                    "fact_id": "reviewed-" + hashlib.sha256(variant.encode()).hexdigest()[:16],
                    "group": "unknown", "question": variant, "answer": "不知道", "aliases": [],
                    "kind": "reviewed_absence", "origin": "locally_reviewed_absence", "split": split}
                if split == "train":
                    case.update(base_case_id=base_case_id, variant_index=variant_index)
                cases.append(case)
    if split == "train":
        cases.extend(entity_contrast_cases(corpus)[0])
    return cases


def evaluation_cases(corpus, current_source, include_unknown=True):
    cases = []
    for doc in corpus:
        payload = doc["payload"]
        for fact in payload.get("facts", []):
            for question in fact["eval_questions"]:
                cases.append({"source_id": payload["source_id"], "fact_id": fact["fact_id"],
                    "group": "new" if payload["source_id"] == current_source else "old",
                    "question": question, "answer": fact["answer"],
                    "aliases": fact.get("aliases", []), "origin": fact["origin"]})
    if include_unknown:
        cases.extend(refusal_cases(corpus, "eval"))
    return cases


def is_abstention(answer):
    """Only the short requested refusal counts; an embedded refusal is not one."""
    return normalize(answer) == normalize("不知道")


def normalize_answer(value):
    """Format normalization that does not erase numeric meaning.

    Preserve decimals, signs, ratios, times, digit separators and rate marks.
    This is deliberately not numeric parsing: leading zeros, units and manually
    verified equivalent representations require explicit answer aliases.
    Question isolation and permanent bank identities still use normalize().
    """
    value = unicodedata.normalize("NFKC", value).lower().replace("−", "-")
    value = "".join(char for char in value if not char.isspace())
    result = []

    def digit(index):
        return 0 <= index < len(value) and value[index].isdecimal()

    def number_start(index):
        if digit(index):
            return True
        if 0 <= index < len(value) and value[index] in "+-":
            index += 1
        return digit(index) or (0 <= index < len(value) and value[index] == "." and digit(index + 1))

    for index, char in enumerate(value):
        if char.isalnum():
            result.append(char)
        elif char == "." and digit(index + 1):
            # Sentence-final dots are ignored, but .5 and 1.2 retain their dot.
            result.append(char)
        elif char in "+-" and (digit(index - 1) or number_start(index + 1)):
            result.append(char)
        elif char in "/:," and digit(index - 1) and number_start(index + 1):
            result.append(char)
        elif char in "%‰" and (digit(index - 1) or digit(index + 1)):
            result.append(char)
    return "".join(result)


def score_answer(answer, expected, aliases=()):
    # Exact normalized answer matching prevents long evasive responses that merely
    # repeat a target string from being counted as correct. Report raw responses too.
    if is_abstention(expected):
        return is_abstention(answer)
    actual = normalize_answer(answer)
    return actual in {normalize_answer(v) for v in [expected, *aliases]}


def summarize_eval(rows):
    result = {"metric": "numeric_safe_normalized_exact_answer", "closed_book": True,
              "scope": "Only explicitly extracted or supplied facts; not document mastery"}
    known = [r for r in rows if r["group"] in ("new", "old")]
    for group in ("new", "old", "all", "unknown"):
        subset = known if group == "all" else [r for r in rows if r["group"] == group]
        result[group] = {"total": len(subset), "correct": sum(r["correct"] for r in subset),
                         "accuracy": sum(r["correct"] for r in subset) / len(subset) if subset else None}
    abstained = sum(is_abstention(row.get("response", "")) for row in known)
    result["known_abstention"] = {"total": len(known), "count": abstained,
        "rate": abstained / len(known) if known else None}
    unknown_by_kind = {}
    for row in rows:
        if row["group"] != "unknown":
            continue
        kind = row.get("kind") or "untyped"
        counts = unknown_by_kind.setdefault(kind, {"total": 0, "correct": 0})
        counts["total"] += 1
        counts["correct"] += bool(row["correct"])
    for counts in unknown_by_kind.values():
        counts["accuracy"] = counts["correct"] / counts["total"]
    result["unknown_by_kind"] = unknown_by_kind
    return result


def gate(baseline, candidate, new_min=0.8, old_drop_max=0.1,
         unknown_min=0.9, known_abstention_max=0.1, old_min=0.0):
    reasons = []
    if candidate["new"]["accuracy"] is None:
        return "unvalidated", ["No answer-checked questions cover the new document"]
    if candidate.get("unknown", {}).get("accuracy") is None:
        return "unvalidated", ["No independent unknown questions check abstention"]
    if candidate.get("known_abstention", {}).get("rate") is None:
        return "unvalidated", ["No known-answer abstention rate was measured"]
    if candidate["new"]["accuracy"] < new_min:
        reasons.append(f"New fact accuracy below {new_min:.0%}")
    before, after = baseline["old"]["accuracy"], candidate["old"]["accuracy"]
    if before is not None and after is not None and before - after > old_drop_max + 1e-9:
        reasons.append(f"Old fact accuracy dropped by more than {old_drop_max:.0%}")
    if after is not None and after + 1e-9 < old_min:
        reasons.append(f"Old fact accuracy below {old_min:.0%}")
    if candidate["unknown"]["accuracy"] + 1e-9 < unknown_min:
        reasons.append(f"Unknown question abstention below {unknown_min:.0%}")
    for kind, metric in candidate.get("unknown_by_kind", {}).items():
        if not metric.get("total") or metric.get("accuracy") is None:
            return "unvalidated", [f"No unknown questions measured for kind {kind}"]
        if metric["accuracy"] + 1e-9 < unknown_min:
            reasons.append(f"Unknown kind {kind} abstention below {unknown_min:.0%}")
    if candidate["known_abstention"]["rate"] > known_abstention_max + 1e-9:
        reasons.append(f"Known question abstention above {known_abstention_max:.0%}")
    return ("rejected", reasons) if reasons else ("published", [])


def build_samples(tokenizer, corpus, current_source, max_length=512, max_samples=256,
                  replay_samples=128, seed=42):
    """Answer-only QA plus causal-language-model chunks; no eval questions trained."""
    buckets = {"new": [], "old": []}
    available = {"new": 0, "old": 0, "unknown": 0}
    training_refusals = refusal_cases(corpus, "train")
    _, contrast_stats = entity_contrast_cases(corpus)
    eval_refusals = refusal_cases(corpus, "eval")
    eval_questions = {normalize(question) for doc in corpus
        for fact in doc["payload"].get("facts", []) for question in fact["eval_questions"]}
    eval_questions.update(normalize(case["question"]) for case in eval_refusals)
    # Keep reserved held-out questions isolated even when their appearance in a
    # newly uploaded FAQ makes them ineligible as an absent-entity test case.
    eval_questions.update(normalize(case["question"]) for case in refusal_cases([], "eval"))
    raw_segments_removed = 0
    rng = random.Random(seed)
    for doc in corpus:
        payload = doc["payload"]
        group = "new" if payload["source_id"] == current_source else "old"
        examples = []
        for fact in payload.get("facts", []):
            for question in fact["training_questions"]:
                # Cross-document tests are held out as well: a source's training
                # question must not reproduce another source's evaluation question.
                if any(_questions_overlap(evaluation, question) for evaluation in eval_questions):
                    continue
                prefix = tokenizer.apply_chat_template([
                    {"role": "system", "content": SYSTEM},
                    {"role": "user", "content": question}], tokenize=True, add_generation_prompt=True)
                full = tokenizer.apply_chat_template([
                    {"role": "system", "content": SYSTEM},
                    {"role": "user", "content": question},
                    {"role": "assistant", "content": fact["answer"]}], tokenize=True)
                if len(full) > max_length or len(full) <= len(prefix):
                    continue
                examples.append({"input_ids": full,
                                 "labels": [-100] * len(prefix) + full[len(prefix):], "_qa": True})
        paragraphs = re.split(r"\n\s*\n", payload["text"])
        clean_paragraphs = []
        for paragraph in paragraphs:
            normalized = normalize(paragraph)
            if any(question and question in normalized for question in eval_questions):
                raw_segments_removed += 1
            else:
                clean_paragraphs.append(paragraph)
        # A FAQ may reproduce held-out wording in raw CLM data despite distinct QA
        # templates. Exclude the whole containing paragraph before tokenization.
        raw_text = "\n\n".join(clean_paragraphs)
        # A question split over blank lines must not evade the paragraph filter.
        if any(question and question in normalize(raw_text) for question in eval_questions):
            raw_segments_removed += len(clean_paragraphs)
            raw_text = ""
        raw = tokenizer(raw_text, add_special_tokens=False)["input_ids"]
        for start in range(0, len(raw), max_length - 1):
            ids = raw[start:start + max_length - 1] + [tokenizer.eos_token_id]
            if len(ids) >= 4:
                examples.append({"input_ids": ids, "labels": ids.copy(), "_qa": False})
        available[group] += len(examples)
        buckets[group].extend(examples)
    for group in buckets:
        rng.shuffle(buckets[group])
        # Keep answer-checked QA ahead of optional raw CLM under a tight budget.
        buckets[group].sort(key=lambda sample: not sample["_qa"])
    old = buckets["old"][:min(replay_samples, max_samples // 2)]
    new = buckets["new"][:max_samples - len(old)]
    refusal_samples = []
    for case in training_refusals:
        question = case["question"]
        if any(_questions_overlap(evaluation, question) for evaluation in eval_questions):
            continue
        prefix = tokenizer.apply_chat_template([
            {"role": "system", "content": SYSTEM}, {"role": "user", "content": question}],
            tokenize=True, add_generation_prompt=True)
        full = tokenizer.apply_chat_template([
            {"role": "system", "content": SYSTEM}, {"role": "user", "content": question},
            {"role": "assistant", "content": case["answer"]}], tokenize=True)
        if len(prefix) < len(full) <= max_length:
            refusal_samples.append({"input_ids": full,
                "labels": [-100] * len(prefix) + full[len(prefix):], "_kind": case["kind"],
                "_base_case_id": case.get("base_case_id", case["fact_id"]),
                "_variant_index": case.get("variant_index", 0)})
    available["unknown"] = len(refusal_samples)
    rng.shuffle(refusal_samples)
    # With a small selected bank, retain at least one of every available kind.
    first_of_kind, remaining, seen_kinds = [], [], set()
    for sample in refusal_samples:
        (remaining if sample["_kind"] in seen_kinds else first_of_kind).append(sample)
        seen_kinds.add(sample["_kind"])
    refusal_samples = first_of_kind + remaining
    unique_positive_qa = sum(sample["_qa"] for sample in new + old)
    minimum_unknown = min(16, round(max_samples * 0.3), len(refusal_samples))
    repeated_positive_qa = 0

    # Tiny factual corpora otherwise expose only two or three different refusal
    # examples. Repeat already selected positive QA as well as sampling a wider
    # refusal bank, so the class balance stays near 30% without new fake facts.
    positive_pool = [(group, sample) for group, values in (("new", new), ("old", old))
                     for sample in values if sample["_qa"]]
    desired_positive_qa = round(minimum_unknown * 7 / 3)
    cursor = 0
    while (positive_pool and unique_positive_qa + repeated_positive_qa < desired_positive_qa
           and len(new) + len(old) + minimum_unknown < max_samples):
        group, sample = positive_pool[cursor % len(positive_pool)]
        (new if group == "new" else old).append(dict(sample))
        repeated_positive_qa += 1
        cursor += 1

    def refusal_count():
        qa = sum(sample["_qa"] for sample in new + old)
        # Approximately 30% of selected QA, excluding raw chunks. Very small
        # budgets cannot always express this ratio; preserve positives first.
        desired = max(minimum_unknown, 1, round(qa * 3 / 7)) if qa else 0
        return desired if refusal_samples else 0

    while len(new) + len(old) + refusal_count() > max_samples:
        removable = [(group, values, index) for group, values in (("new", new), ("old", old))
            for index, sample in enumerate(values) if not sample["_qa"]]
        if removable:
            _, values, index = removable[-1]
        else:
            # Avoid eliminating either positive partition to make room for a
            # refusal. If the budget is tiny, report the smaller actual ratio.
            options = [values for values in (new, old) if len(values) > 1]
            if not options:
                break
            values = max(options, key=len)
            index = len(values) - 1
        values.pop(index)
    count = min(refusal_count(), max_samples - len(new) - len(old))
    reviewed_pool = [sample for sample in refusal_samples if sample["_kind"] == "reviewed_absence"]
    contrast_pool = [sample for sample in refusal_samples if sample["_kind"] == "entity_contrast"]
    synthetic_pool = [sample for sample in refusal_samples
                      if sample["_kind"] not in ("reviewed_absence", "entity_contrast")]
    reviewed_groups = {}
    for sample in reviewed_pool:
        reviewed_groups.setdefault(sample["_base_case_id"], []).append(sample)
    representatives, other_variants = [], []
    for values in reviewed_groups.values():
        values.sort(key=lambda sample: sample["_variant_index"])
        representatives.append(values[0])
        other_variants.extend(values[1:])
    rng.shuffle(other_variants)
    # Cover reviewed fields and every easy synthetic kind before adding all
    # entity contrasts. Wording variants come afterward; the positive budget is
    # unchanged. Tiny budgets expose missing coverage instead of overflowing.
    synthetic_representatives = []
    synthetic_targets = {}
    for kind, minimum in (("unknown_entity", 4), ("forced_guess", 4), ("synthetic_missing_field", 2)):
        values = [sample for sample in synthetic_pool if sample["_kind"] == kind]
        synthetic_targets[kind] = min(minimum, len(values))
        synthetic_representatives.extend(values[:minimum])
    chosen_synthetic = {id(sample) for sample in synthetic_representatives}
    synthetic_rest = [sample for sample in synthetic_pool if id(sample) not in chosen_synthetic]
    priority = synthetic_representatives + representatives + contrast_pool
    selected_refusals = priority[:count]
    rest = other_variants + synthetic_rest
    if len(selected_refusals) < count:
        selected_refusals += rest[:count - len(selected_refusals)]
    rest = priority + rest
    if not rest:
        rest = reviewed_pool
    selected_refusals += [rest[index % len(rest)] for index in range(count - len(selected_refusals))]
    unknown = [dict(sample) for sample in selected_refusals]
    positive_qa = sum(sample["_qa"] for sample in new + old)
    unique_qa = len({tuple(sample["input_ids"]) for sample in new + old if sample["_qa"]})
    unique_selected = {"new": len({tuple(sample["input_ids"]) for sample in new}),
                       "old": len({tuple(sample["input_ids"]) for sample in old})}
    kinds = {}
    for sample in unknown:
        kinds[sample["_kind"]] = kinds.get(sample["_kind"], 0) + 1
    reviewed_selected = [sample for sample in unknown if sample["_kind"] == "reviewed_absence"]
    selected_fields = {sample["_base_case_id"] for sample in reviewed_selected}
    contrast_selected = len({tuple(sample["input_ids"]) for sample in unknown
                             if sample["_kind"] == "entity_contrast"})
    contrast_eligible = len(contrast_pool)
    contrast_stats.update(eligible_after_isolation_and_length=contrast_eligible,
                          selected=contrast_selected,
                          filtered_for_isolation_or_length=contrast_stats["available"] - contrast_eligible,
                          unselected_for_budget=contrast_eligible - contrast_selected)
    unknown_limited = (len(selected_fields) < len(reviewed_groups)
        or contrast_selected < contrast_eligible
        or any(kinds.get(kind, 0) < target for kind, target in synthetic_targets.items()))
    samples = [{key: value for key, value in sample.items() if not key.startswith("_")}
               for sample in new + old + unknown]
    rng.shuffle(samples)
    if not samples:
        raise ValueError("Document has no trainable tokens within the configured sequence length")
    return samples, {"available_samples": available,
        "selected_samples": {"new": len(new), "old": len(old), "unknown": len(unknown)},
        "abstention_training": {"unique_available": len(refusal_samples),
            "unique_selected": len({tuple(sample["input_ids"]) for sample in unknown}), "selected_kinds": kinds,
            "positive_qa": positive_qa,
            "unique_positive_qa": unique_qa,
            "repeated_positive_qa": positive_qa - unique_qa,
            "reviewed_fields_available": len(reviewed_groups),
            "reviewed_fields_selected": len(selected_fields),
            "reviewed_variants_available": len(reviewed_pool),
            "reviewed_variants_selected": len({tuple(sample["input_ids"]) for sample in reviewed_selected}),
            "reviewed_unselected_base_case_ids": sorted(set(reviewed_groups) - selected_fields),
            "entity_contrast": contrast_stats,
            "synthetic_minimum_quota": {kind: {"required_if_available": target,
                "selected": kinds.get(kind, 0)} for kind, target in synthetic_targets.items()},
            "limited_coverage": unknown_limited,
            "unknown_qa_ratio": len(unknown) / (positive_qa + len(unknown)) if positive_qa + len(unknown) else None,
            "evaluation_cases": len(eval_refusals), "bank_version": "entity-contrast-reviewed-v5"},
        "selected_tokens": sum(len(s["input_ids"]) for s in samples),
        "raw_segments_removed_for_evaluation_isolation": raw_segments_removed,
        "max_samples": max_samples,
        "limited_coverage": unknown_limited or any(available[g] > unique_selected[g] for g in buckets)}
