import test from 'node:test';
import assert from 'node:assert/strict';
import { scoreRanking, pairedBootstrap, shuffled, inferenceItems, summarize } from '../run-rerank-ab.mjs';

const items = [
  { key: 'a', title: '示例A', text: '合成直接证据', relevance: 2, rationale: '直接支持' },
  { key: 'b', title: '示例B', text: '合成部分证据', relevance: 1, rationale: '部分支持' },
  { key: 'c', title: '示例C', text: '合成无关证据', relevance: 0, rationale: '不支持' },
  { key: 'd', title: '示例D', text: '合成直接证据二', relevance: 2, rationale: '另一直接证据' },
  { key: 'e', title: '示例E', text: '合成干扰证据', relevance: 0, rationale: '不支持' },
  { key: 'f', title: '示例F', text: '合成其他证据', relevance: 0, rationale: '不支持' },
];
test('graded DCG and strong-evidence rank use distinct label semantics', () => {
  assert.deepEqual(scoreRanking(items, ['a', 'd', 'b', 'c', 'e', 'f']), { ndcg5: 1, top1: 1, mrr: 1, recall5: 1 });
  const result = scoreRanking(items, ['b', 'c', 'e', 'f', 'a', 'd']);
  assert.equal(result.top1, 0); assert.equal(result.mrr, 1 / 5); assert.equal(result.recall5, .5);
  const expected = (1 + 3 / Math.log2(6)) / (3 + 3 / Math.log2(3) + 1 / Math.log2(4));
  assert.equal(result.ndcg5, expected);
});
test('missing, duplicate and unknown candidates cannot silently inflate metrics', () => {
  assert.throws(() => scoreRanking(items, ['a', 'd', 'b']));
  assert.throws(() => scoreRanking(items, ['a', 'd', 'b', 'c', 'e', 'e']));
  assert.throws(() => scoreRanking(items, ['a', 'd', 'b', 'c', 'e', 'unknown']));
});
test('gold annotations never enter inference and permutations are reproducible', () => {
  assert.deepEqual(Object.keys(inferenceItems(items)[0]).sort(), ['key', 'text', 'title']);
  assert.deepEqual(shuffled(items, 'seed'), shuffled(items, 'seed'));
  assert.deepEqual(new Set(shuffled(items, 'other').map(item => item.key)), new Set(items.map(item => item.key)));
});
test('paired bootstrap handles known constant differences and exact ties', () => {
  const constant = pairedBootstrap([.25, .25, .25], 500);
  assert.equal(constant.delta, .25); assert.deepEqual(constant.ci95, [.25, .25]);
  const tie = pairedBootstrap([0, 0, 0], 500);
  assert.equal(tie.delta, 0); assert.deepEqual(tie.ci95, [0, 0]);
});
test('repeated candidate permutations do not become independent bootstrap units', () => {
  const perfect = scoreRanking(items, ['a', 'd', 'b', 'c', 'e', 'f']);
  const weak = scoreRanking(items, ['b', 'c', 'e', 'f', 'a', 'd']);
  const arm = score => ({ score, elapsedMs: 10, order: ['a', 'd', 'b', 'c', 'e', 'f'], diagnostics: { status: 'ok', qualityEligible: true } });
  const rows = [0, 1].flatMap(trial => [
    { id: 'q1', category: '合成', trial, input: arm(weak), llm: arm(perfect), cross: arm(weak) },
    { id: 'q2', category: '合成', trial, input: arm(weak), llm: arm(weak), cross: arm(perfect) },
  ]);
  const result = summarize({ cases: [{ id: 'q1', category: '合成' }, { id: 'q2', category: '合成' }] }, rows);
  assert.equal(result.uniqueQuestions, 2); assert.equal(result.pairedTrials, 4);
  assert.equal(result.llmMinusCross.ndcg5.delta, 0);
  const difference = perfect.ndcg5 - weak.ndcg5;
  assert.deepEqual(result.llmMinusCross.ndcg5.ci95, [-difference, difference]);
  assert.equal(result.successfulOnly.pairedTrials, 4);
});
