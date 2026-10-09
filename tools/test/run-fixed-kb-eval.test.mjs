import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, writeFile, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { answerSignals, corpusSourceIdentity, extractInjectedPassages, hash, pairedContext, redact, run,
  scoreEvidence, stableEnvironment, summarize, validateSuite } from '../run-fixed-kb-eval.mjs';

const groups = [
  { id: 'planning', alternatives: [{ ref: 'note:1', quotes: ['规划负责拆解'] }, { ref: 'file:2', quotes: ['Planning decomposes tasks'] }] },
  { id: 'execution', alternatives: [{ ref: 'note:3', quotes: ['执行负责调用'] }] },
];
const goldCase = { id: 'case-01', dbCaseId: 10001, category: 'cross_source', question: '规划与执行分别做什么？',
  expectedBehavior: 'answer', evidenceGroups: groups, answerFacts: [
    { id: 'planning', description: '规划拆解', patterns: ['规划', 'Planning'] },
    { id: 'execution', description: '执行调用', patterns: ['执行', 'Execution'] },
  ] };

test('evidence scoring requires every group, but accepts interchangeable sources within a group', () => {
  assert.equal(scoreEvidence(groups, ['file:2', 'note:3']).all, true);
  assert.equal(scoreEvidence(groups, ['note:1']).all, false);
  assert.equal(scoreEvidence(groups, ['note:1']).groupRecall, 0.5);
  assert.equal(scoreEvidence([], []).applicable, false);
});
test('quoted evidence is evaluated separately from source presence and excludes generated guides', () => {
  const items = [{ ref: 'note:1', text: '不相关的正文' }, { ref: 'note:3', text: '执行负责调用' },
    { ref: 'wiki:1', text: '规划负责拆解' }];
  assert.equal(scoreEvidence(groups, items).all, true);
  assert.equal(scoreEvidence(groups, items, { requireQuotes: true }).all, false);
  const one = [{ id: 'g', alternatives: [{ ref: 'note:1', quotes: ['规划负责拆解', '动态调整'] }] }];
  assert.equal(scoreEvidence(one, [{ ref: 'note:1', text: '规划负责拆解' }], { requireQuotes: true }).all, false);
  assert.equal(scoreEvidence(one, [{ ref: 'note:1', text: '规划负责拆解' }, { ref: 'note:1', text: '动态调整' }], { requireQuotes: true }).all, true);
  assert.equal(scoreEvidence([{ id: 'g', alternatives: [{ ref: 'note:1' }] }], ['note:1'], { requireQuotes: true }).all, false);
});
test('independent diagnostic excerpts may represent answer evidence only when both hashes match', () => {
  const context = { text: '原文加导览', groundingText: '原文' };
  const detail = { contextHash: hash(context.text), groundingHash: hash(context.groundingText) };
  assert.equal(pairedContext(detail, context), true);
  assert.equal(pairedContext(detail, { ...context, groundingText: '不同原文' }), false);
  assert.equal(pairedContext({ contextHash: detail.contextHash }, context), false);
});
test('exact excerpt parser stops at the original-evidence footer', () => {
  const context = { text: '【检索原文】\n1. [笔记#1] [来源：note:1；片段 seq=4] 标题\n渠道：vector\n原文片段：\n规划负责拆解\n\n'
    + '2. [资料#2] [来源：file:2；片段 seq=8] 标题\n渠道：vector\n原文片段：\n执行负责调用\n\n【检索原文结束】\n\n【Wiki 生成导览】\n这是生成的导览' };
  assert.deepEqual(extractInjectedPassages(context), [{ ref: 'note:1', text: '规划负责拆解' }, { ref: 'file:2', text: '执行负责调用' }]);
});
test('lexical answer signals never establish correctness and require all facts', () => {
  const signal = answerSignals(goldCase, '规划负责调用，执行负责拆解。');
  assert.equal(signal.allFactPatternsHit, true); // Deliberately reversed semantics still matches words.
  assert.equal(signal.requiresHumanReview, true);
  assert.equal(signal.correctness, undefined);
  assert.equal(answerSignals(goldCase, '规划负责拆解').allFactPatternsHit, false);
});
test('failures do not masquerade as answer-quality scores; retrieval still records their evidence', () => {
  const yes = { applicable: true, all: true };
  const summary = summarize([
    { id: 'ok', status: 'ok', actualInjectedSourceScore: yes, candidateSourceScore: yes },
    { id: 'failed', status: 'generation_error', actualInjectedSourceScore: yes, candidateSourceScore: yes },
  ], 2);
  assert.equal(summary.successfulGeneration, 1);
  assert.equal(summary.generationFailures, 1);
  assert.equal(summary.actualInjectedAllGroups.denominator, 2);
  assert.equal(summary.humanAccuracy, null);
});
test('credential fields are redacted while token usage remains inspectable', () => {
  assert.deepEqual(redact({ apiKey: 'secret', wikiApiKey: 'secret', keyHint: 'sk-****1234', milvusToken: 'secret',
    promptTokens: 17, completionTokens: 23, error: 'provider rejected sk-abcdEFGH1234' }),
  { apiKey: '[REDACTED]', wikiApiKey: '[REDACTED]', keyHint: '[REDACTED]', milvusToken: '[REDACTED]',
    promptTokens: 17, completionTokens: 23, error: 'provider rejected [REDACTED]' });
});
test('corpus identity includes title and file summary in addition to content', () => {
  const source = { ref: 'file:2', title: 'paper', content: 'text', summary: 'summary' };
  assert.notEqual(hash(corpusSourceIdentity(source)), hash(corpusSourceIdentity({ ...source, title: 'renamed' })));
  assert.notEqual(hash(corpusSourceIdentity(source)), hash(corpusSourceIdentity({ ...source, summary: 'changed' })));
});
test('gold validation rejects unsupported quotes and duplicate DB ids', () => {
  const corpus = { sources: [{ ref: 'note:1', content: '规划负责拆解', sha256: hash('规划负责拆解') }] };
  const item = { ...goldCase, evidenceGroups: [groups[0]] };
  item.evidenceGroups = [{ id: 'g', alternatives: [groups[0].alternatives[0]] }];
  const suite = { schemaVersion: 1, cases: [item] };
  assert.equal(validateSuite(suite, corpus), suite);
  assert.throws(() => validateSuite({ ...suite, cases: [{ ...item, evidenceGroups: [{ id: 'g', alternatives: [{ ref: 'note:1', quotes: ['虚构句子'] }] }] }] }, corpus), /Unsupported gold quote/);
  assert.throws(() => validateSuite({ ...suite, cases: [item, { ...item, id: 'case-02' }] }, corpus), /dbCaseId/);
});

