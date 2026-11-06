from concurrent.futures import ThreadPoolExecutor
import http.client
import json
import socket
import threading
import unittest

from engine import MAX_INPUT_BYTES, OcrError
from server import OcrServer

RUNTIME = {"ok": True, "ready": True, "engine": "tesseract", "version": "synthetic", "languages": ["chi_sim", "eng"]}


class ServerTests(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self.entered, self.release = threading.Event(), threading.Event()
        self.active = 0
        self.gate = threading.Lock()

        def recognizer(raw, runtime, timeout):
            self.calls.append(raw)
            if raw == b"slow":
                with self.gate:
                    self.active += 1
                    if self.active == 2:
                        self.entered.set()
                self.release.wait(timeout=5)
            if raw == b"timeout":
                raise OcrError("ocr_timeout", "OCR processing exceeded 60 seconds", 504)
            if raw == b"bad":
                raise OcrError("invalid_image", "Invalid PNG", 400)
            return {"engine": "tesseract", "version": runtime["version"], "width": 100, "height": 100, "blocks": [], "warnings": []}

        self.server = OcrServer(("127.0.0.1", 0), runtime_health=RUNTIME.copy(), recognizer=recognizer)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.release.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def request(self, method="POST", path="/ocr", body=b"png", headers=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=8)
        try:
            connection.request(method, path, body=body, headers={"Content-Type": "image/png", **(headers or {})})
            response = connection.getresponse()
            return response.status, json.loads(response.read())
        finally:
            connection.close()

    def test_health_contract_and_ocr_success(self):
        status, value = self.request("GET", "/health", body=None)
        self.assertEqual(status, 200)
        self.assertEqual(set(value), {"ok", "ready", "engine", "version", "languages"})
        self.assertEqual(self.request()[0], 200)
        self.assertEqual(self.calls, [b"png"])

    def test_any_query_or_fragment_and_unknown_path_is_rejected(self):
        for path in ("/ocr?url=https://example.invalid", "/ocr?path=/etc/passwd", "/ocr?", "/ocr#x"):
            self.assertEqual(self.request(path=path)[0], 400)
        self.assertEqual(self.request(path="/other")[0], 404)
        self.assertFalse(self.calls)

    def test_json_urls_credentials_and_wrong_media_type_never_reach_recognizer(self):
        self.assertEqual(self.request(body=b'{"url":"https://example.invalid"}', headers={"Content-Type": "application/json"})[0], 415)
        for header in ("Authorization", "Proxy-Authorization", "Cookie"):
            self.assertEqual(self.request(headers={header: "synthetic"})[0], 403)
        self.assertFalse(self.calls)

    def test_declared_oversize_and_transfer_encoding_are_explicit(self):
        self.assertEqual(self.request(body=b"")[0], 400)
        self.assertEqual(self.request(headers={"Content-Length": str(MAX_INPUT_BYTES + 1)})[0], 413)
        self.assertEqual(self.request(headers={"Content-Length": "9" * 100})[0], 400)
        self.assertEqual(self.request(headers={"Transfer-Encoding": "chunked"})[0], 400)
        self.assertFalse(self.calls)

    def test_incomplete_body_is_reported_without_starting_ocr(self):
        with socket.create_connection(("127.0.0.1", self.port), timeout=3) as connection:
            connection.sendall(b"POST /ocr HTTP/1.1\r\nHost: localhost\r\nContent-Type: image/png\r\nContent-Length: 100\r\n\r\npng")
            connection.shutdown(socket.SHUT_WR)
            response = http.client.HTTPResponse(connection)
            response.begin()
            self.assertEqual(response.status, 400)
            self.assertEqual(json.loads(response.read())["code"], "invalid_image")
        self.assertFalse(self.calls)

    def test_duplicate_content_lengths_are_rejected(self):
        with socket.create_connection(("127.0.0.1", self.port), timeout=3) as connection:
            connection.sendall(b"POST /ocr HTTP/1.1\r\nHost: localhost\r\nContent-Type: image/png\r\nContent-Length: 3\r\nContent-Length: 3\r\n\r\npng")
            response = http.client.HTTPResponse(connection)
            response.begin()
            self.assertEqual(response.status, 400)
            self.assertEqual(json.loads(response.read())["code"], "invalid_length")

    def test_timeout_bad_image_and_unready_engine_have_clear_statuses(self):
        self.assertEqual(self.request(body=b"timeout")[0], 504)
        self.assertEqual(self.request(body=b"bad")[0], 400)
        self.server.runtime["ready"] = False
        self.assertEqual(self.request()[0], 503)
        self.assertEqual(self.request("GET", "/health", body=None)[0], 503)

    def test_third_simultaneous_request_gets_busy_without_waiting(self):
        with ThreadPoolExecutor(max_workers=2) as pool:
            first = pool.submit(self.request, body=b"slow")
            second = pool.submit(self.request, body=b"slow")
            self.assertTrue(self.entered.wait(timeout=3))
            status, value = self.request()
            self.assertEqual((status, value["code"]), (429, "busy"))
            self.assertEqual(self.request("GET", "/health", body=None)[0], 200)
            self.release.set()
            self.assertEqual(first.result()[0], 200)
            self.assertEqual(second.result()[0], 200)


if __name__ == "__main__":
    unittest.main()
