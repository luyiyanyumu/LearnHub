// Isolated fixed-candidate selection A/B. prepare/analyze are offline; only rank calls the LLM.
// Gold is frozen before inference, never passed to Java or the embedding process.
import { readFile, writeFile, mkdir, rename, open, unlink } from 'node:fs/promises';
import { spawn, spawnSync } from 'node:child_process';
import { createInterface } from 'node:readline';
import { resolve, join, dirname, delimiter } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { sha, shuffled, inferenceItems, validateSuite, pairedBootstrap } from './run-rerank-ab.mjs';
import { normalize, similarityMatrix, mmrOrder, scoreSelection } from './mmr.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
export const PLAN = Object.freeze({ version: 'mmr-fixed-candidates-v1', snippet: 120, trials: 2,
  newQuestions: 24, newCandidates: 20, controlQuestions: 40, controlCandidates: 12, topK: 8,
  primaryLambda: .7, lambdas: Object.freeze([.7, .5, .9, 1]), permutations: 'mmr-ab-v1:id:trial',
  sourcePriority: 'new cohort only: stable first two per source before the shared LLM call; append all extras',
  position: 'after one shared production LLM order, before top8; lambda1 must equal baseline',
  relevance: 'R=(N-rank)/(N-1), rank is 1-based; stable LLM-order tie break',
  similarity: 'cosine(title｜production QueryAwareExcerpt120), clamped to [0,1]',
  inferenceFields: ['key', 'title', 'text'], bootstrapIterations: 10000,
  embedding: Object.freeze({ model: 'bge-m3', dimension: 1024,
    modelDigest: '7907646426070047a77226ac3e684fbbe8410524f7b4a74d02837e43f2146bab',
    options: Object.freeze({ num_thread: 1, num_gpu: 0 }) }),
  primaryMetric: 'new-cohort facetCoverage8; relevance and evidence retention reported as paired guardrails',
  limitation: 'Fixed-candidate selection only; no retrieval recall, generated answers or answer accuracy.' });

const PRODUCTION = ['tools/RerankAbProbe.java',
  'backend/src/main/java/org/dyh/learnhub/service/rerank/LlmListwiseReranker.java',
  'backend/src/main/java/org/dyh/learnhub/service/rerank/QueryAwareExcerpt.java',
  'backend/src/main/java/org/dyh/learnhub/ai/DeepSeekClient.java'];
export const SOURCE_FILES = Object.freeze([...PRODUCTION, 'tools/run-mmr-ab.mjs', 'tools/run-rerank-ab.mjs', 'tools/mmr.mjs', 'tools/mmr-embed.py',
  'backend/src/main/java/org/dyh/learnhub/ai/ModelRouting.java',
  'backend/src/main/java/org/dyh/learnhub/service/SettingsService.java']);
const CLASS_FILES = ['org/dyh/learnhub/service/rerank/LlmListwiseReranker.class',
  'org/dyh/learnhub/service/rerank/QueryAwareExcerpt.class', 'org/dyh/learnhub/ai/DeepSeekClient.class'];
const DIAGNOSTICS = ['status', 'fallback', 'qualityEligible', 'strictPermutation', 'truncated',
  'finishReason', 'errorType', 'candidateCount', 'snippet', 'rawOrder', 'invalidIds', 'duplicateIds',
  'missingIds', 'nonIntegerCount', 'acceptedCount', 'promptTokens', 'completionTokens', 'reasoningTokens',
  'requestTimeoutMs', 'maxTokens'];
const mean = values => values.length ? values.reduce((a, b) => a + b, 0) / values.length : null;
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
function fail(code) { const error = Error(code); error.code = code; throw error; }
function json(text) { try { return JSON.parse(text.replace(/^\uFEFF/, '')); } catch { fail('invalid_json'); } }
async function optionalJson(file) {
  try { return json(await readFile(file, 'utf8')); } catch (error) { if (error.code === 'ENOENT') return null; throw error; }
}
async function atomicJson(file, value) {
  const temp = `${file}.${process.pid}.tmp`;
  await writeFile(temp, JSON.stringify(value, null, 2) + '\n');
  await rename(temp, file);
}
function permutation(order, keys) {
  if (!Array.isArray(order) || order.length !== keys.length || new Set(order).size !== keys.length
      || order.some(key => !keys.includes(key))) fail('invalid_candidate_permutation');
}
export function assertSourceSnapshot(current, frozen) {
  if (!same(current, frozen)) fail('source_or_bytecode_changed');
}

