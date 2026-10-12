import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { sha, shuffled, inferenceItems } from '../run-rerank-ab.mjs';
import { PLAN, SOURCE_FILES, assertSourceSnapshot, validateNewSuite, sourceSoftPriority, validateControl, buildPreparedTrials,
  embeddingDocuments, validateVectors, validateRankRows, evaluateTrials, summarizeEvaluations,
  rankExperiment, renderReport, parseArguments } from '../run-mmr-ab.mjs';

const counts = { ...PLAN, newQuestions: 2, controlQuestions: 2, bootstrapIterations: 300 };
const clone = value => structuredClone(value);
const newSuite = () => ({ version: '1.0-synthetic-frozen', cases: Array.from({ length: 2 }, (_, q) => ({
  id: `q${q}`, category: '合成互补', question: `合成问题${q}需要提交、重试与清理哪些事实？`,
  requiredFacets: ['commit', 'retry', 'clean'],
  items: Array.from({ length: 20 }, (_, i) => ({ key: `q${q}d${i}`, title: `合成片段${i}`, text: `合成正文${q}/${i}`,
    relevance: i < 12 ? 2 : i < 14 ? 1 : 0, rationale: '冻结合成理由',
    facets: i < 8 ? ['commit'] : i < 10 ? ['retry'] : i < 12 ? ['clean'] : [],
    duplicateGroup: i < 8 ? 'commit' : i < 10 ? 'retry' : i < 12 ? 'clean' : `other${i}`,
    sourceRef: `s${i % 3}` })) })) });
const controlSuite = () => ({ version: '1.0-synthetic-frozen', cases: Array.from({ length: 2 }, (_, q) => ({
  id: `old${q}`, category: '合成相关性', question: `合成完整方案${q}是什么？`,
  items: Array.from({ length: 12 }, (_, i) => ({ key: `old${q}d${i}`, title: `合成完整片段${i}`, text: `旧合成正文${q}/${i}`,
    relevance: i < 2 ? 2 : i < 4 ? 1 : 0, rationale: '冻结旧合成理由' })) })) });
function excerpt(items) {
  const excerpts = items.map(({ key, title, text }) => ({ key, title, excerpt: text, document: `${title}｜${text}` }));
  return { excerpts, excerptsHash: sha(JSON.stringify(excerpts)) };
}
function rank(trial, status = 'ok') {
  return { order: [...trial.inputOrder], elapsedMs: 10, excerptsHash: trial.excerptsHash,
    diagnostics: { status, fallback: status === 'request_error', qualityEligible: status === 'ok', snippet: 120 } };
}
function oldRows(suite) {
  return suite.cases.flatMap(c => [0, 1].map(trial => {
    const items = inferenceItems(shuffled(c.items, `rerank-ab-v1:${c.id}:${trial}`)), inputOrder = items.map(x => x.key);
    const e = excerpt(items);
    return { id: c.id, trial, candidateHash: sha(JSON.stringify(items)), input: { order: inputOrder }, ...e,
      llm: rank({ inputOrder, excerptsHash: e.excerptsHash }) };
  }));
}
async function preparation() {
  const suite = newSuite(), control = controlSuite(), old = oldRows(control), requests = [];
  const trials = await buildPreparedTrials(suite, control, old, async request => {
    requests.push(clone(request)); return excerpt(request.items);
  }, counts);
  return { suite, control, old, trials, requests };
}
function rankedRows(trials) {
  return trials.map(trial => ({ cohort: trial.cohort, id: trial.id, trial: trial.trial,
    candidateHash: trial.candidateHash, excerptsHash: trial.excerptsHash,
    reused: trial.cohort === 'control', llm: trial.existingRank || rank(trial) }));
}
function pad(values) { const norm = Math.hypot(...values); return [...values.map(v => v / norm), ...Array(1024 - values.length).fill(0)]; }
function embeddingSnapshot(documents, inputHash = 'frozen-input-hash') {
  return { status: 'completed', inputHash, normalized: true, model: PLAN.embedding.model,
    modelDigest: PLAN.embedding.modelDigest, dimension: PLAN.embedding.dimension, options: clone(PLAN.embedding.options),
    completed: documents.length, documentCount: documents.length,
    vectors: documents.map((document, i) => ({ id: document.id, vector: pad(i % 2 ? [0, 1] : [1, 0]) })) };
}

