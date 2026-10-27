"""Paired retrieval experiment using the actual Java splitters and local Ollama only.

No database/API settings are read or written. Gold text spans are used only for scoring.
"""
import argparse
import collections
import difflib
import hashlib
import http.client
import json
import math
import os
from pathlib import Path
import statistics
import subprocess
import sys
import time
from urllib.parse import urlsplit

import numpy as np

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")


def sha(value):
    return hashlib.sha256(value if isinstance(value, bytes) else value.encode("utf-8")).hexdigest()


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def norm(text):
    return "".join(text.split())


def validate_suite(suite):
    documents = {doc["id"]: doc for doc in suite["documents"]}
    if len(documents) != len(suite["documents"]) or len(suite["questions"]) < 30:
        raise ValueError("The frozen suite needs unique documents and at least 30 questions")
    ids = set()
    for question in suite["questions"]:
        if question["id"] in ids or not question["evidence"]:
            raise ValueError("Questions need unique IDs and evidence spans")
        ids.add(question["id"])
        source = documents[question["doc_id"]]["text"]
        for span in question["evidence"]:
            if source[span["start"]:span["end"]] != span["text"]:
                raise ValueError("Evidence offsets no longer match the frozen source")
    return suite


class Ollama:
    def __init__(self, url, model, work, batch):
        parsed = urlsplit(url)
        if parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "localhost", "::1"):
            raise ValueError("This synthetic benchmark permits local HTTP Ollama only")
        self.host, self.port = parsed.hostname, parsed.port or 11434
        self.model, self.work, self.batch = model, work, batch
        tags = self.request("GET", "/api/tags")
        match = next((m for m in tags["models"] if m["name"] == model), None)
        if not match:
            raise ValueError("Requested model must already be installed; the benchmark never pulls models")
        self.digest = match["digest"]
        self.calls = 0

    def request(self, method, path, body=None):
        conn = http.client.HTTPConnection(self.host, self.port, timeout=300)
        try:
            conn.request(method, path, body=None if body is None else json.dumps(body).encode(),
                         headers={"Content-Type": "application/json"})
            response = conn.getresponse()
            raw = response.read()
            if response.status != 200:
                raise RuntimeError(f"Local Ollama returned HTTP {response.status}")
            return json.loads(raw)
        finally:
            conn.close()

    def embed(self, texts, phase):
        """Each variant/phase is measured separately; persisted batches permit honest resume."""
        result, elapsed, cached_batches = [], 0.0, 0
        for offset in range(0, len(texts), self.batch):
            batch = texts[offset:offset + self.batch]
            key = sha(json.dumps([self.digest, phase, batch], ensure_ascii=False))
            cache = self.work / "vectors" / f"{key}.json"
            if cache.exists():
                item = json.loads(cache.read_text(encoding="utf-8"))
                cached_batches += 1
            else:
                started = time.perf_counter()
                raw = self.request("POST", "/api/embed", {"model": self.model, "input": batch})
                item = {"vectors": raw["embeddings"], "ms": (time.perf_counter() - started) * 1000,
                        "load_ms": raw.get("load_duration", 0) / 1e6}
                if len(item["vectors"]) != len(batch):
                    raise RuntimeError("Embedding count mismatch")
                save(cache, item)
                self.calls += 1
            for vector in item["vectors"]:
                if not vector or not all(math.isfinite(x) for x in vector):
                    raise RuntimeError("Invalid vector")
                result.append(vector)
            elapsed += item["ms"]
            print(json.dumps({"phase": phase, "embedded": min(offset + len(batch), len(texts)),
                              "total": len(texts), "recorded_ms": round(elapsed)}, ensure_ascii=False), flush=True)
        return result, elapsed, cached_batches


