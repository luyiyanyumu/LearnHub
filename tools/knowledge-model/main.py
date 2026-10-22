"""LearnHub local specialist-model validation CLI and loopback HTTP service."""
from __future__ import annotations

import argparse
import json
import os
import threading
import traceback
from contextlib import asynccontextmanager
from pathlib import Path

from data import prepare_payload
from engine import Config, Engine, attempt_directory
from store import ProcessLock, Store

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DATA = ROOT / "output" / "knowledge-model" / "runtime"


class Runtime:
    def __init__(self, root, config):
        self.store = Store(root)
        self.config = config
        self.engine = Engine(self.store, config)
        self.gpu_lock = threading.Lock()
        self.stop = threading.Event()
        self.wake = threading.Event()
        self.worker = None
        self.process_lock = ProcessLock(self.store.root)

    def start(self):
        self.process_lock.acquire()
        self.store.recover()
        self.worker = threading.Thread(target=self.work, name="knowledge-model-worker", daemon=True)
        self.worker.start()

    def close(self):
        self.stop.set()
        self.wake.set()
        if self.worker:
            self.worker.join(timeout=5)
        # A live training thread keeps the process lock until process termination.
        if not self.worker or not self.worker.is_alive():
            self.engine.unload()
            self.process_lock.release()

    def work(self):
        while not self.stop.is_set():
            # Taking the GPU lock before claiming avoids claiming a job that cannot start.
            with self.gpu_lock:
                job = self.store.claim()
                if job:
                    self.run_job(job)
            if job is None:
                self.wake.wait(timeout=2)
                self.wake.clear()

    def run_job(self, job):
        try:
            return self.engine.run(job)
        except Exception as error:
            self.store.finish(job["id"], "failed", error=str(error)[:2000])
            error_dir = attempt_directory(self.store.root, job)
            error_dir.mkdir(parents=True, exist_ok=True)
            (error_dir / "error.txt").write_text(traceback.format_exc(), encoding="utf-8")
            self.engine.unload()
            self.engine.progress = {"phase": "idle"}
            return {"status": "failed", "error": str(error), "job_id": job["id"]}

    def status(self):
        active = self.store.active()
        counts = self.store.status_counts()
        return {"service": "learnhub-knowledge-model", "base_model": self.config.model_path,
            "active_version": active["version"] if active else "base", "active_model": active,
            "progress": self.engine.progress,
            "queued_jobs": counts.get("queued", 0),
            "running_jobs": counts.get("running", 0),
            "gpu_busy": self.gpu_lock.locked(), "retrieval_used": False,
            "data_directory": str(self.store.root)}


