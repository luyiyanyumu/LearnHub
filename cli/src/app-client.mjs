import { createWriteStream, openAsBlob } from 'node:fs';
import { link, stat, unlink } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { basename, dirname, join, resolve } from 'node:path';
import { pipeline } from 'node:stream/promises';

const METHODS = new Set(['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD']);
const RESPONSES = new Set(['json', 'text', 'binary', 'sse']);
const LOCATIONS = { path: 'path', query: 'query', header: 'header', form: 'form', file: 'files' };
const PATH_FIELD = /\{([A-Za-z_][\w-]*)\}/g;
const FILE_LIMIT = 50 * 1024 * 1024;
const FORM_LIMIT = 60 * 1024 * 1024;
const SSE_LIMIT = 8 * 1024 * 1024;

function isRecord(value) {
  if (!value || typeof value !== 'object') return false;
  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
}

function originOf(baseUrl) {
  let url;
  try { url = new URL(baseUrl); } catch { throw new Error('应用地址必须是完整的 HTTP 或 HTTPS 地址。'); }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password
      || url.pathname !== '/' || url.search || url.hash) {
    throw new Error('应用地址只接受 HTTP 或 HTTPS 的协议、主机与端口。');
  }
  return url.origin;
}

function isCommandPath(path) {
  if (typeof path !== 'string' || !path.startsWith('/api/')) return false;
  const template = path.replace(PATH_FIELD, 'value');
  return /^[A-Za-z0-9_./-]+$/.test(template)
    && !template.includes('//') && !template.split('/').some(segment => segment === '.' || segment === '..');
}

function validateCatalog(catalog) {
  if (!isRecord(catalog) || catalog.version !== 1 || !Array.isArray(catalog.commands)) {
    throw new Error('当前应用返回了无法识别的命令目录。');
  }
  const commands = new Map();
  for (const command of catalog.commands) {
    if (!isRecord(command) || typeof command.id !== 'string' || !/^[a-z0-9][a-z0-9.-]*$/.test(command.id)
        || commands.has(command.id) || !METHODS.has(command.method) || !RESPONSES.has(command.response)
        || !isCommandPath(command.path) || !Array.isArray(command.parameters)
        || (command.body != null && (!isRecord(command.body) || typeof command.body.required !== 'boolean'))) {
      throw new Error('应用命令目录包含无效命令。');
    }
    const seen = new Set();
    const fields = new Set([...command.path.matchAll(PATH_FIELD)].map(match => match[1]));
    for (const parameter of command.parameters) {
      if (!isRecord(parameter) || typeof parameter.name !== 'string' || !/^[\w.-]+$/.test(parameter.name)
          || !Object.hasOwn(LOCATIONS, parameter.in) || typeof parameter.required !== 'boolean') {
        throw new Error(`命令 ${command.id} 的参数定义无效。`);
      }
      const key = `${parameter.in}:${parameter.name}`;
      if (seen.has(key) || (parameter.in === 'path' && !fields.delete(parameter.name))) {
        throw new Error(`命令 ${command.id} 的参数定义与路径不一致。`);
      }
      seen.add(key);
    }
    if (fields.size) throw new Error(`命令 ${command.id} 缺少路径参数定义。`);
    commands.set(command.id, command);
  }
  return commands;
}

function readInput(command, input) {
  if (!isRecord(input)) throw new Error('命令输入必须是 JSON 对象。');
  const sections = {};
  for (const section of Object.values(LOCATIONS)) {
    const value = Object.hasOwn(input, section) ? input[section] : {};
    if (!isRecord(value)) throw new Error(`${section} 必须是 JSON 对象。`);
    sections[section] = value;
  }
  for (const key of Object.keys(input)) {
    if (key !== 'body' && !Object.hasOwn(sections, key)) throw new Error('命令输入含有未识别的分组。');
  }
  for (const [location, section] of Object.entries(LOCATIONS)) {
    const allowed = command.parameters.filter(parameter => parameter.in === location);
    for (const key of Object.keys(sections[section])) {
      if (!allowed.some(parameter => parameter.name === key)) throw new Error(`${section} 含有命令未声明的参数。`);
    }
    for (const parameter of allowed) {
      const supplied = Object.hasOwn(sections[section], parameter.name);
      const value = sections[section][parameter.name];
      const label = `${section}.${parameter.name}`;
      if ((parameter.required || location === 'path') && (!supplied || value == null)) {
        throw new Error(`缺少参数 ${label}。`);
      }
      if (supplied) validateType(value, parameter, label);
    }
  }
  const body = Object.hasOwn(input, 'body') ? input.body : undefined;
  if (!command.body && body !== undefined) throw new Error('此命令不接收 JSON 请求正文。');
  if (command.body?.required && body == null) throw new Error('缺少参数 body。');
  return { ...sections, body };
}

