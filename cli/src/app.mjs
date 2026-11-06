import { readFile } from 'node:fs/promises';
import { once } from 'node:events';
import { homedir } from 'node:os';
import { resolve } from 'node:path';
import { createInterface } from 'node:readline';
import { createAppClient } from './app-client.mjs';
import { parseEnv } from './manager.mjs';

const HELP = `LearnHub 应用命令

  learnhub app list [前缀]                  列出当前实例的命令
  learnhub app describe <命令>              查看参数、请求体和返回格式
  learnhub app call <命令> [选项]           执行一个应用操作
  learnhub shell [选项]                     进入应用交互终端

  --base-url <URL>       实例地址，也可设置 LEARNHUB_BASE_URL
  --home <目录>          从安装实例配置读取后端端口
  --timeout-ms <毫秒>    覆盖请求超时
  --json <JSON>          直接传入请求对象
  --input <文件|->       从 UTF-8 JSON 文件或标准输入读取请求
  --output <文件>        保存文件响应，目标必须不存在
  --help                 显示帮助

请求对象使用 path、query、header、body、form、files 字段。
应用命令的结果写到标准输出，错误写到标准错误。流式事件逐行输出 JSON。
`;

const SHELL_HELP = `list [前缀]               列出命令
describe <命令>           查看参数
call <命令> [JSON]        执行，JSON 可含本地 output 路径
help                     显示帮助
exit                     退出`;

const OPTION_NAMES = new Map([
  ['--base-url', 'baseUrl'], ['--home', 'home'], ['--timeout-ms', 'timeoutMs'],
  ['--json', 'json'], ['--input', 'input'], ['--output', 'output'],
]);

export function parseAppArgs(argv) {
  if (argv.length === 0 || argv.includes('--help') || argv.includes('-h')) return { action: 'help' };
  const [action, ...args] = argv;
  if (!['list', 'describe', 'call', 'shell'].includes(action)) throw new Error(`未知应用子命令：${action}`);

  const positionals = [];
  const options = {};
  for (let index = 0; index < args.length; index++) {
    const flag = args[index];
    const key = OPTION_NAMES.get(flag);
    if (!key) {
      if (flag.startsWith('-')) throw new Error(`未知选项：${flag}`);
      positionals.push(flag);
      continue;
    }
    const value = args[++index];
    if (!value || value.startsWith('--')) throw new Error(`${flag} 后需要一个值。`);
    if (Object.hasOwn(options, key)) throw new Error(`${flag} 重复指定。`);
    if (action !== 'call' && ['json', 'input', 'output'].includes(key)) {
      throw new Error(`${action} 不支持 ${flag}。`);
    }
    options[key] = value;
  }

  if (positionals.length > 1 || (action === 'shell' && positionals.length)) throw new Error('多余的命令参数。');
  if (['describe', 'call'].includes(action) && !positionals[0]) throw new Error(`${action} 需要命令名。`);
  if (options.json && options.input) throw new Error('--json 和 --input 只能选一个。');
  if (options.timeoutMs !== undefined) {
    const value = Number(options.timeoutMs);
    if (!/^\d+$/.test(options.timeoutMs) || !Number.isSafeInteger(value) || value < 1 || value > 2_147_483_647) {
      throw new Error('--timeout-ms 必须是 1 至 2147483647 之间的整数。');
    }
    options.timeoutMs = value;
  }
  return { action, argument: positionals[0], options };
}

