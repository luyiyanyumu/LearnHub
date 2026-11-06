import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, readFile, readdir, rmdir, unlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { createAppClient } from '../src/app-client.mjs';

function json(response, data, code = 200) {
  response.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
  response.end(JSON.stringify({ code, msg: code === 200 ? 'ok' : '合成业务错误', data }));
}

async function fixture(t, commands, handle) {
  const visits = [];
  const faults = [];
  const server = createServer((request, response) => {
    const url = new URL(request.url, 'http://fixture.invalid');
    visits.push({ method: request.method, url });
    if (url.pathname === '/api/cli/commands') { json(response, { version: 1, commands }); return; }
    Promise.resolve().then(() => handle(request, response, url)).catch(error => {
      faults.push(error);
      response.destroy();
    });
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
    assert.deepEqual(faults, []);
  });
  const baseUrl = `http://127.0.0.1:${server.address().port}`;
  return { client: createAppClient({ baseUrl }), visits, baseUrl };
}

async function tempFiles(t) {
  const path = await mkdtemp(join(tmpdir(), 'learnhub-app-client-'));
  t.after(async () => {
    for (const name of await readdir(path)) await unlink(join(path, name));
    await rmdir(path);
  });
  return path;
}

async function bytes(request) {
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  return Buffer.concat(chunks);
}

const parameter = (name, location, required = true) => ({ name, in: location, required });
const get = (id, path, parameters = [], response = 'json') => ({ id, method: 'GET', path, parameters, response });

test('uses the app catalog to encode path and repeated query arguments and send a JSON body', async t => {
  const commands = [
    get('asset.read', '/api/files/{id}/extracted-assets/{hash}.png', [{ ...parameter('id', 'path'), type: 'integer' }, parameter('hash', 'path'), parameter('q', 'query', false)]),
    { ...get('note.edit', '/api/notes/{id}', [parameter('id', 'path')]), method: 'PATCH', body: { required: true, schema: { type: 'object' } } },
  ];
  const { client, visits } = await fixture(t, commands, async (request, response, url) => {
    if (request.method === 'PATCH') {
      assert.equal(request.headers['content-type'], 'application/json');
      json(response, JSON.parse((await bytes(request)).toString('utf8')));
    } else {
      json(response, { pathname: url.pathname, query: url.searchParams.getAll('q') });
    }
  });
  assert.deepEqual(await client.list(), commands);
  const description = await client.describe('asset.read');
  assert.deepEqual(description, commands[0]);
  description.path = '/api/untrusted';
  assert.deepEqual(await client.call('asset.read', { path: { id: 7, hash: '图 1' }, query: { q: ['含&符号', '+?#'] } }), {
    pathname: '/api/files/7/extracted-assets/%E5%9B%BE%201.png', query: ['含&符号', '+?#'],
  });
  await assert.rejects(client.call('asset.read', { path: { id: 'trash', hash: 'a' } }), /整数/);
  await assert.rejects(client.call('asset.read', { path: { id: 9007199254740992, hash: 'a' } }), /安全整数/);
  assert.match((await client.call('asset.read', { path: { id: '9007199254740993', hash: 'a' } })).pathname, /9007199254740993/);
  const body = { title: '合成笔记', content: '正文\n合成内容' };
  assert.deepEqual(await client.call('note.edit', { path: { id: 2 }, body }), body);
  assert.equal(visits.filter(visit => visit.url.pathname === '/api/cli/commands').length, 1);
  assert.equal(visits.at(-1).method, 'PATCH');
});

test('rejects unknown operations, unsafe paths, undeclared inputs and cross-origin redirects', async t => {
  const command = get('note.read', '/api/notes/{id}', [parameter('id', 'path')]);
  const { client, visits } = await fixture(t, [command, get('note.archive', '/api/notes/archive')], (_request, response) => {
    response.writeHead(302, { Location: 'http://example.invalid/api/external' });
    response.end();
  });
  await assert.rejects(client.call('unknown.command'), /命令不存在/);
  await assert.rejects(client.call('note.read'), /缺少参数 path.id/);
  for (const id of ['.', '..', '../x', 'foo\\bar']) await assert.rejects(client.call('note.read', { path: { id } }), /单个路径片段/);
  await assert.rejects(client.call('note.read', { path: { id: 1 }, query: { unknown: true } }), /未声明/);
  await assert.rejects(client.call('note.read', { path: { id: 1 }, body: {} }), /不接收 JSON/);
  await assert.rejects(client.call('note.read', { path: { id: 'archive' } }), /匹配另一条命令/);
  assert.equal(visits.length, 1);
  await assert.rejects(client.call('note.read', { path: { id: 1 } }), /重定向/);
  assert.equal(visits.length, 2);
  assert.throws(() => createAppClient({ baseUrl: 'file:///fixture' }), /HTTP/);
  assert.throws(() => createAppClient({ baseUrl: 'http://secret@localhost' }), /协议、主机与端口/);

  const unsafe = await fixture(t, [get('escape.path', '/api/../admin')], () => assert.fail('unsafe command dispatched'));
  await assert.rejects(unsafe.client.list(), /无效命令/);
});