def compile_java(work):
    jars = []
    for artifact in ("jackson-annotations", "jackson-core", "jackson-databind"):
        choices = [p for p in (Path.home() / ".m2/repository/com/fasterxml/jackson/core" / artifact).glob("*/*.jar")
                   if "sources" not in p.name and "javadoc" not in p.name]
        if not choices:
            raise RuntimeError("Run Maven dependency resolution once; Jackson is required for the JSON bridge")
        jars.append(str(sorted(choices, key=lambda p: tuple(int(x) for x in p.parent.name.split(".") if x.isdigit()))[-1]))
    dep_cp = os.pathsep.join(jars)
    bridge, legacy, current = (work / x for x in ("bridge", "legacy-classes", "current-classes"))
    for directory in (bridge, legacy, current):
        directory.mkdir(parents=True, exist_ok=True)
    subprocess.run(["javac", "-encoding", "UTF-8", "-cp", dep_cp, "-d", str(bridge), str(HERE / "ChunkExport.java")], check=True)
    subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(legacy), str(HERE / "legacy/TextChunker.java")], check=True)
    product = ROOT / "backend/src/main/java/org/dyh/learnhub/service"
    snapshots = work / "product-source"
    snapshots.mkdir(exist_ok=True)
    hashes = {}
    for name in ("TextChunker.java", "SemanticChunker.java"):
        source = (product / name).read_bytes()
        hashes[name] = sha(source)
        (snapshots / name).write_bytes(source)
    subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(current), str(snapshots / "TextChunker.java"), str(snapshots / "SemanticChunker.java")], check=True)
    return {"legacy": os.pathsep.join([str(bridge), str(legacy), dep_cp]),
            "current": os.pathsep.join([str(bridge), str(current), dep_cp]), "source_sha256": hashes}


def export(cp, mode, fixture):
    proc = subprocess.run(["java", "-cp", cp, "ChunkExport", mode, str(fixture)], check=True,
                          capture_output=True, encoding="utf-8")
    return json.loads(proc.stdout)


def embed_text(doc, piece):
    # Same contextual prefix as TextChunker.embedText; no gold metadata enters inference.
    heading = piece["heading"]
    return doc["title"] + (" · " + heading if heading else "") + "\n" + piece["text"]


def source_positions(source, chunk):
    """Map body characters to the source, ignoring whitespace and synthetic continuation fences."""
    exact = source.find(chunk)
    if exact >= 0:
        return set(range(exact, exact + len(chunk)))
    # Structural continuation may repeat a table header or add balanced code/math markers.
    # Long matching runs map original body evidence only; headings never enter this function.
    matcher = difflib.SequenceMatcher(None, source, chunk, autojunk=False)
    positions = set()
    for match in matcher.get_matching_blocks():
        if match.size >= 16:
            positions.update(range(match.a, match.a + match.size))
    return positions


def protected_integrity(doc, pieces):
    broken = collections.Counter()
    bodies = [norm(p["text"]) for p in pieces]
    for block in doc["protected"]:
        target = norm(doc["text"][block["start"]:block["end"]])
        # This fixture keeps protected blocks below max, so all must fit intact in one chunk.
        if not any(target in body for body in bodies):
            broken[block["type"]] += 1
    return broken


def summarize(rows):
    count = len(rows)
    return {"questions": count,
            "evidence_hit_at_1": sum(r["hit1"] for r in rows) / count,
            "evidence_hit_at_3": sum(r["hit3"] for r in rows) / count,
            "evidence_hit_budget": sum(r["hit_budget"] for r in rows) / count,
            "complete_evidence_budget": sum(r["complete"] for r in rows) / count,
            "mean_evidence_character_coverage": statistics.mean(r["coverage"] for r in rows),
            "mrr": statistics.mean(r["rr"] for r in rows),
            "query_local_rank_ms_p50": statistics.median(r["rank_ms"] for r in rows),
            "budget_blocked_questions": sum(bool(r["blocked"]) for r in rows)}