test('the frozen plan fixes 24x20 new cases, 40x12 old controls, two repeats, top8 and predeclared lambdas', () => {
  assert.deepEqual([PLAN.newQuestions, PLAN.newCandidates, PLAN.controlQuestions, PLAN.controlCandidates, PLAN.trials, PLAN.topK],
    [24, 20, 40, 12, 2, 8]);
  assert.deepEqual(PLAN.lambdas, [.7, .5, .9, 1]); assert.equal(PLAN.primaryLambda, .7);
  assert.equal(PLAN.snippet, 120); assert.equal(PLAN.embedding.dimension, 1024);
  assert.deepEqual(PLAN.embedding.options, { num_thread: 1, num_gpu: 0 });
  assert.equal(PLAN.embedding.modelDigest, '7907646426070047a77226ac3e684fbbe8410524f7b4a74d02837e43f2146bab');
});

test('resume/analyze freeze guard includes imported ranking helpers and embedding implementation', () => {
  assert.ok(SOURCE_FILES.includes('tools/run-rerank-ab.mjs'));
  assert.ok(SOURCE_FILES.includes('tools/mmr-embed.py'));
  const frozen = Object.fromEntries(SOURCE_FILES.map(file => [file, sha(`synthetic-${file}`)]));
  assert.doesNotThrow(() => assertSourceSnapshot(clone(frozen), frozen));
  for (const file of ['tools/run-rerank-ab.mjs', 'tools/mmr-embed.py', 'tools/mmr.mjs']) {
    const current = clone(frozen); current[file] = sha('synthetic changed implementation');
    assert.throws(() => assertSourceSnapshot(current, frozen), /source_or_bytecode_changed/);
  }
});

test('new gold must be frozen v1, complete in facets, and have explicit source/fact groups', () => {
  assert.equal(validateNewSuite(newSuite(), counts).cases.length, 2);
  for (const change of [s => { s.version = '0.1-draft'; }, s => { s.version = '2.0-synthetic-frozen'; },
    s => { s.cases[0].items.pop(); }, s => { s.cases[0].items[0].sourceRef = ''; },
    s => { s.cases[0].items[0].duplicateGroup = ''; }, s => { s.cases[0].items[0].facets = ['invented']; },
    s => { s.cases[0].items[12].facets = ['commit']; }, s => { s.cases[0].requiredFacets.push('missing'); }]) {
    const suite = newSuite(); change(suite); assert.throws(() => validateNewSuite(suite, counts));
  }
});

test('source priority keeps every candidate, reserves two per actual source, and ignores gold/facet groups', () => {
  const items = ['a', 'b', 'c', 'd', 'e', 'f'].map((key, i) => ({ key, sourceRef: i < 3 ? 's1' : i < 5 ? 's2' : 's3',
    relevance: i, duplicateGroup: key, facets: [key] }));
  assert.deepEqual(sourceSoftPriority(items).map(x => x.key), ['a', 'b', 'd', 'e', 'f', 'c']);
  const relabeled = items.map(item => ({ ...item, relevance: 9, facets: [], duplicateGroup: 'same' }));
  assert.deepEqual(sourceSoftPriority(relabeled).map(x => x.key), sourceSoftPriority(items).map(x => x.key));
  assert.throws(() => sourceSoftPriority([{ key: 'a' }]));
  assert.throws(() => sourceSoftPriority([{ key: 'a', sourceRef: 's1' }, { key: 'a', sourceRef: 's2' }]));
});

test('preparation calls only the excerpt arm for new trials and copies old ranks without reordering', async () => {
  const { suite, old, trials, requests } = await preparation();
  assert.equal(requests.length, counts.newQuestions * counts.trials); assert.equal(trials.length, 8);
  for (const request of requests) {
    assert.equal(request.arm, 'excerpt');
    assert.deepEqual(Object.keys(request.items[0]).sort(), ['key', 'text', 'title']);
    assert.equal(request.items.length, 20);
    assert.equal(JSON.stringify(request).includes('requiredFacets'), false);
  }
  for (const trial of trials) {
    if (trial.cohort === 'new') {
      const c = suite.cases.find(c => c.id === trial.id);
      const expected = sourceSoftPriority(shuffled(c.items, `mmr-ab-v1:${c.id}:${trial.trial}`));
      assert.deepEqual(trial.inputOrder, expected.map(x => x.key));
    } else {
      const previous = old.find(x => x.id === trial.id && x.trial === trial.trial);
      assert.deepEqual(trial.existingRank, previous.llm);
      assert.deepEqual(trial.inputOrder, previous.input.order);
    }
  }
  const repeated = await preparation(); assert.deepEqual(repeated.trials, trials);
});

