import test from 'node:test';
import assert from 'node:assert/strict';
import { normalize, similarityMatrix, mmrOrder, scoreSelection } from '../mmr.mjs';

test('lambda=1 exactly reproduces the original rank and preserves all candidates', () => {
  const order = ['a', 'b', 'c', 'd'];
  const matrix = similarityMatrix([[1, 0], [1, 0], [0, 1], [.1, 1]]);
  assert.deepEqual(mmrOrder(order, matrix, 1).order, order);
  assert.deepEqual(new Set(mmrOrder(order, matrix, .7).order), new Set(order));
});

test('MMR retains strongest first evidence and can promote complementary content', () => {
  const matrix = similarityMatrix([[1, 0], [1, 0], [0, 1], [0, 1]]);
  assert.deepEqual(mmrOrder(['a', 'b', 'c', 'd'], matrix, .5).order.slice(0, 2), ['a', 'c']);
});

test('invalid embedding data never silently becomes diverse evidence', () => {
  for (const vector of [[], [0, 0], [NaN, 1], [Infinity]]) assert.throws(() => normalize(vector));
  assert.throws(() => similarityMatrix([[1, 0], [1]]));
  assert.throws(() => mmrOrder(['a', 'a'], [[1, 0], [0, 1]], .7));
  assert.throws(() => mmrOrder(['a'], [[1]], 1.1));
});

test('same-source complementary facts improve coverage without a source-count reward', () => {
  const c = { requiredFacets: ['commit', 'retry'], items: [
    { key: 'a', relevance: 2, facets: ['commit'], duplicateGroup: 'commit', sourceRef: 'note:synthetic' },
    { key: 'b', relevance: 2, facets: ['commit'], duplicateGroup: 'commit', sourceRef: 'file:synthetic' },
    { key: 'c', relevance: 2, facets: ['retry'], duplicateGroup: 'retry', sourceRef: 'note:synthetic' },
    { key: 'd', relevance: 0, facets: [], duplicateGroup: 'noise', sourceRef: 'file:noise' },
  ] };
  const a = scoreSelection(c, ['a', 'b', 'c', 'd'], 2), b = scoreSelection(c, ['a', 'c', 'b', 'd'], 2);
  assert.equal(a.facetCoverage8, .5); assert.equal(a.redundancy8, .5); assert.equal(a.complete8, 0);
  assert.equal(b.facetCoverage8, 1); assert.equal(b.redundancy8, 0); assert.equal(b.complete8, 1);
  assert.equal(a.ndcg8, b.ndcg8); assert.equal(b.uniqueSources8, 1);
});

test('invalid selection permutations cannot hide dropped evidence', () => {
  const c = { items: [{ key: 'a', relevance: 2 }, { key: 'b', relevance: 0 }] };
  assert.throws(() => scoreSelection(c, ['a']));
  assert.throws(() => scoreSelection(c, ['a', 'a']));
  assert.throws(() => scoreSelection(c, ['a', 'unknown']));
});
