// Fixed knowledge-base evaluation runner. Requests answers through LearnHub APIs;
// never contacts providers directly or updates settings. All outputs contain local data.
// node tools/run-fixed-kb-eval.mjs --suite output/fixed-kb-eval/suite.json \
//   --corpus output/fixed-kb-eval/corpus.json --out output/fixed-kb-eval/run
import { readFile, writeFile, mkdir, readdir, rename } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { resolve, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

export const RUNNER_VERSION = 'fixed-kb-eval-v1';
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const SAFE_SETTINGS = [
  'ai.model', 'ai.base_url', 'ai.max_tokens', 'ai.temperature', 'ai.thinking', 'ai.reasoning_effort',
  'ai.model_for_chat', 'ai.model_for_rerank', 'ai.model_for_grounding', 'ai.model_for_rewrite',
  'ai.model_for_embed', 'ai.query_rewrite', 'ai.vector_enabled', 'ai.active_profile',
  'kb.vector_backend', 'kb.contextual_embed', 'kb.rerank', 'kb.rerank_backend', 'kb.rerank_url',
  'kb.rerank_snippet', 'kb.grounding', 'kb.wiki_inject', 'kg.inject',
];
const SOURCE_FILES = [
  'backend/src/main/java/org/dyh/learnhub/service/RagAnswerEvalService.java',
  'backend/src/main/java/org/dyh/learnhub/service/RetrievalContextService.java',
  'backend/src/main/java/org/dyh/learnhub/service/KnowledgeRetrievalService.java',
  'backend/src/main/java/org/dyh/learnhub/service/GroundingService.java',
  'backend/src/main/java/org/dyh/learnhub/ai/AgentService.java',
];
export const hash = value => createHash('sha256').update(typeof value === 'string' ? value : canonical(value)).digest('hex');
export function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value && typeof value === 'object') return `{${Object.keys(value).sort().map(k => `${JSON.stringify(k)}:${canonical(value[k])}`).join(',')}}`;
  return JSON.stringify(value);
}
const pick = (object, fields) => Object.fromEntries(fields.filter(k => object?.[k] !== undefined).map(k => [k, object[k]]));
const splitRefs = value => Array.isArray(value) ? value : String(value || '').split('|').filter(Boolean);
export function sourceRef(item) {
  if (typeof item === 'string') return item;
  return item?.ref || ((item?.sourceType || item?.type) && (item?.sourceId ?? item?.id) != null
    ? `${item.sourceType || item.type}:${item.sourceId ?? item.id}` : '');
}
export function normalizeText(value) { return String(value || '').normalize('NFKC').replace(/\s+/gu, '').toLowerCase(); }
export function redact(value, key = '') {
  if (/api.?key|authorization|password|secret|token$|keyhint/iu.test(key) && !/tokens$/iu.test(key)) return '[REDACTED]';
  if (Array.isArray(value)) return value.map(v => redact(v));
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, redact(v, k)]));
  return typeof value === 'string' ? value.replace(/\bsk-[A-Za-z0-9_-]{8,}\b/gu, '[REDACTED]') : value;
}

export function validateSuite(suite, corpus, { requireDbIds = true } = {}) {
  if (suite.schemaVersion !== 1 || !Array.isArray(suite.cases) || !suite.cases.length) throw new Error('Suite needs schemaVersion=1 and nonempty cases');
  const seen = new Set(), dbSeen = new Set();
  const sources = new Map(corpus.sources.map(s => [s.ref, s]));
  for (const c of suite.cases) {
    if (!/^[a-zA-Z0-9_-]+$/u.test(String(c.id)) || seen.has(c.id)) throw new Error(`Invalid or duplicate case id: ${c.id}`);
    seen.add(c.id);
    if (!c.question?.trim() || !['answer', 'abstain', 'clarify'].includes(c.expectedBehavior)) throw new Error(`Invalid question/expectedBehavior: ${c.id}`);
    if (requireDbIds && (!Number.isSafeInteger(c.dbCaseId) || c.dbCaseId <= 0 || dbSeen.has(c.dbCaseId))) throw new Error(`Missing, invalid or duplicate dbCaseId: ${c.id}`);
    dbSeen.add(c.dbCaseId);
    if (!Array.isArray(c.evidenceGroups) || (c.expectedBehavior === 'answer' && !c.evidenceGroups.length)) throw new Error(`Missing evidenceGroups: ${c.id}`);
    for (const group of c.evidenceGroups) {
      if (!group.alternatives?.length) throw new Error(`Empty evidence alternatives: ${c.id}/${group.id}`);
      for (const alt of group.alternatives) {
        const source = sources.get(alt.ref);
        if (!source) throw new Error(`Unknown evidence source: ${c.id}/${alt.ref}`);
        for (const quote of alt.quotes || []) {
          if (!quote.trim() || !normalizeText(source.content).includes(normalizeText(quote))) throw new Error(`Unsupported gold quote: ${c.id}/${alt.ref}`);
        }
      }
    }
    for (const fact of c.answerFacts || []) for (const pattern of fact.patterns || []) patternMatches('', pattern);
    for (const pattern of [...(c.forbiddenPatterns || []), ...(c.behaviorPatterns || [])]) patternMatches('', pattern);
  }
  for (const source of corpus.sources) if (hash(source.content || '') !== source.sha256) throw new Error(`Corpus content hash is invalid: ${source.ref}`);
  return suite;
}

