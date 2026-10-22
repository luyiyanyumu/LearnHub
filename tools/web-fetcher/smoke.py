"""Real Chromium + Scrapling, synthetic public-origin responses, zero upstream network."""
import json
import socket

from engine import fetch_dynamic, health
from guard import Response


def run(browser_executable=None):
    runtime = health()
    if not runtime["ready"] and browser_executable is None:
        raise RuntimeError("Install the pinned requirements and Playwright Chromium before the browser smoke test")
    visited = []
    html = b'''<!doctype html><html><head><title>Synthetic rendered article</title></head><body>
    <main><p>Waiting for synthetic JavaScript.</p></main><script src="https://cdn.example.com/boot.js"></script></body></html>'''
    script = br'''setTimeout(async () => {
        const data = await (await fetch('/data.json')).json();
        const main = document.querySelector('main');
        main.innerHTML = '<h1></h1><p></p><a href="/papers/synthetic.pdf">Synthetic paper</a><pre><code class="language-python"></code></pre>';
        main.querySelector('h1').textContent = data.heading;
        main.querySelector('p').textContent = data.math;
        main.querySelector('code').textContent = 'def value():\n    return 7';
        try { await fetch('http://127.0.0.1:9/private'); main.append('PRIVATE UNEXPECTED'); }
        catch (_) { main.append('PRIVATE BLOCKED'); }
    }, 650);'''
    data = json.dumps({"heading": "Synthetic JavaScript rendered article", "math": "$$\nx_i = \\sum_j y_j\n$$"}).encode()
    fixtures = {"https://example.com/article": Response(200, {"content-type": "text/html; charset=utf-8"}, html),
                "https://cdn.example.com/boot.js": Response(200, {"content-type": "application/javascript"}, script),
                "https://example.com/data.json": Response(200, {"content-type": "application/json"}, data)}

    def resolver(host, port, **kwargs):
        address = "127.0.0.1" if host == "127.0.0.1" else "93.184.216.34"
        return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (address, port))]

    def transport(url, address, budget):
        assert address == "93.184.216.34", "Private addresses must never reach the fixture transport"
        assert url in fixtures, f"Unexpected synthetic resource: {url}"
        visited.append(url)
        response = fixtures[url]
        budget.consume(len(response.body))
        return response

    result = fetch_dynamic({"url": "https://example.com/article", "timeoutMs": 30000, "maxChars": 100000},
                           resolver=resolver, transport=transport, browser_executable=browser_executable)
    markdown = result["markdown"]
    assert result["ok"] and result["status"] == 200 and not result["truncated"]
    assert "Synthetic JavaScript rendered article" in markdown, markdown
    assert "https://example.com/data.json" in visited and "https://cdn.example.com/boot.js" in visited
    assert "PRIVATE BLOCKED" in markdown and "PRIVATE UNEXPECTED" not in markdown, markdown
    assert "https://example.com/papers/synthetic.pdf" in markdown, markdown
    assert "x_i = \\sum_j y_j" in markdown, markdown
    assert "def value():\n    return 7" in markdown, markdown
    assert all("127.0.0.1" not in url for url in visited)
    print(json.dumps({"ok": True, "runtime": runtime, "syntheticRequests": len(visited),
                      "browser": "operator-provided Chromium binary" if browser_executable else "pinned Playwright Chromium",
                      "javascript": True, "xhr": True, "privateRequestBlocked": True,
                      "absoluteLinks": True, "literalMathAndCode": True}))


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--browser-executable", help="Smoke test only: operator-chosen Chromium binary, always with a new temporary profile")
    run(parser.parse_args().browser_executable)
