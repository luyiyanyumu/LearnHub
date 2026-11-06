"""Internal PNG-only HTTP service. No URLs, paths, credentials or custom commands."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import socket
import threading
import time

from engine import MAX_INPUT_BYTES, MAX_OUTPUT_BYTES, TIMEOUT_SECONDS, OcrError, health, recognize


class OcrServer(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 8

    def __init__(self, address, *, runtime_health=None, recognizer=recognize):
        super().__init__(address, Handler)
        self.runtime = health() if runtime_health is None else runtime_health
        self.recognizer = recognizer
        self.ocr_slots = threading.BoundedSemaphore(2)
        self.connection_slots = threading.BoundedSemaphore(10)

    def process_request(self, request, address):
        if not self.connection_slots.acquire(blocking=False):
            raw = b'{"ok":false,"code":"busy","error":"OCR connection capacity is full"}'
            try:
                request.settimeout(1)
                request.sendall(b"HTTP/1.1 429 Too Many Requests\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: "
                                + str(len(raw)).encode() + b"\r\n\r\n" + raw)
            except OSError:
                pass
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
        self.connection.settimeout(5)

    def log_message(self, *_):
        pass  # Never log images, OCR text, headers or client-provided paths.

    def reply(self, status, value):
        raw = json.dumps(value, ensure_ascii=False).encode("utf-8")
        if len(raw) > MAX_OUTPUT_BYTES:
            status, raw = 502, b'{"ok":false,"code":"output_too_large","error":"OCR response exceeded its limit"}'
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Connection", "close")
        if status == 429:
            self.send_header("Retry-After", "1")
        self.end_headers()
        self.close_connection = True
        try:
            self.wfile.write(raw)
        except OSError:
            pass

    def route(self, expected):
        if "?" in self.path or "#" in self.path:
            self.reply(400, {"ok": False, "code": "query_forbidden", "error": "Query strings and fragments are not accepted"})
            return False
        if self.path != expected:
            self.reply(404, {"ok": False, "code": "not_found", "error": "Unknown endpoint"})
            return False
        return True

    def do_GET(self):
        if self.route("/health"):
            self.reply(200 if self.server.runtime["ready"] else 503, self.server.runtime)

    def do_POST(self):
        if not self.route("/ocr"):
            return
        if not self.server.ocr_slots.acquire(blocking=False):
            self.reply(429, {"ok": False, "code": "busy", "error": "Both OCR slots are busy"})
            return
        deadline = time.monotonic() + TIMEOUT_SECONDS
        try:
            if not self.server.runtime["ready"]:
                raise OcrError("engine_unavailable", "Tesseract chi_sim and eng are not ready", 503)
            if self.headers.get_all("Transfer-Encoding") or len(self.headers.get_all("Content-Length", [])) != 1:
                raise OcrError("invalid_length", "A single Content-Length and no Transfer-Encoding are required")
            length_text = self.headers["Content-Length"]
            if len(length_text) > 10 or not length_text.isascii() or not length_text.isdecimal():
                raise OcrError("invalid_length", "Invalid Content-Length")
            length = int(length_text)
            if length == 0:
                raise OcrError("invalid_image", "PNG request body is empty")
            if length > MAX_INPUT_BYTES:
                raise OcrError("image_too_large", "PNG body must be between 1 byte and 12 MiB", 413)
            if len(self.headers.get_all("Content-Type", [])) != 1 or self.headers.get_content_type() != "image/png":
                raise OcrError("invalid_content_type", "Expected image/png raw bytes", 415)
            if any(self.headers.get_all(name) for name in ("Authorization", "Proxy-Authorization", "Cookie")):
                raise OcrError("credentials_forbidden", "This service does not accept credentials", 403)
            raw = bytearray()
            while len(raw) < length:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise OcrError("request_timeout", "PNG body exceeded the request deadline", 408)
                self.connection.settimeout(min(5, remaining))
                chunk = self.rfile.read1(min(64 * 1024, length - len(raw)))
                if not chunk:
                    raise OcrError("invalid_image", "Incomplete PNG request body")
                raw.extend(chunk)
            self.reply(200, self.server.recognizer(bytes(raw), self.server.runtime, deadline - time.monotonic()))
        except OcrError as error:
            self.reply(error.status, {"ok": False, "code": error.code, "error": str(error)})
        except (TimeoutError, socket.timeout):
            self.reply(408, {"ok": False, "code": "request_timeout", "error": "PNG request body timed out"})
        except Exception:
            self.reply(502, {"ok": False, "code": "ocr_failed", "error": "OCR processing failed"})
        finally:
            self.server.ocr_slots.release()

    def unsupported(self):
        self.reply(405, {"ok": False, "code": "method_not_allowed", "error": "Use GET /health or POST /ocr"})

    do_PUT = do_DELETE = do_PATCH = do_OPTIONS = unsupported


if __name__ == "__main__":
    OcrServer(("0.0.0.0", 8083)).serve_forever()
