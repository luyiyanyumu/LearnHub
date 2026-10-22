"""CPU-only CLI regression checks against an isolated loopback fake service."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "validate-knowledge-memory.py"


class ValidationCliTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.requests = []
        self.answers = {"数字边界甲？": "12", "数字边界乙？": "1.2", "未知边界丙？": "不知道"}
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def respond(self, status, body):
                encoded = json.dumps(body, ensure_ascii=False).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(encoded)))
                self.end_headers()
                self.wfile.write(encoded)

            def do_GET(self):
                owner.requests.append(("GET", self.path, None))
                if self.path == "/v1/status":
                    self.respond(200, {"active_version": "synthetic-test-only"})
                else:
                    self.respond(500, {"error": "Unexpected path"})

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
                owner.requests.append(("POST", self.path, body))
                if self.path == "/v1/query" and body.get("question") in owner.answers:
                    self.respond(200, {"answer": owner.answers[body["question"]],
                                       "model_version": "synthetic-test-only", "retrieval_used": False})
                else:
                    # A documents submission must fail the test, never enqueue.
                    self.respond(500, {"error": "Unexpected path or question"})

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.close_server)
        self.endpoint = f"http://127.0.0.1:{self.server.server_address[1]}"

    def close_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)

    def run_cli(self, *arguments):
        return subprocess.run([sys.executable, str(SCRIPT), "--service", self.endpoint, *map(str, arguments)],
                              cwd=self.root, capture_output=True, text=True, encoding="utf-8", timeout=20)

    def write_json(self, name, value):
        path = self.root / name
        path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
        return path

    def test_audit_only_queries_without_documents_and_uses_numeric_safe_scoring(self):
        audit = self.write_json("audit.json", [
            {"question": "数字边界甲？", "answer": "1.2", "kind": "known"},
            {"question": "数字边界乙？", "answer": "1.2", "kind": "known"},
            {"question": "未知边界丙？", "answer": "不知道", "kind": "unknown"},
        ])
        original = audit.read_bytes()
        report_path = self.root / "reports" / "result.json"
        result = self.run_cli("--audit-only", "--audit", audit, "--output", report_path)
        self.assertEqual(0, result.returncode, result.stderr)
        report = json.loads(report_path.read_text(encoding="utf-8"))
        self.assertEqual("completed", report["status"])
        self.assertTrue(report["audit_only"])
        self.assertIsNone(report["input_path"])
        self.assertEqual([], report["rounds"])
        external = report["external_audit"]
        self.assertEqual("numeric_safe_normalized_exact_answer", external["metric"])
        self.assertEqual([False, True, True], [case["correct"] for case in external["cases"]])
        self.assertEqual(2, external["correct"])
        self.assertFalse(external["used_for_training"])
        self.assertFalse(external["used_for_publish_gate"])
        self.assertEqual(original, audit.read_bytes())
        self.assertFalse(report_path.with_suffix(".json.tmp").exists())
        self.assertEqual([("POST", "/v1/query")] * 3 + [("GET", "/v1/status")],
                         [(method, path) for method, path, _ in self.requests])
        for method, _, body in self.requests:
            if method == "POST":
                self.assertEqual({"question", "max_tokens"}, set(body))
                self.assertIsInstance(body["max_tokens"], int)
                self.assertGreater(body["max_tokens"], 0)
                self.assertNotIn("1.2", json.dumps(body, ensure_ascii=False))
                self.assertNotIn("不知道", json.dumps(body, ensure_ascii=False))

    def test_audit_only_requires_an_audit_before_any_request_or_output(self):
        report_path = self.root / "result.json"
        result = self.run_cli("--audit-only", "--output", report_path)
        self.assertEqual(2, result.returncode)
        self.assertIn("--audit-only requires --audit", result.stderr)
        self.assertEqual([], self.requests)
        self.assertFalse(report_path.exists())
        self.assertFalse(report_path.with_suffix(".json.tmp").exists())

    def test_report_and_temporary_file_cannot_overwrite_either_input(self):
        for target in ("audit", "documents"):
            for temporary in (False, True):
                with self.subTest(target=target, temporary=temporary):
                    suffix = ".json.tmp" if temporary else ".json"
                    audit = self.write_json("audit" + (suffix if target == "audit" else ".json"), [])
                    documents = self.write_json("documents" + (suffix if target == "documents" else ".json"),
                                                [{"source_id": "synthetic-do-not-upload", "text": "合成文本"}])
                    protected = audit if target == "audit" else documents
                    output = protected.with_suffix("") if temporary else protected
                    original_audit, original_documents = audit.read_bytes(), documents.read_bytes()
                    result = self.run_cli("--audit-only", "--audit", audit, "--documents", documents,
                                          "--output", output)
                    self.assertEqual(2, result.returncode, result.stderr)
                    self.assertIn("Report must not overwrite an input file", result.stderr)
                    self.assertEqual(original_audit, audit.read_bytes())
                    self.assertEqual(original_documents, documents.read_bytes())
                    self.assertEqual([], self.requests)


if __name__ == "__main__":
    unittest.main()
