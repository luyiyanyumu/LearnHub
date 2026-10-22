"""Internal HTTP adapter: a narrow anonymous rendering API, two active tasks at most."""
from __future__ import annotations

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import socket
import threading

from engine import MAX_JSON_BYTES, fetch_in_subprocess, health, validate_request
from guard import FetchError

MAX_REQUEST_BYTES = 8192


class FetchServer(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 8

    def __init__(self, address, fetch=fetch_in_subprocess, runtime_health=None):
        super().__init__(address, Handler)
        self.fetch, self.runtime_health = fetch, runtime_health if runtime_health is not None else health()
        self.fetch_slots = threading.BoundedSemaphore(2)
        self.connection_slots = threading.BoundedSemaphore(10)

    def process_request(self, request, address):
        if not self.connection_slots.acquire(blocking=False):
            try:
                request.settimeout(1)
                request.sendall(b"HTTP/1.1 429 Too Many Requests\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            finally:
                self.shutdown_request(request)
            return
        try:
            super().process_request(request, address)
        except BaseException:
            self.connection_slots.release()
            raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.connection_slots.release()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def setup(self):
        super().setup()
        self.connection.settimeout(3)

    def log_message(self, format, *args):
        # URLs may contain personal query strings. Do not log request bodies, upstream URLs, or headers.
        pass

    def reply(self, status, value):
        raw = json.dumps(value, ensure_ascii=False).encode("utf-8")
        if len(raw) > MAX_JSON_BYTES:
            status, raw = 413, b'{"ok":false,"code":"response_too_large","error":"Response exceeds limit"}'
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True
        self.wfile.write(raw)

    def do_GET(self):
        if self.path == "/health":
            self.reply(200 if self.server.runtime_health.get("ready") else 503, self.server.runtime_health)
        else:
            self.reply(404, {"ok": False, "code": "not_found", "error": "Unknown endpoint"})

    def do_POST(self):
        if self.path != "/fetch":
            self.reply(404, {"ok": False, "code": "not_found", "error": "Unknown endpoint"})
            return
        if not self.server.fetch_slots.acquire(blocking=False):
            self.reply(429, {"ok": False, "code": "busy", "error": "Both rendering slots are busy"})
            return
        try:
            if not self.server.runtime_health.get("ready"):
                raise FetchError("runtime_not_ready", "Scrapling or Chromium is unavailable", 503)
            if self.headers.get("Transfer-Encoding") or len(self.headers.get_all("Content-Length", [])) != 1:
                raise FetchError("invalid_length", "A single Content-Length is required", 400)
            try:
                length = int(self.headers["Content-Length"])
            except ValueError:
                raise FetchError("invalid_length", "Invalid Content-Length", 400) from None
            if not 0 < length <= MAX_REQUEST_BYTES:
                raise FetchError("request_too_large", "Request must not exceed 8 KiB", 413)
            if self.headers.get_content_type() != "application/json":
                raise FetchError("invalid_content_type", "Expected application/json", 415)
            if self.headers.get("Authorization") or self.headers.get("Proxy-Authorization") or self.headers.get("Cookie"):
                raise FetchError("credentials_forbidden", "This service accepts anonymous requests only", 403)
            raw = self.rfile.read(length)
            if len(raw) != length:
                raise FetchError("invalid_request", "Incomplete request body", 400)
            try:
                payload = json.loads(raw)
            except (ValueError, UnicodeError):
                raise FetchError("invalid_json", "Request must contain valid JSON", 400) from None
            result = self.server.fetch(validate_request(payload))
            self.reply(200, result)
        except FetchError as error:
            self.reply(error.status, {"ok": False, "code": error.code, "error": str(error)})
        except (TimeoutError, socket.timeout):
            self.reply(408, {"ok": False, "code": "request_timeout", "error": "Request body timed out"})
        except Exception:
            self.reply(502, {"ok": False, "code": "render_failed", "error": "Rendering failed"})
        finally:
            self.server.fetch_slots.release()


if __name__ == "__main__":
    address = (os.environ.get("WEB_FETCHER_HOST", "0.0.0.0"), int(os.environ.get("WEB_FETCHER_PORT", "8082")))
    FetchServer(address).serve_forever()