async function appBaseUrl(options, env) {
  const explicit = options.baseUrl || env.LEARNHUB_BASE_URL;
  if (explicit) return explicit;
  const home = options.home || env.LEARNHUB_HOME || resolve(homedir(), '.learnhub');
  let config;
  try {
    config = parseEnv(await readFile(resolve(home, '.env'), 'utf8'));
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  const port = config?.BACKEND_HOST_PORT || '18080';
  if (!/^\d+$/.test(port) || Number(port) < 1 || Number(port) > 65535) throw new Error('实例的后端端口无效。');
  return `http://127.0.0.1:${port}`;
}

function parseInput(text) {
  let input;
  try { input = JSON.parse(text.replace(/^\uFEFF/, '')); }
  catch { throw new Error('请求必须是有效的 JSON 对象。'); }
  if (input === null || typeof input !== 'object' || Array.isArray(input)) throw new Error('请求必须是 JSON 对象。');
  return input;
}

async function readStdin(stdin) {
  const chunks = [];
  let length = 0;
  for await (const chunk of stdin) {
    const bytes = Buffer.from(chunk);
    length += bytes.length;
    if (length > 16 * 1024 * 1024) throw new Error('标准输入超过 16 MiB，请使用 --input 指定文件。');
    chunks.push(bytes);
  }
  return Buffer.concat(chunks).toString('utf8');
}

async function requestInput(options, stdin) {
  if (options.json !== undefined) return parseInput(options.json);
  if (options.input === '-') return parseInput(await readStdin(stdin));
  if (options.input) return parseInput(await readFile(options.input, 'utf8'));
  return {};
}

function writeJson(stream, value) {
  return stream.write(`${JSON.stringify(value ?? null)}\n`);
}

async function listCommands(client, prefix, stdout) {
  const commands = await client.list();
  writeJson(stdout, commands.filter(({ id }) => !prefix || id.startsWith(prefix)).map(({ id, method, path, response }) => ({ id, method, path, response })));
}

async function execute(client, id, input, output, stdout) {
  const command = await client.describe(id);
  const result = await client.call(id, input, {
    output,
    onEvent: async event => {
      if (!writeJson(stdout, event)) await once(stdout, 'drain');
    },
  });
  if (command.response !== 'sse') writeJson(stdout, result);
}

async function shell(client, stdin, stdout, stderr) {
  const interactive = Boolean(stdin.isTTY && stderr.isTTY);
  const lines = createInterface({ input: stdin, output: stderr, terminal: interactive, prompt: 'learnhub> ' });
  if (interactive) {
    stderr.write('LearnHub 应用终端。输入 help 查看用法。\n');
    lines.prompt();
  }
  try {
    for await (const line of lines) {
      const [, action, argument, json] = line.trim().match(/^(\S+)(?:\s+(\S+))?(?:\s+([\s\S]+))?$/) ?? [];
      if (action === 'exit' && !argument) break;
      try {
        if (!action) continue;
        if (action === 'help' && !argument) stderr.write(`${SHELL_HELP}\n`);
        else if (action === 'list' && !json) await listCommands(client, argument, stdout);
        else if (action === 'describe' && argument && !json) writeJson(stdout, await client.describe(argument));
        else if (action === 'call' && argument) {
          const { output, ...input } = json ? parseInput(json) : {};
          if (output !== undefined && (typeof output !== 'string' || !output.trim())) throw new Error('output 必须是非空文件路径。');
          await execute(client, argument, input, output, stdout);
        } else throw new Error('命令格式无效，输入 help 查看用法。');
      } catch (error) {
        if (!interactive) throw error;
        writeJson(stderr, { error: error.message });
      } finally {
        if (interactive) lines.prompt();
      }
    }
  } finally {
    lines.close();
  }
}

export async function runAppCli(argv, {
  stdin = process.stdin, stdout = process.stdout, stderr = process.stderr,
  env = process.env, clientFactory = createAppClient,
} = {}) {
  const { action, argument, options } = parseAppArgs(argv);
  if (action === 'help') { stdout.write(HELP); return; }

  const client = clientFactory({ baseUrl: await appBaseUrl(options, env), timeoutMs: options.timeoutMs });
  if (action === 'list') return listCommands(client, argument, stdout);
  if (action === 'describe') return writeJson(stdout, await client.describe(argument));
  if (action === 'shell') return shell(client, stdin, stdout, stderr);
  return execute(client, argument, await requestInput(options, stdin), options.output, stdout);
}