export function validateNewSuite(suite, counts = PLAN) {
  validateSuite(suite);
  if (suite.version !== '1.0-synthetic-frozen') fail('new_suite_must_be_frozen_v1');
  if (suite.cases.length !== counts.newQuestions) fail('new_question_count_mismatch');
  for (const c of suite.cases) {
    if (c.items.length !== counts.newCandidates || !Array.isArray(c.requiredFacets)
        || !c.requiredFacets.length || new Set(c.requiredFacets).size !== c.requiredFacets.length
        || c.requiredFacets.some(f => typeof f !== 'string' || !f.trim())) fail('invalid_new_case');
    const covered = new Set();
    for (const item of c.items) {
      if (typeof item.sourceRef !== 'string' || !item.sourceRef.trim()
          || typeof item.duplicateGroup !== 'string' || !item.duplicateGroup.trim()
          || !Array.isArray(item.facets) || new Set(item.facets).size !== item.facets.length
          || item.facets.some(f => !c.requiredFacets.includes(f))
          || (item.relevance === 2 ? !item.facets.length : item.facets.length)) fail('invalid_facet_annotations');
      for (const facet of item.facets) covered.add(facet);
    }
    if (c.requiredFacets.some(f => !covered.has(f))) fail('uncovered_required_facet');
  }
  return suite;
}

/** Mirrors KnowledgeRetrievalService.diversify, with no hard quota or candidate loss. */
export function sourceSoftPriority(items) {
  const first = [], extra = [], counts = new Map(), keys = new Set();
  for (const item of items) {
    if (keys.has(item.key)) fail('duplicate_candidate');
    keys.add(item.key);
    if (typeof item.sourceRef !== 'string' || !item.sourceRef) fail('missing_source');
    const count = (counts.get(item.sourceRef) || 0) + 1; counts.set(item.sourceRef, count);
    (count <= 2 ? first : extra).push(item);
  }
  return [...first, ...extra];
}

function safeRank(result, trial) {
  permutation(result?.order, trial.inputOrder);
  if (result.excerptsHash !== trial.excerptsHash || !Number.isFinite(result.elapsedMs) || result.elapsedMs < 0
      || typeof result.diagnostics?.status !== 'string'
      || typeof result.diagnostics?.fallback !== 'boolean'
      || typeof result.diagnostics?.qualityEligible !== 'boolean') fail('invalid_rank_result');
  const diagnostics = Object.fromEntries(DIAGNOSTICS.filter(key => key in result.diagnostics)
    .map(key => [key, result.diagnostics[key]]));
  return { order: result.order, elapsedMs: result.elapsedMs, excerptsHash: result.excerptsHash, diagnostics };
}

function validateExcerpts(trial, result) {
  if (!Array.isArray(result.excerpts) || result.excerpts.length !== trial.items.length) fail('missing_excerpts');
  const excerpts = result.excerpts.map((entry, index) => {
    const item = trial.items[index];
    if (entry.key !== item.key || entry.title !== item.title || typeof entry.excerpt !== 'string'
        || entry.excerpt.length > PLAN.snippet
        || entry.document !== entry.title + (entry.excerpt ? '｜' + entry.excerpt : '')) fail('invalid_production_excerpt');
    // Keep this field order identical to the production Java adapter's serialized hash.
    return { key: entry.key, title: entry.title, excerpt: entry.excerpt, document: entry.document };
  });
  if (sha(JSON.stringify(excerpts)) !== result.excerptsHash) fail('excerpt_hash_mismatch');
  return excerpts;
}

export function validateControl(suite, rows, metadata, suiteHash, counts = PLAN) {
  validateSuite(suite);
  if (suite.cases.length !== counts.controlQuestions || suite.cases.some(c => c.items.length !== counts.controlCandidates)
      || metadata.suiteHash !== suiteHash || metadata.snippet !== counts.snippet || metadata.trials !== counts.trials
      || !metadata.completedAt || rows.length !== counts.controlQuestions * counts.trials) fail('invalid_control_snapshot');
  const found = new Map();
  for (const row of rows) {
    const c = suite.cases.find(c => c.id === row.id), key = `${row.id}:${row.trial}`;
    if (!c || !Number.isInteger(row.trial) || row.trial < 0 || row.trial >= counts.trials || found.has(key)) fail('invalid_control_trial');
    const items = inferenceItems(shuffled(c.items, `rerank-ab-v1:${c.id}:${row.trial}`));
    const trial = { inputOrder: items.map(x => x.key), items, excerptsHash: row.llm?.excerptsHash };
    if (row.candidateHash !== sha(JSON.stringify(items)) || !same(row.input?.order, trial.inputOrder)) fail('control_candidate_hash_mismatch');
    validateExcerpts(trial, { excerpts: row.excerpts, excerptsHash: trial.excerptsHash });
    safeRank(row.llm, trial);
    found.set(key, row);
  }
  return found;
}

