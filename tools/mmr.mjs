// Experimental selection layer. Inputs contain no gold labels or source-group annotations.
export function normalize(vector) {
  if (!Array.isArray(vector) || !vector.length || vector.some(v => !Number.isFinite(v)))
    throw Error('Embedding must be a nonempty finite vector');
  const norm = Math.hypot(...vector);
  if (!Number.isFinite(norm) || norm <= 0) throw Error('Embedding norm must be positive');
  return vector.map(v => v / norm);
}

export function similarityMatrix(vectors) {
  const normalized = vectors.map(normalize);
  if (!normalized.length || normalized.some(v => v.length !== normalized[0].length))
    throw Error('Embedding dimensions must match');
  return normalized.map(a => normalized.map(b => Math.max(0, Math.min(1,
    a.reduce((sum, value, i) => sum + value * b[i], 0)))));
}

// R is linear normalized production rerank rank, not an invented LLM probability.
// The cosine redundancy term uses only the candidates already selected.
export function mmrOrder(order, matrix, lambda = .7) {
  if (!order.length || new Set(order).size !== order.length || lambda < 0 || lambda > 1
      || !Number.isFinite(lambda) || matrix.length !== order.length
      || matrix.some(row => row.length !== order.length || row.some(v => !Number.isFinite(v) || v < 0 || v > 1)))
    throw Error('Invalid MMR inputs');
  const selected = [], remaining = new Set(order.map((_, i) => i)), trace = [];
  while (remaining.size) {
    let best = null;
    for (const i of remaining) {
      const relevance = order.length === 1 ? 1 : 1 - i / (order.length - 1);
      const redundancy = selected.length ? Math.max(...selected.map(j => matrix[i][j])) : 0;
      const score = lambda * relevance - (1 - lambda) * redundancy;
      // Stable tie-breaking retains the production rerank order.
      if (!best || score > best.score + 1e-12) best = { i, relevance, redundancy, score };
    }
    selected.push(best.i); remaining.delete(best.i);
    trace.push({ key: order[best.i], relevance: best.relevance, redundancy: best.redundancy, score: best.score });
  }
  return { order: selected.map(i => order[i]), trace };
}

export function scoreSelection(testCase, order, k = 8) {
  const items = new Map(testCase.items.map(item => [item.key, item]));
  if (order.length !== items.size || new Set(order).size !== order.length || order.some(key => !items.has(key)))
    throw Error('Selection must preserve the candidate permutation');
  const chosen = order.slice(0, k).map(key => items.get(key)), grades = [...items.values()].map(x => x.relevance);
  const dcg = values => values.slice(0, k).reduce((sum, grade, i) => sum + (2 ** grade - 1) / Math.log2(i + 2), 0);
  const good = chosen.filter(x => x.relevance > 0), direct = grades.filter(x => x === 2).length;
  const groups = new Set(good.map(x => x.duplicateGroup));
  const facets = new Set(chosen.flatMap(x => x.facets ?? [])), required = testCase.requiredFacets ?? [];
  const covered = required.filter(f => facets.has(f)).length;
  return {
    ndcg8: dcg(chosen.map(x => x.relevance)) / dcg(grades.sort((a, b) => b - a)),
    grade2Recall8: chosen.filter(x => x.relevance === 2).length / direct,
    usefulRate8: good.length / chosen.length,
    facetCoverage8: required.length ? covered / required.length : null,
    complete8: required.length ? Number(covered === required.length) : null,
    // A fact-group gold metric, independent from the cosine that drives selection.
    redundancy8: testCase.requiredFacets?.length ? (good.length ? 1 - groups.size / good.length : 0) : null,
    uniqueSources8: new Set(chosen.map(x => x.sourceRef).filter(Boolean)).size,
  };
}
