"""Synthetic CLI regression checks. Fake backend only; no sockets or model calls."""
from pathlib import Path
import hashlib
import importlib.util
import json
import math
import unittest
import uuid

WORKSPACE = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('mmr_embed', WORKSPACE / 'tools/mmr-embed.py')
embed = importlib.util.module_from_spec(spec)
spec.loader.exec_module(embed)


class FakeBackend:
    def __init__(self, mutate=False):
        self.digest = 'a' * 64
        self.calls = []
        self.mutate = mutate

    def identity(self):
        return {'model': 'bge-m3', 'modelDigest': self.digest, 'modelDetails': {}}

    def request(self, path, body=None):
        self.calls.append((path, body))
        if path == '/api/version':
            return {'version': 'fake-local-test'}
        if path != '/api/embed':
            raise AssertionError('Unexpected fake route')
        if self.mutate:
            self.digest = 'b' * 64
        return {'model': 'bge-m3', 'embeddings': [[3.0, 4.0] for _ in body['input']]}


class LocalChecks(unittest.TestCase):
    def setUp(self):
        # Preserve all generated synthetic files for inspection; no recursive cleanup.
        self.directory = WORKSPACE / 'output/mmr-ab-2026-10-10/cli-local-tests' / uuid.uuid4().hex
        self.directory.mkdir(parents=True)
        self.input = self.directory / 'input.json'
        self.output = self.directory / 'vectors.json'
        self.progress = self.directory / 'progress.json'
        self.documents = [{'id': hashlib.sha256(text.encode()).hexdigest(), 'text': text}
                          for text in ['synthetic one', 'synthetic two', 'synthetic three']]
        self.input.write_text(json.dumps({'documents': self.documents}) + '\n', encoding='utf-8')

    def expect_code(self, code, operation):
        with self.assertRaises(embed.ProbeError) as caught:
            operation()
        self.assertEqual(str(caught.exception), code)

    def test_only_isolated_loopback_url(self):
        for value in ['http://127.0.0.1:11434', 'https://127.0.0.1:11435',
                      'http://remote.example:11435', 'http://user:secret@localhost:11435',
                      'http://localhost:11435/api', 'http://localhost:11435?key=synthetic']:
            self.expect_code('isolated_local_url_required', lambda value=value: embed.local_base(value))
        self.assertEqual(embed.local_base('http://127.0.0.1:11435/'), 'http://127.0.0.1:11435')

    def test_document_hash_and_exact_file_hash(self):
        documents, digest = embed.read_input(self.input)
        self.assertEqual(documents, self.documents)
        self.assertEqual(digest, hashlib.sha256(self.input.read_bytes()).hexdigest())
        self.documents[0]['text'] = 'synthetic changed'
        self.input.write_text(json.dumps({'documents': self.documents}), encoding='utf-8')
        self.expect_code('document_hash_mismatch', lambda: embed.read_input(self.input))

    def test_duplicate_document_ids_rejected(self):
        self.input.write_text(json.dumps({'documents': [self.documents[0], self.documents[0]]}), encoding='utf-8')
        self.expect_code('duplicate_document_id', lambda: embed.read_input(self.input))

    def test_invalid_vectors_rejected(self):
        for vector in [[True, 1], [float('nan'), 1], [float('inf'), 1], ['0', 1]]:
            self.expect_code('invalid_embedding_values', lambda vector=vector: embed.normalized(vector))
        self.expect_code('zero_or_invalid_embedding', lambda: embed.normalized([0, 0]))
        self.expect_code('embedding_dimension_mismatch', lambda: embed.normalized([1, 2], 3))
        self.expect_code('progress_vector_not_normalized', lambda: embed.normalized([3, 4], cached=True))

    def test_batches_normalize_and_resume_without_embed(self):
        backend = FakeBackend()
        first = embed.embed_file(self.input, self.output, self.progress, backend, batch_size=2)
        result = json.loads(self.output.read_text())
        self.assertEqual(first['documentCount'], 3)
        self.assertEqual(result['dimension'], 2)
        for entry in result['vectors']:
            self.assertAlmostEqual(math.hypot(*entry['vector']), 1.0)
        calls = [body for path, body in backend.calls if path == '/api/embed']
        self.assertEqual([len(body['input']) for body in calls], [2, 1])
        self.assertTrue(all(body['truncate'] is False for body in calls))
        self.assertTrue(all(body['options'] == {'num_thread': 1, 'num_gpu': 0} for body in calls))
        second_backend = FakeBackend()
        second = embed.embed_file(self.input, self.output, self.progress, second_backend, batch_size=2)
        self.assertEqual(second['resumedCount'], 3)
        self.assertFalse(any(path == '/api/embed' for path, _ in second_backend.calls))

    def test_progress_identity_rejects_changed_exact_input(self):
        embed.embed_file(self.input, self.output, self.progress, FakeBackend())
        self.input.write_bytes(self.input.read_bytes() + b'\n')
        self.expect_code('progress_identity_mismatch', lambda: embed.embed_file(
            self.input, self.output, self.progress, FakeBackend()))

    def test_progress_model_options_and_order_guard(self):
        embed.embed_file(self.input, self.output, self.progress, FakeBackend())
        original = json.loads(self.progress.read_text())
        for field, replacement in [('modelDigest', 'c' * 64), ('options', {'num_thread': 4, 'num_gpu': 0})]:
            changed = {**original, field: replacement}
            self.progress.write_text(json.dumps(changed), encoding='utf-8')
            self.expect_code('progress_identity_mismatch', lambda: embed.embed_file(
                self.input, self.output, self.progress, FakeBackend()))
        original['vectors'].reverse()
        self.progress.write_text(json.dumps(original), encoding='utf-8')
        self.expect_code('progress_document_order_mismatch', lambda: embed.embed_file(
            self.input, self.output, self.progress, FakeBackend()))

    def test_changed_model_during_batch_keeps_output_unpublished(self):
        self.expect_code('model_changed_during_run', lambda: embed.embed_file(
            self.input, self.output, self.progress, FakeBackend(mutate=True)))
        self.assertFalse(self.output.exists())
        self.assertFalse(self.progress.exists())


if __name__ == '__main__':
    unittest.main()