/** The extractor must be the real Java excerpt arm; tests inject a synthetic equivalent. */
export async function buildPreparedTrials(suite, controlSuite, controlRows, extract, counts = PLAN) {
  const trials = [];
  for (const c of suite.cases) for (let trial = 0; trial < counts.trials; trial++) {
    const sourceOrder = sourceSoftPriority(shuffled(c.items, `mmr-ab-v1:${c.id}:${trial}`));
    const items = inferenceItems(sourceOrder);
    const row = { cohort: 'new', id: c.id, category: c.category, question: c.question, trial,
      items, inputOrder: items.map(item => item.key), candidateHash: sha(JSON.stringify(items)) };
    const result = await extract({ id: c.id, question: c.question, items, arm: 'excerpt' });
    row.excerpts = validateExcerpts(row, result); row.excerptsHash = result.excerptsHash;
    row.documents = row.excerpts.map(entry => ({ key: entry.key, id: sha(entry.document) }));
    trials.push(row);
  }
  for (const c of controlSuite.cases) for (let trial = 0; trial < counts.trials; trial++) {
    const saved = controlRows.find(row => row.id === c.id && row.trial === trial);
    if (!saved) fail('missing_control_trial');
    const items = inferenceItems(shuffled(c.items, `rerank-ab-v1:${c.id}:${trial}`));
    const row = { cohort: 'control', id: c.id, category: c.category, question: c.question, trial, items,
      inputOrder: items.map(item => item.key), candidateHash: sha(JSON.stringify(items)), excerptsHash: saved.llm.excerptsHash };
    row.excerpts = validateExcerpts(row, { excerpts: saved.excerpts, excerptsHash: row.excerptsHash });
    row.documents = row.excerpts.map(entry => ({ key: entry.key, id: sha(entry.document) }));
    row.existingRank = safeRank(saved.llm, row);
    trials.push(row);
  }
  return trials;
}

export function embeddingDocuments(trials) {
  const documents = new Map();
  for (const trial of trials) for (const entry of trial.excerpts) {
    const id = sha(entry.document);
    if (documents.has(id) && documents.get(id) !== entry.document) fail('document_hash_collision');
    documents.set(id, entry.document);
  }
  return { documents: [...documents].map(([id, text]) => ({ id, text })) };
}

export function validateVectors(vectors, documents, inputHash) {
  if (vectors.status !== 'completed' || vectors.inputHash !== inputHash || vectors.normalized !== true
      || vectors.model !== PLAN.embedding.model || vectors.modelDigest !== PLAN.embedding.modelDigest
      || vectors.dimension !== PLAN.embedding.dimension || !vectors.options
      || Object.keys(vectors.options).length !== Object.keys(PLAN.embedding.options).length
      || Object.entries(PLAN.embedding.options).some(([key, value]) => vectors.options[key] !== value)
      || vectors.completed !== documents.length || vectors.documentCount !== documents.length
      || !Number.isInteger(vectors.dimension) || vectors.dimension <= 0
      || !Array.isArray(vectors.vectors) || vectors.vectors.length !== documents.length) fail('invalid_embedding_snapshot');
  const result = new Map();
  for (let i = 0; i < documents.length; i++) {
    const { id, vector } = vectors.vectors[i];
    if (id !== documents[i].id || result.has(id) || !Array.isArray(vector)
        || vector.length !== vectors.dimension || vector.some(x => typeof x !== 'number')) fail('embedding_document_mismatch');
    const normalized = normalize(vector), norm = Math.hypot(...vector);
    if (Math.abs(norm - 1) > 1e-5) fail('embedding_not_normalized');
    result.set(id, normalized);
  }
  return result;
}

export function validateRankRows(rows, trials, complete = false) {
  if (!Array.isArray(rows)) fail('invalid_ranks');
  const seen = new Set();
  for (const row of rows) {
    const key = `${row.cohort}:${row.id}:${row.trial}`;
    const trial = trials.find(t => key === `${t.cohort}:${t.id}:${t.trial}`);
    if (!trial || seen.has(key) || row.candidateHash !== trial.candidateHash
        || row.excerptsHash !== trial.excerptsHash || row.reused !== (trial.cohort === 'control')) fail('rank_resume_mismatch');
    safeRank(row.llm, trial); seen.add(key);
    if (row.reused && !same(row.llm, trial.existingRank)) fail('control_rank_changed');
  }
  if (complete && rows.length !== trials.length) fail('incomplete_rank_experiment');
  return rows;
}

export function evaluateTrials(suite, controlSuite, trials, rows, vectors, plan = PLAN) {
  validateRankRows(rows, trials, true);
  return trials.map(trial => {
    const c = (trial.cohort === 'new' ? suite : controlSuite).cases.find(c => c.id === trial.id);
    if (!c) fail('missing_gold_case');
    const row = rows.find(row => row.cohort === trial.cohort && row.id === trial.id && row.trial === trial.trial);
    const documentIds = new Map(trial.documents.map(x => [x.key, x.id]));
    const candidateVectors = row.llm.order.map(key => {
      const vector = vectors.get(documentIds.get(key)); if (!vector) fail('missing_candidate_embedding'); return vector;
    });
    const matrix = similarityMatrix(candidateVectors);
    const score = order => {
      const value = scoreSelection(c, order, plan.topK);
      if (trial.cohort === 'control') value.uniqueSources8 = null;
      if (Object.values(value).some(x => x !== null && !Number.isFinite(x))) fail('nonfinite_selection_metric');
      return value;
    };
    const variants = Object.fromEntries(plan.lambdas.map(lambda => {
      const selected = mmrOrder(row.llm.order, matrix, lambda);
      if (lambda === 1 && !same(selected.order, row.llm.order)) fail('lambda1_sanity_failed');
      return [String(lambda), { ...selected, score: score(selected.order) }];
    }));
    return { cohort: trial.cohort, id: trial.id, category: trial.category, trial: trial.trial,
      diagnostics: row.llm.diagnostics, baseline: { order: row.llm.order, score: score(row.llm.order) }, variants };
  });
}

