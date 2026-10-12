import http.client
import json
import threading
import unittest
from unittest.mock import Mock

import broker
import worker


def frame(value, stream=1):
    payload = value if isinstance(value, bytes) else broker.json_bytes(value)
    return bytes([stream, 0, 0, 0]) + len(payload).to_bytes(4, "big") + payload


class RunnerTests(unittest.TestCase):
    def setUp(self):
        self.engine = Mock()
        self.engine.create.return_value = "synthetic-container"
        self.engine.wait.return_value = {"StatusCode": 0}
        self.engine.logs.return_value = frame({"ok": True, "output": {"answer": 42}, "stdout": "hello", "stderr": ""})
        self.runner = broker.CodeRunner(self.engine, "fixed-sandbox:test")
        self.payload = {"source": "def run(input):\n    return input['value'] * 2", "input": {"value": 21}}

    def test_success_contract_and_cleanup(self):
        result = self.runner.execute(self.payload)
        self.assertEqual(result["output"], {"answer": 42})
        self.assertTrue(result["ok"])
        self.assertIsInstance(result["elapsedMs"], int)
        self.engine.remove.assert_called_once_with("synthetic-container")

    def test_fixed_container_isolation(self):
        self.runner.execute(self.payload)
        config = self.engine.create.call_args.args[0]
        self.assertEqual(config["Image"], "fixed-sandbox:test")
        self.assertEqual(config["User"], "10001:10001")
        self.assertTrue(config["NetworkDisabled"])
        host = config["HostConfig"]
        self.assertEqual(host["NetworkMode"], "none")
        self.assertTrue(host["ReadonlyRootfs"])
        self.assertEqual(host["CapDrop"], ["ALL"])
        self.assertEqual(host["SecurityOpt"], ["no-new-privileges:true"])
        self.assertNotIn("Binds", host)
        self.assertNotIn("Mounts", host)
        self.assertLessEqual(host["PidsLimit"], 32)
        self.assertLessEqual(host["Memory"], 192 * 1024 * 1024)
        self.assertIn("noexec", host["Tmpfs"]["/tmp"])

    def test_payload_transferred_only_through_attached_stdin(self):
        self.runner.execute(self.payload)
        value = self.engine.send_input.call_args.args[1]
        self.assertEqual(value["input"], {"value": 21})
        self.assertEqual(value["mode"], "execute")
        self.engine.open_input.assert_called_once_with("synthetic-container")
        self.engine.open_input.return_value.close.assert_called_once()
        connection = Mock()
        broker.DockerEngine().send_input(connection, value)
        encoded = connection.sendall.call_args.args[0]
        self.assertTrue(encoded.endswith(b"\n"))
        self.assertEqual(json.loads(encoded), value)
        connection.shutdown.assert_called_once()

    def test_analyze_cannot_accept_execution_overrides(self):
        with self.assertRaises(broker.RunnerError):
            self.runner.execute({**self.payload, "mode": "execute"}, analyze=True)
        self.runner.execute({"source": self.payload["source"]}, analyze=True)
        self.assertEqual(self.engine.send_input.call_args.args[1]["mode"], "analyze")

    def test_timeout_force_removes_sandbox(self):
        self.engine.wait.side_effect = TimeoutError()
        result = self.runner.execute(self.payload)
        self.assertEqual(result["code"], "TIMEOUT")
        self.engine.remove.assert_called_once()

    def test_start_and_input_failures_remove_created_container(self):
        for stage in ("start", "send_input", "open_input"):
            with self.subTest(stage=stage):
                getattr(self.engine, stage).side_effect = broker.EngineError(500)
                result = self.runner.execute(self.payload)
                self.assertEqual(result["code"], "RUNNER_UNAVAILABLE")
                self.engine.remove.assert_called()
                getattr(self.engine, stage).side_effect = None

    def test_resource_limit_failure_has_no_unbounded_log_read(self):
        self.engine.wait.return_value = {"StatusCode": 137}
        self.assertEqual(self.runner.execute(self.payload)["code"], "EXECUTION_FAILED")
        self.engine.logs.assert_not_called()

    def test_cleanup_retry(self):
        self.engine.remove.side_effect = [OSError("synthetic"), None]
        self.assertTrue(self.runner.execute(self.payload)["ok"])
        self.assertEqual(self.engine.remove.call_count, 2)

    def test_cleanup_failure_is_reported(self):
        self.engine.remove.side_effect = OSError("synthetic private path")
        result = self.runner.execute(self.payload)
        self.assertEqual(result["code"], "CLEANUP_FAILED")
        self.assertNotIn("private", result["error"])

    def test_stdin_close_failure_still_cleans_container(self):
        self.engine.open_input.return_value.close.side_effect = OSError("synthetic disconnect")
        self.assertTrue(self.runner.execute(self.payload)["ok"])
        self.engine.remove.assert_called_once()

    def test_no_runtime_details_leak_on_engine_failure(self):
        self.engine.create.side_effect = OSError("/private/socket with secret")
        result = self.runner.execute(self.payload)
        self.assertNotIn("secret", json.dumps(result))
        self.engine.remove.assert_not_called()

    def test_rejects_runtime_configuration_from_caller(self):
        for key in ("image", "hostPath", "network", "command", "privileged", "mode"):
            with self.subTest(key=key), self.assertRaises(broker.RunnerError):
                self.runner.execute({**self.payload, key: "anything"})
        self.engine.create.assert_not_called()

    def test_limits_are_byte_based_and_timeout_is_strict(self):
        for patch in ({"source": "中" * 11000}, {"source": "\ud800"}, {"input": "中" * 70000}, {"timeoutMs": 999}, {"timeoutMs": 15001}, {"timeoutMs": True}, {"input": float("nan")}):
            with self.subTest(patch=list(patch)), self.assertRaises(broker.RunnerError):
                self.runner.execute({**self.payload, **patch})

    def test_invalid_worker_json_or_framing_is_rejected(self):
        for raw in (b"short", frame(b"not JSON"), frame({"output": 1}), frame({"ok": True}), frame(b'{"ok":true,"output":NaN}'), frame({"ok": False, "error": 12, "code": 3}), b"\x01\x00\x00\x00\x00\x00\xff\xff"):
            self.engine.logs.return_value = raw
            self.assertEqual(self.runner.execute(self.payload)["code"], "INVALID_OUTPUT")

    def test_stdout_frames_reassembled_without_stderr(self):
        self.engine.logs.return_value = frame(b'{"ok":true,') + frame(b'warning', 2) + frame(b'"output":5}')
        self.assertEqual(self.runner.execute(self.payload)["output"], 5)

    def test_oversized_worker_output_rejected(self):
        self.engine.logs.return_value = frame({"ok": True, "output": "x" * (broker.MAX_VALUE_BYTES + 1)})
        self.assertEqual(self.runner.execute(self.payload)["code"], "OUTPUT_TOO_LARGE")

    def test_reaper_uses_own_containers_only(self):
        self.engine.expired.return_value = ["expired-synthetic"]
        self.runner.reap()
        self.engine.expired.assert_called_once()
        self.assertEqual(self.engine.expired.call_args.args[0], "learnhub")
        self.engine.remove.assert_called_once_with("expired-synthetic")