test('API integration preserves raw answers and resumes without reissuing model calls', async t => {
  const directory = await mkdtemp(join(tmpdir(), 'learnhub-fixed-eval-'));
  const content = '规划负责拆解';
  const item = { ...goldCase, evidenceGroups: [{ id: 'g', alternatives: [{ ref: 'note:1', quotes: [content] }] }] };
  const suite = { schemaVersion: 1, id: 'fixed-test', version: '1.0', cases: [item] };
  const corpus = { sources: [{ ref: 'note:1', title: '原文', content, sha256: hash(content) }] };
  const context = { text: `【检索原文】\n1. [笔记#1] [来源：note:1；片段 seq=0] 原文\n渠道：keyword\n原文片段：\n${content}\n\n【检索原文结束】`,
    groundingText: 'grounding', refs: ['note:1'], retrieved: [], chars: 100, budget: 7000 };
  const snapshot = { kb: { chunks: 1, currentSources: 1, stale: true, rerank: { enabled: false, cross: { healthy: false, lastError: 'volatile' } } },
    settings: [], routing: [], profiles: { profiles: [{ id: 'test', model: 'mock', apiKey: 'forbidden', keyHint: 'masked' }] } };
  assert.equal(stableEnvironment(snapshot).profiles[0].apiKey, undefined);
  let answerCalls = 0;
  let history = [];
  const requests = [];
  const server = createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost'); requests.push({ method: req.method, path: url.pathname });
    let data;
    if (url.pathname === '/api/kb/status') data = snapshot.kb;
    else if (url.pathname === '/api/settings/effective') data = snapshot.settings;
    else if (url.pathname === '/api/model/routing') data = snapshot.routing;
    else if (url.pathname === '/api/model/profiles') data = snapshot.profiles;
    else if (url.pathname === '/api/notes/1') data = { title: '原文', content };
    else if (url.pathname === '/api/kb/search') data = [{ sourceType: 'note', sourceId: 1, text: content }];
    else if (url.pathname === '/api/kb/context') data = context;
    else if (url.pathname === '/api/kb/eval/answer/run') {
      assert.equal(req.method, 'POST'); assert.equal(url.searchParams.get('ids'), '10001');
      answerCalls++;
      const runRow = { id: 7, label: url.searchParams.get('label'), corpusHash: 'backend-frozen-hash', model: 'mock', promptVersion: 'mock-v1',
        totalTokens: 20, detail: JSON.stringify([{ id: 10001, question: item.question, answer: '规划负责拆解，执行负责调用',
          top: 'note:1', contextHash: hash(context.text), groundingHash: hash(context.groundingText), finishReason: 'stop' }]) };
      history = [runRow]; data = { ...runRow, runId: runRow.id }; delete data.detail;
    } else if (url.pathname === '/api/kb/eval/answer/history') data = history;
    else { res.writeHead(404); res.end(JSON.stringify({ code: 404 })); return; }
    res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ code: 200, data }));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => server.close());
  await writeFile(join(directory, 'suite.json'), JSON.stringify(suite));
  await writeFile(join(directory, 'corpus.json'), JSON.stringify(corpus));
  const options = { suite: join(directory, 'suite.json'), corpus: join(directory, 'corpus.json'), out: join(directory, 'run'),
    base: `http://127.0.0.1:${server.address().port}`, topK: 5, candidateK: 24, wiki: true, kg: true, mode: 'fused',
    timeoutMs: 1000, retrievalOnly: false, retryErrors: false };
  const result = await run(options);
  assert.equal(result.successfulGeneration, 1);
  assert.equal(result.actualInjectedQuotes.hit, 1);
  await run(options);
  assert.equal(answerCalls, 1);
  const saved = JSON.parse(await readFile(join(options.out, 'cases', 'case-01.json'), 'utf8'));
  assert.equal(saved.answer, '规划负责拆解，执行负责调用');
  assert.equal(saved.contextPaired, true);
  assert.equal(requests.filter(r => r.method !== 'GET').length, 1);
  assert.ok(requests.every(r => !r.path.includes('rebuild') && !r.path.includes('reindex') && !r.path.includes('/ai/chat')));
  const manifest = await readFile(join(options.out, 'manifest.json'), 'utf8');
  assert.equal(manifest.includes('forbidden'), false);
  await writeFile(join(directory, 'suite.json'), JSON.stringify({ ...suite, version: '2.0' }));
  await assert.rejects(run(options), /Cannot resume/);
});