test('control reuse checks frozen hash, all 40x2 slots, candidate order, excerpt hash and actual LLM permutation', () => {
  const suite = controlSuite(), rows = oldRows(suite), metadata = { suiteHash: 'frozen', snippet: 120, trials: 2, completedAt: 'before-new-run' };
  assert.equal(validateControl(suite, rows, metadata, 'frozen', counts).size, 4);
  for (const change of [r => r.pop(), r => { r[0].candidateHash = 'changed'; },
    r => { r[0].llm.order[1] = r[0].llm.order[0]; }, r => { r[0].excerpts[0].excerpt += '修改'; },
    r => { r[1].trial = r[0].trial; }, r => { r[0].input.order.reverse(); }]) {
    const altered = clone(rows); change(altered); assert.throws(() => validateControl(suite, altered, metadata, 'frozen', counts));
  }
  assert.throws(() => validateControl(suite, rows, metadata, 'wrong-hash', counts));
});

test('embedding documents deduplicate exact title/excerpt bytes, independently from labels and source IDs', async () => {
  const { trials } = await preparation();
  const documents = embeddingDocuments([...trials, ...trials]).documents;
  const uniqueText = new Set(trials.flatMap(t => t.excerpts.map(e => e.document)));
  assert.equal(documents.length, uniqueText.size);
  for (const document of documents) { assert.deepEqual(Object.keys(document).sort(), ['id', 'text']); assert.equal(document.id, sha(document.text)); }
  const changed = clone(trials); changed[0].excerpts[0].document += ' ';
  assert.notDeepEqual(embeddingDocuments(changed), embeddingDocuments(trials));
});

test('vector input must match exact frozen input, document order and fixed bge-m3 digest/dimension/CPU options', () => {
  const documents = [{ id: sha('合成一'), text: '合成一' }, { id: sha('合成二'), text: '合成二' }];
  const snapshot = embeddingSnapshot(documents);
  assert.equal(validateVectors(snapshot, documents, 'frozen-input-hash').size, 2);
  for (const change of [s => { s.inputHash = 'changed'; }, s => { s.status = 'in_progress'; },
    s => { s.modelDigest = 'f'.repeat(64); }, s => { s.dimension = 768; }, s => { s.options.num_thread = 4; },
    s => { s.options.num_gpu = 1; }, s => { s.options.extra = true; }, s => { s.model = 'different'; },
    s => { s.vectors.reverse(); }, s => { s.vectors.pop(); }, s => { s.vectors[0].vector.fill(0); },
    s => { s.vectors[0].vector[0] = NaN; }, s => { s.vectors[0].vector[0] = true; },
    s => { s.vectors[0].vector[0] = 2; }]) {
    const altered = clone(snapshot); change(altered); assert.throws(() => validateVectors(altered, documents, 'frozen-input-hash'));
  }
});

test('rank resume cannot accept duplicates, dropped candidates, mismatched excerpts or modified old controls', async () => {
  const { trials } = await preparation(), rows = rankedRows(trials);
  assert.equal(validateRankRows(rows, trials, true).length, 8);
  assert.throws(() => validateRankRows(rows.slice(1), trials, true));
  for (const change of [r => r.push(clone(r[0])), r => { r[0].candidateHash = 'changed'; },
    r => { r[0].llm.excerptsHash = 'changed'; }, r => { r[0].llm.order.pop(); },
    r => { r[4].llm.order.reverse(); }, r => { r[0].reused = true; }]) {
    const altered = clone(rows); change(altered); assert.throws(() => validateRankRows(altered, trials));
  }
});

test('MMR uses vectors aligned to the shared LLM order, lambda1 matches baseline, and cohorts stay separate', async () => {
  const { suite, control, trials } = await preparation(), rows = rankedRows(trials), vectors = new Map();
  for (const row of rows.filter(r => r.cohort === 'new')) {
    const c = suite.cases.find(c => c.id === row.id);
    row.llm.order = c.items.map(x => x.key); // One LLM order shared by both selection arms.
  }
  for (const trial of trials) for (const entry of trial.documents) {
    const item = (trial.cohort === 'new' ? suite : control).cases.find(c => c.id === trial.id).items.find(x => x.key === entry.key);
    const values = item.facets?.[0] === 'retry' ? [0, 1, 0] : item.facets?.[0] === 'clean' ? [0, 0, 1] : [1, 0, 0];
    vectors.set(entry.id, pad(values));
  }
  const evaluations = evaluateTrials(suite, control, trials, rows, vectors, counts);
  for (const row of evaluations) {
    assert.deepEqual(row.variants['1'].order, row.baseline.order);
    assert.deepEqual(row.variants['1'].score, row.baseline.score);
    if (row.cohort === 'new') {
      assert.equal(row.baseline.score.facetCoverage8, 1 / 3);
      assert.equal(row.variants['0.7'].score.facetCoverage8, 1);
      assert.ok(row.variants['0.7'].score.redundancy8 < row.baseline.score.redundancy8);
    } else {
      assert.equal(row.baseline.score.facetCoverage8, null); assert.equal(row.baseline.score.uniqueSources8, null);
    }
  }
  const summary = summarizeEvaluations(evaluations, counts);
  assert.equal(summary.cohorts.new.all.questions, 2); assert.equal(summary.cohorts.control.all.questions, 2);
  assert.equal('facetCoverage8' in summary.cohorts.control.all.baseline, false);
  assert.equal(summary.cohorts.new.all.variants['1'].minusBaseline.ndcg8.delta, 0);
  assert.equal('pooled' in summary, false);
});

