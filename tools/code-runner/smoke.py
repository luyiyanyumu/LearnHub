"""Synthetic integration checks against a running internal broker; no model/API key needed."""

import argparse
import concurrent.futures
import json
import time
import urllib.error
import urllib.request


def call(base_url, path, payload=None):
    data = json.dumps(payload, ensure_ascii=False).encode() if payload is not None else None
    request = urllib.request.Request(base_url.rstrip("/") + path, data=data, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=25) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


def smoke(base_url):
    checks = []

    def check(name, condition):
        if not condition:
            raise AssertionError(name)
        checks.append(name)
        print("PASS " + name, flush=True)

    check("runtime ready", call(base_url, "/health")[1].get("ready") is True)
    source = "def run(input):\n    print('synthetic sum')\n    return {'sum': sum(input['values'])}"
    status, result = call(base_url, "/execute", {"source": source, "input": {"values": [2, 3, 5]}})
    check("JSON output and captured stdout", status == 200 and result.get("output") == {"sum": 10} and result.get("stdout") == "synthetic sum\n")
    check("AST fingerprint on execution", len(result.get("sourceFingerprint", "")) == 64)
    _, first = call(base_url, "/analyze", {"source": "def run(input):\n return input+1"})
    _, second = call(base_url, "/analyze", {"source": "# harmless comment\ndef run(input):\n    return input + 1\n"})
    check("canonical AST deduplication", first.get("ok") and first.get("output", {}).get("fingerprint") == second.get("output", {}).get("fingerprint"))
    _, analysis = call(base_url, "/analyze", {"source": "raise RuntimeError('must not execute')\ndef run(input): return input"})
    check("analysis never executes source", analysis.get("ok") is True)
    _, syntax = call(base_url, "/analyze", {"source": "def run(input) return input"})
    check("syntax diagnostic", syntax.get("code") == "SYNTAX_ERROR")
    isolation_source = '''def run(input):
    import os, socket
    checks = {'uid': os.getuid(), 'socketAbsent': not os.path.exists('/var/run/docker.sock')}
    with open('/tmp/synthetic-probe', 'w') as output:
        output.write('synthetic')
    checks['tmpWritable'] = os.path.isfile('/tmp/synthetic-probe')
    try:
        with open('/opt/synthetic-probe', 'w') as output:
            output.write('synthetic')
        checks['rootReadonly'] = False
    except OSError:
        checks['rootReadonly'] = True
    sock = socket.socket()
    sock.settimeout(0.2)
    try:
        sock.connect(('1.1.1.1', 443))
        checks['networkDisabled'] = False
    except OSError:
        checks['networkDisabled'] = True
    finally:
        sock.close()
    with open('/proc/self/status') as status:
        state = dict(line.split(':', 1) for line in status if ':' in line)
    checks['noNewPrivileges'] = state['NoNewPrivs'].strip() == '1'
    checks['noCapabilities'] = int(state['CapEff'].strip(), 16) == 0
    return checks
'''
    _, isolation = call(base_url, "/execute", {"source": isolation_source, "input": None})
    expected = {"uid": 10001, "socketAbsent": True, "tmpWritable": True, "rootReadonly": True, "networkDisabled": True, "noNewPrivileges": True, "noCapabilities": True}
    check("nonroot readonly no-network no-socket no-capabilities", isolation.get("output") == expected)
    _, clean = call(base_url, "/execute", {"source": "def run(input):\n import os\n return not os.path.exists('/tmp/synthetic-probe')", "input": None})
    check("fresh filesystem between runs", clean.get("output") is True)
    started = time.monotonic()
    _, timeout = call(base_url, "/execute", {"source": "def run(input):\n import signal\n signal.signal(signal.SIGALRM, signal.SIG_IGN)\n while True: pass", "input": None, "timeoutMs": 1000})
    check("hard timeout independent of generated code", timeout.get("code") in {"TIMEOUT", "EXECUTION_FAILED"} and time.monotonic() - started < 10)
    _, large = call(base_url, "/execute", {"source": "def run(input): return 'x' * 210000", "input": None})
    check("JSON output bounded", large.get("code") == "OUTPUT_TOO_LARGE")
    _, prints = call(base_url, "/execute", {"source": "def run(input):\n print('x' * 1000000)\n return True", "input": None})
    check("print capture bounded", prints.get("output") is True and len(prints.get("stdout", "")) <= 8192)
    _, flood = call(base_url, "/execute", {"source": "def run(input):\n import os\n os.write(1, b'x' * 600000)\n return True", "input": None})
    check("direct stdout flood bounded", flood.get("ok") is False and flood.get("code") in {"OUTPUT_TOO_LARGE", "INVALID_OUTPUT"})
    fork_source = '''def run(input):
    import subprocess
    children = []
    limited = False
    try:
        for index in range(50):
            try:
                children.append(subprocess.Popen(['python', '-c', 'import time; time.sleep(5)']))
            except OSError:
                limited = True
                break
        return {'limited': limited, 'count': len(children)}
    finally:
        for child in children:
            child.terminate()
        for child in children:
            child.wait()
'''
    _, fork = call(base_url, "/execute", {"source": fork_source, "input": None, "timeoutMs": 10000})
    check("PID limit enforced", fork.get("ok") and fork.get("output", {}).get("limited") and fork["output"]["count"] < 32)
    slow = {"source": "def run(input):\n import time\n time.sleep(3)\n return input", "input": 1}
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
        pending = [executor.submit(call, base_url, "/execute", slow) for _ in range(3)]
        results = [future.result() for future in pending]
    check("two concurrent slots and fast busy response", sum(status == 429 and result.get("code") == "RUNNER_BUSY" for status, result in results) == 1 and sum(result.get("ok") is True for _status, result in results) == 2)
    print(json.dumps({"ok": True, "checks": len(checks)}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:5188")
    smoke(parser.parse_args().url)