function groupedSummary(rows, plan) {
  const groups = new Map();
  for (const row of rows) { if (!groups.has(row.id)) groups.set(row.id, []); groups.get(row.id).push(row); }
  if (!rows.length) return { trials: 0, questions: 0, baseline: {}, variants: {} };
  const metrics = Object.keys(rows[0].baseline.score).filter(metric => rows.some(row => row.baseline.score[metric] !== null));
  const byQuestion = select => [...groups.values()].map(group => mean(group.map(select).filter(x => x !== null)));
  const baseline = Object.fromEntries(metrics.map(metric => [metric, mean(byQuestion(row => row.baseline.score[metric]))]));
  const variants = Object.fromEntries(plan.lambdas.map(lambda => {
    const arm = String(lambda);
    return [arm, { metrics: Object.fromEntries(metrics.map(metric => [metric, mean(byQuestion(row => row.variants[arm].score[metric]))])),
      minusBaseline: Object.fromEntries(metrics.map(metric => [metric,
        pairedBootstrap(byQuestion(row => row.variants[arm].score[metric] - row.baseline.score[metric]),
          plan.bootstrapIterations, `mmr-ab-v1:${arm}:${metric}`)])) }];
  }));
  return { trials: rows.length, questions: groups.size, baseline, variants,
    bootstrapUnit: 'question; first average the available frozen permutations within each question',
    permutationCounts: Object.fromEntries([...groups].map(([id, group]) => [id, group.length])) };
}

export function summarizeEvaluations(evaluations, plan = PLAN) {
  const cohort = name => {
    const rows = evaluations.filter(row => row.cohort === name);
    const success = rows.filter(row => row.diagnostics.qualityEligible === true && !row.diagnostics.fallback);
    const fallback = rows.filter(row => row.diagnostics.fallback);
    const successfulIds = new Set([...new Set(rows.map(row => row.id))].filter(id => {
      const group = rows.filter(row => row.id === id);
      return group.length === plan.trials && group.every(row => row.diagnostics.qualityEligible && !row.diagnostics.fallback);
    }));
    return { gold: name === 'new' ? 'grade2 fully supports at least one required facet'
      : 'old grade2 sufficiently answers core requirements; no facet/source annotations',
      candidates: name === 'new' ? plan.newCandidates : plan.controlCandidates,
      all: groupedSummary(rows, plan), successfulOnly: groupedSummary(success, plan),
      successfulQuestionsOnly: groupedSummary(rows.filter(row => successfulIds.has(row.id)), plan),
      fallbackOnly: groupedSummary(fallback, plan),
      otherNonEligible: groupedSummary(rows.filter(row => !row.diagnostics.qualityEligible && !row.diagnostics.fallback), plan),
      failures: fallback.length, repairedOutputs: rows.filter(row => row.diagnostics.status === 'repaired').length,
      truncatedOutputs: rows.filter(row => row.diagnostics.truncated).length,
      categories: Object.fromEntries([...new Set(rows.map(row => row.category))].map(category =>
        [category, groupedSummary(rows.filter(row => row.category === category), plan)])) };
  };
  return { plan, cohorts: { new: cohort('new'), control: cohort('control') },
    limits: ['New and old cohorts have different gold semantics/candidate counts and are never pooled.',
      'Source count measures provenance diversity, not information coverage; gold duplicateGroup measures repeated facts.',
      'Success-only strata condition on observed LLM output quality; all-trial paired results remain primary.',
      'Two permutations are correlated repeats, not independent questions.', plan.limitation] };
}