def create_app(runtime):
    from fastapi import FastAPI, HTTPException
    from pydantic import BaseModel, Field

    class Fact(BaseModel):
        fact_id: str = ""
        subject: str = Field(default="", max_length=300)
        entity: str = Field(default="", max_length=300)
        statement: str = ""
        answer: str = Field(min_length=1, max_length=1000)
        aliases: list[str] = Field(default_factory=list)
        training_questions: list[str] = Field(min_length=1, max_length=30)
        eval_questions: list[str] = Field(min_length=1, max_length=10)
        origin: str = "supplied_fact"

    class Abstention(BaseModel):
        # These require local source review: missing extraction alone is not
        # evidence that a natural question has no answer in the source.
        training_questions: list[str] = Field(default_factory=list, max_length=100)
        eval_questions: list[str] = Field(default_factory=list, max_length=50)

    class Document(BaseModel):
        source_id: str = Field(min_length=1, max_length=300)
        title: str = Field(min_length=1, max_length=500)
        text: str = Field(min_length=1, max_length=5_000_000)
        revision: str | None = None
        facts: list[Fact] = Field(default_factory=list, max_length=500)
        abstention: Abstention | None = None

    class Query(BaseModel):
        question: str = Field(min_length=1, max_length=5000)
        max_tokens: int = Field(default=128, ge=1, le=512)

    class Message(BaseModel):
        role: str
        content: str

    class ChatRequest(BaseModel):
        model: str = "learnhub-knowledge"
        messages: list[Message] = Field(min_length=1, max_length=20)
        max_tokens: int = Field(default=128, ge=1, le=512)
        stream: bool = False

    @asynccontextmanager
    async def lifespan(app):
        runtime.start()
        try:
            yield
        finally:
            runtime.close()

    app = FastAPI(title="LearnHub local knowledge-model validation", lifespan=lifespan)

    @app.get("/health")
    def health():
        return {"status": "ok", "worker_alive": bool(runtime.worker and runtime.worker.is_alive()),
                "model_loaded": runtime.engine.model is not None, "retrieval_used": False}

    @app.get("/v1/status")
    def status():
        return runtime.status()

    @app.get("/v1/jobs")
    def jobs(limit: int = 100):
        return {"jobs": runtime.store.jobs(max(1, min(limit, 500)))}

    @app.get("/v1/jobs/{job_id}")
    def job(job_id: str):
        item = runtime.store.get(job_id)
        if not item:
            raise HTTPException(404, "Job not found")
        return item

    # Annotation resolution uses concrete types; local classes are registered below
    # before FastAPI evaluates postponed annotation strings.
    globals().update(Document=Document, Query=Query, ChatRequest=ChatRequest)

    @app.post("/v1/documents", status_code=202)
    def document(body: Document):
        try:
            payload = prepare_payload(body.model_dump())
        except ValueError as error:
            raise HTTPException(422, str(error)) from error
        item, duplicate = runtime.store.enqueue(payload)
        runtime.wake.set()
        return {"job_id": item["id"], "duplicate": duplicate, "job": item}

    @app.post("/v1/jobs/{job_id}/retry", status_code=202)
    def retry(job_id: str):
        try:
            item = runtime.store.retry(job_id)
        except KeyError as error:
            raise HTTPException(404, "Job not found") from error
        except ValueError as error:
            raise HTTPException(409, str(error)) from error
        runtime.wake.set()
        return {"job_id": job_id, "job": item}

    def answer(question, max_tokens):
        if not runtime.gpu_lock.acquire(blocking=False):
            raise HTTPException(409, "GPU is busy training or answering; retry after this task completes")
        try:
            return runtime.engine.query(question, max_tokens)
        except ValueError as error:
            raise HTTPException(422, str(error)) from error
        except Exception as error:
            runtime.engine.unload()
            raise HTTPException(503, str(error)[:1000]) from error
        finally:
            runtime.gpu_lock.release()

    @app.post("/v1/query")
    def query(body: Query):
        return answer(body.question, body.max_tokens)

    @app.get("/v1/models")
    def models():
        return {"object": "list", "data": [{"id": "learnhub-knowledge", "object": "model",
                "owned_by": "local", "version": runtime.status()["active_version"]}]}

    @app.post("/v1/chat/completions")
    def chat(body: ChatRequest):
        import time
        import uuid
        if body.stream:
            raise HTTPException(422, "This validation API supports stream=false only")
        if len(body.messages) != 1 or body.messages[0].role != "user":
            raise HTTPException(422, "Send one user question without document context for parameter-only inference")
        result = answer(body.messages[0].content, body.max_tokens)
        return {"id": "chatcmpl-" + uuid.uuid4().hex, "object": "chat.completion",
            "created": int(time.time()), "model": "learnhub-knowledge",
            "model_version": result["model_version"], "retrieval_used": False,
            "abstained": result["abstained"],
            "abstention_validated": result["abstention_validated"],
            "choices": [{"index": 0, "message": {"role": "assistant", "content": result["answer"]},
                         "finish_reason": "stop"}]}

    return app


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["serve", "enqueue", "train-next", "status", "query", "smoke"])
    parser.add_argument("--data-dir", type=Path, default=DEFAULT_DATA)
    parser.add_argument("--model-path", default=Config.model_path)
    parser.add_argument("--document", type=Path, help="UTF-8 JSON matching POST /v1/documents")
    parser.add_argument("--question")
    parser.add_argument("--port", type=int, default=8766)
    parser.add_argument("--steps", type=int, default=80)
    parser.add_argument("--max-length", type=int, default=512)
    parser.add_argument("--accumulation", type=int, default=4)
    parser.add_argument("--learning-rate", type=float, default=0.0003)
    parser.add_argument("--rank", type=int, default=16)
    parser.add_argument("--new-min", type=float, default=0.8)
    parser.add_argument("--old-drop-max", type=float, default=0.1)
    parser.add_argument("--old-min", type=float, default=0.0)
    parser.add_argument("--unknown-min", type=float, default=0.9)
    parser.add_argument("--known-abstention-max", type=float, default=0.1)
    args = parser.parse_args()
    if not (1 <= args.steps <= 10000 and 128 <= args.max_length <= 2048 and
            1 <= args.accumulation <= 32 and 1 <= args.rank <= 128 and
            0 < args.learning_rate <= 0.01 and 0 <= args.new_min <= 1 and
            0 <= args.old_drop_max <= 1 and 0 <= args.old_min <= 1 and 0 <= args.unknown_min <= 1 and
            0 <= args.known_abstention_max <= 1):
        parser.error("Training configuration is outside the supported validation range")
    return args


def main():
    args = arguments()
    config = Config(model_path=args.model_path, max_steps=args.steps, max_length=args.max_length,
        accumulation=args.accumulation, learning_rate=args.learning_rate, lora_rank=args.rank,
        new_accuracy_min=args.new_min, old_drop_max=args.old_drop_max,
        old_accuracy_min=args.old_min,
        unknown_accuracy_min=args.unknown_min, known_abstention_max=args.known_abstention_max)
    runtime = Runtime(args.data_dir, config)
    if args.command == "serve":
        import uvicorn
        uvicorn.run(create_app(runtime), host="127.0.0.1", port=args.port, workers=1)
        return
    if args.command == "status":
        result = runtime.status()
    elif args.command == "enqueue":
        if not args.document:
            raise SystemExit("--document is required")
        payload = prepare_payload(json.loads(args.document.read_text(encoding="utf-8-sig")))
        job, duplicate = runtime.store.enqueue(payload)
        result = {"job_id": job["id"], "duplicate": duplicate, "job": job}
    else:
        with_lock = runtime.process_lock.acquire()
        try:
            runtime.store.recover()
            if args.command == "query":
                if not args.question:
                    raise SystemExit("--question is required")
                result = runtime.engine.query(args.question)
            elif args.command == "train-next":
                job = runtime.store.claim()
                result = runtime.run_job(job) if job else {"status": "idle", "message": "No queued upload"}
            else:
                from smoke import run_smoke
                result = run_smoke(runtime)
        finally:
            runtime.engine.unload()
            with_lock.release()
    print(json.dumps(result, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
