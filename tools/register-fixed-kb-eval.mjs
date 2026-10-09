// Register a local fixed suite as isolated disabled rows; never edit existing cases.
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { pathToFileURL } from 'node:url';

export const sqlText = value => `CONVERT(0x${Buffer.from(String(value), 'utf8').toString('hex')} USING utf8mb4)`;
export const markerFor = (suite, item) => `fixed-suite:${suite.id}:${suite.version}:${item.id}`;
export function buildSql(suite) {
  if (!suite.id || !suite.version || !suite.cases?.length) throw Error('Suite id/version/cases required');
  if (new Set(suite.cases.map(c => c.question)).size !== suite.cases.length) throw Error('Duplicate question');
  if (new Set(suite.cases.map(c => c.id)).size !== suite.cases.length) throw Error('Duplicate case id');
  const lines = ['SET NAMES utf8mb4;', 'START TRANSACTION;'];
  for (const item of suite.cases) {
    if (!item.question || item.question.length > 500) throw Error(`Invalid question ${item.id}`);
    const refs = [...new Set((item.evidenceGroups || []).flatMap(g => g.alternatives.map(a => a.ref)))].join('|') || 'none';
    if (refs.length > 500) throw Error(`Too many reference labels ${item.id}`);
    const marker = markerFor(suite, item);
    if (marker.length > 255) throw Error('Registration marker too long');
    // Duplicate questions are left untouched. The caller checks exact ownership below.
    lines.push(`INSERT INTO rag_eval(question,expect_refs,expect_words,note,enabled) SELECT ${sqlText(item.question)},${sqlText(refs)},NULL,${sqlText(marker)},0 WHERE NOT EXISTS (SELECT 1 FROM rag_eval WHERE question=${sqlText(item.question)});`);
  }
  lines.push('COMMIT;');
  lines.push(`SELECT JSON_OBJECT('id',id,'question',question,'expectRefs',expect_refs,'note',note,'enabled',enabled) FROM rag_eval WHERE question IN (${suite.cases.map(c => sqlText(c.question)).join(',')}) ORDER BY id;`);
  return lines.join('\n');
}

export function validateRegistration(suite, rows) {
  for (const item of suite.cases) {
    const row = rows.find(r => r.question === item.question);
    if (!row || row.note !== markerFor(suite, item) || row.enabled !== 0) throw Error(`Question ${item.id} belongs to an existing case or registration failed; existing row was not changed`);
    const expected = [...new Set((item.evidenceGroups || []).flatMap(g => g.alternatives.map(a => a.ref)))].join('|') || 'none';
    if (row.expectRefs !== expected) throw Error(`Labels changed for ${item.id}; create a new suite version`);
    if (item.dbCaseId && item.dbCaseId !== row.id) throw Error(`Database id changed for ${item.id}`);
  }
  return rows;
}

async function main() {
  const argv = process.argv.slice(2);
  const at = key => { const i = argv.indexOf(key); return i < 0 ? null : argv[i + 1]; };
  const suitePath = at('--suite');
  if (!suitePath) throw Error('Usage: node tools/register-fixed-kb-eval.mjs --suite local-suite.json [--container learn-hub-mysql] [--database learn_hub]');
  const suite = JSON.parse(fs.readFileSync(suitePath, 'utf8').replace(/^\uFEFF/, ''));
  const container = at('--container') || 'learn-hub-mysql';
  const database = at('--database') || 'learn_hub';
  if (!/^[A-Za-z0-9_-]+$/.test(database)) throw Error('Invalid database name');
  const args = ['exec', '-i', container, 'sh', '-c', `MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B -D ${database}`];
  const result = spawnSync('docker', args, { input: buildSql(suite), encoding: 'utf8', maxBuffer: 4 * 1024 * 1024 });
  if (result.status !== 0) throw Error(`Registration failed (${result.status}); inspect local database availability`);
  const rows = result.stdout.trim().split(/\r?\n/).filter(Boolean).map(line => JSON.parse(line));
  validateRegistration(suite, rows);
  if (suite.cases.every(item => item.dbCaseId === rows.find(r => r.question === item.question).id)) {
    console.log(JSON.stringify({ registered: rows.length, suite: path.resolve(suitePath), disabled: true, existingCasesEdited: false, alreadyRegistered: true }));
    return;
  }
  const beforeSha = createHash('sha256').update(fs.readFileSync(suitePath)).digest('hex');
  for (const item of suite.cases) item.dbCaseId = rows.find(r => r.question === item.question).id;
  suite.registration = { registeredAt: new Date().toISOString(), container, database, priorFileSha256: beforeSha,
    state: 'disabled', semantics: 'Database flat expect_refs are compatibility labels; canonical AND/OR evidence groups and answer rubrics are in this suite.' };
  fs.writeFileSync(suitePath, JSON.stringify(suite, null, 2) + '\n');
  console.log(JSON.stringify({ registered: rows.length, suite: path.resolve(suitePath), disabled: rows.every(r => r.enabled === 0), existingCasesEdited: false }));
}
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) main().catch(e => { console.error(e.message); process.exitCode = 1; });