export function renderReport(metadata, summary, embeddings) {
  const fmt = value => value === null || value === undefined ? '—' : value.toFixed(4);
  const table = (group, metrics) => ['| 指标 | 当前LLM后top8 | MMR λ=0.7 | 配对差值及95%区间 |', '|---|---:|---:|---|',
    ...metrics.map(metric => { const b = group.variants['0.7']; const d = b?.minusBaseline[metric];
      return `| ${metric} | ${fmt(group.baseline[metric])} | ${fmt(b?.metrics[metric])} | ${d ? `${fmt(d.delta)} [${d.ci95.map(fmt).join(', ')}]` : '—'} |`; })].join('\n');
  const newer = summary.cohorts.new, control = summary.cohorts.control;
  const sensitivity = ['| λ | facetCoverage8 | complete8 | redundancy8 | NDCG@8 |', '|---|---:|---:|---:|---:|',
    ...metadata.plan.lambdas.map(lambda => { const m = newer.all.variants[String(lambda)]?.metrics || {};
      return `| ${lambda} | ${fmt(m.facetCoverage8)} | ${fmt(m.complete8)} | ${fmt(m.redundancy8)} | ${fmt(m.ndcg8)} |`; })].join('\n');
  return `# 固定候选 MMR A/B\n\n新合成集 ${newer.all.questions} 个问题，每题20候选、2种冻结排列；旧集 ${control.all.questions} 个问题、每题12候选，仅复用已冻结LLM排序作为相关性控制。两个cohort分别报告，不合并nDCG。\n\nA与B共享同一次生产LLM排序；A直接取前8，B应用MMR后取前8。新集在LLM前共同使用每来源前2优先的稳定排序，保留所有候选。主λ预先固定0.7，0.5/0.9仅敏感性检查，1为严格基线自检。模型不接收gold、facet、duplicateGroup、rationale或sourceRef。\n\n${table(newer.all, ['facetCoverage8', 'complete8', 'redundancy8', 'ndcg8', 'grade2Recall8', 'usefulRate8', 'uniqueSources8'])}\n\n${sensitivity}\n\n旧40题相关性控制：\n\n${table(control.all, ['ndcg8', 'grade2Recall8', 'usefulRate8'])}\n\n新集LLM原序回退 ${newer.failures}/${newer.all.trials}；成功输出 ${newer.successfulOnly.trials}，两排列均成功的问题 ${newer.successfulQuestionsOnly.questions}；修复 ${newer.repairedOutputs}，截断 ${newer.truncatedOutputs}。旧集回退 ${control.failures}/${control.all.trials}。全量、成功、回退及其余非合格输出分层结果见summary.json；失败样本未静默删除。\n\n## 方法与局限\n\n- 先对同一问题的两种排列求均值，再按问题配对bootstrap（${metadata.plan.bootstrapIterations}次）；分层成功/回退统计按实际可用排列求均值，另报两排列均成功的问题，避免伪增样本数。\n- 新集grade2代表完整支持至少一个必要facet，旧集grade2代表完整满足核心问题；facet集合是必要信息的AND覆盖，同一facet的多个片段是备选。来源数只是诊断，信息去冗余按冻结duplicateGroup独立计算，不能把换来源当作增加信息。\n- R来自生产LLM名次线性归一化，并非概率；相似度使用真实${embeddings.model}语义向量和相同120字符生产摘录，余弦限制到[0,1]。Embedding缓存按精确title｜excerpt UTF-8 SHA-256复用，模型摘要 ${embeddings.modelDigest}。\n- 固定候选选材评测只测覆盖、冗余和证据保留，不测召回率或生成答案准确率；不能从这些指标推断端到端RAG提升。合成题集由智能体编写与独立复核，不能代表真实用户分布。旧排序的重排时间来自前次运行，本次没有重复调用旧题LLM。\n- 120字符窗口和20候选单批固定，不代表生产长上下文或多批行为；不同λ的结果不用于事后选择主参数。相关性guardrail给出配对差值与区间，不自动宣称提升或部署。\n- 冻结计划/语料/摘录/候选/源码/字节码哈希见metadata.json；选择记录见selections.json。线上配置、语料及旧A/B工具未修改。\n\n新题集SHA-256：\`${metadata.suiteHash}\`；旧题集：\`${metadata.controlSuiteHash}\`。\n`;
}