class WorkerTests(unittest.TestCase):
    def run_source(self, source, value=None, mode="execute"):
        return worker.run_code({"source": source, "input": value, "mode": mode})

    def test_executes_json_function_and_captures_prints(self):
        result = self.run_source("def run(input):\n print('处理中')\n return {'total': sum(input)}", [2, 3])
        self.assertTrue(result["ok"])
        self.assertEqual(result["output"], {"total": 5})
        self.assertEqual(result["stdout"], "处理中\n")
        self.assertEqual(len(result["sourceFingerprint"]), 64)

    def test_canonical_fingerprint_ignores_comments_and_spacing(self):
        first = self.run_source("def run(input):\n return input+1", mode="analyze")
        second = self.run_source("# comment\ndef run(input):\n    return input + 1\n", mode="analyze")
        changed = self.run_source("def run(input):\n return input+2", mode="analyze")
        self.assertEqual(first["output"]["fingerprint"], second["output"]["fingerprint"])
        self.assertNotEqual(first["output"]["fingerprint"], changed["output"]["fingerprint"])

    def test_analysis_compiles_but_never_executes(self):
        result = self.run_source("raise RuntimeError('must not run')\ndef run(input):\n return input", mode="analyze")
        self.assertTrue(result["ok"])
        self.assertEqual(result["stdout"], "")

    def test_syntax_error_returns_diagnostic_without_fingerprint(self):
        result = self.run_source("def run(input)\n return input", mode="analyze")
        self.assertEqual(result["code"], "SYNTAX_ERROR")
        self.assertIn("line 1", result["error"])
        self.assertNotIn("sourceFingerprint", result)

    def test_missing_run_or_exception_is_failure_with_fingerprint(self):
        for source in ("value = 1", "def run(input):\n raise ValueError('synthetic failure')"):
            result = self.run_source(source)
            self.assertFalse(result["ok"])
            self.assertEqual(len(result["sourceFingerprint"]), 64)

    def test_nonfinite_or_non_json_output_is_rejected(self):
        for source in ("def run(input): return {1,2}", "def run(input): return float('nan')"):
            self.assertFalse(self.run_source(source)["ok"])

    def test_output_limit_is_enforced(self):
        result = self.run_source("def run(input): return 'x' * 210000")
        self.assertEqual(result["code"], "OUTPUT_TOO_LARGE")

    def test_print_capture_is_bounded_without_losing_result(self):
        result = self.run_source("def run(input):\n print('x' * 1000000)\n return 'done'")
        self.assertEqual(result["output"], "done")
        self.assertEqual(len(result["stdout"].encode()), worker.MAX_CAPTURE_BYTES)