// Necessary groups are AND; interchangeable sources inside a group are OR.
// Source presence and sufficient quoted evidence are different measurements.
export function scoreEvidence(groups, items, { requireQuotes = false } = {}) {
  if (!groups.length) return { applicable: false, all: null, groupRecall: null, groups: [] };
  const byRef = new Map();
  for (const item of items) {
    const ref = sourceRef(item);
    if (!/^(note|file|quick_ref):\d+$/u.test(ref)) continue; // Generated Wiki/graph metadata is not original evidence.
    if (!byRef.has(ref)) byRef.set(ref, []);
    byRef.get(ref).push(typeof item === 'string' ? '' : String(item.text ?? item.snippet ?? ''));
  }
  const rows = groups.map(group => {
    const alternatives = group.alternatives.map(alt => {
      const texts = byRef.get(alt.ref);
      const quotes = alt.quotes || [];
      const sourceHit = Boolean(texts);
      const quoteHits = quotes.map(quote => ({ quote, hit: Boolean(texts?.some(t => normalizeText(t).includes(normalizeText(quote)))) }));
      // Missing gold quotes cannot establish whether the required fact was injected.
      const hit = requireQuotes ? sourceHit && quotes.length > 0 && quoteHits.every(q => q.hit) : sourceHit;
      return { ref: alt.ref, sourceHit, hit, quoteHits };
    });
    return { id: group.id, hit: alternatives.some(a => a.hit), alternatives };
  });
  return { applicable: true, all: rows.every(g => g.hit), groupRecall: rows.filter(g => g.hit).length / rows.length, groups: rows };
}

export function patternMatches(text, pattern) {
  if (typeof pattern === 'string') return normalizeText(text).includes(normalizeText(pattern));
  if (pattern.regex != null) return new RegExp(pattern.regex, pattern.flags || 'iu').test(text);
  if (pattern.text != null) return normalizeText(text).includes(normalizeText(pattern.text));
  throw new Error('Pattern must be a string, {text}, or {regex,flags}');
}
export function answerSignals(c, answer) {
  const facts = (c.answerFacts || []).map(fact => ({ id: fact.id, description: fact.description,
    patternHit: (fact.patterns || []).some(pattern => patternMatches(answer, pattern)) }));
  return { definition: 'Pattern coverage is an auxiliary lexical signal; it does not establish correctness or entailment.',
    factPatternCoverage: facts.length ? facts.filter(f => f.patternHit).length / facts.length : null,
    allFactPatternsHit: facts.length ? facts.every(f => f.patternHit) : null,
    facts, forbiddenPatternHits: (c.forbiddenPatterns || []).filter(p => patternMatches(answer, p)),
    expectedBehavior: c.expectedBehavior,
    behaviorPatternHit: c.behaviorPatterns?.length ? c.behaviorPatterns.some(p => patternMatches(answer, p)) : null,
    requiresHumanReview: true };
}

