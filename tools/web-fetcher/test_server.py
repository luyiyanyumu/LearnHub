import http.client
import json
import threading
import unittest

from server import FetchServer

READY = {"ok": True, "ready": True, "runtime": "scrapling", "version": "0.4.15"}


class ServerTests(unittest.TestCase):
    def setUp(self):
        self.entered = threading.Barrier(3)
        self.release = threading.Event()
        self.server = FetchServer(("127.0.0.1", 0), self.fetch, READY)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.release.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(5)

    def fetch(self, payload):
        if payload["url"].endswith("/busy"):
            self.entered.wait(5)
            self.release.wait(5)
        return {"ok": True, "url": payload["url"], "status": 200, "markdown": "synthetic", "truncated": False}

    def request(self, method, path, body=None, headers=None):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=5)
        try:
            connection.request(method, path, body=body, headers=headers or {})
            response = connection.getresponse()
            return response.status, json.loads(response.read())
        finally:
            connection.close()

    def test_health_and_strict_schema(self):
        self.assertEqual((200, READY), self.request("GET", "/health"))
        status, result = self.request("POST", "/fetch", json.dumps({"url": "https://example.com", "cookies": []}),
                                      {"Content-Type": "application/json"})
        self.assertEqual(400, status)
        self.assertFalse(result["ok"])

    def test_oversized_request_and_incoming_credentials_are_rejected(self):
        status, _ = self.request("POST", "/fetch", b" " * 8193, {"Content-Type": "application/json"})
        self.assertEqual(413, status)
        status, _ = self.request("POST", "/fetch", '{"url":"https://example.com"}',
                                 {"Content-Type": "application/json", "Cookie": "synthetic=value"})
        self.assertEqual(403, status)

    def test_two_active_fetches_return_busy_for_the_third_without_blocking_health(self):
        results = []
        def send():
            results.append(self.request("POST", "/fetch", '{"url":"https://example.com/busy"}',
                                        {"Content-Type": "application/json"})[0])
        callers = [threading.Thread(target=send, daemon=True) for _ in range(2)]
        try:
            for caller in callers:
                caller.start()
            self.entered.wait(5)
            self.assertEqual(200, self.request("GET", "/health")[0])
            status, result = self.request("POST", "/fetch", '{"url":"https://example.com/busy"}',
                                          {"Content-Type": "application/json"})
            self.assertEqual(429, status)
            self.assertEqual("busy", result["code"])
        finally:
            self.release.set()
            for caller in callers:
                caller.join(5)
        self.assertEqual([200, 200], sorted(results))


if __name__ == "__main__":
    unittest.main()
