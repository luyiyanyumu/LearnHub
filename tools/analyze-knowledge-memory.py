"""Summarize local memory experiments without exposing individual questions.

Example:
  python tools/analyze-knowledge-memory.py --report output/experiment/report.json \
      --output output/experiment/memory-summary.json

Only the workspace's output/ tree may contain inputs or results. This tool reads
recorded baseline/candidate evaluations; it does not call a model or train one.
An in-progress experiment is supported: completed rounds are analyzed and other
rounds are marked pending. Alignment/schema errors are explicit and cause exit 1.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import math
import sys
import unicodedata
from functools import lru_cache
from pathlib import Path


TERMINAL = {"published", "rejected", "unvalidated", "failed"}
WORKSPACE = Path(__file__).resolve().parent.parent
LEGACY_METRIC = "normalized_exact_answer"
NUMERIC_SAFE_METRIC = "numeric_safe_normalized_exact_answer"


class AnalysisError(ValueError):
    """Messages deliberately omit private question, answer, and title values."""

    def __init__(self, code, message, **details):
        super().__init__(message)
        self.item = {"code": code, "message": message, **details}


def normalize(value):
    return "".join(c for c in unicodedata.normalize("NFKC", value).lower() if c.isalnum())


def evaluation_metric(document):
    summary = document.get("summary")
    if summary is None:
        return LEGACY_METRIC
    if not isinstance(summary, dict):
        raise AnalysisError("invalid_summary", "An evaluation summary must be an object.")
    metric = summary.get("metric", LEGACY_METRIC)
    if metric not in (LEGACY_METRIC, NUMERIC_SAFE_METRIC):
        raise AnalysisError("unsupported_metric", "Evaluation uses an unsupported exact-answer scoring rule.")
    return metric


@lru_cache(maxsize=2)
def answer_normalizer(metric):
    if metric == LEGACY_METRIC:
        return normalize
    if metric == NUMERIC_SAFE_METRIC:
        # The pure-CPU data module owns this rule. Import it only for artifacts
        # that declare the new metric; historical reports keep their old rule.
        spec = importlib.util.spec_from_file_location("knowledge_memory_data_rules",
            WORKSPACE / "tools" / "knowledge-model" / "data.py")
        if spec is None or spec.loader is None:
            raise AnalysisError("unavailable_metric", "The declared numeric-safe scoring rule cannot be loaded.")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        normalizer = getattr(module, "normalize_answer", None)
        if not callable(normalizer):
            raise AnalysisError("unavailable_metric", "The declared numeric-safe scoring rule is unavailable.")
        return normalizer
    raise AnalysisError("unsupported_metric", "Evaluation uses an unsupported exact-answer scoring rule.")


def local_path(value, workspace, *, relative_to=None):
    if not isinstance(value, (str, Path)) or not str(value).strip() or "\0" in str(value):
        raise AnalysisError("invalid_path", "A required local artifact path is absent or invalid.")
    root = (workspace / "output").resolve()
    path = Path(value)
    if not path.is_absolute():
        # Paths beginning with output/ are relative to the workspace; other
        # relative artifact paths are relative to the experiment report.
        base = workspace if path.parts and path.parts[0].lower() == "output" else (relative_to or workspace)
        path = base / path
    try:
        path = path.resolve()
    except (OSError, RuntimeError, ValueError):
        raise AnalysisError("invalid_path", "A local artifact path cannot be resolved.") from None
    if not path.is_relative_to(root) or path == root:
        raise AnalysisError("path_outside_output", "Artifact paths must stay within this workspace's output directory.")
    return path


def read_json(path):
    try:
        return json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, UnicodeError, ValueError):
        raise AnalysisError("unreadable_json", "A required local JSON artifact is missing, unreadable, or invalid.") from None


def case_digest(key):
    return hashlib.sha256(json.dumps(key, ensure_ascii=False).encode("utf-8")).hexdigest()[:16]


def index_cases(document):
    if not isinstance(document, dict) or not isinstance(document.get("cases"), list):
        raise AnalysisError("invalid_evaluation", "Evaluation must contain a cases array.")
    normalizer = answer_normalizer(evaluation_metric(document))
    indexed = {}
    for row in document["cases"]:
        if not isinstance(row, dict):
            raise AnalysisError("invalid_case", "Evaluation cases must be objects.")
        key = tuple(row.get(name) for name in ("source_id", "fact_id", "question"))
        if any(not isinstance(value, str) or not value.strip() for value in key):
            raise AnalysisError("invalid_case_key", "Each case needs nonempty source_id, fact_id, and question strings.")
        digest = case_digest(key)
        if key in indexed:
            raise AnalysisError("duplicate_case", "An evaluation contains a duplicate case identity.", case_digest=digest)
        aliases = row.get("aliases", [])
        if (row.get("group") not in {"new", "old", "unknown"}
                or not isinstance(row.get("answer"), str)
                or not normalize(row["answer"])
                or not isinstance(row.get("response"), str)
                or type(row.get("correct")) is not bool
                or not isinstance(aliases, list)
                or any(not isinstance(alias, str) or not normalize(alias) for alias in aliases)
                or ("kind" in row and (not isinstance(row["kind"], str) or not row["kind"].strip()))):
            raise AnalysisError("invalid_case", "Case metadata, response, answer, aliases, or correctness is invalid.", case_digest=digest)
        if row["group"] == "unknown" and normalize(row["answer"]) != normalize("不知道"):
            raise AnalysisError("invalid_unknown_target", "An unknown case must expect the short abstention answer.", case_digest=digest)
        expected = {normalizer(value) for value in [row["answer"], *aliases]}
        correct = (normalize(row["response"]) == normalize("不知道")
            if normalize(row["answer"]) == normalize("不知道") else normalizer(row["response"]) in expected)
        if row["correct"] != correct:
            raise AnalysisError("correctness_mismatch", "Recorded correctness disagrees with normalized exact answer matching.", case_digest=digest)
        indexed[key] = row
    return indexed


def accuracy(rows):
    total = len(rows)
    correct = sum(row["correct"] for row in rows)
    return {"total": total, "correct": correct, "accuracy": correct / total if total else None}


def statistics(rows):
    rows = list(rows)
    known = [row for row in rows if row["group"] in {"new", "old"}]
    unknown = [row for row in rows if row["group"] == "unknown"]
    result = {group: accuracy([row for row in known if row["group"] == group]) for group in ("new", "old")}
    result["all"] = accuracy(known)
    abstained = sum(normalize(row["response"]) == normalize("不知道") for row in known)
    result["known_abstention"] = {"total": len(known), "count": abstained,
        "rate": abstained / len(known) if known else None}
    result["unknown"] = {"overall": accuracy(unknown)}
    for field in ("kind", "source_id"):
        groups = {}
        for row in unknown:
            groups.setdefault(row.get(field, "unspecified"), []).append(row)
        result["unknown"]["by_" + field] = {key: accuracy(group) for key, group in sorted(groups.items())}
    return result


def check_aggregate(actual, expected, **details):
    if not isinstance(actual, dict):
        raise AnalysisError("summary_mismatch", "Recorded aggregate summary disagrees with its cases.", **details)
    for field, value in expected.items():
        observed = actual.get(field)
        if value is None:
            equal = observed is None
        elif isinstance(value, int):
            equal = type(observed) is int and observed == value
        else:
            try:
                equal = (isinstance(observed, (float, int)) and not isinstance(observed, bool)
                    and math.isfinite(observed) and math.isclose(observed, value, rel_tol=1e-9, abs_tol=1e-12))
            except OverflowError:
                equal = False
        if not equal:
            raise AnalysisError("summary_mismatch", "Recorded aggregate summary disagrees with its cases.", field=field, **details)


def check_summary(document, recomputed):
    """A stale aggregate must not silently disagree with its individual cases."""
    summary = document.get("summary")
    if summary is None:
        return
    if not isinstance(summary, dict):
        raise AnalysisError("invalid_summary", "An evaluation summary must be an object.")
    for group in ("new", "old", "all", "unknown", "known_abstention"):
        if group not in summary:
            continue
        expected = recomputed["unknown"]["overall"] if group == "unknown" else recomputed[group]
        check_aggregate(summary[group], expected, group=group)
    # Earlier artifacts omit this v4 field. If it is present, every kind and its
    # exact counts must match the individual rows, including low-scoring kinds.
    if "unknown_by_kind" in summary:
        actual = summary["unknown_by_kind"]
        expected = recomputed["unknown"]["by_kind"]
        if not isinstance(actual, dict) or actual.keys() != expected.keys():
            raise AnalysisError("summary_mismatch", "Recorded unknown kind groups disagree with their cases.", group="unknown_by_kind")
        for kind, metric in expected.items():
            check_aggregate(actual[kind], metric, group="unknown_by_kind", kind=kind)


def compare_evaluations(baseline_document, candidate_document):
    if not isinstance(baseline_document, dict) or not isinstance(candidate_document, dict):
        raise AnalysisError("invalid_evaluation", "Evaluation must contain a cases array.")
    metric = evaluation_metric(baseline_document)
    if metric != evaluation_metric(candidate_document):
        raise AnalysisError("metric_mismatch", "Baseline and candidate use different scoring rules and cannot be compared fairly.")
    before = index_cases(baseline_document)
    after = index_cases(candidate_document)
    missing = before.keys() - after.keys()
    added = after.keys() - before.keys()
    if missing or added:
        raise AnalysisError("case_alignment_mismatch", "Baseline and candidate contain different case identities.",
            missing_in_candidate=len(missing), missing_in_baseline=len(added),
            missing_case_digests=sorted(case_digest(key) for key in missing),
            added_case_digests=sorted(case_digest(key) for key in added))
    for key, row in before.items():
        other = after[key]
        # Gold metadata must itself stay unchanged, even when a legacy scoring
        # rule would erase the difference between e.g. 1.2 and 12.
        canonical = lambda value: unicodedata.normalize("NFKC", value).lower().strip()
        metadata = (row["group"], canonical(row["answer"]),
            frozenset(canonical(value) for value in row.get("aliases", [])), row.get("kind", "unspecified"))
        other_metadata = (other["group"], canonical(other["answer"]),
            frozenset(canonical(value) for value in other.get("aliases", [])), other.get("kind", "unspecified"))
        if metadata != other_metadata:
            raise AnalysisError("case_metadata_mismatch", "Aligned cases disagree on group, expected answer, aliases, or kind.",
                case_digest=case_digest(key))
    before_stats = statistics(before.values())
    after_stats = statistics(after.values())
    check_summary(baseline_document, before_stats)
    check_summary(candidate_document, after_stats)
    changes = {}
    for group in ("new", "old", "unknown"):
        keys = [key for key, row in before.items() if row["group"] == group]
        improved = sum(not before[key]["correct"] and after[key]["correct"] for key in keys)
        regressed = sum(before[key]["correct"] and not after[key]["correct"] for key in keys)
        changes[group] = {"total": len(keys), "correct_before": sum(before[key]["correct"] for key in keys),
            "correct_after": sum(after[key]["correct"] for key in keys),
            "already_correct_before": sum(before[key]["correct"] for key in keys),
            "wrong_to_correct": improved, "correct_to_wrong": regressed,
            "remained_correct": sum(before[key]["correct"] and after[key]["correct"] for key in keys),
            "remained_wrong": sum(not before[key]["correct"] and not after[key]["correct"] for key in keys)}
    changes["new"]["newly_learned"] = changes["new"]["wrong_to_correct"]
    changes["old"]["forgotten"] = changes["old"]["correct_to_wrong"]
    changes["old"]["regained"] = changes["old"]["wrong_to_correct"]
    return {"metric": metric, "before": before_stats, "after": after_stats, "transitions": changes,
        "alignment": {"status": "matched", "cases": len(before),
            "key_fields": ["source_id", "fact_id", "question"]}}


def analyze(report_path, output_path, *, workspace=WORKSPACE):
    workspace = Path(workspace).resolve()
    report_path = local_path(report_path, workspace)
    output_path = local_path(output_path, workspace)
    temporary = local_path(output_path.with_suffix(output_path.suffix + ".tmp"), workspace)
    report = read_json(report_path)
    if not isinstance(report, dict) or not isinstance(report.get("rounds"), list):
        raise AnalysisError("invalid_report", "Experiment report must contain a rounds array.")
    inputs = {report_path}
    # Protect the source document file as well, even though it is not read here.
    if report.get("input_path"):
        inputs.add(local_path(report["input_path"], workspace, relative_to=report_path.parent))
    prepared = []
    for position, row in enumerate(report["rounds"], 1):
        item = {"round": row.get("round", position) if isinstance(row, dict) else position}
        try:
            if not isinstance(row, dict):
                raise AnalysisError("invalid_round", "Experiment rounds must be objects.")
            if "round" in row and (type(row["round"]) is not int or row["round"] < 1):
                # Keep invalid input values out of the aggregate privacy boundary.
                item["round"] = position
                raise AnalysisError("invalid_round_number", "Round numbers must be positive integers.")
            job = row.get("job") or {}
            if not isinstance(job, dict):
                raise AnalysisError("invalid_job", "A recorded job must be an object.")
            state = job.get("status", "pending")
            if not isinstance(state, str) or state not in TERMINAL | {"pending", "queued", "running", "training"}:
                raise AnalysisError("invalid_job_status", "A job status is invalid.")
            item["job_status"] = state
            result = job.get("result") or {}
            if not isinstance(result, dict):
                raise AnalysisError("invalid_job_result", "A recorded job result must be an object.")
            reference = result.get("evaluation_report")
            if reference:
                candidate = local_path(reference, workspace, relative_to=report_path.parent)
                baseline = local_path(candidate.parent / "baseline-evaluation.json", workspace)
                inputs.update((candidate, baseline))
                item["candidate_report"] = str(candidate)
                item["baseline_report"] = str(baseline)
            if state not in TERMINAL:
                if report.get("status") == "completed":
                    raise AnalysisError("incomplete_round", "A completed experiment contains a nonterminal round.")
                item["analysis_status"] = "pending"
            elif not reference and state == "failed":
                item.update(analysis_status="unavailable", reason="Failed job recorded no evaluation artifacts.")
            elif not reference:
                raise AnalysisError("missing_evaluation_reference", "A completed job has no candidate evaluation path.")
            else:
                item["analysis_status"] = "ready"
        except AnalysisError as error:
            item.update(analysis_status="error", errors=[error.item])
        prepared.append(item)
    if output_path in inputs or temporary in inputs:
        raise AnalysisError("output_input_collision", "Output or its temporary file would overwrite an input artifact.")
    if temporary.exists():
        raise AnalysisError("temporary_exists", "Output temporary file already exists; no file was overwritten.")
    for item in prepared:
        if item["analysis_status"] != "ready":
            continue
        try:
            item.update(compare_evaluations(read_json(Path(item["baseline_report"])),
                read_json(Path(item["candidate_report"]))))
            item["analysis_status"] = "completed"
        except AnalysisError as error:
            item.update(analysis_status="error", errors=[error.item])
    counts = {state: sum(item["analysis_status"] == state for item in prepared)
        for state in ("completed", "pending", "unavailable", "error")}
    status = "error" if counts["error"] else ("partial" if counts["pending"] or counts["unavailable"] else "completed")
    metrics = sorted({item["metric"] for item in prepared if item["analysis_status"] == "completed"})
    summary = {"status": status, "experiment_status": report.get("status", "unspecified"),
        "experiment_report": str(report_path), "round_counts": counts, "rounds": prepared,
        "metric": metrics[0] if len(metrics) == 1 else "per_round", "metrics": metrics,
        "all_scope": "Known cases only: new plus old.",
        "privacy": "No question, answer, response, or source title is copied to this summary.",
        "limits": ["Transitions compare question phrasings, not distinct factual targets.",
            "Forgotten means a previously correct old case became incorrect, including abstention.",
            "Recorded candidate results can include rejected or unvalidated adapters; publication status is reported separately."]}
    output_path.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive temporary creation prevents accidentally replacing another input
    # or an existing writer's temporary file between preflight and serialization.
    with temporary.open("x", encoding="utf-8") as handle:
        json.dump(summary, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    temporary.replace(output_path)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = analyze(args.report, args.output)
    except (AnalysisError, OSError) as error:
        # Never stringify filesystem errors: their path values may contain private
        # content. Schema errors have deliberately sanitized descriptions.
        message = error.item if isinstance(error, AnalysisError) else {
            "code": "filesystem_error", "message": "Cannot safely read or write a local analysis artifact."}
        print(json.dumps(message, ensure_ascii=False), file=sys.stderr)
        print(json.dumps({"rounds": 0, "status": "error", "report_path": str(args.output.resolve())}), flush=True)
        return 1
    print(json.dumps({"rounds": len(result["rounds"]), "status": result["status"],
        "report_path": str(args.output.resolve())}), flush=True)
    return 1 if result["status"] == "error" else 0


if __name__ == "__main__":
    raise SystemExit(main())
