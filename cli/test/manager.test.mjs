import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { createManager } from '../src/manager.mjs';

const templatePath = fileURLToPath(new URL('../../deploy/docker-compose.release.yml', import.meta.url));

// No Docker daemon is used. These fixtures exercise persisted state and the
// ordering of destructive steps, including failures between those steps.
async function fixture(t, version = '1.0.0') {
  const directory = await mkdtemp(path.join(tmpdir(), 'learnhub-cli-test-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const home = path.join(directory, 'instance');
  const calls = [];
  let fail;
  let hook;
  let existingVolumes = [];
  let sequence = 0;

  const inspect = (service) => ({
    Id: `${service}-id`,
    Image: service === 'mysql' ? 'sha256:fixture-mysql-image' : 'sha256:fixture-backend-image',
    Name: `/learn-hub-${service}`,
    Config: {
      Image: service === 'mysql' ? 'mysql:8.0.40' : `learn-hub-${service}:local`,
      Env: service === 'mysql' ? ['MYSQL_ROOT_PASSWORD=fixture-secret', 'MYSQL_DATABASE=learn_hub'] : [],
      Labels: { 'com.docker.compose.project': 'learn-hub', 'com.docker.compose.service': service },
    },
    State: { Status: 'running', Health: { Status: 'healthy' } },
    Mounts: service === 'mysql'
      ? [{ Type: 'volume', Name: 'existing_mysql', Destination: '/var/lib/mysql' }]
      : [{ Type: 'volume', Name: 'existing_uploads', Destination: '/app/backend/uploads' }],
    NetworkSettings: { Ports: {} },
  });

  const runner = async (args, options = {}) => {
    calls.push({ args: [...args], options });
    await hook?.(args, options);
    if (fail?.(args, options)) throw new Error('simulated Docker failure');
    if (options.outputFile) {
      await mkdir(path.dirname(options.outputFile), { recursive: true });
      await writeFile(options.outputFile, Buffer.from('-- fixture backup\nINSERT INTO note VALUES (1,\'中文\');\n'), { flag: 'wx' });
    }
    if (args[0] === 'inspect') {
      const service = args.some((value) => String(value).includes('mysql')) ? 'mysql' : 'backend';
      return { stdout: JSON.stringify([inspect(service)]), stderr: '' };
    }
    if (args[0] === 'volume' && args[1] === 'ls') return { stdout: existingVolumes.join('\n'), stderr: '' };
    if (args.includes('cp')) {
      const destination = args.at(-1);
      const source = args.at(-2);
      if (!destination.startsWith('backend:')) {
        await mkdir(destination, { recursive: true });
        await writeFile(path.join(destination, source.includes('/skills') ? 'SKILL.md' : 'fixture.pdf'), 'fixture data');
      }
    }
    if (args[0] === 'compose' && args.includes('config')) {
      return { stdout: JSON.stringify({ services: { mysql: { environment: {
        MYSQL_ROOT_PASSWORD: options.env?.MYSQL_ROOT_PASSWORD || 'fixture-secret', MYSQL_DATABASE: options.env?.MYSQL_DATABASE || 'learn_hub',
      } } }, volumes: { 'ollama-models': { name: 'existing_ollama' } } }), stderr: '' };
    }
    if (args[0] === 'compose' && args.includes('ps')) {
      if (args.includes('-q') || args.includes('--quiet')) {
        const service = args.at(-1);
        return { stdout: `${service}-id\n`, stderr: '' };
      }
      return { stdout: JSON.stringify([{ Service: 'backend', State: 'running', Health: 'healthy' }]), stderr: '' };
    }
    if (args.includes('version')) return { stdout: '2.35.0\n', stderr: '' };
    return { stdout: '', stderr: '' };
  };

  const manager = (packageVersion = version) => createManager({
    home,
    packageVersion,
    templatePath,
    runner,
    log: () => {},
    clock: () => new Date(1750000000000 + sequence++ * 1000),
    openBrowser: async () => {},
  });
  const env = () => readFile(path.join(home, '.env'), 'utf8');
  const appliedVersion = () => calls.findLast((call) => composeCommand(call, 'up'))?.options.env.LEARNHUB_VERSION;
  const state = async () => JSON.parse(await readFile(path.join(home, 'installation.json'), 'utf8'));
  const setFailure = (predicate) => { fail = predicate; };
  const setRunnerHook = (callback) => { hook = callback; };
  const setExistingVolumes = (names) => { existingVolumes = names; };
  return { directory, home, calls, manager, env, state, setFailure, setRunnerHook, setExistingVolumes, appliedVersion };
}

function composeCommand(call, command) {
  return call.args[0] === 'compose' && call.args.includes(command);
}

async function legacyDeployment(f, environment) {
  const from = path.join(f.directory, 'legacy', 'deploy');
  await mkdir(from, { recursive: true });
  await writeFile(path.join(from, 'docker-compose.yml'), 'name: learn-hub\nservices:\n  mysql:\n    image: mysql:8\n  backend:\n    image: learn-hub-backend:local\n  frontend:\n    image: learn-hub-frontend:local\n');
  await writeFile(path.join(from, '.env'), environment);
  return from;
}

test('a newer launcher starts an existing installation at its installed version', async (t) => {
  const f = await fixture(t);
  await f.manager().start({ noOpen: true });
  const before = await f.state();
  const environment = await f.env();
  f.calls.length = 0;
  await f.manager('1.1.0').start({ noOpen: true });
  assert.deepEqual(await f.state(), before);
  assert.equal(await f.env(), environment);
  assert.equal(f.appliedVersion(), '1.0.0');
  assert.ok(f.calls.some((call) => composeCommand(call, 'up')));
  assert.ok(!f.calls.some((call) => call.args.includes('1.1.0')));
});

test('update pulls before downtime and backs up before running the new release', async (t) => {
  const f = await fixture(t);
  await f.manager().start({ noOpen: true });
  f.calls.length = 0;
  await f.manager('1.1.0').update({ to: '1.1.0', noOpen: true });
  const pull = f.calls.findIndex((call) => composeCommand(call, 'pull'));
  const stop = f.calls.findIndex((call) => composeCommand(call, 'stop'));
  const dump = f.calls.findIndex((call) => call.options.outputFile);
  const copy = f.calls.findIndex((call) => call.args.includes('cp'));
  const up = f.calls.findIndex((call) => composeCommand(call, 'up'));
  assert.ok(pull >= 0 && pull < stop, 'images must be available before stopping the application');
  assert.ok(stop < dump && dump < up, 'the stopped application is backed up before migration');
  assert.ok(copy > dump && copy < up, 'uploaded files are copied before the new backend starts');
  const sql = await readFile(f.calls[dump].options.outputFile, 'utf8');
  assert.match(sql, /中文/);
  assert.equal(f.appliedVersion(), '1.1.0');
  assert.equal((await f.state()).version, '1.1.0');
  await assert.rejects(readFile(path.join(f.home, 'pending-update.json')), { code: 'ENOENT' });
  assert.ok(!f.calls.some((call) => call.args.includes('down') || call.args.includes('--volumes')));
});

test('a failed health check preserves the installed state and blocks start until the update is retried', async (t) => {
  const f = await fixture(t);
  await f.manager().start({ noOpen: true });
  const before = await f.state();
  f.setFailure((args) => args[0] === 'compose' && args.includes('up'));
  await assert.rejects(f.manager('1.1.0').update({ to: '1.1.0', noOpen: true }), /simulated Docker failure/);
  assert.deepEqual(await f.state(), before, 'success is only recorded after the health check');
  const pending = await readFile(path.join(f.home, 'pending-update.json'), 'utf8');
  assert.match(pending, /1\.1\.0/);
  const backupEntries = await readdir(path.join(f.home, 'backups'));
  assert.ok(backupEntries.length > 0, 'the failed release keeps its recovery backup');
  f.calls.length = 0;
  await assert.rejects(f.manager().start({ noOpen: true }));
  assert.ok(!f.calls.some((call) => composeCommand(call, 'up')), 'start cannot restart the old backend against a changed schema');
  f.setFailure(undefined);
  f.calls.length = 0;
  await assert.rejects(f.manager('1.2.0').update({ to: '1.2.0', noOpen: true }));
  assert.ok(!f.calls.some((call) => composeCommand(call, 'up')), 'a pending migration must not switch targets');
  await f.manager('1.1.0').update({ to: '1.1.0', noOpen: true });
  await assert.rejects(readFile(path.join(f.home, 'pending-update.json')), { code: 'ENOENT' });
  assert.equal(f.appliedVersion(), '1.1.0');
  assert.equal((await f.state()).version, '1.1.0');
});

test('a failed database backup blocks deployment and retry preserves the incomplete backup', async (t) => {
  const f = await fixture(t);
  await f.manager().start({ noOpen: true });
  const before = await f.state();
  f.calls.length = 0;
  f.setFailure((_args, options) => Boolean(options.outputFile));
  await assert.rejects(f.manager('1.1.0').update({ to: '1.1.0', noOpen: true }));
  assert.deepEqual(await f.state(), before);
  assert.ok(f.calls.some((call) => composeCommand(call, 'stop')));
  assert.ok(!f.calls.some((call) => composeCommand(call, 'up')));
  const pending = JSON.parse(await readFile(path.join(f.home, 'pending-update.json'), 'utf8'));
  assert.equal(pending.target.version, '1.1.0');
  f.setFailure(undefined);
  await f.manager('1.1.0').update({ to: '1.1.0', noOpen: true });
  const backup = (await f.state()).lastBackup;
  assert.notEqual(backup, pending.backupDir, 'the interrupted backup is retained and a fresh complete backup is made');
  await readdir(pending.backupDir);
  assert.equal(JSON.parse(await readFile(path.join(backup, 'backup.json'), 'utf8')).consistent, true);
});

test('a failed image pull keeps the running installation usable', async (t) => {
  const f = await fixture(t);
  await f.manager().start({ noOpen: true });
  const before = await f.state();
  const environment = await f.env();
  f.calls.length = 0;
  f.setFailure((args) => args[0] === 'compose' && args.includes('pull'));
  await assert.rejects(f.manager('1.1.0').update({ to: '1.1.0', noOpen: true }));
  assert.deepEqual(await f.state(), before);
  assert.equal(await f.env(), environment);
  assert.ok(!f.calls.some((call) => composeCommand(call, 'stop')));
  await assert.rejects(readFile(path.join(f.home, 'pending-update.json')), { code: 'ENOENT' });
});

test('stop retains persistent volumes and invalid versions never reach Docker', async (t) => {
  const f = await fixture(t);
  await f.manager().start({ noOpen: true });
  f.calls.length = 0;
  await f.manager().stop();
  assert.ok(f.calls.some((call) => composeCommand(call, 'stop')));
  assert.ok(!f.calls.some((call) => call.args.includes('down') || call.args.includes('--volumes')));
  f.calls.length = 0;
  for (const to of ['../../outside', '01.2.3', '99999999999999999999999.0.0', '1.0.0-beta', 'latest']) {
    await assert.rejects(f.manager().update({ to }));
  }
  assert.equal(f.calls.length, 0);
});

test('adoption records existing data volumes and database image without upgrading services', async (t) => {
  const f = await fixture(t);
  const from = await legacyDeployment(f, 'MYSQL_ROOT_PASSWORD=fixture-secret\nMYSQL_DATABASE=learn_hub\nWEB_HOST_PORT=8899\nDEEPSEEK_API_KEY=keep-this-value\n');
  await f.manager().adopt({ from, project: 'learn-hub' });
  const environment = await f.env();
  assert.match(environment, /MYSQL_IMAGE=['"]?sha256:fixture-mysql-image/);
  assert.match(environment, /MYSQL_VOLUME_NAME=['"]?existing_mysql/);
  assert.match(environment, /UPLOADS_VOLUME_NAME=['"]?existing_uploads/);
  assert.match(environment, /WEB_HOST_PORT=['"]?8899/);
  assert.match(environment, /DEEPSEEK_API_KEY=['"]?keep-this-value/);
  assert.ok(!f.calls.some((call) => composeCommand(call, 'up') || composeCommand(call, 'pull') || composeCommand(call, 'stop')));
  assert.ok(JSON.stringify(await f.state()).includes(path.join(from, 'docker-compose.yml').replaceAll('\\', '\\\\')));
});

test('adoption supports original deployments that use Compose database defaults', async (t) => {
  const f = await fixture(t);
  const from = await legacyDeployment(f, 'DEEPSEEK_API_KEY=keep-this-value\n');
  await f.manager().adopt({ from, project: 'learn-hub' });
  await f.manager().status();
  f.calls.length = 0;
  await f.manager().update({ to: '1.0.0', noOpen: true });
  assert.equal((await f.state()).version, '1.0.0');
  assert.match(await f.env(), /MYSQL_ROOT_PASSWORD=['"]?fixture-secret/);
  assert.ok(f.calls.some((call) => composeCommand(call, 'create')));
  const restore = f.calls.find((call) => call.args.includes('cp') && call.args.at(-1) === 'backend:/app/skills');
  assert.ok(restore.args.at(-2).endsWith('/.'), 'legacy skill contents are restored directly into the skills volume');
});

test('adoption preserves the running database credentials if the source env was edited later', async (t) => {
  const f = await fixture(t);
  const from = await legacyDeployment(f, 'MYSQL_ROOT_PASSWORD=edited-after-container-start\nMYSQL_DATABASE=another_database\n');
  await f.manager().adopt({ from, project: 'learn-hub' });
  assert.match(await f.env(), /MYSQL_ROOT_PASSWORD=['"]?fixture-secret/);
  assert.match(await f.env(), /MYSQL_DATABASE=['"]?learn_hub/);
});

test('two simultaneous commands cannot mutate the same installation', async (t) => {
  const f = await fixture(t);
  let release;
  let entered;
  const blocked = new Promise((resolve) => { release = resolve; });
  const inProgress = new Promise((resolve) => { entered = resolve; });
  f.setRunnerHook(async (args) => {
    if (args[0] === 'compose' && args.includes('up')) {
      entered();
      await blocked;
    }
  });
  const first = f.manager().start({ noOpen: true });
  await inProgress;
  try {
    const previousCalls = f.calls.length;
    await assert.rejects(f.manager().start({ noOpen: true }), /另一个 LearnHub 命令/);
    assert.equal(f.calls.length, previousCalls, 'the competing command cannot reach Docker');
  } finally { release(); }
  await first;
  assert.equal((await f.state()).version, '1.0.0');
});

test('a failed standalone backup can be retried without starting a different release', async (t) => {
  const f = await fixture(t);
  await f.manager().start({ noOpen: true });
  const before = await f.state();
  f.setFailure((_args, options) => Boolean(options.outputFile));
  await assert.rejects(f.manager().backup());
  const pendingPath = path.join(f.home, 'pending-update.json');
  const failed = JSON.parse(await readFile(pendingPath, 'utf8'));
  assert.equal(failed.operation, 'backup');
  await assert.rejects(f.manager('1.1.0').update({ to: '1.1.0' }), /backup/);
  await assert.rejects(f.manager().start({ noOpen: true }));
  f.setFailure(undefined);
  const saved = await f.manager('1.1.0').backup();
  assert.notEqual(saved, failed.backupDir, 'the partial backup is retained instead of being overwritten');
  assert.deepEqual(await f.state(), before);
  assert.equal(f.appliedVersion(), '1.0.0');
  assert.equal(JSON.parse(await readFile(path.join(saved, 'backup.json'), 'utf8')).consistent, true);
  await assert.rejects(readFile(pendingPath), { code: 'ENOENT' });
});

test('retrying backup after restart failure reuses the finished backup without dumping again', async (t) => {
  const f = await fixture(t);
  await f.manager().start({ noOpen: true });
  f.setFailure((args) => args[0] === 'compose' && args.includes('up'));
  await assert.rejects(f.manager().backup());
  const record = JSON.parse(await readFile(path.join(f.home, 'pending-update.json'), 'utf8'));
  assert.equal(record.stage, 'backed-up');
  f.setFailure(undefined);
  f.calls.length = 0;
  assert.equal(await f.manager().backup(), record.backupDir);
  assert.ok(!f.calls.some((call) => call.options.outputFile), 'a completed recovery backup must not be overwritten');
  assert.ok(f.calls.some((call) => composeCommand(call, 'up')));
});

test('an interrupted first installation is completed by updating to the same version', async (t) => {
  const f = await fixture(t);
  f.setFailure((args) => args[0] === 'compose' && args.includes('up'));
  await assert.rejects(f.manager().start({ noOpen: true }));
  await assert.rejects(f.state(), { code: 'ENOENT' });
  const environment = await f.env();
  f.setFailure(undefined);
  f.calls.length = 0;
  await f.manager().update({ to: '1.0.0', noOpen: true });
  assert.equal((await f.state()).version, '1.0.0');
  assert.equal(await f.env(), environment, 'retry reuses the original generated credentials');
  assert.ok(!f.calls.some((call) => call.options.outputFile), 'a new installation has no prior database to back up');
  await assert.rejects(readFile(path.join(f.home, 'pending-update.json')), { code: 'ENOENT' });
});

test('changing data volume identity or downgrading cannot restart the application', async (t) => {
  const f = await fixture(t, '1.1.0');
  await f.manager().start({ noOpen: true });
  f.calls.length = 0;
  await assert.rejects(f.manager().update({ to: '1.0.0', noOpen: true }), /降级/);
  assert.equal(f.calls.length, 0);
  const environment = await f.env();
  await writeFile(path.join(f.home, '.env'), environment.replace(/^MYSQL_VOLUME_NAME=.*$/m, "MYSQL_VOLUME_NAME='other-database'"));
  await assert.rejects(f.manager().start({ noOpen: true }), /MYSQL_VOLUME_NAME/);
  assert.ok(!f.calls.some((call) => composeCommand(call, 'up')));
  assert.equal((await f.state()).version, '1.1.0');
});

test('update preserves user credentials, comments, AI settings and external configuration', async (t) => {
  const f = await fixture(t);
  await f.manager().start({ noOpen: true });
  const environment = await f.env();
  const userSettings = "# user annotation\nDEEPSEEK_API_KEY='sk-fixture$literal'\nKB_EMBED_BASE_URL='http://host.docker.internal:11434'\nKB_EMBED_MODEL='custom-embed'\n";
  const changed = environment.replace(/^DEEPSEEK_API_KEY=.*$/m, '') + userSettings;
  await writeFile(path.join(f.home, '.env'), changed);
  await writeFile(path.join(f.home, 'config', 'application.yml'), 'learnhub:\n  fixture: retained\n');
  await f.manager('1.1.0').update({ to: '1.1.0', noOpen: true });
  const saved = await f.env();
  assert.ok(saved.includes(userSettings));
  const password = environment.match(/^MYSQL_ROOT_PASSWORD=.*$/m)[0];
  assert.ok(saved.includes(password));
  const backup = (await f.state()).lastBackup;
  assert.equal(await readFile(path.join(backup, '.env'), 'utf8'), changed);
  assert.match(await readFile(path.join(backup, 'config', 'application.yml'), 'utf8'), /retained/);
  assert.equal(await readFile(path.join(backup, 'uploads', 'fixture.pdf'), 'utf8'), 'fixture data');
  assert.equal(await readFile(path.join(backup, 'skills', 'SKILL.md'), 'utf8'), 'fixture data');
});

test('first installation refuses an existing data volume and stops if volume discovery fails', async (t) => {
  const f = await fixture(t);
  f.setExistingVolumes(['learn-hub_mysql-data']);
  await assert.rejects(f.manager().start({ noOpen: true }), /数据卷.*已存在/);
  assert.ok(!f.calls.some((call) => composeCommand(call, 'pull') || composeCommand(call, 'up')));
  await assert.rejects(f.env(), { code: 'ENOENT' });
  f.setExistingVolumes([]);
  f.setFailure((args) => args[0] === 'volume' && args[1] === 'ls');
  f.calls.length = 0;
  await assert.rejects(f.manager().start({ noOpen: true }), /simulated Docker failure/);
  assert.ok(!f.calls.some((call) => composeCommand(call, 'pull') || composeCommand(call, 'up')));
  await assert.rejects(f.state(), { code: 'ENOENT' });
});

test('offline installation and update check local images without pulling', async (t) => {
  const f = await fixture(t);
  await f.manager().start({ imagePrefix: 'learnhub-fixture', offline: true, noOpen: true });
  await f.manager('1.1.0').update({ to: '1.1.0', offline: true, noOpen: true });
  assert.ok(!f.calls.some((call) => composeCommand(call, 'pull')));
  assert.ok(f.calls.some((call) => call.args[0] === 'image' && call.args.includes('learnhub-fixture-backend:1.0.0') && call.args.includes('mysql:8.4')));
  assert.ok(f.calls.some((call) => call.args[0] === 'image' && call.args.includes('learnhub-fixture-frontend:1.1.0')));
  assert.equal(f.appliedVersion(), '1.1.0');
});
