# Anonymous rendered-page sidecar

This optional internal service uses Scrapling **0.4.15** and Playwright **1.62.0** to execute JavaScript and turn the resulting DOM into Markdown. Existing search remains in LearnHub. No API key, login cookie, host browser profile, remote CDP endpoint, custom script, proxy, selector, or filesystem path is accepted from callers.

The default address is `0.0.0.0:8082`. Keep it on the private application network; do not publish the port to the Internet.

- `GET /health`: `{ "ok": true, "ready": true, "runtime": "scrapling", "version": "0.4.15" }`. Missing dependencies/browser produce HTTP 503 and `ready: false`.
- `POST /fetch`: JSON with only `url`, optional `timeoutMs` (1–60000, default 30000) and optional `maxChars` (1–100000, default 100000).
- Success returns `ok`, final `url`, actual HTTP `status`, `contentType`, `title`, `markdown`, `truncated` and `fetchMode: "dynamic"`. An upstream HTTP 403/404 remains visible as `status`; it is not reclassified as HTTP 200 content.
- Invalid input, blocked requests, resource limits, overload, unavailable runtime and timeout return HTTP 400/403/413/429/503/504 with `{ "ok": false, "code": "...", "error": "..." }`.

Requests are limited to 8 KiB; responses to 1 MB; only two render tasks may run at once. Each task has one total deadline covering browser startup, navigation, DNS, subresources, DOM extraction and conversion. The isolated worker process group is terminated at that deadline. A page gets at most 80 anonymous GET requests and a shared 5 MB response/decompression budget. Main navigation stays on the original origin and permits at most five hops.

Every Chromium HTTP request is intercepted before navigation and fulfilled from a Python transport that validates every DNS answer, rejects non-public/mapped addresses and pins its socket to the validated IP. HTTPS certificates are checked against the original hostname. Public cross-origin scripts/XHR can work; credential headers, POST, private resources, WebSockets, service workers and other browser network channels are blocked. A fixed dead proxy makes unrouted browser connections fail closed. Page-created cookies are never forwarded and response Set-Cookie headers are suppressed. The browser uses a fresh disposable profile for every task. Rendered text remains untrusted source material; the Java layer retains its content notice and model-context limits.

DOM extraction uses main/article/body, removes noise, resolves relative URLs, and protects literal code and TeX from Markdown escaping. Character truncation does not split protected formula/code fragments. It may return fewer than `maxChars` characters when the next protected fragment cannot fit. JavaScript has a bounded DOM-quiet wait; sites requiring user interaction or authentication are unsupported.

Build and verify from the repository root:

```sh
python -m unittest discover -s tools/web-fetcher -p 'test_*.py'
docker build -f tools/web-fetcher/Dockerfile -t learnhub-web-fetcher .
docker run --rm --cap-drop ALL --read-only --tmpfs /tmp:rw,nosuid,nodev,size=512m --shm-size=512m learnhub-web-fetcher python /app/smoke.py
```

Optional build arguments support trusted download mirrors or caches without changing runtime fetching:

- `PIP_INDEX_URL`: Python package index; defaults to PyPI.
- `DEBIAN_MIRROR`: base URL serving both `/debian` and `/debian-security`; defaults to Debian's own servers.
- `PLAYWRIGHT_DOWNLOAD_HOST`: browser archive mirror with Playwright's original URL paths; defaults to Playwright's own servers.

Pass each as `--build-arg NAME=value`. Python packages, system dependencies, and browser downloads use separate cached build steps.

Ordinary unit tests need only the Python standard library; DOM conversion tests run when optional dependencies are installed. The browser smoke test requires the real pinned dependencies and Chromium, executes JavaScript plus delayed cross-origin script/XHR fixtures, and verifies a private-address request is blocked. All upstream responses and DNS answers are injected **inside the test**, using public-looking synthetic origins. No production HTTP option or environment variable disables SSRF protection.

For local work, create a temporary virtual environment, install `requirements.txt`, then run `python -m playwright install chromium`. Run `python tools/web-fetcher/smoke.py` with that environment. Do not mount personal profiles, credentials, databases, or the host Docker socket.

When the pinned browser download is unavailable, the smoke script alone accepts `--browser-executable /operator/chosen/chromium`. It still creates a fresh temporary profile and guards all upstream requests. This tests the installed Chromium variant rather than the production image's pinned Playwright browser; the HTTP API does not accept executable/profile paths.
