#!/usr/bin/env python3
"""Embed a frozen synthetic document file using an isolated, local Ollama cache.

Input: {"documents": [{"id": sha256(text.encode("utf-8")), "text": "..."}]}.
This CLI never starts services, pulls models, accepts credentials, or substitutes vectors.
The default batch size is 16. Atomic progress is resumed only when input bytes, model
digest, and CPU options match. All input text must already be the runner's title|excerpt.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import sys
import tempfile
import time
from typing import Any
import urllib.error
import urllib.parse
import urllib.request

MODEL = "bge-m3"
OPTIONS = {"num_thread": 1, "num_gpu": 0}
VERSION = 1


class ProbeError(Exception):
    """A safe, fixed diagnostic code, without provider bodies or document text."""


def fail(code: str) -> None:
    raise ProbeError(code)


def local_base(value: str) -> str:
    try:
        parsed = urllib.parse.urlsplit(value)
        port = parsed.port
    except ValueError:
        fail("invalid_local_url")
    if (parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost", "::1"}
            or parsed.username is not None or parsed.password is not None
            or parsed.query or parsed.fragment or parsed.path not in {"", "/"}
            or port is None or port == 11434):
        fail("isolated_local_url_required")
    return value.rstrip("/")


class Ollama:
    def __init__(self, base: str, timeout: float):
        self.base = local_base(base)
        self.timeout = timeout
        # Proxy environment variables must never route local requests through a cloud proxy.
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(self, path: str, body: dict | None = None) -> dict:
        payload = None if body is None else json.dumps(body, ensure_ascii=False,
                                                       allow_nan=False).encode("utf-8")
        request = urllib.request.Request(self.base + path, data=payload,
                                         headers={"Content-Type": "application/json"},
                                         method="GET" if body is None else "POST")
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                raw = response.read()
            value = json.loads(raw)
        except urllib.error.HTTPError as error:
            fail("http_" + str(error.code))
        except (urllib.error.URLError, TimeoutError, OSError):
            fail("local_transport_error")
        except (ValueError, UnicodeError):
            fail("invalid_response_json")
        if not isinstance(value, dict) or "error" in value:
            fail("invalid_response_object")
        return value

    def identity(self) -> dict:
        models = self.request("/api/tags").get("models")
        if not isinstance(models, list):
            fail("invalid_tags")
        matches = [item for item in models if isinstance(item, dict)
                   and item.get("name", item.get("model")) in {MODEL, MODEL + ":latest"}]
        if len(matches) != 1:
            fail("cached_bge_m3_required")
        match = matches[0]
        digest = match.get("digest")
        if not isinstance(digest, str) or not re.fullmatch(r"(?:sha256:)?[0-9a-f]{64}", digest):
            fail("invalid_model_digest")
        details = match.get("details", {})
        if not isinstance(details, dict):
            fail("invalid_model_details")
        safe_details = {key: details[key] for key in
                        ("format", "family", "parameter_size", "quantization_level")
                        if isinstance(details.get(key), str)}
        return {"model": MODEL, "modelDigest": digest, "modelDetails": safe_details}


def read_input(path: Path) -> tuple[list[dict], str]:
    try:
        raw = path.read_bytes()
        root = json.loads(raw)
    except (OSError, ValueError, UnicodeError):
        fail("invalid_input_json")
    documents = root.get("documents") if isinstance(root, dict) else None
    if not isinstance(documents, list) or not documents:
        fail("documents_required")
    seen = set()
    for document in documents:
        if not isinstance(document, dict) or set(document) != {"id", "text"}:
            fail("invalid_document_shape")
        text, identifier = document["text"], document["id"]
        if not isinstance(text, str) or not text.strip() or not isinstance(identifier, str):
            fail("invalid_document")
        expected = hashlib.sha256(text.encode("utf-8")).hexdigest()
        if identifier != expected:
            fail("document_hash_mismatch")
        if identifier in seen:
            fail("duplicate_document_id")
        seen.add(identifier)
    return documents, hashlib.sha256(raw).hexdigest()


def normalized(vector: Any, dimension: int | None = None, *, cached: bool = False) -> list[float]:
    if (not isinstance(vector, list) or not vector or
            any(isinstance(value, bool) or not isinstance(value, (int, float))
                or not math.isfinite(value) for value in vector)):
        fail("invalid_embedding_values")
    if dimension is not None and len(vector) != dimension:
        fail("embedding_dimension_mismatch")
    norm = math.sqrt(math.fsum(float(value) * float(value) for value in vector))
    if not math.isfinite(norm) or norm <= 1e-12:
        fail("zero_or_invalid_embedding")
    if cached and abs(norm - 1.0) > 1e-5:
        fail("progress_vector_not_normalized")
    return [float(value) / norm for value in vector]


def atomic_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temp_name = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent,
                                         prefix=path.name + ".", suffix=".tmp", delete=False) as stream:
            temp_name = stream.name
            json.dump(value, stream, ensure_ascii=False, allow_nan=False, separators=(",", ":"))
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp_name, path)
        temp_name = None
    finally:
        if temp_name is not None:
            try:
                os.unlink(temp_name)
            except OSError:
                pass


def read_progress(path: Path, documents: list[dict], expected: dict) -> dict | None:
    if not path.exists():
        return None
    try:
        saved = json.loads(path.read_bytes())
    except (OSError, ValueError, UnicodeError):
        fail("invalid_progress_json")
    if not isinstance(saved, dict):
        fail("invalid_progress_shape")
    for key in ("version", "inputHash", "model", "modelDigest", "options", "normalized"):
        if saved.get(key) != expected[key]:
            fail("progress_identity_mismatch")
    vectors, dimension = saved.get("vectors"), saved.get("dimension")
    if not isinstance(vectors, list) or len(vectors) > len(documents):
        fail("invalid_progress_count")
    if vectors and (isinstance(dimension, bool) or not isinstance(dimension, int) or dimension <= 0):
        fail("invalid_progress_dimension")
    for index, entry in enumerate(vectors):
        if not isinstance(entry, dict) or entry.get("id") != documents[index]["id"]:
            fail("progress_document_order_mismatch")
        normalized(entry.get("vector"), dimension, cached=True)
    elapsed = saved.get("elapsedMs", 0)
    if isinstance(elapsed, bool) or not isinstance(elapsed, (int, float)) or not math.isfinite(elapsed) or elapsed < 0:
        fail("invalid_progress_elapsed")
    if not isinstance(saved.get("batches", []), list):
        fail("invalid_progress_batches")
    return saved


def embed_file(input_path: Path, output_path: Path, progress_path: Path, backend: Ollama,
               batch_size: int = 16) -> dict:
    if not 1 <= batch_size <= 16:
        fail("batch_size_out_of_range")
    if len({input_path.resolve(), output_path.resolve(), progress_path.resolve()}) != 3:
        fail("input_output_progress_paths_must_differ")
    start = time.perf_counter()
    documents, input_hash = read_input(input_path)
    identity = backend.identity()
    runtime_version = backend.request("/api/version").get("version")
    if not isinstance(runtime_version, str):
        fail("invalid_runtime_version")
    expected = {"version": VERSION, "inputHash": input_hash, "normalized": True,
                "options": OPTIONS, **identity}
    saved = read_progress(progress_path, documents, expected)
    vectors = [] if saved is None else saved["vectors"]
    dimension = None if saved is None else saved.get("dimension")
    previous_ms = 0 if saved is None else saved.get("elapsedMs", 0)
    batches = [] if saved is None else saved.get("batches", [])
    initial_count = len(vectors)

    def state(status: str) -> dict:
        return {**expected, "status": status, "runtime": "Ollama", "runtimeVersion": runtime_version,
                "batchSize": batch_size, "documentCount": len(documents), "completed": len(vectors),
                "dimension": dimension, "vectors": vectors, "batches": batches,
                "elapsedMs": previous_ms + (time.perf_counter() - start) * 1000,
                "resumedCount": initial_count, "inputHashFormat": "sha256 of exact input file bytes",
                "documentHashFormat": "sha256 of exact text UTF-8 bytes"}

    for offset in range(len(vectors), len(documents), batch_size):
        if backend.identity()["modelDigest"] != identity["modelDigest"]:
            fail("model_changed_during_run")
        batch = documents[offset:offset + batch_size]
        batch_start = time.perf_counter()
        response = backend.request("/api/embed", {"model": MODEL,
                                  "input": [item["text"] for item in batch], "truncate": False,
                                  "keep_alive": "10m", "options": OPTIONS})
        if response.get("model") not in {MODEL, MODEL + ":latest"}:
            fail("embedding_model_mismatch")
        embeddings = response.get("embeddings")
        if not isinstance(embeddings, list) or len(embeddings) != len(batch):
            fail("embedding_count_mismatch")
        validated = []
        for item, vector in zip(batch, embeddings):
            value = normalized(vector, dimension)
            if dimension is None:
                dimension = len(value)
            validated.append({"id": item["id"], "vector": value})
        if backend.identity()["modelDigest"] != identity["modelDigest"]:
            fail("model_changed_during_run")
        vectors.extend(validated)
        batch_metrics = {"start": offset, "count": len(batch),
                         "elapsedMs": (time.perf_counter() - batch_start) * 1000}
        for key in ("total_duration", "load_duration", "prompt_eval_count"):
            value = response.get(key)
            if isinstance(value, int) and not isinstance(value, bool) and value >= 0:
                batch_metrics[key] = value
        batches.append(batch_metrics)
        atomic_json(progress_path, state("in_progress"))
    if backend.identity()["modelDigest"] != identity["modelDigest"]:
        fail("model_changed_during_run")
    result = state("completed")
    atomic_json(output_path, result)
    atomic_json(progress_path, result)
    return {"status": "completed", "model": MODEL, "modelDigest": identity["modelDigest"],
            "dimension": dimension, "documentCount": len(documents), "normalized": True,
            "elapsedMs": result["elapsedMs"], "resumedCount": initial_count,
            "output": str(output_path.resolve())}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--progress", type=Path)
    parser.add_argument("--base-url", default="http://127.0.0.1:11435")
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--timeout", type=float, default=300)
    args = parser.parse_args()
    progress = args.progress or args.output.with_name(args.output.stem + ".progress.json")
    try:
        if not math.isfinite(args.timeout) or args.timeout <= 0:
            fail("invalid_timeout")
        result = embed_file(args.input, args.output, progress,
                            Ollama(args.base_url, args.timeout), args.batch_size)
        print(json.dumps(result, ensure_ascii=False, allow_nan=False))
        return 0
    except ProbeError as error:
        print(json.dumps({"status": "error", "code": str(error)}), file=sys.stderr)
    except (OSError, ValueError, TypeError):
        print(json.dumps({"status": "error", "code": "local_cli_error"}), file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