def score(suite, export_data, vectors, query_vectors, budget):
    docs = {d["id"]: d for d in suite["documents"]}
    chunks, broken = [], collections.Counter()
    for entry in export_data["documents"]:
        doc = docs[entry["id"]]
        broken.update(protected_integrity(doc, entry["pieces"]))
        source = norm(doc["text"])
        for i, piece in enumerate(entry["pieces"]):
            chunks.append({"doc_id": doc["id"], "index": i, "text": piece["text"],
                           "positions": source_positions(source, norm(piece["text"]))})
    matrix = np.array(vectors, dtype=np.float32)
    matrix /= np.maximum(np.linalg.norm(matrix, axis=1, keepdims=True), 1e-12)
    rows = []
    for q, query in zip(suite["questions"], query_vectors):
        doc = docs[q["doc_id"]]
        source = norm(doc["text"])
        spans = []
        for span in q["evidence"]:
            target = norm(span["text"])
            start = source.index(target)
            spans.append(set(range(start, start + len(target))))
        all_gold = set.union(*spans)
        started = time.perf_counter()
        vector = np.array(query, dtype=np.float32)
        vector /= max(np.linalg.norm(vector), 1e-12)
        order = np.argsort(-(matrix @ vector), kind="stable").tolist()
        rank_ms = (time.perf_counter() - started) * 1000
        useful = []
        for chunk in chunks:
            matched = chunk["positions"] & all_gold if chunk["doc_id"] == q["doc_id"] else set()
            useful.append(len(matched) >= min(24, len(all_gold)) and len(matched) / len(all_gold) >= .2)
        first = next((i + 1 for i, index in enumerate(order) if useful[index]), None)
        selected, used, blocked = [], 0, []
        for index in order[:10]:
            size = len(chunks[index]["text"])
            if used + size > budget:
                blocked.append({"doc_id": chunks[index]["doc_id"], "chunk": chunks[index]["index"], "chars": size})
                continue  # Never truncate a code block/formula just to spend the remaining budget.
            selected.append(index)
            used += size
        covered = set()
        for index in selected:
            if chunks[index]["doc_id"] == q["doc_id"]:
                covered.update(chunks[index]["positions"])
        coverage = len(covered & all_gold) / len(all_gold)
        rows.append({"id": q["id"], "category": q["category"], "doc_id": q["doc_id"],
                     "hit1": bool(useful[order[0]]), "hit3": any(useful[i] for i in order[:3]),
                     "hit_budget": any(useful[i] for i in selected),
                     "complete": all(span <= covered for span in spans), "coverage": coverage,
                     "rr": 1 / first if first else 0, "rank_ms": rank_ms,
                     "selected": [{"doc_id": chunks[i]["doc_id"], "chunk": chunks[i]["index"], "score": float(matrix[i] @ vector), "chars": len(chunks[i]["text"])} for i in selected],
                     "blocked": blocked, "context_chars": used})
    metrics = summarize(rows)
    metrics.update({"chunks": len(chunks), "mean_chunk_chars": statistics.mean(len(c["text"]) for c in chunks),
                    "max_chunk_chars": max(len(c["text"]) for c in chunks),
                    "protected_blocks": sum(len(d["protected"]) for d in docs.values()),
                    "broken_protected_blocks": sum(broken.values()), "broken_by_type": dict(broken)})
    return metrics, rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ollama", default="http://127.0.0.1:11434")
    parser.add_argument("--model", default="qwen3-embedding:4b")
    parser.add_argument("--batch", type=int, default=4)
    parser.add_argument("--budget", type=int, default=2400)
    parser.add_argument("--output", type=Path, default=HERE / "results")
    args = parser.parse_args()
    if not 1 <= args.batch <= 8:
        parser.error("Use batch 1..8 to avoid competing with the local application")
    suite = validate_suite(json.loads((HERE / "fixture.json").read_text(encoding="utf-8")))
    work = HERE / ".work"
    work.mkdir(exist_ok=True)
    cp = compile_java(work)
    ollama = Ollama(args.ollama, args.model, work, args.batch)
    # One warmup before all three conditions; loading is reported separately from steady build time.
    _, warmup_ms, _ = ollama.embed(["合成测试预热：局部变量、事务与缓存。"], "warmup")
    query_vectors, query_ms, query_cached = ollama.embed([q["question"] for q in suite["questions"]], "query")
    units = export(cp["current"], "units", HERE / "fixture.json")
    prepared = json.loads(json.dumps(suite, ensure_ascii=False))
    by_id = {d["id"]: d for d in prepared["documents"]}
    boundary_ms, boundary_cached, boundary_count = 0.0, 0, 0
    for entry in units["documents"]:
        doc = by_id[entry["id"]]
        if doc["kind"] == "file" and len(doc["text"]) >= 4000:
            texts = [p["text"] for p in entry["pieces"] if not p["protected"]]
            vectors, elapsed, cached = ollama.embed(texts, "boundary:" + doc["id"])
            doc["vectors"] = vectors
            boundary_ms += elapsed
            boundary_cached += cached
            boundary_count += len(texts)
    semantic_input = work / "semantic-input.json"
    save(semantic_input, prepared)
    results = {"fixture_version": suite["version"], "fixture_sha256": sha((HERE / "fixture.json").read_bytes()),
               "model": args.model, "model_digest": ollama.digest, "dimensions": len(query_vectors[0]),
               "options": {"target": 800, "overlap": 120, "max": 1200, "semantic_percentile": 85,
                           "semantic_min_chars": 4000, "context_budget_chars": args.budget, "top_candidates": 10},
               "baseline": (HERE / "legacy/baseline.txt").read_text(),
               "product_source_sha256": cp["source_sha256"],
               "query_embedding_ms_total": query_ms, "query_embedding_ms_per_question": query_ms / len(query_vectors),
               "query_embedding_cached_batches": query_cached, "warmup_ms": warmup_ms,
               "boundary_vectors": boundary_count, "boundary_embedding_ms": boundary_ms,
               "variants": {}}
    for name, classpath, mode, fixture in (("legacy", cp["legacy"], "chunks", HERE / "fixture.json"),
                                          ("structural", cp["current"], "chunks", HERE / "fixture.json"),
                                          ("semantic", cp["current"], "semantic", semantic_input)):
        exported = export(classpath, mode, fixture)
        save(args.output / f"chunks-{name}.json", exported)
        docs = {d["id"]: d for d in suite["documents"]}
        texts = [embed_text(docs[entry["id"]], p) for entry in exported["documents"] for p in entry["pieces"]]
        vectors, embed_ms, cached = ollama.embed(texts, "index:" + name)
        metrics, rows = score(suite, exported, vectors, query_vectors, args.budget)
        split_ms = sum(d["split_ms"] for d in exported["documents"])
        metrics.update({"split_ms": split_ms, "chunk_embedding_ms": embed_ms,
                        "build_ms": split_ms + embed_ms + (boundary_ms if name == "semantic" else 0),
                        "build_cached_batches": cached + (boundary_cached if name == "semantic" else 0),
                        "query_embedding_plus_rank_ms_mean": query_ms / len(query_vectors) + statistics.mean(r["rank_ms"] for r in rows),
                        "semantic_documents": [d["id"] for d in exported["documents"] if d["semantic_applied"]]})
        metrics["categories"] = {category: summarize([r for r in rows if r["category"] == category]) for category in sorted({r["category"] for r in rows})}
        results["variants"][name] = metrics
        save(args.output / f"questions-{name}.json", rows)
        save(args.output / "report.json", results)
        print(json.dumps({"variant": name, "metrics": metrics}, ensure_ascii=False), flush=True)
    print("REPORT " + str((args.output / "report.json").resolve()), flush=True)


if __name__ == "__main__":
    main()
