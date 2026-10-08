// Recompute paired source-hit metrics from untouched LearnHub evaluation snapshots.
// Usage: node tools/analyze-retrieval-benchmark.mjs docs/benchmarks/2026-10-07
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';

const dir = path.resolve(process.argv[2] ?? 'docs/benchmarks/2026-10-07');
const read = name => JSON.parse(fs.readFileSync(path.join(dir, name), 'utf8').replace(/^\uFEFF/, ''));
const cases = read('cases.json').data;
assert.deepEqual(read('cases-after.json').data, cases, 'Evaluation questions changed between arms');
const before = read('status-before.json').data;
const after = read('status-after.json').data;
for (const key of ['chunks', 'indexedChars', 'indexedSources', 'currentSources',
  'indexedAt', 'sourceLatestChange', 'indexedModel', 'enabled', 'contextualEmbed']) {
  assert.deepEqual(before[key], after[key], `Corpus or index setting changed: ${key}`);
}
assert.deepEqual(before.rerank, after.rerank, 'Rerank configuration changed between arms');
assert.equal(before.stale, false, 'Vector index was stale at baseline');
assert.equal(after.stale, false, 'Vector index became stale');
assert.equal(read('fused-response.json').data.vectorRecall,
  read('vector-response.json').data.recallAtK, 'Vector control changed inside the fused run');
const checks = read('label-source-checks.json');
const auditFiles = ['gold-audit-java.json', 'gold-audit-other.json'];
const audits = new Map();
for (const name of auditFiles) {
  if (!fs.existsSync(path.join(dir, name))) continue;
  const raw = read(name);
  for (const a of (Array.isArray(raw) ? raw : raw.annotations)) {
    const status = a.status ?? a.verdict;
    assert.ok(['supported', 'unsupported', 'ambiguous'].includes(status), `Invalid audit verdict for ${a.id}`);
    assert.ok(!audits.has(a.id), `Duplicate audit for ${a.id}`);
    audits.set(a.id, { status, reason: a.reason, evidence: a.evidence });
  }
}
const available = new Set(checks.filter(s => s.exists &&
  (s.contentChars > 0 || (s.status === 'ok' && s.extractedChars > 0))).map(s => s.ref));
const split = text => text ? text.split('|').filter(Boolean) : [];
const vector = read('vector-run.json');
const fused = read('fused-run.json');
assert.equal(vector.topK, fused.topK);
assert.equal(vector.topK, 5);
const vectorDetail = JSON.parse(vector.detail);
const fusedDetail = JSON.parse(fused.detail);
const fusedLogs = fs.readFileSync(path.join(dir, 'fused-retrieval-log.txt'), 'utf8')
  .split(/\r?\n/).filter(line => line.includes('mode=fused'));
assert.equal(cases.length, vectorDetail.length);
assert.equal(cases.length, fusedDetail.length);
assert.equal(cases.length, fusedLogs.length, 'Fused log count must match the evaluation, without unrelated searches');

const rows = cases.map((c, i) => {
  const v = vectorDetail[i], f = fusedDetail[i];
  assert.equal(v.q, c.question);
  assert.equal(f.q, c.question);
  assert.equal(v.expect, c.expectRefs);
  assert.equal(f.expect, c.expectRefs);
  const expected = split(c.expectRefs);
  const validExpected = expected.filter(ref => available.has(ref));
  const vr = split(v.top), fr = split(f.top);
  // rank is the first expected source's position AFTER passage-to-source deduplication.
  const rank = refs => { const at = refs.findIndex(ref => expected.includes(ref)); return at < 0 ? -1 : at + 1; };
  assert.equal(v.rank, rank(vr));
  assert.equal(f.rank, rank(fr));
  return {
    id: c.id, question: c.question, expectRefs: expected, validExpectRefs: validExpected,
    validLabel: validExpected.length > 0,
    autoDerived: (c.note ?? '').startsWith('自动派生'),
    semantic: (c.note ?? '').startsWith('语义型'),
    paper: c.id >= 111 && c.id <= 122,
    // Only these two prompts explicitly require relating a paper to the note.
    explicitCrossSource: c.id === 121 || c.id === 122,
    contentAudit: audits.get(c.id) ?? null,
    graphCandidateCount: Number(fusedLogs[i].match(/graph=(\d+)/)?.[1] ?? 0),
    vector: { rank: v.rank, hit: v.rank > 0, top: vr },
    fused: { rank: f.rank, hit: f.rank > 0, top: fr },
    note: c.note,
  };
});

