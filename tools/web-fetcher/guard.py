"""Anonymous GET transport. Chromium never connects to an upstream host itself."""
from __future__ import annotations

import gzip
import http.client
import io
import ipaddress
import socket
import ssl
import time
import zlib
from dataclasses import dataclass, field
from urllib.parse import urlsplit, urlunsplit

MAX_BYTES = 5_000_000
MAX_REQUESTS = 80
MAX_REDIRECTS = 5
USER_AGENT = "learn-hub/1.0 (anonymous public-page reader)"


class FetchError(Exception):
    def __init__(self, code: str, message: str, status: int = 502):
        super().__init__(message)
        self.code, self.status = code, status


def normalize_url(value: str) -> str:
    if not isinstance(value, str) or not value or len(value) > 2048:
        raise FetchError("invalid_url", "URL must contain 1 to 2048 characters", 400)
    if any(ord(char) <= 32 or ord(char) == 127 for char in value) or "\\" in value:
        raise FetchError("invalid_url", "URL contains forbidden characters", 400)
    try:
        parts = urlsplit(value)
        port = parts.port
        if parts.scheme.lower() not in {"http", "https"} or not parts.hostname:
            raise ValueError()
        if parts.username is not None or parts.password is not None or "%" in parts.hostname:
            raise ValueError()
        host = parts.hostname.encode("idna").decode("ascii").lower().rstrip(".")
        if host == "localhost" or host.endswith(".localhost"):
            raise FetchError("blocked_address", "Only public Internet addresses are allowed", 403)
        authority = f"[{host}]" if ":" in host else host
        if port is not None:
            if not 1 <= port <= 65535:
                raise ValueError()
            authority += f":{port}"
        return urlunsplit((parts.scheme.lower(), authority, parts.path or "/", parts.query, ""))
    except FetchError:
        raise
    except (ValueError, UnicodeError):
        raise FetchError("invalid_url", "Only HTTP(S) URLs without credentials are allowed", 400) from None


def origin(url: str) -> tuple[str, str, int]:
    parts = urlsplit(url)
    return parts.scheme, parts.hostname, parts.port or (443 if parts.scheme == "https" else 80)


def resolve_public(host: str, port: int, resolver=socket.getaddrinfo) -> str:
    try:
        addresses = list(dict.fromkeys(info[4][0] for info in resolver(host, port, type=socket.SOCK_STREAM)))
    except OSError:
        raise FetchError("dns_failed", "Public host resolution failed") from None
    if not addresses:
        raise FetchError("dns_failed", "Public host resolution returned no addresses")
    for raw in addresses:
        try:
            address = ipaddress.ip_address(raw)
        except ValueError:
            raise FetchError("blocked_address", "Invalid network address", 403) from None
        # Reject mapped forms even when the embedded IPv4 address is public.
        if (not address.is_global or getattr(address, "ipv4_mapped", None) is not None
                or raw == "168.63.129.16" or getattr(address, "sixtofour", None) is not None
                or getattr(address, "teredo", None) is not None):
            raise FetchError("blocked_address", "Only public Internet addresses are allowed", 403)
    return addresses[0]


@dataclass
class Budget:
    deadline: float
    clock: object = time.monotonic
    requests: int = 0
    bytes: int = 0

    def remaining(self) -> float:
        remaining = self.deadline - self.clock()
        if remaining <= 0:
            raise FetchError("fetch_timeout", "Page fetch exceeded its total deadline", 504)
        return remaining

    def begin(self):
        self.remaining()
        self.requests += 1
        if self.requests > MAX_REQUESTS:
            raise FetchError("request_limit", "Page exceeded its network request budget", 413)

    def consume(self, count: int):
        self.remaining()
        self.bytes += count
        if self.bytes > MAX_BYTES:
            raise FetchError("response_too_large", "Page exceeded its total 5 MB response budget", 413)


@dataclass(frozen=True)
class Response:
    status: int
    headers: dict[str, str]
    body: bytes


class _DeadlineReader(io.RawIOBase):
    def __init__(self, sock, budget):
        self.sock, self.budget = sock, budget
        self.header_phase, self.header_bytes = True, 0

    def readable(self):
        return True

    def readinto(self, buffer):
        self.sock.settimeout(self.budget.remaining())
        count = self.sock.recv_into(buffer)
        if self.header_phase:
            self.header_bytes += count
            if self.header_bytes > 65_536:
                raise FetchError("headers_too_large", "Upstream headers exceeded 64 KiB", 413)
        return count


class _DeadlineSocket:
    def __init__(self, sock, budget):
        self.reader = _DeadlineReader(sock, budget)

    def makefile(self, mode):
        return io.BufferedReader(self.reader)


