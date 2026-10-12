"""Fixed sandbox entrypoint. Each request runs in a fresh container with no mounts/network."""

import ast
import contextlib
import hashlib
import io
import json
import math
import signal
import sys

MAX_OUTPUT_BYTES = 200 * 1024
MAX_CAPTURE_BYTES = 8 * 1024


class BoundedText(io.TextIOBase):
    def __init__(self):
        self.data = bytearray()
        self.truncated = False

    def write(self, text):
        raw = str(text).encode("utf-8", errors="replace")
        remaining = MAX_CAPTURE_BYTES - len(self.data)
        self.data.extend(raw[:max(0, remaining)])
        self.truncated |= len(raw) > remaining
        return len(text)

    def getvalue(self):
        return self.data.decode("utf-8", errors="ignore")

    def writable(self):
        return True


def encode(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")


def run_code(payload):
    stdout, stderr = BoundedText(), BoundedText()
    result = {"ok": False, "stdout": "", "stderr": ""}
    try:
        parsed = ast.parse(payload["source"], filename="<generated-tool>")
        compiled = compile(parsed, "<generated-tool>", "exec")
        fingerprint = hashlib.sha256(ast.dump(parsed, include_attributes=False).encode("utf-8")).hexdigest()
        result["sourceFingerprint"] = fingerprint
        if payload.get("mode") == "analyze":
            result.update(ok=True, output={"fingerprint": fingerprint, "language": "python"})
            return result
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            namespace = {"__name__": "__learnhub_tool__"}
            exec(compiled, namespace, namespace)
            operation = namespace.get("run")
            if not callable(operation):
                raise ValueError("Source must define a callable run(input)")
            output = operation(payload["input"])
            # Roundtrip disallows custom Python objects and prevents output mutation after validation.
            encoded = encode(output)
            if len(encoded) > MAX_OUTPUT_BYTES:
                result.update(code="OUTPUT_TOO_LARGE", error="Output exceeds 200 KiB")
            else:
                result.update(ok=True, output=json.loads(encoded))
    except BaseException as exc:
        if isinstance(exc, SyntaxError):
            result.update(code="SYNTAX_ERROR", error=("SyntaxError at line " + str(exc.lineno) + ": " + str(exc.msg))[:400])
        elif isinstance(exc, TimeoutError):
            result.update(code="TIMEOUT", error="Execution exceeded its time limit")
        elif isinstance(exc, (TypeError, ValueError)) and not isinstance(exc, SyntaxError):
            result.update(code="INVALID_OUTPUT" if "JSON" in str(exc) or "serializable" in str(exc) or "compliant" in str(exc) else "EXECUTION_FAILED", error=(type(exc).__name__ + ": " + str(exc))[:400])
        else:
            result.update(code="EXECUTION_FAILED", error=(type(exc).__name__ + ": " + str(exc))[:400])
    result["stdout"], result["stderr"] = stdout.getvalue(), stderr.getvalue()
    return result


def limits(timeout_ms):
    import resource
    cpu = math.ceil(timeout_ms / 1000) + 1
    resource.setrlimit(resource.RLIMIT_CPU, (cpu, cpu + 1))
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))

    def timed_out(_signum, _frame):
        raise TimeoutError("Execution time limit")

    signal.signal(signal.SIGALRM, timed_out)
    signal.setitimer(signal.ITIMER_REAL, timeout_ms / 1000)


def main():
    # Input is a bounded JSON line on an attached stdin, never an argument or host file.
    signal.signal(signal.SIGALRM, lambda _signum, _frame: sys.exit(1))
    signal.setitimer(signal.ITIMER_REAL, 10)
    raw = sys.stdin.buffer.readline(1024 * 1024 + 1)
    if not raw.endswith(b"\n") or len(raw) > 1024 * 1024:
        raise ValueError("Invalid execution input")
    payload = json.loads(raw)
    limits(payload["timeoutMs"])
    result = run_code(payload)
    signal.setitimer(signal.ITIMER_REAL, 0)
    sys.stdout.buffer.write(encode(result))
    sys.stdout.buffer.flush()


if __name__ == "__main__":
    main()