function validateType(value, parameter, label) {
  const values = Array.isArray(value) && parameter.in !== 'path' ? value : [value];
  for (const item of values) {
    if (parameter.in === 'file' && (typeof item !== 'string' || !item.trim())) {
      throw new Error(`${label} 必须是本地文件路径。`);
    }
    if (parameter.type === 'integer' && !Number.isSafeInteger(item)
        && !(typeof item === 'string' && /^[+-]?\d+$/.test(item))) {
      throw new Error(`${label} 必须是整数。超出 JavaScript 安全整数范围时请使用字符串。`);
    }
    if (parameter.type === 'number' && !Number.isFinite(item)
        && !(typeof item === 'string' && item.trim() && Number.isFinite(Number(item)))) {
      throw new Error(`${label} 必须是数字。`);
    }
    if (parameter.type === 'boolean' && typeof item !== 'boolean'
        && !(typeof item === 'string' && /^(true|false|on|off|yes|no|1|0)$/i.test(item))) {
      throw new Error(`${label} 必须是布尔值。`);
    }
  }
}

function scalar(value, name) {
  if (typeof value === 'string' || typeof value === 'boolean' || Number.isFinite(value)) return String(value);
  throw new Error(`${name} 必须是字符串、数字或布尔值。`);
}

function repeated(value, name) {
  const values = Array.isArray(value) ? value : [value];
  if (!values.length) throw new Error(`${name} 至少需要一个值。`);
  return values.map(item => scalar(item, name));
}

function commandUrl(origin, command, input) {
  const path = command.path.replace(PATH_FIELD, (_match, name) => {
    const value = scalar(input.path[name], `path.${name}`);
    if (!value || value === '.' || value === '..' || /[\\/\x00-\x1F\x7F]/.test(value)) {
      throw new Error(`path.${name} 必须是有效的单个路径片段。`);
    }
    try { return encodeURIComponent(value); } catch { throw new Error(`path.${name} 不是有效的文本。`); }
  });
  const url = new URL(path, origin);
  if (url.origin !== origin || url.pathname !== path) throw new Error('命令解析后的请求路径无效。');
  for (const [name, value] of Object.entries(input.query)) {
    for (const item of repeated(value, `query.${name}`)) url.searchParams.append(name, item);
  }
  return url;
}

async function multipart(input) {
  const body = new FormData();
  let total = 0;
  for (const [name, value] of Object.entries(input.form)) {
    for (const item of repeated(value, `form.${name}`)) body.append(name, item);
  }
  for (const [name, value] of Object.entries(input.files)) {
    for (const filename of repeated(value, `files.${name}`)) {
      let file;
      try {
        if (!(await stat(filename)).isFile()) throw new Error();
        file = await openAsBlob(filename);
      } catch { throw new Error(`无法读取上传文件 files.${name}。`); }
      if (file.size > FILE_LIMIT) throw new Error(`上传文件 files.${name} 超出 50 MiB。`);
      total += file.size;
      if (total > FORM_LIMIT) throw new Error('上传文件总大小超出 60 MiB。');
      body.append(name, file, basename(filename));
    }
  }
  return body;
}

function message(value) {
  return typeof value === 'string' ? value.replace(/[\x00-\x1F\x7F]/g, ' ').trim().slice(0, 400) : '';
}

function isJson(type) {
  return type === 'application/json' || type.endsWith('+json');
}

async function readJson(response) {
  try { return await response.json(); } catch { throw new Error('应用返回了无法解析的 JSON。'); }
}