test('bootstrap and success/fallback strata average within question instead of counting two permutations as independent', () => {
  const evaluations = [0, 1].flatMap(trial => ['q1', 'q2'].map(id => ({
    cohort: 'new', id, trial, category: '合成', diagnostics: { qualityEligible: !(id === 'q1' && trial === 1),
      fallback: id === 'q1' && trial === 1, status: id === 'q1' && trial === 1 ? 'request_error' : 'ok' },
    baseline: { score: { ndcg8: .5, facetCoverage8: 0 } },
    variants: Object.fromEntries(PLAN.lambdas.map(lambda => [String(lambda), { score: {
      ndcg8: .5, facetCoverage8: lambda === 1 ? 0 : id === 'q1' ? 1 : 0 } }])) })));
  const summary = summarizeEvaluations(evaluations, counts).cohorts.new;
  assert.equal(summary.all.questions, 2); assert.equal(summary.all.trials, 4);
  assert.equal(summary.all.variants['0.7'].minusBaseline.facetCoverage8.delta, .5);
  assert.equal(summary.successfulOnly.trials, 3); assert.equal(summary.successfulOnly.questions, 2);
  assert.equal(summary.successfulOnly.variants['0.7'].minusBaseline.facetCoverage8.delta, .5); // not 1/3
  assert.equal(summary.successfulQuestionsOnly.questions, 1);
  assert.equal(summary.fallbackOnly.trials, 1); assert.equal(summary.fallbackOnly.questions, 1);
  assert.deepEqual(summary.all.variants['0.7'].minusBaseline.facetCoverage8.ci95, [0, 1]);
});

async function temporary(action) {
  const directory = await mkdtemp(join(tmpdir(), 'learnhub-mmr-synthetic-'));
  try { return await action(directory); }
  finally {
    assert.ok(resolve(directory).startsWith(resolve(tmpdir()) + '/'.replace('/', process.platform === 'win32' ? '\\' : '/')));
    await rm(directory, { recursive: true, force: true });
  }
}
function runtimeFor(trials, custom = {}) {
  const before = { enabled: true, backend: 'llm', active: 'llm', model: 'synthetic-model', snippet: 120, target: 'synthetic-profile' };
  return { frozenState: async () => ({ metadata: { controlModel: before.model }, prepared: trials }),
    routedConfig: async () => ({ before, config: { baseUrl: 'http://127.0.0.1:1', apiKey: 'synthetic-secret-never-persist', model: before.model } }),
    routingState: async () => before, log: () => {}, ...custom };
}

test('rank is single-inflight, resumes only missing trials, reuses controls, and never persists stdin credentials', async () => temporary(async directory => {
  const { trials } = await preparation(); let calls = 0, active = 0, closed = 0, failOnce = true;
  const seen = [];
  const runtime = runtimeFor(trials, { startProbe: async config => {
    assert.equal(config.apiKey, 'synthetic-secret-never-persist');
    return { close: () => { closed++; }, send: async request => {
      calls++; active++; assert.equal(active, 1); seen.push(request);
      try {
        await Promise.resolve();
        if (calls === 2 && failOnce) { failOnce = false; throw Error('synthetic_interruption'); }
        assert.equal(request.arm, 'llm'); assert.deepEqual(Object.keys(request.items[0]).sort(), ['key', 'text', 'title']);
        const trial = trials.find(t => t.cohort === 'new' && t.id === request.id && t.candidateHash === sha(JSON.stringify(request.items)));
        return rank(trial, calls === 4 ? 'request_error' : 'ok');
      } finally { active--; }
    } };
  } });
  await assert.rejects(rankExperiment({ directory }, runtime), /synthetic_interruption/);
  const first = JSON.parse(await readFile(join(directory, 'ranks.json'), 'utf8'));
  assert.equal(first.filter(row => row.reused).length, 4); assert.equal(first.filter(row => !row.reused).length, 1);
  await rankExperiment({ directory }, runtime);
  const rows = JSON.parse(await readFile(join(directory, 'ranks.json'), 'utf8'));
  assert.equal(rows.length, 8); assert.equal(calls, 5); assert.equal(closed, 2);
  assert.equal(rows.filter(row => !row.reused && row.llm.diagnostics.fallback).length, 1);
  const done = await rankExperiment({ directory }, runtime); assert.equal(done.resumed, true); assert.equal(calls, 5);
  for (const file of await readdir(directory)) {
    const text = await readFile(join(directory, file), 'utf8');
    assert.equal(text.includes('synthetic-secret-never-persist'), false);
    assert.equal(text.includes('apiKey'), false);
  }
  assert.equal(seen.some(request => request.id.startsWith('old')), false);
}));

