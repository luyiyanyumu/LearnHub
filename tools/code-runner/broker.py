"""Internal JSON-only broker for disposable, resource-limited Python containers."""

import http.client
import json
import os
import re
import socket
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import quote

MAX_SOURCE_BYTES = 32 * 1024
MAX_VALUE_BYTES = 200 * 1024
MAX_REQUEST_BYTES = 1024 * 1024
MAX_LOG_BYTES = 256 * 1024
MAX_CAPTURE_BYTES = 8 * 1024
DEFAULT_TIMEOUT_MS = 8000
MAX_TIMEOUT_MS = 15000
OWNER_LABEL = "io.learnhub.code-runner"


class RunnerError(Exception):
    def __init__(self, code, message, status=422):
        super().__init__(message)
        self.code, self.status = code, status


class EngineError(Exception):
    def __init__(self, status):
        super().__init__("Container engine request failed")
        self.status = status


def json_bytes(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")


def error_result(code, message, elapsed_ms=0):
    return {"ok": False, "code": code, "error": message, "stdout": "", "stderr": "", "elapsedMs": elapsed_ms}


def validate_request(payload, analyze=False):
    allowed = {"source"} if analyze else {"source", "input", "timeoutMs"}
    if not isinstance(payload, dict) or set(payload) - allowed:
        raise RunnerError("INVALID_REQUEST", "Only source, input and timeoutMs are accepted", 400)
    source = payload.get("source")
    if not isinstance(source, str) or not source.strip():
        raise RunnerError("INVALID_SOURCE", "source must define run(input)", 400)
    try:
        source_size = len(source.encode("utf-8"))
    except UnicodeError:
        raise RunnerError("INVALID_SOURCE", "source must contain valid Unicode text", 400)
    if source_size > MAX_SOURCE_BYTES:
        raise RunnerError("SOURCE_TOO_LARGE", "Source exceeds 32 KiB", 413)
    if analyze:
        return {"source": source, "input": None, "timeoutMs": 5000, "mode": "analyze"}
    if "input" not in payload:
        raise RunnerError("INVALID_INPUT", "input is required", 400)
    try:
        input_size = len(json_bytes(payload["input"]))
    except (ValueError, TypeError, RecursionError):
        raise RunnerError("INVALID_INPUT", "input must be a finite JSON value", 400)
    if input_size > MAX_VALUE_BYTES:
        raise RunnerError("INPUT_TOO_LARGE", "Input exceeds 200 KiB", 413)
    timeout_ms = payload.get("timeoutMs", DEFAULT_TIMEOUT_MS)
    if type(timeout_ms) is not int or not 1000 <= timeout_ms <= MAX_TIMEOUT_MS:
        raise RunnerError("INVALID_TIMEOUT", "timeoutMs must be an integer between 1000 and 15000", 400)
    return {"source": source, "input": payload["input"], "timeoutMs": timeout_ms, "mode": "execute"}


class UnixHTTPConnection(http.client.HTTPConnection):
    def __init__(self, socket_path, timeout):
        super().__init__("localhost", timeout=timeout)
        self.socket_path = socket_path

    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(self.timeout)
        self.sock.connect(self.socket_path)


class DockerEngine:
    """No subprocesses, configurable commands, host paths or image names from requests."""

    def __init__(self, socket_path="/var/run/docker.sock"):
        self.socket_path = socket_path

    def request(self, method, path, body=None, timeout=5, raw=False, limit=MAX_LOG_BYTES):
        connection = UnixHTTPConnection(self.socket_path, timeout)
        try:
            encoded = body if isinstance(body, bytes) else json_bytes(body) if body is not None else None
            headers = {"Content-Type": "application/x-tar" if raw else "application/json"}
            connection.request(method, "/v1.45" + path, body=encoded, headers=headers)
            response = connection.getresponse()
            data = response.read(limit + 1)
            if len(data) > limit:
                raise RunnerError("OUTPUT_TOO_LARGE", "Execution output exceeded its limit")
            if not 200 <= response.status < 300:
                # Docker error bodies can include machine paths/configuration. Never forward them.
                raise EngineError(response.status)
            if raw:
                return data
            return json.loads(data) if data else {}
        finally:
            connection.close()

    def image_available(self, image):
        self.request("GET", "/images/" + quote(image, safe="") + "/json")

    def create(self, configuration):
        return self.request("POST", "/containers/create?name=learnhub-code-" + uuid.uuid4().hex, configuration)["Id"]

    def start(self, container_id):
        self.request("POST", "/containers/" + container_id + "/start")

    def open_input(self, container_id):
        # Attach before start so the fixed worker can read JSON from its stdin.
        # An HTTP upgrade is needed; this socket carries only stdin, never generated stdout.
        connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        connection.settimeout(5)
        try:
            connection.connect(self.socket_path)
            path = "/v1.45/containers/" + container_id + "/attach?stream=1&stdin=1&stdout=0&stderr=0"
            connection.sendall(("POST " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade\r\nUpgrade: tcp\r\nContent-Length: 0\r\n\r\n").encode("ascii"))
            headers = bytearray()
            while not headers.endswith(b"\r\n\r\n"):
                part = connection.recv(1)
                if not part or len(headers) >= 8192:
                    raise EngineError(502)
                headers.extend(part)
            status = int(bytes(headers).split(b" ", 2)[1])
            if status != 101:
                raise EngineError(status)
            return connection
        except BaseException:
            connection.close()
            raise

    def send_input(self, connection, payload):
        connection.sendall(json_bytes(payload) + b"\n")
        # Keep the read side attached until the worker exits. Closing both ends here can
        # cancel Docker's stdin copy before the payload has reached the process.
        connection.shutdown(socket.SHUT_WR)

    def wait(self, container_id, timeout):
        return self.request("POST", "/containers/" + container_id + "/wait?condition=not-running", timeout=timeout)

    def logs(self, container_id):
        return self.request("GET", "/containers/" + container_id + "/logs?stdout=1&stderr=1", raw=True)

    def remove(self, container_id):
        # force removes a running container as well, including its tmpfs and anonymous volumes.
        self.request("DELETE", "/containers/" + container_id + "?force=1&v=1", timeout=3)

    def expired(self, owner, now):
        filters = quote(json.dumps({"label": [OWNER_LABEL + "=" + owner]}), safe="")
        containers = self.request("GET", "/containers/json?all=1&filters=" + filters)
        return [item["Id"] for item in containers if now - item.get("Created", now) > 60]


def container_configuration(image, owner):
    return {
        "Image": image,
        "User": "10001:10001",
        "WorkingDir": "/tmp",
        "Entrypoint": ["python", "-I", "-B", "/opt/runner/worker.py"],
        "Cmd": [],
        "Env": ["PYTHONDONTWRITEBYTECODE=1", "PYTHONUNBUFFERED=1", "HOME=/tmp", "LANG=C.UTF-8"],
        "NetworkDisabled": True,
        "OpenStdin": True,
        "StdinOnce": True,
        "AttachStdin": True,
        "Tty": False,
        "Labels": {OWNER_LABEL: owner},
        "HostConfig": {
            "NetworkMode": "none",
            "ReadonlyRootfs": True,
            "CapDrop": ["ALL"],
            "SecurityOpt": ["no-new-privileges:true"],
            "PidsLimit": 32,
            "Memory": 192 * 1024 * 1024,
            "MemorySwap": 192 * 1024 * 1024,
            "CpuPeriod": 100000,
            "CpuQuota": 100000,
            "Tmpfs": {"/tmp": "rw,noexec,nosuid,nodev,size=16m,mode=1777"},
            "Ulimits": [{"Name": "nofile", "Soft": 64, "Hard": 64}, {"Name": "fsize", "Soft": 2 * 1024 * 1024, "Hard": 2 * 1024 * 1024}],
            "LogConfig": {"Type": "json-file", "Config": {"max-size": "256k", "max-file": "1"}},
        },
    }


def decode_logs(raw):
    """Docker's non-TTY stream has an 8-byte header for each stdout/stderr frame."""
    outputs = {1: bytearray(), 2: bytearray()}
    offset = 0
    while offset < len(raw):
        if len(raw) - offset < 8:
            raise RunnerError("INVALID_OUTPUT", "Execution returned an incomplete output frame")
        header = raw[offset:offset + 8]
        length = int.from_bytes(header[4:], "big")
        offset += 8
        if header[0] not in outputs or header[1:4] != b"\0\0\0" or length > len(raw) - offset:
            raise RunnerError("INVALID_OUTPUT", "Execution returned an invalid output frame")
        outputs[header[0]].extend(raw[offset:offset + length])
        offset += length
    return bytes(outputs[1]), bytes(outputs[2])


class CodeRunner:
    def __init__(self, engine, image, owner="learnhub"):
        self.engine, self.image, self.owner = engine, image, owner

    def health(self):
        self.engine.image_available(self.image)
        return {"ok": True, "ready": True, "available": True, "runtime": "python", "maxTimeoutMs": MAX_TIMEOUT_MS, "maxConcurrency": 2}

    def execute(self, payload, analyze=False):
        payload = validate_request(payload, analyze)
        started = time.monotonic()
        container_id = None
        input_connection = None
        result = None
        try:
            container_id = self.engine.create(container_configuration(self.image, self.owner))
            input_connection = self.engine.open_input(container_id)
            self.engine.start(container_id)
            self.engine.send_input(input_connection, payload)
            state = self.engine.wait(container_id, payload["timeoutMs"] / 1000 + 0.75)
            if state.get("StatusCode") != 0:
                raise RunnerError("EXECUTION_FAILED", "Python process exited before returning valid JSON (resource limit or runtime failure)")
            stdout, _stderr = decode_logs(self.engine.logs(container_id))
            try:
                result = json.loads(stdout)
            except (ValueError, UnicodeError, RecursionError):
                raise RunnerError("INVALID_OUTPUT", "Python process did not return a single JSON result")
            if not isinstance(result, dict) or type(result.get("ok")) is not bool:
                raise RunnerError("INVALID_OUTPUT", "Python process returned an invalid result")
            if result["ok"]:
                if "output" not in result:
                    raise RunnerError("INVALID_OUTPUT", "Python process returned no output value")
                try:
                    output_size = len(json_bytes(result["output"]))
                except (ValueError, TypeError, RecursionError):
                    raise RunnerError("INVALID_OUTPUT", "Python output must be a finite JSON value")
                if output_size > MAX_VALUE_BYTES:
                    raise RunnerError("OUTPUT_TOO_LARGE", "Output exceeds 200 KiB")
            # Keep only the public contract; generated code cannot add broker metadata.
            result = {key: value for key, value in result.items() if key in {"ok", "output", "error", "code", "stdout", "stderr", "sourceFingerprint"}}
            if not result["ok"]:
                if not isinstance(result.get("error"), str) or not isinstance(result.get("code"), str):
                    raise RunnerError("INVALID_OUTPUT", "Python process returned an invalid error result")
                result["error"], result["code"] = result["error"][:400], result["code"][:64]
                result.pop("output", None)
            if "sourceFingerprint" in result and (not isinstance(result["sourceFingerprint"], str) or not re.fullmatch(r"[a-f0-9]{64}", result["sourceFingerprint"])):
                result.pop("sourceFingerprint", None)
            for key in ("stdout", "stderr"):
                result[key] = str(result.get(key, "")).encode("utf-8")[:MAX_CAPTURE_BYTES].decode("utf-8", errors="ignore")
        except (TimeoutError, socket.timeout):
            result = error_result("TIMEOUT", "Execution exceeded its time limit")
        except RunnerError as exc:
            result = error_result(exc.code, str(exc))
        except (EngineError, OSError, ValueError, TypeError, KeyError, RecursionError):
            result = error_result("RUNNER_UNAVAILABLE", "Isolated Python runtime is unavailable")
        finally:
            if input_connection:
                try:
                    input_connection.close()
                except OSError:
                    pass
            if container_id:
                # A transient engine error is retried. The label-based reaper handles broker restarts.
                cleaned = False
                for _attempt in range(2):
                    try:
                        self.engine.remove(container_id)
                        cleaned = True
                        break
                    except EngineError as exc:
                        if exc.status == 404:
                            cleaned = True
                            break
                    except (OSError, RunnerError):
                        pass
                if not cleaned:
                    result = error_result("CLEANUP_FAILED", "Sandbox cleanup is pending and will be retried by the runner")
        result["elapsedMs"] = int((time.monotonic() - started) * 1000)
        return result

    def reap(self):
        for container_id in self.engine.expired(self.owner, time.time()):
            try:
                self.engine.remove(container_id)
            except (EngineError, OSError, RunnerError):
                pass


class RunnerHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, runner):
        self.runner = runner
        self.slots = threading.BoundedSemaphore(2)
        super().__init__(address, RunnerHandler)


class RunnerHandler(BaseHTTPRequestHandler):
    server_version = "LearnHubCodeRunner/1"

    def setup(self):
        super().setup()
        self.connection.settimeout(5)

    def log_message(self, _format, *_args):
        # No source, input, model output or local configuration is written to access logs.
        pass

    def reply(self, status, payload):
        data = json_bytes(payload)
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        if self.path != "/health":
            return self.reply(404, error_result("NOT_FOUND", "Unknown endpoint"))
        try:
            self.reply(200, self.server.runner.health())
        except (EngineError, OSError, RunnerError, ValueError, KeyError):
            self.reply(503, error_result("RUNNER_UNAVAILABLE", "Sandbox image or container engine is unavailable"))

    def do_POST(self):
        if self.path not in {"/execute", "/analyze"}:
            return self.reply(404, error_result("NOT_FOUND", "Unknown endpoint"))
        if not self.server.slots.acquire(blocking=False):
            return self.reply(429, error_result("RUNNER_BUSY", "Both execution slots are busy; retry later"))
        try:
            if self.headers.get("Transfer-Encoding") or not self.headers.get("Content-Type", "").lower().startswith("application/json"):
                raise RunnerError("INVALID_REQUEST", "A JSON body with Content-Length is required", 400)
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                raise RunnerError("INVALID_REQUEST", "Invalid Content-Length", 400)
            if not 0 < length <= MAX_REQUEST_BYTES:
                raise RunnerError("REQUEST_TOO_LARGE", "Request body must be between 1 byte and 1 MiB", 413)
            raw = self.rfile.read(length)
            if len(raw) != length:
                raise RunnerError("INVALID_REQUEST", "Incomplete request body", 400)
            try:
                payload = json.loads(raw)
            except (ValueError, UnicodeError, RecursionError):
                raise RunnerError("INVALID_REQUEST", "Request body must be valid JSON", 400)
            self.reply(200, self.server.runner.execute(payload, analyze=self.path == "/analyze"))
        except RunnerError as exc:
            self.reply(exc.status, error_result(exc.code, str(exc)))
        except (TimeoutError, socket.timeout):
            self.reply(408, error_result("REQUEST_TIMEOUT", "Request body read timed out"))
        finally:
            self.server.slots.release()


def main():
    image = os.environ.get("LEARNHUB_SANDBOX_IMAGE", "learn-hub-code-sandbox:local")
    owner = os.environ.get("LEARNHUB_RUNNER_OWNER", "learnhub")
    runner = CodeRunner(DockerEngine(), image, owner)

    def cleanup_loop():
        while True:
            try:
                runner.reap()
            except (EngineError, OSError, RunnerError, ValueError, KeyError):
                pass
            time.sleep(30)

    threading.Thread(target=cleanup_loop, daemon=True, name="sandbox-cleanup").start()
    RunnerHTTPServer(("0.0.0.0", 8081), runner).serve_forever(poll_interval=0.5)


if __name__ == "__main__":
    main()