async function sourceSnapshot(probeDirectory) {
  const hashes = {};
  for (const file of SOURCE_FILES) hashes[file] = sha(await readFile(join(ROOT, file)));
  for (const file of CLASS_FILES) hashes[`bytecode:${file}`] = sha(await readFile(join(ROOT, 'backend/target/classes', file)));
  hashes['bytecode:RerankAbProbe.class'] = sha(await readFile(join(probeDirectory, 'RerankAbProbe.class')));
  hashes['classpath'] = sha(await readFile(join(ROOT, 'backend/target/rerank-ab-classpath.txt')));
  return hashes;
}
async function startProbe(config, probeDirectory) {
  const dependencies = (await readFile(join(ROOT, 'backend/target/rerank-ab-classpath.txt'), 'utf8')).trim();
  const child = spawn('java', ['-Dfile.encoding=UTF-8', '-cp',
    [join(ROOT, 'backend/target/classes'), probeDirectory, dependencies].join(delimiter), 'RerankAbProbe'],
  { cwd: ROOT, windowsHide: true });
  let pending = null, failed = false;
  const reject = code => { failed = true; if (pending) { clearTimeout(pending.timer); pending.reject(Object.assign(Error(code), { code })); pending = null; } };
  const reader = createInterface({ input: child.stdout });
  reader.on('line', line => {
    if (!line.startsWith('{')) return;
    if (!pending) return reject('unexpected_probe_output');
    let result; try { result = json(line); } catch { return reject('invalid_probe_json'); }
    const waiter = pending; pending = null; clearTimeout(waiter.timer); waiter.resolve(result);
  });
  child.stderr.on('data', () => {}); // No upstream error text or credentials persisted.
  child.on('error', () => reject('probe_launch_failed'));
  child.on('exit', () => reject('probe_stopped'));
  const send = value => new Promise((resolve, rejectSend) => {
    if (failed || pending) return rejectSend(Object.assign(Error('probe_not_single_inflight'), { code: 'probe_not_single_inflight' }));
    const timer = setTimeout(() => { reject('probe_timeout'); child.kill(); }, 45000);
    pending = { resolve, reject: rejectSend, timer };
    child.stdin.write(JSON.stringify(value) + '\n', error => { if (error) reject('probe_stdin_failed'); });
  });
  const close = () => { child.stdin.end(); const timer = setTimeout(() => child.kill(), 3000); timer.unref(); child.once('exit', () => clearTimeout(timer)); };
  try { if ((await send(config)).status !== 'ready') fail('probe_not_ready'); }
  catch (error) { child.kill(); throw error; }
  return { send, close };
}

async function api(base, path) {
  const response = await fetch(base + path, { signal: AbortSignal.timeout(15000) });
  if (!response.ok) fail('application_status_failed');
  const value = await response.json();
  if (value.code !== 200 && value.code !== 0) fail('application_status_failed');
  return value.data;
}
async function routingState(base) {
  const routing = await api(base, '/api/model/routing'), rerank = await api(base, '/api/kb/rerank');
  const profile = routing.find(row => row.task === 'rerank');
  if (!profile || !/^[A-Za-z0-9_-]+$/.test(profile.target) || rerank.llm?.snippet !== PLAN.snippet
      || !rerank.enabled || rerank.active !== 'llm') fail('unexpected_rerank_configuration');
  return { enabled: rerank.enabled, backend: rerank.backend, active: rerank.active,
    model: rerank.llm.model, snippet: rerank.llm.snippet, target: profile.target };
}
async function routedConfig(options) {
  const before = await routingState(options.base);
  if (!/^[A-Za-z0-9_-]+$/.test(options.database)) fail('invalid_database_name');
  const sql = `SELECT JSON_OBJECT('baseUrl',base_url,'apiKey',api_key,'model',model,'provider',provider) FROM model_profile WHERE id='${before.target}';`;
  const result = spawnSync('docker', ['exec', '-i', options.container, 'sh', '-c',
    `MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B -D ${options.database}`],
  { input: sql, encoding: 'utf8', maxBuffer: 1024 * 1024, windowsHide: true });
  if (result.status !== 0) fail('profile_read_failed');
  const config = json(result.stdout.trim());
  if (config.provider !== 'deepseek' || config.model !== before.model || !config.apiKey || !config.baseUrl) fail('unexpected_profile');
  return { before, config };
}

async function withLock(directory, action) {
  await mkdir(directory, { recursive: true }); const file = join(directory, '.mmr-ab.lock');
  let lock; try { lock = await open(file, 'wx'); } catch (error) { if (error.code === 'EEXIST') fail('output_directory_locked'); throw error; }
  try { await lock.writeFile(JSON.stringify({ pid: process.pid })); return await action(); }
  finally { await lock.close(); await unlink(file); }
}

async function frozenState(options) {
  const directory = options.directory, metadata = await optionalJson(join(directory, 'metadata.json'));
  if (!metadata || !same(metadata.plan, PLAN)) fail('missing_or_changed_plan');
  const files = { suite: 'suite-frozen.json', controlSuite: 'control-suite-frozen.json',
    prepared: 'prepared.json', documents: 'embedding-documents.json' }, values = {};
  for (const [key, file] of Object.entries(files)) {
    const text = await readFile(join(directory, file), 'utf8');
    if (sha(text) !== metadata.frozenFiles[file]) fail('frozen_file_changed'); values[key] = json(text);
  }
  validateNewSuite(values.suite);
  if (sha(await readFile(options.suite)) !== metadata.suiteHash) fail('source_suite_changed');
  assertSourceSnapshot(await sourceSnapshot(options.probeDirectory), metadata.sourceHashes);
  return { metadata, ...values };
}

