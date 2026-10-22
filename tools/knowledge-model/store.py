"""Local durable upload queue. No document or model data belongs in source control."""
from __future__ import annotations

import hashlib
import json
import sqlite3
import uuid
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path


def utcnow():
    return datetime.now(timezone.utc).isoformat()


class Store:
    def __init__(self, root: Path):
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.path = self.root / "state.sqlite3"
        with self.connect() as conn:
            conn.executescript("""
                CREATE TABLE IF NOT EXISTS jobs (
                    seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL,
                    source_id TEXT NOT NULL, fingerprint TEXT NOT NULL,
                    content_fingerprint TEXT NOT NULL DEFAULT '', attempt INTEGER NOT NULL DEFAULT 1,
                    payload TEXT NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL,
                    started_at TEXT, finished_at TEXT, error TEXT, result TEXT,
                    UNIQUE(source_id, fingerprint)
                );
                CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
            """)
            columns = {row["name"] for row in conn.execute("PRAGMA table_info(jobs)")}
            if "content_fingerprint" not in columns:
                conn.execute("ALTER TABLE jobs ADD COLUMN content_fingerprint TEXT NOT NULL DEFAULT ''")
            if "attempt" not in columns:
                conn.execute("ALTER TABLE jobs ADD COLUMN attempt INTEGER NOT NULL DEFAULT 1")
            # Earlier schemas used the raw content digest as the event fingerprint.
            conn.execute("UPDATE jobs SET content_fingerprint=fingerprint WHERE content_fingerprint=''")

    @contextmanager
    def connect(self):
        conn = sqlite3.connect(self.path, timeout=30)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        try:
            yield conn
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()

    @staticmethod
    def decode(row, include_payload=False):
        if row is None:
            return None
        item = dict(row)
        if include_payload:
            item["payload"] = json.loads(item["payload"])
        else:
            payload = json.loads(item.pop("payload"))
            item["title"] = payload["title"]
            item["revision"] = payload.get("revision")
            item["text_characters"] = len(payload["text"])
            item["verified_fact_count"] = len(payload.get("facts", []))
            item["evaluation_scope"] = payload.get("evaluation_scope", "unknown")
        if item["result"]:
            item["result"] = json.loads(item["result"])
        return item

    def enqueue(self, payload):
        canonical = json.dumps({k: v for k, v in payload.items() if k != "revision"},
                               ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        content_fingerprint = hashlib.sha256(canonical.encode()).hexdigest()
        with self.connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            latest = conn.execute("SELECT * FROM jobs WHERE source_id=? ORDER BY seq DESC LIMIT 1",
                                  (payload["source_id"],)).fetchone()
            if latest and latest["content_fingerprint"] == content_fingerprint:
                return self.decode(latest), True
            # The same historical bytes may be intentionally uploaded again after an
            # intervening revision. Deduplicate current content, not every past event.
            fingerprint = hashlib.sha256((content_fingerprint + ":" +
                (latest["id"] if latest else "initial")).encode()).hexdigest()
            job_id = uuid.uuid4().hex
            conn.execute("INSERT INTO jobs(id,source_id,fingerprint,content_fingerprint,payload,status,created_at) "
                         "VALUES(?,?,?,?,?,?,?)", (job_id, payload["source_id"], fingerprint,
                         content_fingerprint, json.dumps(payload, ensure_ascii=False), "queued", utcnow()))
            return self.decode(conn.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()), False

    def get(self, job_id, include_payload=False):
        with self.connect() as conn:
            return self.decode(conn.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone(),
                               include_payload)

    def jobs(self, limit=100):
        with self.connect() as conn:
            return [self.decode(row) for row in conn.execute(
                "SELECT * FROM jobs ORDER BY seq DESC LIMIT ?", (limit,))]

    def status_counts(self):
        with self.connect() as conn:
            return {row["status"]: row["total"] for row in conn.execute(
                "SELECT status, COUNT(*) AS total FROM jobs GROUP BY status")}

    def recover(self):
        # Called only after taking the exclusive process lock.
        with self.connect() as conn:
            conn.execute("UPDATE jobs SET status='queued', started_at=NULL, "
                         "error='Recovered after interrupted process' WHERE status='running'")

    def claim(self):
        with self.connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            row = conn.execute("SELECT * FROM jobs WHERE status='queued' ORDER BY seq LIMIT 1").fetchone()
            if not row:
                return None
            conn.execute("UPDATE jobs SET status='running', started_at=?, finished_at=NULL, error=NULL "
                         "WHERE id=?", (utcnow(), row["id"]))
            return self.decode(row, include_payload=True)

    def corpus(self, seq):
        # Immutable upload payloads prevent concurrent uploads changing an in-flight dataset.
        with self.connect() as conn:
            rows = conn.execute("SELECT j.* FROM jobs j JOIN "
                "(SELECT source_id,MAX(seq) seq FROM jobs WHERE seq<=? GROUP BY source_id) latest "
                "ON j.seq=latest.seq ORDER BY j.seq", (seq,)).fetchall()
            return [self.decode(row, include_payload=True) for row in rows]

    def finish(self, job_id, status, result=None, error=None, publish=None):
        with self.connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            conn.execute("UPDATE jobs SET status=?,finished_at=?,result=?,error=? WHERE id=?",
                         (status, utcnow(), json.dumps(result, ensure_ascii=False) if result else None,
                          error, job_id))
            if publish is not None:
                conn.execute("INSERT INTO settings(key,value) VALUES('active',?) "
                             "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                             (json.dumps(publish, ensure_ascii=False),))

    def retry(self, job_id):
        with self.connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            row = conn.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
            if row is None:
                raise KeyError(job_id)
            if row["status"] not in ("failed", "rejected", "unvalidated"):
                raise ValueError("Only failed, rejected, or unvalidated jobs can be retried")
            latest = conn.execute("SELECT MAX(seq) FROM jobs WHERE source_id=?", (row["source_id"],)).fetchone()[0]
            if row["seq"] != latest:
                raise ValueError("This revision is superseded; retry the latest upload")
            # A retry is a fresh queue attempt. Keeping its old sequence would omit
            # documents learned by an active model published after the original try.
            next_seq = conn.execute("SELECT COALESCE(MAX(seq),0)+1 FROM jobs").fetchone()[0]
            conn.execute("UPDATE jobs SET seq=?,attempt=attempt+1,status='queued',error=NULL,result=NULL, "
                         "started_at=NULL,finished_at=NULL WHERE id=?", (next_seq, job_id))
        return self.get(job_id)

    def active(self):
        with self.connect() as conn:
            row = conn.execute("SELECT value FROM settings WHERE key='active'").fetchone()
            return json.loads(row[0]) if row else None


class ProcessLock:
    """OS lock, held for the worker lifetime; automatically released after a crash."""
    def __init__(self, root):
        self.path = Path(root) / "worker.lock"
        self.file = None

    def acquire(self):
        self.file = self.path.open("a+b")
        try:
            if self.path.stat().st_size == 0:
                self.file.write(b"0")
                self.file.flush()
            self.file.seek(0)
            if __import__("os").name == "nt":
                import msvcrt
                msvcrt.locking(self.file.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.file, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except (OSError, IOError):
            self.file.close()
            self.file = None
            raise RuntimeError("Another knowledge-model worker is already using this data directory")
        return self

    def release(self):
        if self.file:
            self.file.close()
            self.file = None
