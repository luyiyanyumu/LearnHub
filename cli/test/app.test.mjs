import assert from 'node:assert/strict';
import { Readable, Writable } from 'node:stream';
import test from 'node:test';
import { runCli } from '../bin/learnhub.mjs';

function harness(input = '') {
  const calls = [];
  const lines = [];
  const command = { id: 'note.page', method: 'GET', path: '/api/notes', response: 'json', parameters: [] };
  return {
    calls, lines,
    options: {
      env: { LEARNHUB_BASE_URL: 'http://127.0.0.1:9999' },
      stdin: Readable.from([input]),
      stdout: new Writable({ write(chunk, _encoding, done) { lines.push(JSON.parse(chunk.toString())); done(); } }),
      clientFactory(connection) {
        assert.equal(connection.baseUrl, 'http://127.0.0.1:9999');
        return {
          async list() { return [command, { id: 'tag.list', method: 'GET', path: '/api/tags', response: 'json' }]; },
          async describe(id) { assert.equal(id, command.id); return command; },
          async call(id, request, { output }) { calls.push({ id, request, output }); return { list: [], total: 0 }; },
        };
      },
    },
  };
}

test('app call reads piped UTF-8 JSON with a BOM and prints only the machine result', async () => {
  const { calls, lines, options } = harness('\uFEFF{"query":{"kw":"合成 例子"}}');
  await runCli(['app', 'call', 'note.page', '--input', '-'], options);
  assert.deepEqual(calls, [{ id: 'note.page', request: { query: { kw: '合成 例子' } }, output: undefined }]);
  assert.deepEqual(lines, [{ list: [], total: 0 }]);
});

test('a scripted shell discovers and invokes commands without treating arguments as host code', async () => {
  const { calls, lines, options } = harness('list note.\ndescribe note.page\ncall note.page {"query":{"kw":"$(literal) & 合成"}}\nexit\ncall note.page\n');
  await runCli(['shell'], options);
  assert.deepEqual(lines[0].map(item => item.id), ['note.page']);
  assert.equal(lines[1].id, 'note.page');
  assert.deepEqual(calls, [{ id: 'note.page', request: { query: { kw: '$(literal) & 合成' } }, output: undefined }]);
  assert.deepEqual(lines[2], { list: [], total: 0 });
});

test('an invalid request stops scripted execution before sending any operation', async () => {
  const { calls, options } = harness('call note.page [1]\ncall note.page\n');
  await assert.rejects(runCli(['shell'], options), /JSON 对象/);
  assert.deepEqual(calls, []);
  await assert.rejects(runCli(['app', 'call', 'note.page', '--input', '-', '--json', '{}'], options), /只能选一个/);
});