export async function prepareExperiment(options) {
  return withLock(options.directory, async () => {
    const suiteText = await readFile(options.suite, 'utf8'), suite = validateNewSuite(json(suiteText));
    const controlText = await readFile(join(options.controlDirectory, 'suite-frozen.json'), 'utf8');
    const controlSuite = json(controlText), controlRowsText = await readFile(join(options.controlDirectory, 'results.json'), 'utf8');
    const controlRows = json(controlRowsText), controlMeta = json(await readFile(join(options.controlDirectory, 'metadata.json'), 'utf8'));
    validateControl(controlSuite, controlRows, controlMeta, sha(controlText));
    const sourceHashes = await sourceSnapshot(options.probeDirectory);
    if (PRODUCTION.some(file => sourceHashes[file] !== controlMeta.sourceHashes?.[file])) fail('control_production_source_changed');
    const previous = await optionalJson(join(options.directory, 'metadata.json'));
    if (previous) {
      const saved = await frozenState(options);
      if (previous.controlResultsHash !== sha(controlRowsText) || previous.controlSuiteHash !== sha(controlText)) fail('control_snapshot_changed');
      return { status: 'prepared', resumed: true, trials: saved.prepared.length, documents: saved.documents.documents.length };
    }
    if (await optionalJson(join(options.directory, 'ranks.json'))) fail('unfrozen_existing_ranks');
    // This dummy configuration cannot access a real provider; only the CPU excerpt arm runs.
    const probe = await startProbe({ baseUrl: 'http://127.0.0.1:1', apiKey: '', model: 'excerpt-only',
      snippet: PLAN.snippet, crossUrl: 'http://127.0.0.1:1/rerank' }, options.probeDirectory);
    let trials; try { trials = await buildPreparedTrials(suite, controlSuite, controlRows, request => probe.send(request)); }
    finally { probe.close(); }
    const documents = embeddingDocuments(trials);
    await writeFile(join(options.directory, 'suite-frozen.json'), suiteText);
    await writeFile(join(options.directory, 'control-suite-frozen.json'), controlText);
    await atomicJson(join(options.directory, 'prepared.json'), trials);
    await atomicJson(join(options.directory, 'embedding-documents.json'), documents);
    const frozenFiles = {};
    for (const file of ['suite-frozen.json', 'control-suite-frozen.json', 'prepared.json', 'embedding-documents.json'])
      frozenFiles[file] = sha(await readFile(join(options.directory, file)));
    const metadata = { preparedAt: new Date().toISOString(), plan: PLAN, suiteHash: sha(suiteText), controlSuiteHash: sha(controlText),
      controlResultsHash: sha(controlRowsText), controlModel: controlMeta.llmModel, sourceHashes, frozenFiles };
    await atomicJson(join(options.directory, 'metadata.json'), metadata);
    return { status: 'prepared', trials: trials.length, documents: documents.documents.length,
      suiteHash: metadata.suiteHash, embeddingInputHash: frozenFiles['embedding-documents.json'] };
  });
}

export async function rankExperiment(options, runtime = {}) {
  const loadFrozen = runtime.frozenState || frozenState, getConfig = runtime.routedConfig || routedConfig;
  const createProbe = runtime.startProbe || startProbe, readRouting = runtime.routingState || routingState;
  const log = runtime.log || (value => console.log(JSON.stringify(value)));
  return withLock(options.directory, async () => {
    const { metadata, prepared } = await loadFrozen(options);
    const file = join(options.directory, 'ranks.json'), rows = validateRankRows(await optionalJson(file) || [], prepared);
    for (const trial of prepared.filter(t => t.cohort === 'control')) {
      if (!rows.some(row => row.cohort === trial.cohort && row.id === trial.id && row.trial === trial.trial)) {
        rows.push({ cohort: trial.cohort, id: trial.id, trial: trial.trial, candidateHash: trial.candidateHash,
          excerptsHash: trial.excerptsHash, reused: true, llm: trial.existingRank });
      }
    }
    await atomicJson(file, rows);
    const pending = prepared.filter(t => t.cohort === 'new'
      && !rows.some(row => row.cohort === t.cohort && row.id === t.id && row.trial === t.trial));
    if (!pending.length) {
      validateRankRows(rows, prepared, true);
      // A crash after the final atomic row write still needs the end-of-run config guard.
      const state = await optionalJson(join(options.directory, 'rank-state.json'));
      if (!state?.before) fail('missing_rank_configuration_guard');
      if (!state.completedAt) {
        const after = await readRouting(options.base);
        if (!same(after, state.before)) fail('configuration_changed_during_rank');
        await atomicJson(join(options.directory, 'rank-state.json'), { ...state, after, completedAt: new Date().toISOString() });
      } else if (!same(state.before, state.after)) fail('invalid_rank_configuration_guard');
      return { status: 'ranked', resumed: true, trials: rows.length };
    }
    const { config, before } = await getConfig(options);
    if (config.model !== metadata.controlModel) fail('control_llm_model_changed');
    const savedConfig = await optionalJson(join(options.directory, 'rank-state.json'));
    if (savedConfig && !same(savedConfig.before, before)) fail('rank_configuration_changed');
    const state = savedConfig || { startedAt: new Date().toISOString(), before };
    await atomicJson(join(options.directory, 'rank-state.json'), state);
    const probe = await createProbe({ baseUrl: config.baseUrl, apiKey: config.apiKey, model: config.model,
      snippet: PLAN.snippet, crossUrl: 'http://127.0.0.1:1/rerank' }, options.probeDirectory);
    config.apiKey = ''; // Config is never written, logged, or placed in command arguments.
    try {
      for (const trial of pending) {
        const result = await probe.send({ id: trial.id, question: trial.question, items: trial.items, arm: 'llm' });
        const llm = safeRank(result, trial);
        rows.push({ cohort: trial.cohort, id: trial.id, trial: trial.trial, candidateHash: trial.candidateHash,
          excerptsHash: trial.excerptsHash, reused: false, llm });
        await atomicJson(file, rows);
        log({ status: 'rank_progress', newCompleted: rows.filter(row => !row.reused).length,
          newTotal: PLAN.newQuestions * PLAN.trials, id: trial.id, trial: trial.trial,
          diagnostic: llm.diagnostics.status, elapsedMs: llm.elapsedMs });
      }
      const after = await readRouting(options.base);
      if (!same(after, before)) fail('configuration_changed_during_rank');
      await loadFrozen(options);
      validateRankRows(rows, prepared, true);
      await atomicJson(join(options.directory, 'rank-state.json'), { ...state, after, completedAt: new Date().toISOString() });
      return { status: 'ranked', trials: rows.length, newCalls: PLAN.newQuestions * PLAN.trials };
    } finally { probe.close(); }
  });
}

