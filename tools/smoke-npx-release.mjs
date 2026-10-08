// Run only with locally built, disposable smoke images. Never adopts an existing instance.
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { resolve, join, dirname } from 'node:path';
import { createServer } from 'node:net';
import { randomUUID, createHash } from 'node:crypto';
import { createManager } from '../cli/src/manager.mjs';
import { runDocker } from '../cli/src/process.mjs';

async function freePort() {
  const server = createServer();
  await new Promise((resolve, reject) => server.once('error', reject).listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}

const imagePrefix = process.env.LEARNHUB_SMOKE_IMAGE_PREFIX || 'learnhub-npx-smoke';
const version = process.env.LEARNHUB_SMOKE_FROM || '0.1.0';
const nextVersion = process.env.LEARNHUB_SMOKE_TO || '0.1.1';
const mysqlImage = (await runDocker(['image', 'inspect', process.env.LEARNHUB_SMOKE_MYSQL_IMAGE || 'mysql:8.4', '--format', '{{.Id}}'])).stdout.trim();
const home = await mkdtemp(join(tmpdir(), 'learnhub-npx-smoke-'));
const project = 'learnhub-npx-smoke-' + randomUUID().slice(0, 8);
const ports = [await freePort(), await freePort(), await freePort()];
await writeFile(join(home, '.env'), `MYSQL_IMAGE='${mysqlImage}'\n`, { mode: 0o600 });
const manager = (packageVersion = version) => createManager({ home, packageVersion, log: console.log });
const api = `http://127.0.0.1:${ports[1]}`;
async function request(path, options) {
  const response = await fetch(api + path, options);
  const json = await response.json();
  assert.ok(response.ok && json.code === 200, JSON.stringify(json));
  return json.data;
}

let completed = false;
try {
  await manager().start({ project, imagePrefix, webPort: ports[0], backendPort: ports[1], mysqlPort: ports[2], offline: true, noOpen: true });
  const note = await request('/api/notes', { method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ title: 'npx 升级保留测试', content: '# 中文笔记\n\n升级前后原样保留。', tagIds: [] }) });
  const uploadedText = '原始资料与中文内容必须在更新后完整保留。\n';
  const form = new FormData();
  form.append('file', new Blob([uploadedText], { type: 'text/plain' }), 'npx-smoke.txt');
  const file = await request('/api/files/upload', { method: 'POST', body: form });
  await runDocker(['exec', `${project}-backend`, 'sh', '-c', 'printf "%s" "user-edited-skill" > /app/skills/npx-smoke-marker.txt']);
  await writeFile(join(home, 'config', 'smoke-marker.txt'), 'persistent external config');
  await manager(nextVersion).start({ noOpen: true });
  assert.equal(JSON.parse(await readFile(join(home, 'installation.json'), 'utf8')).version, version);
  await manager(nextVersion).update({ offline: true, noOpen: true });
  const current = JSON.parse(await readFile(join(home, 'installation.json'), 'utf8'));
  assert.equal(current.version, nextVersion);
  const preserved = await request(`/api/notes/${note.id}`);
  assert.equal(preserved.content, note.content);
  const downloaded = await fetch(api + `/api/files/${file.id}/download`);
  assert.equal(await downloaded.text(), uploadedText);
  const skill = (await runDocker(['exec', `${project}-backend`, 'cat', '/app/skills/npx-smoke-marker.txt'])).stdout;
  assert.equal(skill, 'user-edited-skill');
  const backupSql = await readFile(join(current.lastBackup, 'database.sql'));
  assert.ok(backupSql.toString('utf8').includes('npx 升级保留测试'));
  assert.equal(await readFile(join(current.lastBackup, 'config', 'smoke-marker.txt'), 'utf8'), 'persistent external config');
  const htmlResponse = await fetch(`http://127.0.0.1:${ports[0]}/`);
  assert.equal(htmlResponse.status, 200);
  const html = await htmlResponse.text();
  const script = html.match(/<script[^>]+src="([^"]+)"/);
  assert.ok(script, 'built Vue entry point exists');
  assert.equal((await fetch(`http://127.0.0.1:${ports[0]}${script[1]}`)).status, 200);
  await manager(nextVersion).backup();
  await manager(nextVersion).stop();
  await manager(nextVersion).start({ noOpen: true });
  assert.equal((await request(`/api/notes/${note.id}`)).content, note.content);
  const report = { passed: true, checkedAt: new Date().toISOString(), initialVersion: version, upgradedVersion: nextVersion,
    newLauncherKeepsInstalledVersion: true, notePreserved: true, originalFilePreserved: true, editedSkillPreserved: true,
    externalConfigBackedUp: true, databaseBackupBytes: backupSql.length, databaseBackupSha256: createHash('sha256').update(backupSql).digest('hex'),
    backupStopStartPassed: true, frontendServesBuiltAssets: true };
  const reportDir = resolve('output', 'npx');
  await mkdir(reportDir, { recursive: true });
  await writeFile(join(reportDir, 'docker-smoke-result.json'), JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify(report, null, 2));
  completed = true;
} finally {
  // The project ID was generated in this script. It cannot name a user's existing project.
  assert.match(project, /^learnhub-npx-smoke-[0-9a-f]{8}$/);
  const state = JSON.parse(await readFile(join(home, 'installation.json'), 'utf8').catch(() => 'null'))
    || JSON.parse(await readFile(join(home, 'pending-update.json'), 'utf8').catch(() => 'null'))?.target;
  if (state) {
    assert.equal(state.projectName, project);
    const composeFile = resolve(home, state.composeFile);
    await runDocker(['compose', '-p', project, '--env-file', join(home, '.env'), '-f', composeFile,
      'down', '--volumes', '--remove-orphans'], { env: { LEARNHUB_VERSION: state.version, LEARNHUB_IMAGE_PREFIX: imagePrefix,
        LEARNHUB_PROJECT_NAME: project, CONFIG_DIR: join(home, 'config').replaceAll('\\', '/') }, inherit: true });
  }
  if (completed) {
    assert.equal(dirname(home), resolve(tmpdir()));
    assert.ok(home.startsWith(join(resolve(tmpdir()), 'learnhub-npx-smoke-')));
    await rm(home, { recursive: true, force: true });
  } else console.error(`隔离验证失败，诊断目录保留：${home}`);
}
