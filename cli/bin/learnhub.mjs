#!/usr/bin/env node
import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { createManager } from '../src/manager.mjs';

const pkg = JSON.parse(await readFile(new URL('../package.json', import.meta.url), 'utf8'));
const HELP = `LearnHub ${pkg.version}

用法：npx ${pkg.name}@latest <命令> [选项]

  start               首次安装，或启动已保存的应用版本
  update              备份并升级到此 CLI 对应的发布版本
  stop                停止服务并保留数据
  status              查看应用版本、容器与未完成更新
  backup              暂停写入，备份数据库、资料与技能，然后恢复服务
  adopt --from <目录>  接管现有 Docker Compose 部署（原 deploy 目录）
  doctor              检查 Docker / Compose

  --home <目录>       固定配置、版本记录和备份目录（默认 ~/.learnhub）
  --project <名称>    首次安装/接管的 Compose 实例名（默认 learn-hub）
  --to <X.Y.Z>        首次安装或更新的应用版本，禁止直接降级
  --image-prefix <值> 首次安装的镜像前缀（支持本地验证镜像）
  --web-port <端口>   首次安装网页端口（默认 8888）
  --backend-port <端口> 首次安装后端端口（默认 18080）
  --mysql-port <端口> 首次安装数据库端口（默认 3307）
  --no-open           不打开浏览器
  --offline           使用已准备的本地镜像（隔离验证/离线部署）
  --help / --version

需要 Node.js 22.14+ 与已启动的 Docker / Compose 2.20+。
发布包尚未首发时，可用 node cli/bin/learnhub.mjs <命令> 本地验证。
`;

export function parseArgs(argv) {
  const options = {};
  let command;
  const values = { '--home': 'home', '--project': 'project', '--to': 'to', '--image-prefix': 'imagePrefix',
    '--web-port': 'webPort', '--backend-port': 'backendPort', '--mysql-port': 'mysqlPort', '--from': 'from' };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--help' || arg === '-h') return { command: 'help', options };
    if (arg === '--version' || arg === '-v') return { command: 'version', options };
    if (arg === '--no-open') { options.noOpen = true; continue; }
    if (arg === '--offline') { options.offline = true; continue; }
    if (values[arg]) {
      if (!argv[i + 1] || argv[i + 1].startsWith('--')) throw new Error(`${arg} 后需要一个值。`);
      if (Object.hasOwn(options, values[arg])) throw new Error(`${arg} 重复指定。`);
      options[values[arg]] = argv[++i];
    } else if (!arg.startsWith('-') && !command) command = arg;
    else throw new Error(`未知参数：${arg}`);
  }
  command ||= 'help';
  const allowed = {
    start: ['home', 'project', 'to', 'imagePrefix', 'webPort', 'backendPort', 'mysqlPort', 'noOpen', 'offline'],
    update: ['home', 'to', 'noOpen', 'offline'], adopt: ['home', 'project', 'from'],
    status: ['home'], stop: ['home'], backup: ['home'], doctor: ['home'], help: [], version: [],
  };
  if (!Object.hasOwn(allowed, command)) throw new Error(`未知命令：${command}`);
  for (const key of Object.keys(options)) if (!allowed[command].includes(key)) throw new Error(`${command} 不支持选项 ${key}。`);
  return { command, options };
}

export async function runCli(argv, dependencies = {}) {
  const { command, options } = parseArgs(argv);
  if (command === 'help') { console.log(HELP); return; }
  if (command === 'version') { console.log(pkg.version); return; }
  const manager = createManager({ packageVersion: pkg.version, home: options.home, ...dependencies });
  return manager[command](options);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try { await runCli(process.argv.slice(2)); }
  catch (error) { console.error(`LearnHub：${error.message}`); process.exitCode = 1; }
}
