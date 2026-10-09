import test from 'node:test';
import assert from 'node:assert/strict';
import { sqlText, buildSql, validateRegistration, markerFor } from './register-fixed-kb-eval.mjs';
const suite = () => ({ id: 'local-kb', version: 'v1', cases: [{ id: 'A', question: "引用 ' ; DROP TABLE rag_eval; 中文？", evidenceGroups: [{ alternatives: [{ ref: 'note:2' }, { ref: 'file:2' }] }] }] });
test('SQL text always uses UTF-8 hex rather than interpolated strings', () => {
  const value = "' ; DROP TABLE rag_eval; 中文";
  assert.equal(sqlText(value), `CONVERT(0x${Buffer.from(value).toString('hex')} USING utf8mb4)`);
  const sql = buildSql(suite());
  assert.ok(!sql.includes('DROP TABLE'));
  assert.ok(sql.includes('NULL'));
  assert.ok(!/UPDATE|DELETE|INSERT IGNORE/.test(sql));
  assert.ok(sql.includes('WHERE NOT EXISTS'));
});
test('registration refuses a question owned by another suite without altering its row', () => {
  const s = suite();
  const row = { id: 100, question: s.cases[0].question, note: 'existing', expectRefs: 'note:2|file:2', enabled: 1 };
  assert.throws(() => validateRegistration(s, [row]), /existing case/);
  assert.equal(row.enabled, 1);
  assert.equal(row.note, 'existing');
});
test('same marker and disabled row can be safely reused; different labels cannot', () => {
  const s = suite();
  const row = { id: 100, question: s.cases[0].question, note: markerFor(s, s.cases[0]), expectRefs: 'note:2|file:2', enabled: 0 };
  assert.equal(validateRegistration(s, [row])[0].id, 100);
  assert.throws(() => validateRegistration(s, [{ ...row, expectRefs: 'note:1' }]), /Labels changed/);
});
test('duplicate questions and oversized labels fail before database execution', () => {
  const s = suite();
  s.cases.push({ ...s.cases[0], id: 'B' });
  assert.throws(() => buildSql(s), /Duplicate question/);
  const x = suite();
  x.cases[0].evidenceGroups[0].alternatives = [{ ref: 'x'.repeat(501) }];
  assert.throws(() => buildSql(x), /Too many reference labels/);
});
