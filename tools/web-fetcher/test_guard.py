"""Standard-library-only tests: DNS/socket fixtures never connect to the Internet."""
import gzip
import io
import socket
import unittest
import zlib
from unittest.mock import Mock, patch

from engine import _stop_process_tree, validate_request
from guard import (Budget, FetchError, MAX_BYTES, MAX_REQUESTS, NetworkGuard, Response,
                   normalize_url, pinned_get, resolve_public)


def dns(*addresses):
    return lambda host, port, **kwargs: [(socket.AF_INET6 if ":" in address else socket.AF_INET,
                                         socket.SOCK_STREAM, 6, "", (address, port)) for address in addresses]


class FakeSocket:
    def __init__(self, raw):
        self.raw, self.connected, self.sent, self.timeouts = io.BytesIO(raw), None, b"", []
        self.closed = False

    def connect(self, address):
        self.connected = address

    def settimeout(self, value):
        self.timeouts.append(value)

    def recv_into(self, target):
        return self.raw.readinto(target)

    def sendall(self, value):
        self.sent += value

    def close(self):
        self.closed = True


class GuardTests(unittest.TestCase):
    def budget(self):
        return Budget(100, clock=lambda: 1)

    def test_schema_is_narrow_and_numbers_do_not_accept_booleans(self):
        self.assertEqual(100000, validate_request({"url": "https://example.com"})["maxChars"])
        for field in ["headers", "cookies", "proxy", "scripts", "selector", "paths", "profile"]:
            with self.subTest(field=field), self.assertRaises(FetchError):
                validate_request({"url": "https://example.com", field: {}})
        for key, value in [("timeoutMs", True), ("timeoutMs", 60001), ("timeoutMs", 0),
                           ("maxChars", 100001), ("maxChars", 0)]:
            with self.subTest(key=key, value=value), self.assertRaises(FetchError):
                validate_request({"url": "https://example.com", key: value})

    def test_urls_reject_credentials_and_non_http_channels(self):
        for value in ["file:///secret", "ftp://example.com/a", "ws://example.com/a", "http://localhost/a",
                      "https://user:pass@example.com", "https://example.com\\@localhost/a",
                      "http://[fe80::1%25eth0]/", "https://example.com/\r\nx:a"]:
            with self.subTest(value=value), self.assertRaises(FetchError):
                normalize_url(value)
        self.assertEqual("https://example.com/path?q=x", normalize_url("https://EXAMPLE.com/path?q=x#part"))

    def test_all_dns_answers_must_be_public_and_mapped_ipv6_is_rejected(self):
        for value in ["127.0.0.1", "10.0.0.1", "169.254.169.254", "100.100.100.200", "168.63.129.16",
                      "::1", "fd00::1", "::ffff:8.8.8.8", "::ffff:127.0.0.1"]:
            with self.subTest(value=value), self.assertRaises(FetchError):
                resolve_public("example.com", 443, dns("93.184.216.34", value))
        self.assertEqual("93.184.216.34", resolve_public("example.com", 443, dns("93.184.216.34")))

    def test_each_resource_and_redirect_gets_fresh_validation_and_only_get_is_allowed(self):
        seen = []
        resolver = Mock(side_effect=[dns("93.184.216.34")("", 443), dns("127.0.0.1")("", 443)])
        def transport(url, address, budget):
            seen.append((url, address))
            return Response(200, {"content-type": "text/html"}, b"synthetic")
        guard = NetworkGuard("https://example.com/", self.budget(), resolver, transport)
        guard.request("https://example.com/", main=True)
        with self.assertRaises(FetchError):
            guard.request("https://example.com/script.js")
        self.assertEqual(1, len(seen))
        for method, headers in [("POST", {}), ("GET", {"Authorization": "synthetic"}),
                                ("GET", {"Cookie": "synthetic=x"})]:
            with self.assertRaises(FetchError):
                guard.request("https://example.com/", method, headers)
        with self.assertRaises(FetchError):
            guard.request("https://other.example.com/", main=True)

    def test_global_deadline_request_and_navigation_budgets_do_not_reset(self):
        budget = self.budget()
        budget.requests = MAX_REQUESTS
        with self.assertRaises(FetchError):
            budget.begin()
        with self.assertRaises(FetchError):
            Budget(1, clock=lambda: 2).remaining()
        guard = NetworkGuard("https://example.com/", self.budget(), dns("93.184.216.34"),
                             lambda *args: Response(200, {"content-type": "text/html"}, b"synthetic"))
        for index in range(6):
            guard.request(f"https://example.com/{index}", main=True)
        with self.assertRaises(FetchError) as failure:
            guard.request("https://example.com/seventh", main=True)
        self.assertEqual("redirect_limit", failure.exception.code)

    def test_pinned_transport_connects_validated_ip_and_verifies_original_tls_hostname(self):
        body = b"synthetic response"
        sock = FakeSocket(b"HTTP/1.1 404 Missing\r\nContent-Type: text/plain\r\nContent-Length: "
                          + str(len(body)).encode() + b"\r\n\r\n" + body)
        context = Mock()
        context.wrap_socket.return_value = sock
        with patch("guard.socket.socket", return_value=sock), patch("guard.ssl.create_default_context", return_value=context):
            response = pinned_get("https://example.com/path#not-sent", "93.184.216.34", self.budget())
        self.assertEqual(("93.184.216.34", 443), sock.connected)
        context.wrap_socket.assert_called_once_with(sock, server_hostname="example.com")
        self.assertEqual(404, response.status)
        self.assertEqual(body, response.body)
        self.assertIn(b"Accept-Encoding: identity", sock.sent)
        self.assertNotIn(b"Cookie:", sock.sent)
        self.assertNotIn(b"Authorization:", sock.sent)
        self.assertNotIn(b"#not-sent", sock.sent)
        self.assertTrue(sock.closed)
        self.assertGreater(len(sock.timeouts), 2)

    def test_compressed_expansion_obeys_total_decoded_budget(self):
        compressed = gzip.compress(b"x" * (MAX_BYTES + 1))
        sock = FakeSocket(b"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Encoding: gzip\r\nContent-Length: "
                          + str(len(compressed)).encode() + b"\r\n\r\n" + compressed)
        with patch("guard.socket.socket", return_value=sock), self.assertRaises(FetchError) as failure:
            pinned_get("http://example.com/", "93.184.216.34", self.budget())
        self.assertEqual("response_too_large", failure.exception.code)

    def test_truncated_content_length_and_compressed_streams_never_look_complete(self):
        plain = b"synthetic complete body"
        cases = [(plain, "identity", len(plain) + 100),
                 (zlib.compress(plain)[:-3], "deflate", None),
                 (gzip.compress(plain)[:-4], "gzip", None)]
        for body, encoding, declared in cases:
            with self.subTest(encoding=encoding):
                header = (f"HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Encoding: {encoding}\r\n"
                          f"Content-Length: {declared or len(body)}\r\n\r\n").encode()
                sock = FakeSocket(header + body)
                with patch("guard.socket.socket", return_value=sock), self.assertRaises(FetchError) as failure:
                    pinned_get("http://example.com/", "93.184.216.34", self.budget())
                self.assertEqual("incomplete_response", failure.exception.code)

    def test_route_installation_failure_closes_page_instead_of_leaving_unguarded_navigation(self):
        page = Mock()
        page.context.route.side_effect = RuntimeError("synthetic setup failure")
        guard = NetworkGuard("https://example.com/", self.budget())
        with self.assertRaises(FetchError):
            guard.install(page)
        page.close.assert_called_once()
        self.assertEqual("network_guard_failed", guard.fatal.code)

    def test_posix_cleanup_kills_own_session_even_after_python_worker_has_exited(self):
        process = Mock(pid=54321)
        process.poll.return_value = 1
        with patch("engine.os.name", "posix"), patch("engine.os.killpg", create=True) as kill_group, \
                patch("engine.signal.SIGKILL", 9, create=True):
            _stop_process_tree(process)
        kill_group.assert_called_once()
        self.assertEqual(54321, kill_group.call_args.args[0])


if __name__ == "__main__":
    unittest.main()