def pinned_get(url: str, address: str, budget: Budget) -> Response:
    """Connect to the validated IP once; verify TLS using the original DNS hostname."""
    parts = urlsplit(url)
    port = parts.port or (443 if parts.scheme == "https" else 80)
    family = socket.AF_INET6 if ":" in address else socket.AF_INET
    sock = socket.socket(family, socket.SOCK_STREAM)
    response = None
    try:
        sock.settimeout(min(15, budget.remaining()))
        sock.connect((address, port))
        if parts.scheme == "https":
            sock.settimeout(budget.remaining())
            sock = ssl.create_default_context().wrap_socket(sock, server_hostname=parts.hostname)
        sock.settimeout(budget.remaining())
        path = parts.path or "/"
        if parts.query:
            path += "?" + parts.query
        # Encode non-ASCII path/query without interpreting encoded delimiters.
        from urllib.parse import quote
        path = quote(path, safe="/%?:@!$&'()*+,;=-._~")
        request = (f"GET {path} HTTP/1.1\r\nHost: {parts.netloc}\r\n"
                   f"User-Agent: {USER_AGENT}\r\nAccept: */*\r\n"
                   "Accept-Encoding: identity\r\nConnection: close\r\n\r\n")
        sock.sendall(request.encode("ascii"))
        bounded = _DeadlineSocket(sock, budget)
        response = http.client.HTTPResponse(bounded)
        response.begin()
        bounded.reader.header_phase = False
        headers = {key.lower(): value for key, value in response.getheaders()}
        available = MAX_BYTES - budget.bytes
        chunks, size = [], 0
        while size <= available:
            budget.remaining()
            chunk = response.read1(min(65_536, available + 1 - size))
            if not chunk:
                break
            chunks.append(chunk)
            size += len(chunk)
        body = b"".join(chunks)
        budget.consume(len(body))
        if response.length not in (None, 0):
            raise FetchError("incomplete_response", "Upstream closed before its declared Content-Length")
        encoding = headers.get("content-encoding", "identity").lower().strip()
        if encoding not in {"", "identity"}:
            if encoding == "gzip":
                with gzip.GzipFile(fileobj=io.BytesIO(body)) as compressed:
                    decoded = compressed.read(available + 1)
            elif encoding == "deflate":
                decoder = zlib.decompressobj()
                decoded = decoder.decompress(body, available + 1)
            else:
                raise FetchError("unsupported_encoding", "Unsupported upstream content encoding")
            budget.consume(max(0, len(decoded) - len(body)))
            if encoding == "deflate" and (not decoder.eof or decoder.unused_data):
                raise FetchError("incomplete_response", "Upstream returned an incomplete deflate response")
            body = decoded
        return Response(response.status, headers, body)
    except FetchError:
        raise
    except (TimeoutError, socket.timeout):
        raise FetchError("fetch_timeout", "Upstream exceeded the shared deadline", 504) from None
    except EOFError:
        raise FetchError("incomplete_response", "Upstream returned an incomplete compressed response") from None
    except (OSError, http.client.HTTPException, ValueError, zlib.error):
        raise FetchError("upstream_failed", "Anonymous upstream request failed") from None
    finally:
        if response is not None:
            response.close()
        sock.close()


@dataclass
class NetworkGuard:
    entry_url: str
    budget: Budget
    resolver: object = socket.getaddrinfo
    transport: object = pinned_get
    main_requests: int = 0
    main_response: Response | None = None
    final_url: str = ""
    blocked: list[str] = field(default_factory=list)
    fatal: FetchError | None = None

    def request(self, url: str, method="GET", headers=None, main=False) -> Response:
        self.budget.begin()
        target = normalize_url(url)
        if method != "GET":
            raise FetchError("readonly_required", "Only anonymous GET requests are allowed", 403)
        incoming = {key.lower() for key in (headers or {})}
        if incoming.intersection({"authorization", "proxy-authorization", "cookie"}):
            raise FetchError("credentials_forbidden", "Credential-bearing requests are not allowed", 403)
        if main:
            if origin(target) != origin(self.entry_url):
                raise FetchError("cross_origin_redirect", "Main-document navigation must stay on the original origin", 403)
            self.main_requests += 1
            if self.main_requests > MAX_REDIRECTS + 1:
                raise FetchError("redirect_limit", "Main document exceeded five navigation hops", 403)
        parts = urlsplit(target)
        address = resolve_public(parts.hostname, parts.port or (443 if parts.scheme == "https" else 80), self.resolver)
        self.budget.remaining()
        # The transport never receives request headers, browser cookies, or proxy/environment credentials.
        response = self.transport(target, address, self.budget)
        if main:
            content_type = response.headers.get("content-type", "").lower()
            if not 300 <= response.status < 400 and not (
                    content_type.startswith("text/") or "json" in content_type or "xml" in content_type):
                raise FetchError("unsupported_content_type", "Main document must have a supported text Content-Type", 415)
            self.main_response, self.final_url = response, target
        return response

    def install(self, page):
        """Register every route before goto. Never continue(), route.fetch(), or use browser DNS."""
        def handle(route):
            request = route.request
            main = request.is_navigation_request() and request.frame == page.main_frame
            try:
                result = self.request(request.url, request.method, request.all_headers(), main)
                allowed = {"content-type", "location", "access-control-allow-origin", "content-security-policy",
                           "x-content-type-options", "referrer-policy"}
                headers = {key: value for key, value in result.headers.items() if key in allowed}
                headers["cache-control"] = "no-store"
                route.fulfill(status=result.status, headers=headers, body=result.body)
            except FetchError as error:
                self.blocked.append(error.code)
                if main or error.code in {"fetch_timeout", "request_limit", "response_too_large"}:
                    self.fatal = error
                route.abort("blockedbyclient")
            except Exception:
                self.fatal = FetchError("network_guard_failed", "Browser network guard failed closed")
                route.abort("blockedbyclient")

        try:
            page.context.route("**/*", handle)
            page.context.route_web_socket("**/*", lambda websocket: websocket.close())
            page.set_default_timeout(max(1, int(self.budget.remaining() * 1000)))
            page.set_default_navigation_timeout(max(1, int(self.budget.remaining() * 1000)))
            page.add_init_script("""(() => {
                const blocked = function() { throw new Error('Unsupported network channel'); };
                for (const name of ['WebSocket','WebTransport','RTCPeerConnection','webkitRTCPeerConnection']) {
                    Object.defineProperty(globalThis, name, {value: blocked, configurable: false});
                }
                navigator.sendBeacon = () => false;
                window.open = () => null;
            })();""")
        except Exception:
            self.fatal = FetchError("network_guard_failed", "Browser network guard could not be installed")
            # Scrapling logs page_setup exceptions and continues. Closing prevents an unguarded goto.
            page.close()
            raise self.fatal