test('uses multipart for explicitly named local files and repeated fields', async t => {
  const root = await tempFiles(t);
  const localFile = join(root, 'synthetic file.txt');
  await writeFile(localFile, 'synthetic body');
  const command = { ...get('file.upload', '/api/files/upload', [parameter('file', 'file'), parameter('folderId', 'form'), parameter('tags', 'form', false)]), method: 'POST' };
  const { client, visits } = await fixture(t, [command], async (request, response) => {
    const form = await new Response(await bytes(request), { headers: { 'Content-Type': request.headers['content-type'] } }).formData();
    const file = form.get('file');
    json(response, { name: file.name, content: await file.text(), folderId: form.get('folderId'), tags: form.getAll('tags') });
  });
  await assert.rejects(client.call('file.upload', { form: { folderId: 4 } }), /缺少参数 files.file/);
  await assert.rejects(client.call('file.upload', { files: { file: root }, form: { folderId: 4 } }), /无法读取/);
  assert.equal(visits.length, 1);
  assert.deepEqual(await client.call('file.upload', { files: { file: localFile }, form: { folderId: 4, tags: ['synthetic', '测试'] } }), {
    name: 'synthetic file.txt', content: 'synthetic body', folderId: '4', tags: ['synthetic', '测试'],
  });
});

test('publishes a complete download without overwriting and cleans up interrupted downloads', async t => {
  const root = await tempFiles(t);
  const output = join(root, 'download.json');
  const broken = join(root, 'broken.json');
  const failed = join(root, 'failed.json');
  const command = get('file.raw', '/api/files/{id}/raw', [parameter('id', 'path')], 'binary');
  const payload = Buffer.from('{"synthetic":true}');
  const { client, visits } = await fixture(t, [command], (_request, response, url) => {
    if (url.pathname === '/api/files/3/raw') { json(response, null, 404); return; }
    response.writeHead(200, { 'Content-Type': 'application/json', 'Content-Disposition': 'inline; filename="synthetic.json"', 'Content-Length': payload.length });
    if (url.pathname === '/api/files/2/raw') {
      response.write(payload.subarray(0, 6));
      setTimeout(() => response.destroy(), 20);
      return;
    }
    response.end(payload);
  });
  await assert.rejects(client.call('file.raw', { path: { id: 1 } }), /--output/);
  assert.equal(visits.length, 1);
  assert.deepEqual(await client.call('file.raw', { path: { id: 1 } }, { output }), { output, bytes: payload.length, contentType: 'application/json' });
  await assert.rejects(client.call('file.raw', { path: { id: 1 } }, { output }), /已存在/);
  await assert.rejects(client.call('file.raw', { path: { id: 2 } }, { output: broken }), /下载未完成/);
  await assert.rejects(client.call('file.raw', { path: { id: 3 } }, { output: failed }), /合成业务错误/);
  assert.deepEqual(await readFile(output), payload);
  assert.deepEqual(await readdir(root), ['download.json']);
});

test('receives split SSE events and rejects terminal errors or a missing result', async t => {
  const command = { ...get('ai.stream', '/api/ai/stream', [], 'sse'), method: 'POST', body: { required: true } };
  const { client } = await fixture(t, [command], async (request, response) => {
    const body = JSON.parse((await bytes(request)).toString('utf8'));
    if (body.case === 'rejected') { json(response, null, 422); return; }
    assert.equal(request.headers.accept, 'text/event-stream');
    response.writeHead(200, { 'Content-Type': 'text/event-stream' });
    if (body.case === 'error') { response.end('event: failed\ndata: {"message":"合成流式错误"}\n\n'); return; }
    if (body.case === 'incomplete') { response.end('event: progress\ndata: 1\n\n'); return; }
    response.write(': keep-alive\r\n\r\nid: 12\r\nevent: progress\r');
    response.write('\ndata: {\r\ndata: "count": 1}\r\n\r\n');
    const terminal = Buffer.from('event: result\ndata: {"answer":"完成"}\n\n');
    const split = terminal.indexOf(Buffer.from('完')) + 1;
    response.write(terminal.subarray(0, split));
    setTimeout(() => response.end(terminal.subarray(split)), 5);
  });
  const events = [];
  const onEvent = event => { events.push(event); };
  assert.deepEqual(await client.call('ai.stream', { body: { case: 'complete' } }, { onEvent }), { events: 2 });
  assert.deepEqual(events, [
    { event: 'progress', data: { count: 1 }, id: '12' },
    { event: 'result', data: { answer: '完成' }, id: '12' },
  ]);
  await assert.rejects(client.call('ai.stream', { body: { case: 'error' } }, { onEvent }), /合成流式错误/);
  await assert.rejects(client.call('ai.stream', { body: { case: 'incomplete' } }, { onEvent }), /最终结果前中断/);
  await assert.rejects(client.call('ai.stream', { body: { case: 'rejected' } }, { onEvent }), /合成业务错误/);
});