// Only extracts the exact original excerpt block from the diagnosed context; never uses corpus gold text.
export function extractInjectedPassages(context) {
  const text = context.text || '';
  const heading = /(?:^|\n)\d+\. \[(?:笔记|资料|速查卡)#\d+\] \[来源：((?:note|file|quick_ref):\d+)；片段 seq=[^\]]+\]/gu;
  const starts = [...text.matchAll(heading)];
  return starts.flatMap((match, index) => {
    const entry = text.slice(match.index, starts[index + 1]?.index ?? text.length);
    const marker = entry.indexOf('原文片段：\n');
    if (marker < 0) return [];
    const body = entry.slice(marker + '原文片段：\n'.length).split('【检索原文结束】')[0].trim();
    return [{ ref: match[1], text: body }];
  });
}
export function pairedContext(detail, context) {
  if (!context || !detail.contextHash || !detail.groundingHash) return false;
  return detail.contextHash === hash(context.text || '') && detail.groundingHash === hash(context.groundingText || '');
}

export function stableEnvironment(snapshot) {
  const status = pick(snapshot.kb, ['chunks', 'indexedChars', 'indexedModel', 'indexedSources', 'currentSources',
    'model', 'baseUrl', 'provider', 'endpoint', 'enabled', 'configured', 'embeddingSpace', 'indexedSpaces',
    'compatibleChunks', 'legacyChunks', 'embeddingChanged', 'annReady', 'stale', 'sourceLatestChange',
    'indexedAt', 'vectorBackend', 'contextualEmbed']);
  const rerank = snapshot.kb.rerank || {};
  status.rerank = { ...pick(rerank, ['enabled', 'backend', 'active']),
    llm: pick(rerank.llm, ['backend', 'batch', 'maxWindow', 'snippet', 'model']),
    cross: pick(rerank.cross, ['backend', 'url', 'healthy']) };
  return { kb: status, settings: snapshot.settings.map(s => ({ key: s.key, effective: s.effective })).sort((a, b) => a.key.localeCompare(b.key)),
    routing: snapshot.routing, profiles: snapshot.profiles.profiles.map(p => pick(p, ['id', 'name', 'provider', 'purpose', 'baseUrl',
      'model', 'active', 'maxTokens', 'temperature', 'thinking', 'reasoningEffort'])).sort((a, b) => a.id.localeCompare(b.id)) };
}
export function actualAnswerProtocol(response) {
  const flag = value => value == null ? null : value === true || value === 1 || value === '1' || value === 'true';
  return { model: response.model ?? null, promptVersion: response.promptVersion ?? null,
    topK: response.topK == null ? null : Number(response.topK), mode: response.mode ?? null,
    wiki: flag(response.wikiInject), kg: flag(response.kgInject) };
}

export function summarize(rows, totalCases) {
  const complete = rows.filter(r => r.status === 'ok');
  const sourceRows = rows.filter(r => r.actualInjectedSourceScore?.applicable);
  const candidateRows = rows.filter(r => r.candidateSourceScore?.applicable);
  const excerptRows = complete.filter(r => r.actualInjectedQuoteScore?.applicable);
  const errors = rows.filter(r => r.status !== 'ok' && r.status !== 'retrieval_only');
  const mean = (sample, getter) => sample.length ? sample.reduce((sum, r) => sum + getter(r), 0) / sample.length : null;
  return { totalCases, recordedCases: rows.length, successfulGeneration: complete.length,
    generationFailures: rows.filter(r => r.status === 'generation_error').length,
    otherFailures: errors.filter(r => r.status !== 'generation_error').length,
    failureCases: errors.map(r => ({ id: r.id, status: r.status })),
    candidateAllGroups: { hit: candidateRows.filter(r => r.candidateSourceScore.all).length, denominator: candidateRows.length,
      rate: mean(candidateRows, r => Number(r.candidateSourceScore.all)) },
    actualInjectedAllGroups: { hit: sourceRows.filter(r => r.actualInjectedSourceScore.all).length, denominator: sourceRows.length,
      rate: mean(sourceRows, r => Number(r.actualInjectedSourceScore.all)) },
    actualInjectedQuotes: { hit: excerptRows.filter(r => r.actualInjectedQuoteScore.all).length, denominator: excerptRows.length,
      rate: mean(excerptRows, r => Number(r.actualInjectedQuoteScore.all)), pairedContexts: complete.filter(r => r.contextPaired).length },
    outputTruncated: complete.filter(r => r.outputTruncated).length,
    storedAnswerMayBeClipped: complete.filter(r => r.storedAnswerMayBeClipped).length,
    tokens: rows.reduce((n, r) => n + (r.response?.totalTokens || 0), 0),
    promptTokens: rows.reduce((n, r) => n + (r.response?.promptTokens || 0), 0),
    completionTokens: rows.reduce((n, r) => n + (r.response?.completionTokens || 0), 0),
    humanAccuracy: null,
    limitations: ['Candidate search, context diagnosis and answer evaluation are separate retrieval calls.',
      'Actual injected source IDs and context hashes come from the answer run; excerpt scores require both hashes to match.',
      'The backend stores answers clipped at 2000 characters; clipped answers require separate review.',
      'Lexical pattern signals are not correctness scores. Model failures are separate from answer quality.',
      'Local source-file hashes identify this checkout, not necessarily the deployed backend build.'] };
}

async function jsonFile(path) { return JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/u, '')); }
async function save(path, value) {
  await mkdir(dirname(path), { recursive: true });
  await writeFile(`${path}.tmp`, `${JSON.stringify(redact(value), null, 2)}\n`, 'utf8');
  await rename(`${path}.tmp`, path);
}
export function corpusSourceIdentity(source) {
  return { ref: source.ref, title: source.title || '', contentSha256: hash(source.content || ''),
    summary: source.ref.startsWith('file:') ? (source.summary ?? source.metadata?.summary ?? '') : undefined };
}
function argumentsOf(argv) {
  const out = { base: process.env.LEARNHUB_BASE_URL || 'http://localhost:18080', topK: 5, candidateK: 24,
    wiki: true, kg: true, mode: 'fused', timeoutMs: 330000, retrievalOnly: false, retryErrors: false };
  const names = { '--suite': 'suite', '--corpus': 'corpus', '--out': 'out', '--base': 'base', '--only': 'only',
    '--top-k': 'topK', '--candidate-k': 'candidateK', '--timeout-ms': 'timeoutMs', '--wiki': 'wiki', '--kg': 'kg', '--mode': 'mode' };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--retrieval-only') out.retrievalOnly = true;
    else if (argv[i] === '--retry-errors') out.retryErrors = true;
    else if (names[argv[i]] && argv[i + 1] != null) out[names[argv[i]]] = argv[++i];
    else throw new Error(`Unknown or incomplete argument: ${argv[i]}`);
  }
  if (!out.suite || !out.corpus || !out.out) throw new Error('Required: --suite PATH --corpus PATH --out DIRECTORY');
  for (const key of ['topK', 'candidateK', 'timeoutMs']) out[key] = Number(out[key]);
  for (const key of ['wiki', 'kg']) { if (!['true', 'false', true, false].includes(out[key])) throw new Error(`${key} must be true/false`); out[key] = String(out[key]) === 'true'; }
  if (!['fused', 'keyword', 'vector'].includes(out.mode) || !Number.isInteger(out.topK) || out.topK < 1 || out.topK > 8
    || !Number.isInteger(out.candidateK) || out.candidateK < out.topK || out.timeoutMs <= 0) throw new Error('Invalid mode/topK/candidateK/timeout');
  const base = new URL(out.base);
  if (base.username || base.password || base.search || base.hash) throw new Error('Base URL must not contain credentials, query, or fragment');
  out.suite = resolve(out.suite); out.corpus = resolve(out.corpus); out.out = resolve(out.out);
  return out;
}