export async function analyzeExperiment(options) {
  return withLock(options.directory, async () => {
    const { metadata, suite, controlSuite, prepared, documents } = await frozenState(options);
    const state = await optionalJson(join(options.directory, 'rank-state.json'));
    if (!state?.completedAt || !same(state.before, state.after)) fail('rank_not_verified_complete');
    const ranksText = await readFile(join(options.directory, 'ranks.json'), 'utf8'), rows = json(ranksText);
    validateRankRows(rows, prepared, true);
    const vectorText = await readFile(options.vectors, 'utf8'), embeddings = json(vectorText);
    const vectors = validateVectors(embeddings, documents.documents, metadata.frozenFiles['embedding-documents.json']);
    const identity = { ranksHash: sha(ranksText), vectorsHash: sha(vectorText), model: embeddings.model, modelDigest: embeddings.modelDigest };
    const previous = await optionalJson(join(options.directory, 'analysis-state.json'));
    if (previous && !same(previous, identity)) fail('analysis_inputs_changed');
    const selections = evaluateTrials(suite, controlSuite, prepared, rows, vectors);
    const summary = summarizeEvaluations(selections);
    await atomicJson(join(options.directory, 'analysis-state.json'), identity);
    await atomicJson(join(options.directory, 'selections.json'), selections);
    await atomicJson(join(options.directory, 'summary.json'), summary);
    await writeFile(join(options.directory, 'report.md'), renderReport(metadata, summary, embeddings));
    return { status: 'analyzed', directory: options.directory,
      newQuestions: summary.cohorts.new.all.questions, controlQuestions: summary.cohorts.control.all.questions };
  });
}

export function parseArguments(args) {
  const phase = args[0]; if (!['prepare', 'rank', 'analyze'].includes(phase)) fail('phase_required_prepare_rank_analyze');
  const known = new Set(['--suite', '--out', '--control', '--probe-java', '--vectors', '--base', '--container', '--database']);
  const values = {};
  for (let i = 1; i < args.length; i += 2) {
    if (!known.has(args[i]) || !args[i + 1] || args[i + 1].startsWith('--') || args[i] in values) fail('invalid_cli_argument');
    values[args[i]] = args[i + 1];
  }
  const directory = resolve(values['--out'] || join(ROOT, 'output/mmr-ab-2026-10-10'));
  return { phase, directory, suite: resolve(values['--suite'] || join(ROOT, 'tools/fixtures/mmr-ab.synthetic.json')),
    controlDirectory: resolve(values['--control'] || join(ROOT, 'output/rerank-ab-2026-10-10')),
    probeDirectory: resolve(values['--probe-java'] || join(ROOT, 'output/rerank-ab-2026-10-10/java')),
    vectors: resolve(values['--vectors'] || join(directory, 'embeddings.json')),
    base: values['--base'] || 'http://localhost:18080', container: values['--container'] || 'learn-hub-mysql',
    database: values['--database'] || 'learn_hub' };
}
async function main() {
  const options = parseArguments(process.argv.slice(2));
  const result = await ({ prepare: prepareExperiment, rank: rankExperiment, analyze: analyzeExperiment })[options.phase](options);
  console.log(JSON.stringify(result));
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href)
  main().catch(error => { console.error(JSON.stringify({ status: 'error', code: /^[a-z_]+$/.test(error.code || '') ? error.code : 'mmr_cli_failed' })); process.exitCode = 1; });