test('invalid backend permutations or differing excerpts abort instead of adding a substitute ranking', async () => temporary(async directory => {
  const { trials } = await preparation();
  const runtime = runtimeFor(trials, { startProbe: async () => ({ close() {}, send: async () => ({
    order: [], elapsedMs: 1, excerptsHash: 'changed', diagnostics: { status: 'request_error', fallback: false, qualityEligible: false } }) }) });
  await assert.rejects(rankExperiment({ directory }, runtime), /invalid_candidate_permutation/);
  const rows = JSON.parse(await readFile(join(directory, 'ranks.json'), 'utf8'));
  assert.equal(rows.every(row => row.reused), true);
}));

test('a failed final configuration check is retained across resume and cannot be silently accepted', async () => temporary(async directory => {
  const { trials } = await preparation(); let calls = 0;
  const runtime = runtimeFor(trials, { routingState: async () => ({ target: 'changed' }),
    startProbe: async () => ({ close() {}, send: async request => {
      calls++; const trial = trials.find(t => t.cohort === 'new' && t.id === request.id
        && t.candidateHash === sha(JSON.stringify(request.items))); return rank(trial);
    } }) });
  await assert.rejects(rankExperiment({ directory }, runtime), /configuration_changed_during_rank/);
  assert.equal(calls, 4);
  await assert.rejects(rankExperiment({ directory }, runtime), /configuration_changed_during_rank/);
  assert.equal(calls, 4);
}));

test('the same output lock prevents a second process from launching model work', async () => temporary(async directory => {
  const { trials } = await preparation(); let release, started;
  const gate = new Promise(resolve => { release = resolve; }), ready = new Promise(resolve => { started = resolve; });
  let calls = 0;
  const runtime = runtimeFor(trials, { startProbe: async () => ({ close() {}, send: async request => {
    if (!calls++) { started(); await gate; }
    const trial = trials.find(t => t.cohort === 'new' && t.id === request.id && t.candidateHash === sha(JSON.stringify(request.items)));
    return rank(trial);
  } }) });
  const first = rankExperiment({ directory }, runtime); await ready;
  await assert.rejects(rankExperiment({ directory }, runtime), /output_directory_locked/);
  release(); await first; assert.equal(calls, 4);
}));

test('CLI accepts only explicit phases and known flags; reports limit conclusions to fixed selection', async () => {
  assert.throws(() => parseArguments([])); assert.throws(() => parseArguments(['rank', '--api-key', 'secret']));
  assert.throws(() => parseArguments(['prepare', '--out'])); assert.throws(() => parseArguments(['rank', '--out', 'a', '--out', 'b']));
  assert.equal(parseArguments(['prepare']).phase, 'prepare');
  assert.equal(parseArguments(['analyze', '--vectors', 'synthetic.json']).vectors, resolve('synthetic.json'));
  const { suite, control, trials } = await preparation(), rows = rankedRows(trials);
  const documents = embeddingDocuments(trials), snapshot = embeddingSnapshot(documents.documents);
  const vectors = validateVectors(snapshot, documents.documents, 'frozen-input-hash');
  const summary = summarizeEvaluations(evaluateTrials(suite, control, trials, rows, vectors, counts), counts);
  const report = renderReport({ plan: counts, suiteHash: 'new', controlSuiteHash: 'old' }, summary, snapshot);
  assert.match(report, /不测召回率或生成答案准确率/); assert.match(report, /两个cohort分别报告/);
  assert.match(report, /并非概率/); assert.match(report, /不自动宣称提升或部署/);
});
