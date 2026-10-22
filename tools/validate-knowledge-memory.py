"""Run local, parameter-only learning/retention/abstention experiments.

Input documents and private source audits must remain in ignored local output.
The final audit file is never submitted to training or used by the publish gate.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--documents', type=Path)
    parser.add_argument('--audit', type=Path)
    parser.add_argument('--audit-only', action='store_true',
                        help='Query the current model without submitting any training documents')
    parser.add_argument('--service', default='http://127.0.0.1:8766')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--timeout', type=int, default=7200)
    args = parser.parse_args()
    if args.audit_only and not args.audit:
        parser.error('--audit-only requires --audit')
    if not args.audit_only and not args.documents:
        parser.error('--documents is required for training experiments')
    endpoint = urllib.parse.urlparse(args.service)
    if endpoint.scheme != 'http' or endpoint.hostname not in ('127.0.0.1', 'localhost', '::1'):
        parser.error('Use a local loopback model service')
    temporary = args.output.with_suffix('.json.tmp')
    input_paths = {args.documents.resolve() if args.documents else None,
                   args.audit.resolve() if args.audit else None}
    if args.output.resolve() in input_paths or temporary.resolve() in input_paths:
        parser.error('Report must not overwrite an input file')
    sys.path.insert(0, str(Path(__file__).resolve().parent / 'knowledge-model'))
    from data import score_answer

    def request(path, body=None):
        for attempt in range(61):
            data = None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8')
            req = urllib.request.Request(args.service.rstrip('/') + path, data=data,
                                         headers={'Content-Type': 'application/json'})
            try:
                with urllib.request.urlopen(req, timeout=240) as response:
                    return json.load(response)
            except urllib.error.HTTPError as error:
                if path == '/v1/query' and error.code == 409 and attempt < 60:
                    time.sleep(2)
                    continue
                raise

    documents = [] if args.audit_only else json.loads(args.documents.read_text(encoding='utf-8-sig'))
    if not args.audit_only and (not isinstance(documents, list) or not documents):
        parser.error('Documents must be a nonempty JSON list')
    report = {'service': args.service, 'retrieval_used': False,
              'input_path': str(args.documents.resolve()) if args.documents else None,
              'audit_only': args.audit_only,
              'started_at': time.time(), 'rounds': [], 'status': 'running'}
    args.output.parent.mkdir(parents=True, exist_ok=True)

    def save():
        temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        temporary.replace(args.output)

    for index, document in enumerate(documents, 1):
        accepted = request('/v1/documents', document)
        job_id = accepted['job_id']
        row = {'round': index, 'job_id': job_id, 'source_id': document['source_id'],
               'duplicate': accepted['duplicate']}
        report['rounds'].append(row)
        save()
        deadline = time.monotonic() + args.timeout
        previous = None
        while time.monotonic() < deadline:
            job = request('/v1/jobs/' + job_id)
            state = job['status']
            if state != previous:
                print(json.dumps({'round': index, 'status': state}), flush=True)
                previous = state
            if state in ('published', 'rejected', 'unvalidated', 'failed'):
                row['job'] = job
                save()
                break
            time.sleep(5)
        else:
            report['status'] = 'timed_out'
            save()
            return 1
        if state != 'published':
            report['status'] = 'candidate_not_published'
            save()
            return 1
        # These probe wordings have been held out from the upload's QA and raw CLM.
        question = document['facts'][0]['eval_questions'][0] if document.get('facts') else None
        if question:
            row['local_query'] = request('/v1/query', {'question': question, 'max_tokens': 64})
            save()

    if args.audit:
        # Read only after all training rounds finish. No audit item is passed to
        # /documents, training samples, or the model's prompt as expected context.
        audits = json.loads(args.audit.read_text(encoding='utf-8-sig'))
        rows = []
        for case in audits:
            response = request('/v1/query', {'question': case['question'], 'max_tokens': 64})
            correct = score_answer(response['answer'], case['answer'], case.get('aliases', []))
            rows.append({**case, 'response': response, 'correct': correct})
        report['external_audit'] = {'used_for_training': False, 'used_for_publish_gate': False,
            'metric': 'numeric_safe_normalized_exact_answer',
            'total': len(rows), 'correct': sum(row['correct'] for row in rows), 'cases': rows}
    report['status'] = 'completed'
    report['finished_at'] = time.time()
    report['final_status'] = request('/v1/status')
    save()
    print(json.dumps({'status': 'completed', 'rounds': len(report['rounds']),
                      'report_path': str(args.output.resolve())}), flush=True)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
