"""Confirm a final product fix against frozen Java exports and cached local embeddings.

This command never calls Ollama and never replaces the original measured timings.
Run before discarding the original .work/current-classes and results/chunks-*.json.
"""
import importlib.util
import json
import os
from pathlib import Path

spec = importlib.util.spec_from_file_location("chunking_benchmark", Path(__file__).with_name("run.py"))
benchmark = importlib.util.module_from_spec(spec)
spec.loader.exec_module(benchmark)
HERE, ROOT = benchmark.HERE, benchmark.ROOT
WORK = HERE / ".work"
CHECK = WORK / "final-validation"
REPORT = ROOT / "docs/chunking-benchmark-2026-10-26/report.json"


def old_classpath():
    jars = []
    for artifact in ("jackson-annotations", "jackson-core", "jackson-databind"):
        choices = [p for p in (Path.home() / ".m2/repository/com/fasterxml/jackson/core" / artifact).glob("*/*.jar")
                   if "sources" not in p.name and "javadoc" not in p.name]
        jars.append(str(sorted(choices, key=lambda p: tuple(int(x) for x in p.parent.name.split(".") if x.isdigit()))[-1]))
    return os.pathsep.join([str(WORK / "bridge"), str(WORK / "current-classes"), *jars])


def bodies(exported):
    return [{"id": d["id"], "pieces": d["pieces"]} for d in exported["documents"]]


def cached_vectors(measurement, phase, texts):
    vectors = []
    for offset in range(0, len(texts), 4):
        batch = texts[offset:offset + 4]
        key = benchmark.sha(json.dumps([measurement["model_digest"], phase, batch], ensure_ascii=False))
        item = json.loads((WORK / "vectors" / f"{key}.json").read_text(encoding="utf-8"))
        vectors.extend(item["vectors"])
    return vectors


def main():
    CHECK.mkdir(exist_ok=True)
    measurement = json.loads(REPORT.read_text(encoding="utf-8"))
    fixture = HERE / "fixture.json"
    suite = benchmark.validate_suite(json.loads(fixture.read_text(encoding="utf-8")))
    assert benchmark.sha(fixture.read_bytes()) == measurement["fixture_sha256"]
    previous_units = CHECK / "previous-units.json"
    if not previous_units.exists():
        # The first check captures units from the exact classes used in the original model run.
        benchmark.save(previous_units, benchmark.export(old_classpath(), "units", fixture))
    reference_units = json.loads(previous_units.read_text(encoding="utf-8"))
    classpaths = benchmark.compile_java(WORK)
    final_units = benchmark.export(classpaths["current"], "units", fixture)
    assert bodies(reference_units) == bodies(final_units), "Frozen structural units changed: cached boundary vectors cannot be reused"
    benchmark.save(CHECK / "final-units.json", final_units)
    output = {"fixture_sha256": measurement["fixture_sha256"],
              "original_measured_source_sha256": measurement["product_source_sha256"],
              "final_product_source_sha256": classpaths["source_sha256"],
              "structural_units_identical": True,
              "structural_units_sha256": benchmark.sha(json.dumps(bodies(final_units), ensure_ascii=False, sort_keys=True)),
              "model_calls": 0,
              "timing_policy": "Original measured build/query times retained unchanged; this validation only checks outputs and cached-vector retrieval metrics.",
              "variants": {}}
    queries = cached_vectors(measurement, "query", [q["question"] for q in suite["questions"]])
    docs = {d["id"]: d for d in suite["documents"]}
    metric_fields = ("questions", "evidence_hit_at_1", "evidence_hit_at_3", "evidence_hit_budget",
                     "complete_evidence_budget", "mean_evidence_character_coverage", "mrr", "chunks",
                     "mean_chunk_chars", "max_chunk_chars", "protected_blocks", "broken_protected_blocks", "broken_by_type")
    for variant, cp, mode, source in (("legacy", classpaths["legacy"], "chunks", fixture),
                                      ("structural", classpaths["current"], "chunks", fixture),
                                      ("semantic", classpaths["current"], "semantic", WORK / "semantic-input.json")):
        previous = json.loads((HERE / "results" / f"chunks-{variant}.json").read_text(encoding="utf-8"))
        current = benchmark.export(cp, mode, source)
        assert bodies(previous) == bodies(current), f"{variant} chunk output changed"
        benchmark.save(CHECK / f"final-chunks-{variant}.json", current)
        texts = [benchmark.embed_text(docs[entry["id"]], piece)
                 for entry in current["documents"] for piece in entry["pieces"]]
        vectors = cached_vectors(measurement, "index:" + variant, texts)
        metrics, rows = benchmark.score(suite, current, vectors, queries, 2400)
        for field in metric_fields:
            assert metrics[field] == measurement["variants"][variant][field], (variant, field)
        previous_rows = json.loads((REPORT.parent / f"questions-{variant}.json").read_text(encoding="utf-8"))
        passive_fields = {"rank_ms"}
        assert [{k: v for k, v in r.items() if k not in passive_fields} for r in rows] == [
            {k: v for k, v in r.items() if k not in passive_fields} for r in previous_rows], f"{variant} per-question retrieval changed"
        output["variants"][variant] = {"chunks_identical": True, "metrics_identical": True,
                                       "per_question_evidence_and_ranking_identical": True,
                                       "chunks_sha256": benchmark.sha(json.dumps(bodies(current), ensure_ascii=False, sort_keys=True)),
                                       "metrics": {field: metrics[field] for field in metric_fields}}
    destination = REPORT.parent / "final-validation.json"
    benchmark.save(destination, output)
    print(json.dumps(output, ensure_ascii=False), flush=True)
    print("FINAL VALIDATION " + str(destination), flush=True)


if __name__ == "__main__":
    main()
