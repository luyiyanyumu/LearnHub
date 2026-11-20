"""Internal, bounded PNG-only HTTP endpoint for the local structure worker."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import multiprocessing
import threading
import time
import traceback

from engine import DEVICE, ENGINE, MODEL_PROFILE, MAX_INPUT_BYTES, MAX_OUTPUT_BYTES, TIMEOUT_SECONDS, VERSION, ModelEngine, PageError
from models import MODELS


def error_body(error):
    return {"ok": False, "code": error.code, "error": str(error)}


def pack(status, body):
    raw = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode()
    if len(raw) > MAX_OUTPUT_BYTES:
        status = 502
        raw = b'{"ok":false,"code":"output_too_large","error":"Page result exceeded 2 MiB"}'
    return status.to_bytes(2, "big") + raw


def work(pipe):
    try:
        engine = ModelEngine()
        pipe.send_bytes(b"ready")
    except Exception:
        # Initialization receives no document data.
        traceback.print_exc()
        pipe.close()
        return
    try:
        while True:
            raw = pipe.recv_bytes(MAX_INPUT_BYTES)
            try:
                packet = pack(200, engine.recognize(raw))
            except PageError as error:
                packet = pack(error.status, error_body(error))
            except Exception:
                packet = pack(502, {"ok": False, "code": "structure_failed", "error": "Page structure recognition failed"})
            pipe.send_bytes(packet)
    except (EOFError, OSError):
        pass
    finally:
        pipe.close()


class Runtime:
    """Model hangs and crashes do not occupy the HTTP server or the next page slot."""
    def __init__(self, worker=work):
        self.context = multiprocessing.get_context("spawn")
        self.worker = worker
        self.lock = threading.Lock()
        self.closed = False
        self.restart_after = None
        self.restart_delay = 5
        self._start()

    def _start(self):
        self.pipe, child = self.context.Pipe()
        self.process = self.context.Process(target=self.worker, args=(child,), daemon=True)
        self.process.start()
        child.close()
        self.ready = False
        self.started = time.monotonic()

    def _stop(self):
        self.ready = False
        self.pipe.close()
        if self.process.is_alive():
            self.process.kill()
        self.process.join(timeout=1)

    def close(self):
        with self.lock:
            self.closed = True
            self._stop()

    def _schedule_restart(self):
        # Recover an idle crash or initialization failure without spinning on broken models.
        # No page is replayed: health only prepares a worker for the next explicit request.
        self._stop()
        self.restart_after = time.monotonic() + self.restart_delay
        self.restart_delay = min(60, self.restart_delay * 2)

    def available(self):
        with self.lock:
            if self.closed:
                return False
            if self.restart_after is not None:
                if time.monotonic() < self.restart_after:
                    return False
                self.restart_after = None
                self._start()
                return False
            if not self.process.is_alive():
                self._schedule_restart()
                return False
            if not self.ready and self.pipe.poll():
                try:
                    self.ready = self.pipe.recv_bytes(8) == b"ready"
                except (EOFError, OSError):
                    self._schedule_restart()
                    return False
                if not self.ready:
                    self._schedule_restart()
                    return False
                self.restart_delay = 5
            if not self.ready and time.monotonic() - self.started > 180:
                self._schedule_restart()
            return self.ready

    def recognize(self, raw, deadline):
        if not self.available():
            raise PageError("engine_unavailable", "Structure models are not ready", 503)
        try:
            self.pipe.send_bytes(raw)
            if not self.pipe.poll(max(0, deadline - time.monotonic())):
                raise TimeoutError
            packet = self.pipe.recv_bytes(MAX_OUTPUT_BYTES + 2)
            return int.from_bytes(packet[:2], "big"), packet[2:]
        except (EOFError, OSError) as error:
            with self.lock:
                self._stop()
                self._start()
            if isinstance(error, TimeoutError):
                raise PageError("ocr_timeout", "Page structure recognition exceeded its deadline", 504) from None
            raise PageError("structure_failed", "Page structure worker stopped unexpectedly", 502) from None


class StructureServer(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 8

    def __init__(self, address, runtime):
        self.runtime = runtime
        self.page_slot = threading.BoundedSemaphore(1)
        self.connection_slots = threading.BoundedSemaphore(8)
        super().__init__(address, Handler)

    def process_request(self, request, address):
        if not self.connection_slots.acquire(blocking=False):
            try:
                request.settimeout(1)
                request.sendall(b"HTTP/1.1 429 Too Many Requests\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
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
        pass

    def reply(self, status, raw):
        self.close_connection = True
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Connection", "close")
            if status == 429:
                self.send_header("Retry-After", "1")
            self.end_headers()
            self.wfile.write(raw)
        except OSError:
            pass

    def respond(self, status, body):
        self.reply(status, pack(status, body)[2:])

    def route(self, expected):
        if self.path == expected:
            return True
        if "?" in self.path or "#" in self.path:
            error = PageError("query_forbidden", "Query strings and fragments are not accepted")
        else:
            error = PageError("not_found", "Unknown endpoint", 404)
        self.respond(error.status, error_body(error))
        return False

    def do_GET(self):
        if self.route("/health"):
            ready = self.server.runtime.available()
            self.respond(200 if ready else 503, {"ok": ready, "ready": ready, "engine": ENGINE, "version": VERSION,
                                               "profile": MODEL_PROFILE, "models": list(MODELS), "device": DEVICE})

    def do_POST(self):
        if not self.route("/ocr"):
            return
        if not self.server.page_slot.acquire(blocking=False):
            error = PageError("busy", "The structure page slot is busy", 429)
            self.respond(error.status, error_body(error))
            return
        deadline = time.monotonic() + TIMEOUT_SECONDS
        try:
            raw = self.read_page(deadline)
            status, result = self.server.runtime.recognize(raw, deadline)
            self.reply(status, result)
        except PageError as error:
            self.respond(error.status, error_body(error))
        except TimeoutError:
            error = PageError("request_timeout", "PNG request body timed out", 408)
            self.respond(error.status, error_body(error))
        finally:
            self.server.page_slot.release()

    def read_page(self, deadline):
        length_header = self.headers.get_all("Content-Length", [])
        if (self.headers.get_all("Transfer-Encoding") or len(length_header) != 1
                or len(length_header[0]) > 10 or not length_header[0].isascii() or not length_header[0].isdecimal()):
            raise PageError("invalid_length", "A single numeric Content-Length is required")
        length = int(length_header[0])
        if not length:
            raise PageError("invalid_image", "PNG request body is empty")
        if length > MAX_INPUT_BYTES:
            raise PageError("image_too_large", "PNG body exceeds 12 MiB", 413)
        if len(self.headers.get_all("Content-Type", [])) != 1 or self.headers.get_content_type() != "image/png":
            raise PageError("invalid_content_type", "Expected image/png raw bytes", 415)
        if any(self.headers.get_all(name) for name in ("Authorization", "Proxy-Authorization", "Cookie")):
            raise PageError("credentials_forbidden", "This service does not accept credentials", 403)
        if not self.server.runtime.available():
            raise PageError("engine_unavailable", "Structure models are not ready", 503)
        body = bytearray()
        while len(body) < length:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError
            self.connection.settimeout(min(5, remaining))
            chunk = self.rfile.read1(min(64 * 1024, length - len(body)))
            if not chunk:
                raise PageError("invalid_image", "Incomplete PNG request body")
            body.extend(chunk)
        return bytes(body)

    def unsupported(self):
        self.respond(405, {"ok": False, "code": "method_not_allowed", "error": "Use GET /health or POST /ocr"})

    do_PUT = do_DELETE = do_PATCH = do_OPTIONS = unsupported


if __name__ == "__main__":
    runtime = Runtime()
    try:
        with StructureServer(("0.0.0.0", 8084), runtime) as server:
            server.serve_forever()
    finally:
        runtime.close()
