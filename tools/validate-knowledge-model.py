"""Two synthetic uploads through LearnHub's real upload/outbox/model API.

Run against a separate validation database, not a personal library. Reports and
uploaded examples stay under the ignored output directory.
"""
from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backend', default='http://127.0.0.1:18081')
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parents[1] /
                        'output/knowledge-model/api-validation-report.json')
    parser.add_argument('--timeout', type=int, default=7200)
    args = parser.parse_args()
    parsed = urllib.parse.urlparse(args.backend)
    if parsed.hostname not in ('127.0.0.1', 'localhost', '::1'):
        parser.error('Use a local, isolated validation backend')
    base = args.backend.rstrip('/')

    def request(method, path, data=None, content_type='application/json'):
        if data is not None and not isinstance(data, bytes):
            data = json.dumps(data, ensure_ascii=False).encode('utf-8')
        for attempt in range(31):
            req = urllib.request.Request(base + path, data=data, method=method,
                                         headers={'Content-Type': content_type})
            with urllib.request.urlopen(req, timeout=180) as response:
                result = json.load(response)
            if result.get('code') == 200:
                return result['data']
            # Publication can precede release of the worker's CUDA lock by a
            # moment. Retrying this read-only query must not upload files twice.
            message = result.get('msg', 'Backend rejected request')
            busy = ('busy' in message.lower() or
                    (result.get('code') == 503 and 'HTTP 409' in message))
            if path.endswith('/query') and busy and attempt < 30:
                time.sleep(2)
                continue
            raise RuntimeError(message)

    documents = [
        ('synthetic-round-1.txt', '合成资料，仅用于记忆实验。\n澄岚站的校验码是 松柏47。\n琥珀桥的登记号是 星沙82。',
         '资料里，澄岚站使用什么校验码？'),
        ('synthetic-round-2.txt', '合成资料，仅用于记忆实验。\n青涧作物的试验周期是 十九天。\n岚桥协议的重试上限是 七次。',
         '岚桥协议最多允许重试多少次？'),
    ]
    run_id = uuid.uuid4().hex[:8]
    report = {'run_id': run_id, 'synthetic_only': True, 'backend': base,
              'retrieval_used': False, 'rounds': []}
    args.output.parent.mkdir(parents=True, exist_ok=True)

    def save():
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')

    request('PUT', '/api/knowledge-model/settings', {'enabled': True})
    for index, (filename, text, question) in enumerate(documents, 1):
        boundary = 'LearnHubValidation' + uuid.uuid4().hex
        filename = run_id + '-' + filename
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; '
                f'filename="{filename}"\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n'
                f'{text}\r\n--{boundary}--\r\n').encode('utf-8')
        file = request('POST', '/api/files/upload', body, f'multipart/form-data; boundary={boundary}')
        row = {'round': index, 'file_id': file['id'], 'filename': filename}
        report['rounds'].append(row)
        save()
        print(json.dumps({'round': index, 'uploaded_file_id': file['id']}, ensure_ascii=False), flush=True)
        deadline = time.monotonic() + args.timeout
        previous = None
        while time.monotonic() < deadline:
            jobs = request('GET', '/api/knowledge-model/jobs')
            local = next((j for j in jobs.get('outbox', []) if j['fileId'] == file['id']), None)
            remote_id = local.get('remoteJobId') if local else None
            remote = next((j for j in jobs.get('service', {}).get('jobs', []) if j['id'] == remote_id), None)
            state = remote.get('status') if remote else local.get('state') if local else 'waiting_outbox'
            if state != previous:
                print(json.dumps({'round': index, 'status': state}, ensure_ascii=False), flush=True)
                previous = state
                row.update(outbox=local, job=remote)
                save()
            if remote and state in ('published', 'rejected', 'unvalidated', 'failed'):
                row.update(outbox=local, job=remote)
                if state == 'published':
                    row['query'] = request('POST', '/api/knowledge-model/query',
                                           {'question': question, 'max_tokens': 48})
                save()
                break
            time.sleep(5)
        else:
            row['error'] = 'Timed out waiting for the real training task'
            save()
            raise RuntimeError(row['error'])
        if row['job']['status'] != 'published':
            report['status'] = 'candidate_not_published'
            save()
            print(json.dumps({'status': report['status'], 'report': str(args.output)}, ensure_ascii=False), flush=True)
            return 1
    report['status'] = 'two_uploads_trained_evaluated_and_queried'
    report['final_status'] = request('GET', '/api/knowledge-model/status')
    save()
    print(json.dumps({'status': report['status'], 'report': str(args.output)}, ensure_ascii=False), flush=True)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