class HTTPTests(unittest.TestCase):
    def setUp(self):
        self.runner = Mock()
        self.runner.health.return_value = {"ok": True, "ready": True}
        self.runner.execute.return_value = {"ok": True, "output": 2, "stdout": "", "stderr": "", "elapsedMs": 1}
        self.server = broker.RunnerHTTPServer(("127.0.0.1", 0), self.runner)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def request(self, path, body=None, method="POST"):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=2)
        try:
            connection.request(method, path, broker.json_bytes(body) if body is not None else None, {"Content-Type": "application/json"})
            response = connection.getresponse()
            return response.status, json.loads(response.read())
        finally:
            connection.close()

    def test_health_reports_available_runtime(self):
        self.assertEqual(self.request("/health", method="GET"), (200, {"ok": True, "ready": True}))

    def test_health_unavailable_hides_engine_details(self):
        self.runner.health.side_effect = OSError("private/path")
        status, result = self.request("/health", method="GET")
        self.assertEqual(status, 503)
        self.assertNotIn("private", json.dumps(result))

    def test_analysis_routes_to_fixed_nonexecuting_mode(self):
        self.assertEqual(self.request("/analyze", {"source": "def run(input): return input"})[0], 200)
        self.runner.execute.assert_called_once_with({"source": "def run(input): return input"}, analyze=True)

    def test_concurrency_limit_returns_busy_immediately(self):
        self.server.slots.acquire()
        self.server.slots.acquire()
        try:
            status, body = self.request("/execute", {})
            self.assertEqual(status, 429)
            self.assertEqual(body["code"], "RUNNER_BUSY")
            self.runner.execute.assert_not_called()
        finally:
            self.server.slots.release()
            self.server.slots.release()

    def test_unknown_endpoint_does_not_reach_engine(self):
        self.assertEqual(self.request("/shell", {})[0], 404)
        self.runner.execute.assert_not_called()


if __name__ == "__main__":
    unittest.main()