export async function run(options) {
  const suite = validateSuite(await jsonFile(options.suite), await jsonFile(options.corpus), { requireDbIds: !options.retrievalOnly });
  const corpus = await jsonFile(options.corpus);
  await mkdir(join(options.out, 'raw'), { recursive: true });
  const optionsFrozen = pick(options, ['base', 'topK', 'candidateK', 'wiki', 'kg', 'mode', 'timeoutMs', 'retrievalOnly']);
  const suiteHash = hash(suite), corpusHash = hash(corpus.sources.map(corpusSourceIdentity).sort((a, b) => a.ref.localeCompare(b.ref)));
  let requestCounter = 0;
  async function request(path, params = {}, { method = 'GET', tag = 'api' } = {}) {
    const url = new URL(path, options.base);
    for (const [key, value] of Object.entries(params)) url.searchParams.set(key, String(value));
    const startedAt = new Date().toISOString(), started = performance.now();
    const rawPath = join(options.out, 'raw', `${Date.now()}-${++requestCounter}-${tag}.json`);
    try {
      const response = await fetch(url, { method, signal: AbortSignal.timeout(options.timeoutMs) });
      const text = await response.text();
      let body; try { body = JSON.parse(text); } catch { body = { nonJsonBody: text }; }
      const raw = { startedAt, url: url.toString(), method, status: response.status, elapsedMs: Math.round(performance.now() - started), body };
      await save(rawPath, raw);
      if (!response.ok || body.code !== 200) throw new Error(`${path}: HTTP ${response.status}; ${body.msg || 'API failed'}`);
      return body.data;
    } catch (error) {
      await save(`${rawPath}.error.json`, { startedAt, url: url.toString(), method, error: error.message,
        elapsedMs: Math.round(performance.now() - started) });
      throw error;
    }
  }
  async function environment(tag) {
    const paths = [['kb', '/api/kb/status'], ['settings', '/api/settings/effective'],
      ['routing', '/api/model/routing'], ['profiles', '/api/model/profiles']];
    const results = await Promise.allSettled(paths.map(([key, path]) => request(path,
      key === 'settings' ? { keys: SAFE_SETTINGS.join(',') } : {}, { tag: `${tag}-${key}` })));
    const failed = results.find(r => r.status === 'rejected');
    if (failed) throw failed.reason;
    return Object.fromEntries(paths.map(([key], index) => [key, results[index].value]));
  }
  async function verifyCorpus(tag) {
    const results = await Promise.allSettled(corpus.sources.map(async source => {
      const [type, id] = source.ref.split(':');
      let live;
      if (type === 'file') {
        const replies = await Promise.allSettled([
          request(`/api/files/${id}`, {}, { tag: `${tag}-${source.ref.replace(':', '-')}-detail` }),
          request(`/api/files/${id}/text`, {}, { tag: `${tag}-${source.ref.replace(':', '-')}-text` }),
        ]);
        const failure = replies.find(r => r.status === 'rejected');
        if (failure) throw failure.reason;
        live = { ref: source.ref, title: replies[0].value.originName || replies[0].value.name || replies[1].value.originName,
          summary: replies[0].value.summary || '', content: replies[1].value.text || '' };
      } else {
        const reply = await request(`/api/${type === 'note' ? 'notes' : 'quick-refs'}/${id}`, {}, { tag: `${tag}-${source.ref.replace(':', '-')}` });
        live = { ref: source.ref, title: reply.title, content: reply.content || '' };
      }
      const expected = corpusSourceIdentity(source), actual = corpusSourceIdentity(live);
      if (canonical(expected) !== canonical(actual)) throw new Error(`Frozen corpus differs from live source: ${source.ref}`);
      return actual;
    }));
    const failures = results.filter(r => r.status === 'rejected');
    if (failures.length) throw failures[0].reason;
    const fingerprint = hash(results.map(r => r.value).sort((a, b) => a.ref.localeCompare(b.ref)));
    await save(join(options.out, `corpus-${tag}.json`), { checkedAt: new Date().toISOString(), fingerprint,
      sources: results.map(r => r.value) });
    return fingerprint;
  }
  const currentSnapshot = await environment('before');
  await verifyCorpus('before');
  const environmentHash = hash(stableEnvironment(currentSnapshot));
  const sourceFiles = Object.fromEntries(await Promise.all(SOURCE_FILES.map(async file => {
    try { return [file, hash(await readFile(join(ROOT, file), 'utf8'))]; } catch { return [file, null]; }
  })));
  const identity = { runnerVersion: RUNNER_VERSION, suiteHash, corpusHash, environmentHash, options: optionsFrozen, localSourceFiles: sourceFiles,
    answerPath: '/api/kb/eval/answer/run', answerProtocol: 'Project answer-evaluation prompt, no chat tool loop; fixed per-run retrieval overrides.' };
  const manifestPath = join(options.out, 'manifest.json');
  let previous; try { previous = await jsonFile(manifestPath); } catch (error) { if (error.code !== 'ENOENT') throw error; }
  if (previous && canonical(previous.identity) !== canonical(identity)) throw new Error('Cannot resume: suite, corpus, configuration, options or local source hashes changed. Use a new output directory.');
  if (!previous) await save(manifestPath, { createdAt: new Date().toISOString(), identity,
    snapshot: currentSnapshot, frozenCorpusPath: options.corpus, suitePath: options.suite,
    staleIndex: Boolean(currentSnapshot.kb.stale), note: 'No rebuild/settings changes; stale index is part of the recorded baseline.' });
  await save(join(options.out, 'suite.json'), suite);
  await mkdir(join(options.out, 'cases'), { recursive: true });
  await mkdir(join(options.out, 'pending'), { recursive: true });
  const rows = new Map();
  for (const name of await readdir(join(options.out, 'cases'))) {
    if (!name.endsWith('.json') || name.endsWith('.pending.json')) continue;
    const row = await jsonFile(join(options.out, 'cases', name));
    rows.set(row.id, row);
  }
  const selected = options.only ? new Set(options.only.split(',')) : null;
  if (selected && [...selected].some(id => !suite.cases.some(c => c.id === id))) throw new Error('--only contains an unknown case id');
  let actualCorpusHash = previous?.actualCorpusHash || [...rows.values()].find(r => r.response?.corpusHash)?.response.corpusHash;
  let actualProtocol = previous?.actualProtocol || [...rows.values()].find(r => r.response)?.response;
  if (actualProtocol?.runId) actualProtocol = actualAnswerProtocol(actualProtocol);
  async function persist(row) {
    await save(join(options.out, 'cases', `${row.id}.json`), row);
    rows.set(row.id, row);
    await save(join(options.out, 'results.json'), { manifest: identity, rows: suite.cases.map(c => rows.get(c.id)).filter(Boolean) });
    await save(join(options.out, 'summary.json'), summarize([...rows.values()], suite.cases.length));
  }
  for (const c of suite.cases) {
    if (selected && !selected.has(c.id)) continue;
    const existing = rows.get(c.id);
    if (existing && !(options.retryErrors && !['ok', 'retrieval_only'].includes(existing.status))) continue;
    const snapshot = await environment(`pre-${c.id}`);
    if (hash(stableEnvironment(snapshot)) !== environmentHash) throw new Error(`Stopped before ${c.id}: corpus/index/configuration changed`);
    const row = { id: c.id, dbCaseId: c.dbCaseId, category: c.category, question: c.question,
      expectedBehavior: c.expectedBehavior, startedAt: new Date().toISOString(), attempt: (existing?.attempt || 0) + 1 };
    let pending;
    try { pending = await jsonFile(join(options.out, 'pending', `${c.id}.json`)); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
    const recovering = pending && (!existing || pending.attempt > existing.attempt);
    if (recovering) Object.assign(row, pending);
    // The production search API is fused with shared wiki/kg flags, independent of answer-run overrides.
    const diagnostics = recovering ? null : await Promise.allSettled([
      request('/api/kb/search', { q: c.question, topK: options.candidateK }, { tag: `${c.id}-candidate` }),
      request('/api/kb/context', { q: c.question, topK: options.topK, mode: options.mode, wiki: options.wiki, kg: options.kg }, { tag: `${c.id}-context` }),
    ]);
    for (const [i, key] of ['candidates', 'context'].entries()) {
      if (!diagnostics) break;
      if (diagnostics[i].status === 'fulfilled') row[key] = diagnostics[i].value;
      else row[`${key}Error`] = diagnostics[i].reason.message;
    }
    if (Array.isArray(row.candidates)) {
      row.candidateSourceScore = scoreEvidence(c.evidenceGroups, row.candidates.slice(0, options.topK));
      row.candidateWindowSourceScore = scoreEvidence(c.evidenceGroups, row.candidates);
      row.candidateQuoteScore = scoreEvidence(c.evidenceGroups, row.candidates.slice(0, options.topK), { requireQuotes: true });
      row.candidateDefinition = `First ${options.topK} passages of independent fused /search; wiki/kg follow shared settings.`;
    }
    if (row.context) {
      row.diagnosticInjectedSourceScore = scoreEvidence(c.evidenceGroups, row.context.refs || []);
      row.diagnosticInjectedQuoteScore = scoreEvidence(c.evidenceGroups, extractInjectedPassages(row.context), { requireQuotes: true });
    }
    if (options.retrievalOnly) { row.status = 'retrieval_only'; await persist(row); continue; }
    try {
      let history;
      const label = `${suite.id || 'fixed'}:${hash(identity).slice(0, 8)}:${c.id}:a${row.attempt}`;
      if (recovering && !row.response) {
        history = await request('/api/kb/eval/answer/history', {}, { tag: `${c.id}-recover-history` });
        const recovered = history.find(r => r.label === label);
        if (!recovered) throw new Error('Interrupted model request has no recoverable history; do not automatically reissue it. Review raw logs and retry explicitly.');
        row.response = { ...recovered, runId: recovered.id };
      }
      if (!row.response) {
        await save(join(options.out, 'pending', `${c.id}.json`), { ...row, status: 'submitting_answer', label });
        row.response = await request('/api/kb/eval/answer/run', { label, ids: c.dbCaseId, limit: 1,
          topK: options.topK, mode: options.mode, wiki: options.wiki, kg: options.kg }, { method: 'POST', tag: `${c.id}-answer` });
        await save(join(options.out, 'pending', `${c.id}.json`), { ...row, status: 'awaiting_history', label });
      }
      history ||= await request('/api/kb/eval/answer/history', {}, { tag: `${c.id}-history` });
      const run = history.find(r => r.id === row.response.runId);
      if (!run) throw new Error(`Run ${row.response.runId} missing from the latest 20 history entries`);
      row.run = run;
      const details = typeof run.detail === 'string' ? JSON.parse(run.detail) : run.detail;
      if (details?.length !== 1 || details[0].id !== c.dbCaseId || details[0].question !== c.question) throw new Error('DB case does not match the frozen question');
      row.detail = details[0];
      if (actualCorpusHash && actualCorpusHash !== row.response.corpusHash) throw new Error('Answer-run corpus fingerprint changed');
      actualCorpusHash ||= row.response.corpusHash;
      const protocol = actualAnswerProtocol(row.response);
      if (actualProtocol && canonical(actualProtocol) !== canonical(protocol)) throw new Error('Answer-run model/prompt/protocol changed');
      for (const key of ['topK', 'mode', 'wiki', 'kg']) {
        if (protocol[key] != null && protocol[key] !== options[key]) throw new Error('Answer-run model/prompt/protocol changed');
      }
      actualProtocol ||= protocol;
      row.actualInjectedRefs = splitRefs(row.detail.top);
      row.actualInjectedSourceScore = scoreEvidence(c.evidenceGroups, row.actualInjectedRefs);
      row.contextPaired = pairedContext(row.detail, row.context);
      const excerpts = row.contextPaired ? extractInjectedPassages(row.context) : [];
      row.actualInjectedQuoteScore = row.contextPaired && (excerpts.length || !row.actualInjectedRefs.length)
        ? scoreEvidence(c.evidenceGroups, excerpts, { requireQuotes: true })
        : { applicable: false, all: null, groupRecall: null, reason: row.contextPaired
          ? 'Original excerpt parser unavailable for this backend format' : 'Exact generated-context/grounding hashes not both matched' };
      row.answer = row.detail.answer || '';
      row.storedAnswerMayBeClipped = row.answer.length >= 2000 && row.answer.endsWith('…');
      row.outputTruncated = row.detail.finishReason === 'length';
      row.status = row.detail.error || !row.answer.trim() ? 'generation_error' : 'ok';
      row.error = row.detail.error || (row.status === 'generation_error' ? 'Empty model answer' : undefined);
      row.signals = row.status === 'ok' ? answerSignals(c, row.answer) : null;
    } catch (error) {
      row.status = row.response ? 'result_error' : 'transport_error';
      row.error = error.message;
    }
    row.finishedAt = new Date().toISOString();
    await persist(row);
    console.log(JSON.stringify({ id: row.id, status: row.status, runId: row.response?.runId,
      injectedAllGroups: row.actualInjectedSourceScore?.all, paired: row.contextPaired }));
    if (['Answer-run corpus fingerprint changed', 'Answer-run model/prompt/protocol changed', 'DB case does not match the frozen question'].includes(row.error)) throw new Error(`Stopped: ${row.error}`);
  }
  const after = await environment('after');
  await verifyCorpus('after');
  const unchanged = hash(stableEnvironment(after)) === environmentHash;
  await save(join(options.out, 'after.json'), { unchanged, snapshot: after, actualCorpusHash, actualProtocol });
  await save(manifestPath, { ...(previous || await jsonFile(manifestPath)), actualCorpusHash, actualProtocol });
  if (!unchanged) throw new Error('Corpus/index/configuration changed during the run; results need review');
  return summarize([...rows.values()], suite.cases.length);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  run(argumentsOf(process.argv.slice(2))).then(summary => console.log(JSON.stringify(summary, null, 2))).catch(error => {
    console.error(redact(error.message)); process.exitCode = 1;
  });
}
