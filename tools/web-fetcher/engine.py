"""Scrapling 0.4.15 adapter; optional browser dependencies are loaded lazily."""
from __future__ import annotations

import importlib.metadata
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time

from guard import Budget, FetchError, NetworkGuard, normalize_url, origin

VERSION = "0.4.15"
MAX_JSON_BYTES = 1_000_000


def validate_request(payload) -> dict:
    if not isinstance(payload, dict) or set(payload) - {"url", "timeoutMs", "maxChars"}:
        raise FetchError("invalid_request", "Only url, timeoutMs, and maxChars are supported", 400)
    url = normalize_url(payload.get("url"))
    timeout = payload.get("timeoutMs", 30_000)
    maximum = payload.get("maxChars", 100_000)
    if type(timeout) is not int or not 1 <= timeout <= 60_000:
        raise FetchError("invalid_timeout", "timeoutMs must be an integer from 1 to 60000", 400)
    if type(maximum) is not int or not 1 <= maximum <= 100_000:
        raise FetchError("invalid_max_chars", "maxChars must be an integer from 1 to 100000", 400)
    return {"url": url, "timeoutMs": timeout, "maxChars": maximum}


def health() -> dict:
    version, ready = None, False
    try:
        version = importlib.metadata.version("scrapling")
        from scrapling.fetchers import DynamicFetcher  # noqa: F401
        from playwright.sync_api import sync_playwright
        import markdownify  # noqa: F401
        with sync_playwright() as playwright:
            # A driver round trip completes initialization before shutdown; no HTTP request is sent.
            empty_context = playwright.request.new_context()
            empty_context.dispose()
            ready = version == VERSION and Path(playwright.chromium.executable_path).is_file()
    except Exception:
        pass
    return {"ok": ready, "ready": ready, "runtime": "scrapling", "version": version}


def fetch_dynamic(payload: dict, *, resolver=None, transport=None, fetcher=None, browser_executable=None) -> dict:
    """Test injection is Python-only; neither the HTTP schema nor environment enables private hosts."""
    request = validate_request(payload)
    budget = Budget(time.monotonic() + request["timeoutMs"] / 1000)
    options = {}
    if resolver is not None:
        options["resolver"] = resolver
    if transport is not None:
        options["transport"] = transport
    guard = NetworkGuard(request["url"], budget, **options)
    if fetcher is None:
        from scrapling.fetchers import DynamicFetcher
        fetcher = DynamicFetcher

    def settle(page):
        budget.remaining()
        # Bounded DOM quiet period catches deferred scripts/XHR without user-supplied JavaScript/selectors.
        remaining_ms = max(1, int(budget.remaining() * 1000))
        page.evaluate("""({minimum,maximum}) => new Promise(resolve => {
            let quiet, finished = false, changed = performance.now();
            const started = performance.now();
            const finish = () => { if (finished) return; finished = true; observer.disconnect(); clearTimeout(quiet); resolve(); };
            const attempt = () => {
                const now = performance.now(), delay = Math.max(minimum - (now-started), 200 - (now-changed));
                if (delay > 0) { quiet = setTimeout(attempt, delay); } else { finish(); }
            };
            const reset = () => { changed = performance.now(); clearTimeout(quiet); attempt(); };
            const observer = new MutationObserver(reset);
            observer.observe(document.documentElement, {subtree:true,childList:true,characterData:true,attributes:true});
            attempt(); setTimeout(finish, maximum);
        })""", {"minimum": min(1000, max(0, remaining_ms - 250)), "maximum": min(2000, remaining_ms)})
        budget.remaining()

    # Each invocation gets an ephemeral profile; never connect to a real browser or reuse cookies.
    with tempfile.TemporaryDirectory(prefix="learnhub-web-") as profile:
        try:
            browser_options = {"executable_path": browser_executable} if browser_executable is not None else {}
            response = fetcher.fetch(
                request["url"], headless=True, real_chrome=False, retries=1,
                user_data_dir=profile, google_search=False, cookies=[],
                useragent="learn-hub/1.0 (anonymous public-page reader)",
                page_setup=guard.install, page_action=settle,
                selector_config={"adaptive": False, "huge_tree": False},
                network_idle=False, load_dom=True, timeout=max(1, int(budget.remaining() * 1000)),
                # Unrouted browser traffic fails closed. Upstream HTTP goes through Python's pinned sockets.
                proxy={"server": "http://127.0.0.1:1", "bypass": "<-loopback>"},
                additional_args={"service_workers": "block", "accept_downloads": False,
                                 "ignore_https_errors": False, "permissions": [],
                                 "timeout": max(1, int(budget.remaining() * 1000))},
                extra_flags=["--disable-background-networking", "--disable-component-update",
                             "--disable-domain-reliability", "--disable-sync", "--disable-quic",
                             "--no-pings", "--disable-extensions",
                             "--host-resolver-rules=MAP * ~NOTFOUND",
                             "--webrtc-ip-handling-policy=disable_non_proxied_udp",
                             "--force-webrtc-ip-handling-policy=disable_non_proxied_udp"],
                **browser_options,
            )
        except Exception as error:
            if guard.fatal is not None:
                raise guard.fatal
            budget.remaining()
            raise FetchError("render_failed", "Scrapling could not render the public page") from error
    if guard.fatal is not None:
        raise guard.fatal
    budget.remaining()
    if guard.main_response is None:
        raise FetchError("render_failed", "The guarded main document did not return a response")
    final_url = normalize_url(response.url)
    if origin(final_url) != origin(request["url"]):
        raise FetchError("cross_origin_redirect", "Rendered document changed origin", 403)
    html = str(response.html_content)
    if len(html.encode("utf-8")) > MAX_JSON_BYTES:
        raise FetchError("rendered_page_too_large", "Rendered DOM exceeded its 1 MB budget", 413)
    from extract import extract_markdown
    title, markdown, truncated = extract_markdown(html, final_url, request["maxChars"])
    budget.remaining()
    result = {"ok": True, "url": final_url, "status": guard.main_response.status,
              "contentType": guard.main_response.headers.get("content-type", ""),
              "title": title, "markdown": markdown, "truncated": truncated, "fetchMode": "dynamic"}
    if len(json.dumps(result, ensure_ascii=False).encode("utf-8")) > MAX_JSON_BYTES:
        raise FetchError("response_too_large", "Rendered result exceeded its JSON response budget", 413)
    return result


