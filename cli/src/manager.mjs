import { mkdir, readFile, writeFile, copyFile, cp, rename, rm, open, stat } from 'node:fs/promises';
import { join, resolve, isAbsolute } from 'node:path';
import { homedir } from 'node:os';
import { randomBytes, randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { runDocker, openBrowser as defaultOpenBrowser } from './process.mjs';

const IMAGE_PREFIX = 'ghcr.io/luyiyanyumu/learnhub';
const DUMP = 'exec mysqldump --user=root --password="$MYSQL_ROOT_PASSWORD" --single-transaction --routines --events --triggers --hex-blob --no-tablespaces --set-gtid-purged=OFF "$MYSQL_DATABASE"';

export function parseEnv(text) {
  const result = {};
  for (const line of text.replace(/^\uFEFF/, '').split(/\r?\n/)) {
    if (!line.trim() || line.trimStart().startsWith('#')) continue;
    const match = line.match(/^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/);
    if (!match) throw new Error('配置中有无效的 .env 行，请使用 KEY=value 格式。');
    let value = match[2];
    if ((value.startsWith("'") && value.endsWith("'")) || (value.startsWith('"') && value.endsWith('"'))) {
      value = value.startsWith("'") ? value.slice(1, -1).replaceAll("\\'", "'") : value.slice(1, -1).replace(/\\([\\"$])/g, '$1');
    } else value = value.replace(/\s+#.*$/, '').trimEnd();
    if (/[\r\n\0]/.test(value)) throw new Error('配置不能包含换行或空字符。');
    result[match[1]] = value;
  }
  return result;
}

function encodeEnv(values) {
  return Object.entries(values).map(([key, value]) => {
    const text = String(value);
    if (/[\r\n\0]/.test(text)) throw new Error(`配置 ${key} 含有换行或空字符。`);
    return `${key}='${text.replaceAll("'", "\\'")}'`;
  }).join('\n') + '\n';
}

async function readJson(path) {
  try { return JSON.parse(await readFile(path, 'utf8')); }
  catch (error) { if (error.code === 'ENOENT') return null; throw error; }
}

async function saveJson(path, value) {
  const temp = `${path}.${randomUUID()}.tmp`;
  await writeFile(temp, JSON.stringify(value, null, 2) + '\n', { mode: 0o600 });
  await rename(temp, path);
}

function validateVersion(value) {
  if (!/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(value) || !value.split('.').every(part => Number.isSafeInteger(Number(part)))) throw new Error('版本必须是稳定版本号，例如 0.1.0。');
  return value;
}

function compareVersion(a, b) {
  const aa = a.split('.').map(Number);
  const bb = b.split('.').map(Number);
  for (let i = 0; i < 3; i++) if (aa[i] !== bb[i]) return aa[i] > bb[i] ? 1 : -1;
  return 0;
}

function validateProject(value) {
  if (!/^[a-z0-9][a-z0-9_-]{0,62}$/.test(value)) throw new Error('实例名只能包含小写字母、数字、下划线和短横线。');
  return value;
}

function validatePrefix(value) {
  if (!/^[a-z0-9][a-z0-9._:/-]*$/.test(value) || value.includes('..') || value.endsWith('/')) throw new Error('镜像前缀格式无效。');
  return value;
}

function port(value, fallback) {
  const text = String(value ?? fallback);
  if (!/^\d+$/.test(text) || Number(text) < 1 || Number(text) > 65535) throw new Error('端口必须在 1–65535 之间。');
  return text;
}

export function createManager({
  home = process.env.LEARNHUB_HOME || join(homedir(), '.learnhub'),
  packageVersion = '0.1.0',
  templatePath = fileURLToPath(new URL('../templates/compose.yml', import.meta.url)),
  runner = runDocker, log = console.log, clock = () => new Date(), openBrowser = defaultOpenBrowser,
} = {}) {
  home = resolve(home);
  const statePath = join(home, 'installation.json');
  const pendingPath = join(home, 'pending-update.json');
  const envPath = join(home, '.env');
  const lockPath = join(home, '.operation.lock');

  async function locked(operation) {
    await mkdir(home, { recursive: true, mode: 0o700 });
    const token = randomUUID();
    let handle;
    for (let retry = 0; retry < 2; retry++) {
      try { handle = await open(lockPath, 'wx', 0o600); break; }
      catch (error) {
        if (error.code !== 'EEXIST') throw error;
        let previous;
        try { previous = await readJson(lockPath); }
        catch { throw new Error('实例锁文件尚未写完或已损坏。请先确认没有 LearnHub 命令运行，再移除 .operation.lock。'); }
        if (!Number.isInteger(previous?.pid) || previous.pid < 1) throw new Error('实例锁记录无效，请确认没有其他命令运行后移除 .operation.lock。');
        let alive = true;
        try { process.kill(previous?.pid, 0); } catch (error) { if (error.code === 'ESRCH') alive = false; }
        if (alive || retry) throw new Error('另一个 LearnHub 命令正在操作此实例，请等它完成。');
        await rm(lockPath);
      }
    }
    await handle.writeFile(JSON.stringify({ pid: process.pid, token, startedAt: clock().toISOString() }));
    await handle.close();
    try { return await operation(); }
    finally { if ((await readJson(lockPath))?.token === token) await rm(lockPath); }
  }

  async function dockerReady() {
    try {
      await runner(['version', '--format', '{{.Server.Version}}']);
      const result = await runner(['compose', 'version', '--short']);
      const match = (result.stdout || '').trim().replace(/^v/, '').match(/^(\d+)\.(\d+)/);
      if (match && Number(match[1]) === 2 && Number(match[2]) < 20) throw new Error('Docker Compose 需要 2.20 或更新版本。');
    } catch (error) { throw new Error(`请先安装并启动 Docker Desktop / Docker Engine（含 Compose 2.20+）。\n${error.message}`); }
  }

  async function environment(state) {
    let values = parseEnv(await readFile(state.envFile || envPath, 'utf8'));
    // Legacy Compose defaults need not appear in its original .env. Adoption saves
    // the running database's credentials; these take precedence until first upgrade.
    if (state.legacy) values = { ...values, ...parseEnv(await readFile(envPath, 'utf8')) };
    if (!values.MYSQL_ROOT_PASSWORD) throw new Error('请在持久目录 .env 中设置 MYSQL_ROOT_PASSWORD。');
    if (!state.legacy) {
      for (const [key, value] of Object.entries(state.identity || {})) {
        if (values[key] && values[key] !== value) throw new Error(`配置 ${key} 与已安装实例不一致，请恢复原值以继续使用已有数据。`);
        values[key] = value;
      }
    }
    values.LEARNHUB_PROJECT_NAME = state.projectName;
    values.LEARNHUB_VERSION = state.version;
    values.LEARNHUB_IMAGE_PREFIX = state.imagePrefix;
    values.CONFIG_DIR = join(home, 'config').replaceAll('\\', '/');
    return values;
  }

  async function compose(state, args, options = {}) {
    const env = await environment(state);
    const composePath = isAbsolute(state.composeFile) ? state.composeFile : join(home, state.composeFile);
    return runner(['compose', '--project-name', state.projectName, '--env-file', state.envFile || envPath,
      '--file', composePath, ...args], { cwd: home, env, ...options });
  }

  function requireState(state) {
    if (!state) throw new Error('此目录还没有安装 LearnHub，请先执行 start 或 adopt --from <原部署目录>。');
    if (state.formatVersion !== 1) throw new Error('此实例使用了其他格式的安装记录，请使用匹配的 CLI。');
    return state;
  }

  async function requireNoPending() {
    const pending = await readJson(pendingPath);
    if (pending?.operation === 'backup') throw new Error(`上次备份未完成，请执行 backup 重试。备份：${pending.backupDir}。`);
    if (pending) throw new Error(`上次安装/升级 ${pending.target.version} 未完成。请重新执行同版本 update；旧应用不会自动连接已迁移的数据库。备份：${pending.backupDir || '首次安装，无旧数据'}。`);
  }

  async function releaseState(base, version) {
    validateVersion(version);
    const relative = join('releases', version, 'compose.yml');
    const target = join(home, relative);
    await mkdir(join(home, 'releases', version), { recursive: true });
    // A saved installed release never changes simply because npx fetched a newer CLI.
    try { await stat(target); }
    catch (error) { if (error.code !== 'ENOENT') throw error; await copyFile(templatePath, target); }
    return { ...base, version, legacy: false, composeFile: relative, envFile: envPath };
  }

  async function initialState(options) {
    const version = validateVersion(options.to || packageVersion);
    const projectName = validateProject(options.project || 'learn-hub');
    const existing = await runner(['ps', '-a', '--filter', `label=com.docker.compose.project=${projectName}`, '--format', '{{.ID}}']);
    if (existing.stdout.trim()) throw new Error(`检测到已有 ${projectName} 部署。请使用 adopt --from <原 deploy 目录> 接管，或用 --project 选择独立实例。`);
    let values;
    try { values = parseEnv(await readFile(envPath, 'utf8')); }
    catch (error) { if (error.code !== 'ENOENT') throw error; values = {}; }
    values = {
      MYSQL_ROOT_PASSWORD: randomBytes(24).toString('hex'), MYSQL_DATABASE: 'learn_hub', MYSQL_IMAGE: 'mysql:8.4',
      BIND_ADDRESS: '127.0.0.1', WEB_HOST_PORT: port(options.webPort, 8888), BACKEND_HOST_PORT: port(options.backendPort, 18080),
      MYSQL_HOST_PORT: port(options.mysqlPort, 3307), DEEPSEEK_API_KEY: '', ...values,
    };
    for (const key of ['WEB_HOST_PORT', 'BACKEND_HOST_PORT', 'MYSQL_HOST_PORT']) values[key] = port(values[key]);
    const identity = {
      MYSQL_VOLUME_NAME: values.MYSQL_VOLUME_NAME || `${projectName}_mysql-data`,
      UPLOADS_VOLUME_NAME: values.UPLOADS_VOLUME_NAME || `${projectName}_uploads`,
      SKILLS_VOLUME_NAME: values.SKILLS_VOLUME_NAME || `${projectName}_skills-data`,
      OLLAMA_VOLUME_NAME: values.OLLAMA_VOLUME_NAME || `${projectName}_ollama-models`,
      MYSQL_IMAGE: values.MYSQL_IMAGE,
    };
    const existingVolumes = new Set((await runner(['volume', 'ls', '--format', '{{.Name}}'])).stdout.trim().split(/\r?\n/));
    for (const volume of [identity.MYSQL_VOLUME_NAME, identity.UPLOADS_VOLUME_NAME, identity.SKILLS_VOLUME_NAME]) {
      if (existingVolumes.has(volume)) throw new Error(`数据卷 ${volume} 已存在，请使用 adopt 接管原部署，避免连接错误的数据。`);
    }
    const imagePrefix = validatePrefix(options.imagePrefix || IMAGE_PREFIX);
    await writeFile(envPath, encodeEnv({ ...values, ...identity, LEARNHUB_VERSION: version, LEARNHUB_PROJECT_NAME: projectName,
      LEARNHUB_IMAGE_PREFIX: imagePrefix }), { mode: 0o600 });
    await mkdir(join(home, 'config'), { recursive: true, mode: 0o700 });
    return releaseState({ formatVersion: 1, projectName, imagePrefix, identity,
      installedAt: clock().toISOString() }, version);
  }

  async function up(state) {
    await compose(state, ['up', '-d', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '240'], { inherit: true });
  }

  async function pull(state, includeDatabase, offline = false) {
    if (offline) {
      const env = await environment(state);
      await runner(['image', 'inspect', `${state.imagePrefix}-backend:${state.version}`, `${state.imagePrefix}-frontend:${state.version}`,
        ...(includeDatabase ? [env.MYSQL_IMAGE] : [])]);
      log(`使用本地 LearnHub ${state.version} 镜像。`);
      return;
    }
    log(`下载 LearnHub ${state.version} 镜像…`);
    await compose(state, ['pull', ...(includeDatabase ? ['mysql', 'backend', 'frontend'] : ['backend', 'frontend'])], { inherit: true });
  }

  async function backupFiles(state, backupDir) {
    await mkdir(backupDir, { recursive: true, mode: 0o700 });
    await compose(state, ['exec', '-T', 'mysql', 'sh', '-c', DUMP], { outputFile: join(backupDir, 'database.sql') });
    if ((await stat(join(backupDir, 'database.sql'))).size === 0) throw new Error('数据库备份为空，升级已停止。');
    await mkdir(join(backupDir, 'uploads'));
    await compose(state, ['cp', 'backend:/app/backend/uploads/.', join(backupDir, 'uploads')]);
    await mkdir(join(backupDir, 'skills'));
    await compose(state, ['cp', 'backend:/app/skills/.', join(backupDir, 'skills')]);
    await copyFile(envPath, join(backupDir, '.env'));
    await cp(join(home, 'config'), join(backupDir, 'config'), { recursive: true });
    await copyFile(isAbsolute(state.composeFile) ? state.composeFile : join(home, state.composeFile), join(backupDir, 'compose.yml'));
    await saveJson(join(backupDir, 'installation.json'), state);
    await saveJson(join(backupDir, 'backup.json'), { formatVersion: 1, version: state.version, createdAt: clock().toISOString(), consistent: true });
    return backupDir;
  }

  function newBackupDir() {
    return join(home, 'backups', clock().toISOString().replace(/[:.]/g, '-') + '-' + randomBytes(3).toString('hex'));
  }

  async function web(state, options) {
    const env = await environment(state);
    const url = `http://localhost:${port(env.WEB_HOST_PORT, 8888)}`;
    log(`LearnHub ${state.version} 已就绪：${url}`);
    if (!options.noOpen) openBrowser(url);
    return { ...state, url };
  }

  async function commit(state) {
    const values = parseEnv(await readFile(envPath, 'utf8'));
    // Preserve user config text and comments; replace only launcher-owned metadata.
    let text = await readFile(envPath, 'utf8');
    const metadata = { LEARNHUB_VERSION: state.version, LEARNHUB_IMAGE_PREFIX: state.imagePrefix, LEARNHUB_PROJECT_NAME: state.projectName };
    for (const [key, value] of Object.entries(metadata)) {
      const line = encodeEnv({ [key]: value }).trimEnd();
      const pattern = new RegExp(`^\\s*${key}\\s*=.*$`, 'gm');
      text = Object.hasOwn(values, key) ? text.replace(pattern, line) : text.trimEnd() + '\n' + line + '\n';
    }
    const temp = `${envPath}.${randomUUID()}.tmp`;
    await writeFile(temp, text, { mode: 0o600 });
    await rename(temp, envPath);
    await saveJson(statePath, state);
  }

  async function updateInternal(options = {}) {
    const state = await readJson(statePath);
    const pending = await readJson(pendingPath);
    const targetVersion = validateVersion(options.to || packageVersion);
    if (state) requireState(state);
    if (pending?.operation === 'backup') throw new Error('上次独立备份未完成，请执行 backup 重试。');
    if (!state && !pending) throw new Error('还没有已安装版本，请先执行 start。');
    if (pending && pending.target.version !== targetVersion) throw new Error(`请先完成上次 ${pending.target.version} 升级，备份：${pending.backupDir || '无旧数据'}。`);
    if (state && !state.legacy && compareVersion(targetVersion, state.version) < 0) throw new Error('数据库可能已升级，不能直接降级镜像。请按恢复文档恢复相应版本备份。');
    if (state && state.version === targetVersion && !pending) { log(`已安装 ${targetVersion}，无需更新。`); return state; }
    await dockerReady();
    const target = pending?.target || await releaseState(state, targetVersion);
    await pull(target, !state, options.offline);
    let record = pending;
    if (!record) {
      record = { formatVersion: 1, target, previous: state, stage: 'prepared', backupDir: state ? newBackupDir() : null, startedAt: clock().toISOString() };
      await saveJson(pendingPath, record);
    }
    try {
      if (state && record.stage === 'prepared') {
        // Interrupted partial dumps are retained, but never overwritten or used as a recovery backup.
        try {
          await stat(record.backupDir);
          record.incompleteBackups = [...(record.incompleteBackups || []), record.backupDir];
          record.backupDir = newBackupDir();
          await saveJson(pendingPath, record);
        } catch (error) { if (error.code !== 'ENOENT') throw error; }
        log('暂停写入，备份数据库、资料和技能…');
        await compose(state, ['stop', 'frontend', 'backend'], { inherit: true });
        await backupFiles(state, record.backupDir);
        record.stage = 'backed-up';
        await saveJson(pendingPath, record);
      }
      if (state?.legacy && record.stage === 'backed-up') {
        await compose(target, ['create', '--no-build', 'backend'], { inherit: true });
        await compose(target, ['cp', join(record.backupDir, 'skills') + '/.', 'backend:/app/skills']);
        record.stage = 'skills-preserved';
        await saveJson(pendingPath, record);
      }
      record.stage = 'starting';
      await saveJson(pendingPath, record);
      await up(target);
      const committed = { ...target, updatedAt: clock().toISOString(), lastBackup: record.backupDir };
      await commit(committed);
      await rm(pendingPath);
      return web(committed, options);
    } catch (error) {
      throw new Error(`安装/升级未完成：${error.message}\n记录已保留，可重新执行同版本 update。备份：${record.backupDir || '首次安装，无旧数据'}。不会自动回退数据库。`);
    }
  }

  return {
    home,
    start: (options = {}) => locked(async () => {
      await requireNoPending();
      if (options.to) validateVersion(options.to);
      await dockerReady();
      const state = await readJson(statePath);
      if (state) {
        requireState(state);
        if (options.to && options.to !== state.version) throw new Error('start 保持已安装版本，升级请使用 update。');
        if (options.project && options.project !== state.projectName) throw new Error('实例名已保存；多实例请使用不同 --home。');
        await up(state);
        return web(state, options);
      }
      const target = await initialState(options);
      await pull(target, true, options.offline);
      await saveJson(pendingPath, { formatVersion: 1, target, previous: null, stage: 'starting', backupDir: null, startedAt: clock().toISOString() });
      try {
        await up(target);
        await commit(target);
        await rm(pendingPath);
        return web(target, options);
      } catch (error) { throw new Error(`首次安装未完成，可执行 update --to ${target.version} 重试。${error.message}`); }
    }),
    update: (options = {}) => locked(() => updateInternal(options)),
    stop: () => locked(async () => {
      const pending = await readJson(pendingPath);
      const state = requireState(pending?.target || await readJson(statePath));
      await compose(state, ['stop'], { inherit: true });
      log('LearnHub 已停止，数据卷已保留。');
    }),
    status: async () => {
      const state = await readJson(statePath);
      const pending = await readJson(pendingPath);
      if (!state && !pending) { log(`尚未安装。持久目录：${home}`); return { installed: false }; }
      const active = pending?.target || requireState(state);
      log(`版本：${state?.version || '首次安装中'}；实例：${active.projectName}；目录：${home}`);
      if (pending) log(`未完成更新：${pending.target.version}（${pending.stage}），备份：${pending.backupDir || '无旧数据'}`);
      const result = await compose(active, ['ps', '-a'], { inherit: true });
      return { state, pending, ...result };
    },
    backup: () => locked(async () => {
      const pending = await readJson(pendingPath);
      if (pending && pending.operation !== 'backup') await requireNoPending();
      const state = requireState(await readJson(statePath));
      await dockerReady();
      const backupDir = pending?.stage === 'backed-up' ? pending.backupDir : newBackupDir();
      // The marker also protects against a process interruption during a standalone backup.
      const record = { formatVersion: 1, target: state, previous: state, stage: pending?.stage || 'prepared', backupDir, operation: 'backup' };
      await saveJson(pendingPath, record);
      try {
        if (record.stage === 'prepared') {
          await compose(state, ['stop', 'frontend', 'backend'], { inherit: true });
          await backupFiles(state, backupDir);
          record.stage = 'backed-up';
          await saveJson(pendingPath, record);
        }
        await up(state);
        await rm(pendingPath);
        log(`备份完成：${backupDir}`);
        return backupDir;
      } catch (error) {
        throw new Error(`备份未完成，请执行 backup 重试。备份目录：${backupDir}。${error.message}`);
      }
    }),
    adopt: ({ from, project = 'learn-hub' } = {}) => locked(async () => {
      await requireNoPending();
      if (await readJson(statePath)) throw new Error('此目录已有安装记录，不能重复接管。');
      if (!from) throw new Error('请用 --from 指定已有部署的 deploy 目录。');
      await dockerReady();
      const projectName = validateProject(project);
      const source = resolve(from);
      const sourceCompose = join(source, 'docker-compose.yml');
      const sourceEnv = join(source, '.env');
      const sourceValues = parseEnv(await readFile(sourceEnv, 'utf8'));
      const args = ['compose', '--project-name', projectName, '--env-file', sourceEnv, '--file', sourceCompose];
      const normalized = JSON.parse((await runner([...args, '--profile', '*', 'config', '--format', 'json'], { env: sourceValues, cwd: source })).stdout);
      const inspected = {};
      for (const service of ['mysql', 'backend']) {
        const id = (await runner([...args, 'ps', '-a', '-q', service], { env: sourceValues, cwd: source })).stdout.trim();
        if (!id || id.includes('\n')) throw new Error(`原部署缺少唯一的 ${service} 容器，请先确认源部署可用。`);
        const data = JSON.parse((await runner(['inspect', id])).stdout)[0];
        if (data.Config.Labels['com.docker.compose.project'] !== projectName || data.Config.Labels['com.docker.compose.service'] !== service) throw new Error('原容器不属于指定 Compose 实例。');
        inspected[service] = data;
      }
      function volume(service, destination) {
        const mount = inspected[service].Mounts.find(item => item.Destination === destination);
        if (!mount || mount.Type !== 'volume' || !mount.Name) throw new Error(`原 ${destination} 不是 Docker 数据卷，请先按迁移文档处理。`);
        return mount.Name;
      }
      const identity = {
        MYSQL_IMAGE: inspected.mysql.Image,
        MYSQL_VOLUME_NAME: volume('mysql', '/var/lib/mysql'),
        UPLOADS_VOLUME_NAME: volume('backend', '/app/backend/uploads'),
        SKILLS_VOLUME_NAME: `${projectName}_skills-data`,
        OLLAMA_VOLUME_NAME: normalized.volumes?.['ollama-models']?.name || `${projectName}_ollama-models`,
      };
      const runningDatabaseEnv = Object.fromEntries((inspected.mysql.Config.Env || []).map(entry => {
        const index = entry.indexOf('=');
        return [entry.slice(0, index), entry.slice(index + 1)];
      }));
      const values = { BIND_ADDRESS: '127.0.0.1', WEB_HOST_PORT: '8888', BACKEND_HOST_PORT: '18080', MYSQL_HOST_PORT: '3307',
        ...sourceValues, ...identity,
        MYSQL_ROOT_PASSWORD: runningDatabaseEnv.MYSQL_ROOT_PASSWORD,
        MYSQL_DATABASE: runningDatabaseEnv.MYSQL_DATABASE || normalized.services.mysql.environment.MYSQL_DATABASE,
        MYSQL_CONTAINER_NAME: inspected.mysql.Name.replace(/^\//, ''),
        BACKEND_CONTAINER_NAME: inspected.backend.Name.replace(/^\//, ''),
        ...(normalized.services.ollama ? { OLLAMA_IMAGE: normalized.services.ollama.image } : {}),
      };
      if (!values.MYSQL_ROOT_PASSWORD) throw new Error('无法获取原数据库配置，接管已停止。');
      await writeFile(envPath, encodeEnv(values), { mode: 0o600 });
      await mkdir(join(home, 'config'), { recursive: true, mode: 0o700 });
      const state = { formatVersion: 1, version: 'source', legacy: true, projectName, imagePrefix: IMAGE_PREFIX, identity,
        composeFile: sourceCompose, envFile: sourceEnv, installedAt: clock().toISOString() };
      await saveJson(statePath, state);
      log(`已记录原部署（未重启服务）：${projectName}。数据卷 ${identity.MYSQL_VOLUME_NAME} / ${identity.UPLOADS_VOLUME_NAME}。执行 update 切换到发布版。`);
      return state;
    }),
    doctor: async () => { await dockerReady(); log(`Docker 可用。持久目录：${home}`); },
  };
}