function unwrap(payload, requireEnvelope = false) {
  if (isRecord(payload) && Number.isInteger(payload.code) && typeof payload.msg === 'string') {
    if (payload.code !== 200) throw new Error(message(payload.msg) || `应用命令失败（${payload.code}）。`);
    return payload.data ?? null;
  }
  if (requireEnvelope) throw new Error('应用返回了无法识别的命令目录响应。');
  return payload;
}

async function readResponse(fetcher, url, init, consume, timeoutMs, signal) {
  const timeout = AbortSignal.timeout(timeoutMs);
  const requestSignal = signal ? AbortSignal.any([timeout, signal]) : timeout;
  try {
    let response;
    try { response = await fetcher(url, { ...init, signal: requestSignal, redirect: 'error' }); }
    catch { throw new Error('无法连接应用或应用返回了重定向，请核对应用地址。'); }
    const contentType = response.headers.get('content-type') || '';
    const type = contentType.split(';', 1)[0].trim().toLowerCase();
    try {
      if (!response.ok) {
        const payload = isJson(type) ? await response.json().catch(() => null) : null;
        const detail = message(payload?.msg ?? payload?.message);
        throw new Error(`应用请求失败（HTTP ${response.status}）${detail ? ` ${detail}` : '。'}`);
      }
      return await consume(response, type, contentType);
    } finally {
      if (!response.bodyUsed) await response.body?.cancel().catch(() => {});
    }
  } catch (error) {
    if (timeout.aborted) throw new Error(`应用请求超时（${timeoutMs} 毫秒）。`);
    if (signal?.aborted) throw new Error('应用请求已取消。');
    throw error;
  }
}

async function saveBinary(response, destination, contentType) {
  if (!response.body) throw new Error('应用未返回下载内容。');
  const output = resolve(destination);
  const temporary = join(dirname(output), `.learnhub-${randomUUID()}.part`);
  const writer = createWriteStream(temporary, { flags: 'wx', mode: 0o600 });
  let created = false;
  writer.once('open', () => { created = true; });
  try {
    await pipeline(response.body, writer);
    // A sibling hard link publishes the complete download without replacing an existing path.
    await link(temporary, output);
    return { output, bytes: writer.bytesWritten, contentType };
  } catch (error) {
    if (error.code === 'EEXIST') throw new Error('输出文件已存在，请指定新文件名。');
    throw new Error('下载未完成，请检查网络、输出目录与磁盘空间。');
  } finally {
    if (created) await unlink(temporary).catch(() => {});
  }
}

async function readEvents(body, onEvent) {
  if (!body) throw new Error('应用未返回事件流。');
  let buffer = '';
  let event = 'message';
  let id;
  let data = [];
  let size = 0;
  let events = 0;
  const dispatch = async line => {
    if (line === '') {
      if (!data.length) { event = 'message'; return false; }
      const raw = data.join('\n');
      let value;
      try { value = JSON.parse(raw); } catch { value = raw; }
      const current = { event, data: value, ...(id === undefined ? {} : { id }) };
      events++;
      await onEvent(current);
      if (event === 'error' || event === 'failed') {
        throw new Error(message(value?.message ?? value) || '应用事件流报告失败。');
      }
      if (event === 'result' || event === 'done') return true;
      event = 'message';
      data = [];
      size = 0;
      return false;
    }
    const colon = line.indexOf(':');
    const field = colon < 0 ? line : line.slice(0, colon);
    const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '');
    if (field === 'event') event = value || 'message';
    if (field === 'id' && !value.includes('\0')) id = value;
    if (field === 'data') {
      size += value.length + 1;
      if (size > SSE_LIMIT) throw new Error('应用事件超过可处理大小。');
      data.push(value);
    }
    return false;
  };
  for await (const chunk of body.pipeThrough(new TextDecoderStream())) {
    const lines = (buffer + chunk).split(/\r\n|\n|\r(?!$)/);
    buffer = lines.pop();
    if (buffer.length > SSE_LIMIT) throw new Error('应用事件行超过可处理大小。');
    for (const line of lines) if (await dispatch(line)) return { events };
  }
  if (buffer.endsWith('\r') && await dispatch(buffer.slice(0, -1))) return { events };
  throw new Error('应用事件流在返回最终结果前中断。');
}