function arm(sample, key) {
  const hit = sample.filter(r => r[key].hit).length;
  return {
    hits: hit, total: sample.length,
    hitAt5: hit / sample.length,
    hitAt1: sample.filter(r => r[key].rank === 1).length / sample.length,
    sourceMrr: sample.reduce((sum, r) => sum + (r[key].rank > 0 ? 1 / r[key].rank : 0), 0) / sample.length,
  };
}
function compare(sample) {
  const v = arm(sample, 'vector'), f = arm(sample, 'fused');
  return {
    vector: v, fused: f,
    hitDeltaPercentagePoints: (f.hitAt5 - v.hitAt5) * 100,
    hitRelativePercent: v.hitAt5 ? (f.hitAt5 / v.hitAt5 - 1) * 100 : null,
    sourceMrrDelta: f.sourceMrr - v.sourceMrr,
    hitGains: sample.filter(r => !r.vector.hit && r.fused.hit).map(r => r.id),
    hitLosses: sample.filter(r => r.vector.hit && !r.fused.hit).map(r => r.id),
    rankGains: sample.filter(r => (r.fused.rank > 0 ? 1 / r.fused.rank : 0) > (r.vector.rank > 0 ? 1 / r.vector.rank : 0)).length,
    rankLosses: sample.filter(r => (r.fused.rank > 0 ? 1 / r.fused.rank : 0) < (r.vector.rank > 0 ? 1 / r.vector.rank : 0)).length,
  };
}
const subsets = {
  all99: rows,
  validLabels: rows.filter(r => r.validLabel),
  validNonAutoDerived: rows.filter(r => r.validLabel && !r.autoDerived),
  validAutoDerived: rows.filter(r => r.validLabel && r.autoDerived),
  validSemantic: rows.filter(r => r.validLabel && r.semantic),
  validPapers: rows.filter(r => r.validLabel && r.paper),
};
const metrics = Object.fromEntries(Object.entries(subsets).map(([name, sample]) => [name, compare(sample)]));
if (audits.size) {
  const supported = rows.filter(r => r.validLabel && r.contentAudit?.status === 'supported');
  assert.ok(supported.length > 0);
  metrics.contentSupported = compare(supported);
  const nonDerived = supported.filter(r => !r.autoDerived);
  if (nonDerived.length) metrics.contentSupportedNonAutoDerived = compare(nonDerived);
}
const crossSource = rows.filter(r => r.explicitCrossSource).map(r => ({
  id: r.id, question: r.question,
  expected: r.expectRefs,
  vectorAllRequired: r.expectRefs.every(ref => r.vector.top.includes(ref)),
  fusedAllRequired: r.expectRefs.every(ref => r.fused.top.includes(ref)),
  vectorTop: r.vector.top, fusedTop: r.fused.top,
}));
assert.equal(Math.round(metrics.all99.vector.hitAt5 * 1000) / 1000, vector.recallAtK);
assert.equal(Math.round(metrics.all99.fused.hitAt5 * 1000) / 1000, fused.recallAtK);
assert.equal(Math.round(metrics.all99.vector.sourceMrr * 1000) / 1000, vector.mrr);
assert.equal(Math.round(metrics.all99.fused.sourceMrr * 1000) / 1000, fused.mrr);
const output = {
  generatedAt: new Date().toISOString(), vectorRunId: vector.id, fusedRunId: fused.id, topKPassages: 5,
  metricDefinition: 'Hit@5: any expected source appears among the first five passages. MRR uses deduplicated source rank, not passage rank. Neither measures answer correctness.',
  invalidSourceRefs: checks.filter(s => !available.has(s.ref)).map(s => s.ref),
  invalidCaseIds: rows.filter(r => !r.validLabel).map(r => r.id), metrics, crossSource, rows,
  contentAuditCoverage: {
    reviewed: rows.filter(r => r.validLabel && r.contentAudit).length,
    supported: rows.filter(r => r.validLabel && r.contentAudit?.status === 'supported').length,
    unsupported: rows.filter(r => r.validLabel && r.contentAudit?.status === 'unsupported').length,
    ambiguous: rows.filter(r => r.validLabel && r.contentAudit?.status === 'ambiguous').length,
    unreviewed: rows.filter(r => r.validLabel && !r.contentAudit).map(r => r.id),
  },
};
fs.writeFileSync(path.join(dir, 'comparison.json'), JSON.stringify(output, null, 2) + '\n');
console.log(JSON.stringify({vectorRunId: vector.id, fusedRunId: fused.id, metrics, crossSource}, null, 2));