def _stop_process_tree(process):
    if os.name == "nt":
        if process.poll() is not None:
            return
        # PID belongs to the child created immediately below; no caller-supplied process/path is accepted.
        subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5, check=False)
    else:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    if process.poll() is None:
        process.kill()
    if process.poll() is None:
        process.wait(timeout=5)


def fetch_in_subprocess(payload: dict) -> dict:
    request = validate_request(payload)
    kwargs = {"start_new_session": True} if os.name != "nt" else {
        "creationflags": subprocess.CREATE_NEW_PROCESS_GROUP}
    process = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), "--worker"],
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                               **kwargs)
    try:
        output, _ = process.communicate(json.dumps(request, ensure_ascii=False).encode("utf-8"),
                                        timeout=request["timeoutMs"] / 1000)
    except subprocess.TimeoutExpired:
        raise FetchError("fetch_timeout", "Rendering exceeded its total deadline", 504) from None
    finally:
        # A crashed/reaped Python worker can leave a live Chromium descendant in this POSIX session.
        _stop_process_tree(process)
    if process.returncode != 0 or len(output) > MAX_JSON_BYTES:
        raise FetchError("worker_failed", "The isolated rendering worker did not return a bounded result")
    try:
        result = json.loads(output)
    except (ValueError, UnicodeError):
        raise FetchError("worker_failed", "The isolated rendering worker returned an invalid result") from None
    if not result.get("ok"):
        raise FetchError(result.get("code", "render_failed"), result.get("error", "Rendering failed"),
                         result.get("_httpStatus", 502))
    return result


if __name__ == "__main__" and sys.argv[1:] == ["--worker"]:
    try:
        raw = sys.stdin.buffer.read(8193)
        if len(raw) > 8192:
            raise FetchError("request_too_large", "Request exceeds 8 KiB", 413)
        result = fetch_dynamic(json.loads(raw))
    except FetchError as error:
        result = {"ok": False, "error": str(error), "code": error.code, "_httpStatus": error.status}
    except Exception:
        result = {"ok": False, "error": "Rendering worker unavailable", "code": "worker_failed", "_httpStatus": 503}
    sys.stdout.write(json.dumps(result, ensure_ascii=False))