export function createAppClient({ baseUrl = 'http://localhost:18080', fetch: fetcher = globalThis.fetch, timeoutMs } = {}) {
  const origin = originOf(baseUrl);
  if (timeoutMs !== undefined && (!Number.isInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 2 ** 31 - 1)) {
    throw new Error('请求超时必须是 1 至 2147483647 的整数毫秒。');
  }
  let catalog;
  const load = () => {
    catalog ??= readResponse(fetcher, new URL('/api/cli/commands', origin), { headers: { Accept: 'application/json' } },
      async (response, type) => {
        if (!isJson(type)) throw new Error('应用未返回命令目录，请确认应用地址和版本。');
        return validateCatalog(unwrap(await readJson(response), true));
      }, timeoutMs ?? 15_000).catch(error => { catalog = undefined; throw error; });
    return catalog;
  };
  const lookup = async id => {
    const command = (await load()).get(id);
    if (!command) throw new Error('命令不存在，请先查看应用命令目录。');
    return command;
  };
  return {
    async list() { return structuredClone([...(await load()).values()]); },
    async describe(id) { return structuredClone(await lookup(id)); },
    async call(id, input = {}, { output, onEvent, signal } = {}) {
      const command = await lookup(id);
      const parts = readInput(command, input);
      if (command.response === 'binary' && (typeof output !== 'string' || !output.trim())) {
        throw new Error('下载命令需要通过 --output 指定新文件。');
      }
      if (output !== undefined && command.response !== 'binary') throw new Error('--output 只适用于下载命令。');
      if (command.response === 'sse' && typeof onEvent !== 'function') throw new Error('流式命令需要提供事件接收函数。');

      const url = commandUrl(origin, command, parts);
      // A literal Spring route takes precedence over a parameterized route.
      if (url.pathname !== command.path && [...(await load()).values()].some(candidate =>
        candidate.method === command.method && candidate.path === url.pathname)) {
        throw new Error('路径参数会匹配另一条命令，请检查参数。');
      }
      const accept = { json: 'application/json', sse: 'text/event-stream', text: 'text/*, application/json', binary: '*/*' };
      const headers = new Headers({ Accept: accept[command.response] });
      for (const [name, value] of Object.entries(parts.header)) {
        if (/^(host|content-length|content-type|transfer-encoding|connection)$/i.test(name)) {
          throw new Error(`请求头 ${name} 由客户端管理。`);
        }
        for (const item of repeated(value, `header.${name}`)) {
          try { headers.append(name, item); } catch { throw new Error(`请求头 ${name} 的值无效。`); }
        }
      }
      const hasForm = command.parameters.some(parameter => parameter.in === 'form' || parameter.in === 'file');
      let body;
      if (hasForm) {
        if (parts.body !== undefined) throw new Error('同一命令不能同时发送 JSON 正文和上传表单。');
        body = await multipart(parts);
      } else if (parts.body !== undefined) {
        try { body = JSON.stringify(parts.body); } catch { throw new Error('body 无法转成 JSON。'); }
        if (body === undefined) throw new Error('body 无法转成 JSON。');
        headers.set('Content-Type', 'application/json');
      }
      const duration = timeoutMs ?? (command.response === 'sse' || id.startsWith('ai.') ? 1_020_000 : hasForm ? 300_000 : 120_000);
      return readResponse(fetcher, url, { method: command.method, headers, body }, async (response, type, contentType) => {
        if (command.response === 'binary') {
          if (!response.headers.has('content-disposition')) {
            if (isJson(type)) {
              unwrap(await readJson(response));
              throw new Error('应用返回了 JSON 响应，未提供下载内容。');
            }
            if (type === 'text/html') throw new Error('应用返回了网页，未提供下载内容。');
          }
          return saveBinary(response, output, contentType);
        }
        if (isJson(type)) return unwrap(await readJson(response));
        if (command.response === 'sse' && type === 'text/event-stream') return readEvents(response.body, onEvent);
        if (command.response === 'text' && type.startsWith('text/') && type !== 'text/html') return response.text();
        if (response.status === 204 || command.method === 'HEAD') return null;
        throw new Error('应用响应格式与命令定义不符。');
      }, duration, signal);
    },
  };
}
