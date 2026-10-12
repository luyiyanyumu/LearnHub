// Paired reranker evaluation on synthetic, frozen candidates. No application writes.
// Credentials are read into memory from the existing routed profile and sent to Java stdin only.
import { readFile, writeFile, mkdir, appendFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';
import { createInterface } from 'node:readline';
import { resolve, join, dirname, delimiter } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
export const sha = text => createHash('sha256').update(text).digest('hex');
const mean = values => values.reduce((a, b) => a + b, 0) / values.length;
const quantile = (values, p) => {
  if (!values.length) return null;
  const sorted = [...values].sort((a, b) => a - b), x = (sorted.length - 1) * p;
  const low = Math.floor(x), high = Math.ceil(x);
  return sorted[low] + (sorted[high] - sorted[low]) * (x - low);
};
function random(seed) {
  let state = parseInt(sha(String(seed)).slice(0, 8), 16) >>> 0;
  return () => { state += 0x6D2B79F5; let t = state; t = Math.imul(t ^ t >>> 15, t | 1);
    t ^= t + Math.imul(t ^ t >>> 7, t | 61); return ((t ^ t >>> 14) >>> 0) / 4294967296; };
}
export function shuffled(items, seed) {
  const out = [...items], rng = random(seed);
  for (let i = out.length - 1; i > 0; i--) { const j = Math.floor(rng() * (i + 1)); [out[i], out[j]] = [out[j], out[i]]; }
  return out;
}
export function validateSuite(suite) {
  if (!suite.version || !Array.isArray(suite.cases) || !suite.cases.length) throw Error('Missing version/cases');
  if (/draft|草稿/iu.test(String(suite.version))) throw Error('Synthetic gold suite must be frozen before inference');
  const ids = new Set();
  for (const c of suite.cases) {
    if (ids.has(c.id) || !c.id || !c.question || !c.category) throw Error('Invalid case id/question/category');
    ids.add(c.id);
    if (!Array.isArray(c.items) || c.items.length < 3 || c.items.length > 20) throw Error(`Candidate window invalid: ${c.id}`);
    const keys = new Set();
    for (const item of c.items) {
      if (!item.key || keys.has(item.key) || !item.title || !item.text || !item.rationale || ![0, 1, 2].includes(item.relevance))
        throw Error(`Invalid candidate/gold: ${c.id}`);
      keys.add(item.key);
    }
    if (!c.items.some(item => item.relevance === 2)) throw Error(`No directly supporting evidence: ${c.id}`);
  }
  return suite;
}
// Only these fields go to the models. Gold labels and rationales never enter inference.
export const inferenceItems = items => items.map(({ key, title, text }) => ({ key, title, text }));
export function scoreRanking(items, order, k = 5) {
  const labels = new Map(items.map(item => [item.key, item.relevance]));
  if (order.length !== items.length || new Set(order).size !== items.length || order.some(key => !labels.has(key)))
    throw Error('Ranked output is not a permutation of the frozen candidates');
  const grades = order.map(key => labels.get(key));
  const dcg = values => values.slice(0, k).reduce((sum, grade, index) => sum + (2 ** grade - 1) / Math.log2(index + 2), 0);
  const ideal = dcg([...labels.values()].sort((a, b) => b - a));
  const goldCount = [...labels.values()].filter(grade => grade === 2).length;
  const rank = grades.indexOf(2) + 1;
  return { ndcg5: dcg(grades) / ideal, top1: grades[0] === 2 ? 1 : 0, mrr: rank ? 1 / rank : 0,
    recall5: grades.slice(0, k).filter(grade => grade === 2).length / goldCount };
}
export function pairedBootstrap(differences, iterations = 10000, seed = 'rerank-ab-bootstrap-v1') {
  const rng = random(seed), draws = [];
  for (let i = 0; i < iterations; i++) {
    let sum = 0;
    for (let j = 0; j < differences.length; j++) sum += differences[Math.floor(rng() * differences.length)];
    draws.push(sum / differences.length);
  }
  return { delta: mean(differences), ci95: [quantile(draws, .025), quantile(draws, .975)],
    unit: 'question (mean across candidate order permutations)', iterations };
}
export function summarize(suite, rows) {
  const paired = rows.filter(row => row.llm && row.cross);
  if (!paired.length) throw Error('No complete pairs to analyze');
  const metrics = ['ndcg5', 'top1', 'mrr', 'recall5'];
  const arms = Object.fromEntries(['input', 'llm', 'cross'].map(arm => [arm,
    Object.fromEntries(metrics.map(metric => [metric, mean(paired.map(row => row[arm].score[metric]))]))]));
  const differences = metric => suite.cases.flatMap(c => {
    const trials = paired.filter(row => row.id === c.id);
    return trials.length ? [mean(trials.map(row => row.llm.score[metric] - row.cross.score[metric]))] : [];
  });
  const categories = Object.fromEntries([...new Set(suite.cases.map(c => c.category))].map(category => {
    const sample = paired.filter(row => row.category === category);
    return [category, { pairs: sample.length,
      llmNdcg5: mean(sample.map(row => row.llm.score.ndcg5)), crossNdcg5: mean(sample.map(row => row.cross.score.ndcg5)),
      llmTop1: mean(sample.map(row => row.llm.score.top1)), crossTop1: mean(sample.map(row => row.cross.score.top1)) }];
  }));
  const valid = paired.filter(row => row.llm.diagnostics?.qualityEligible && row.cross.diagnostics?.qualityEligible);
  const latency = Object.fromEntries(['llm', 'cross'].map(arm => [arm, { medianMs: quantile(paired.map(row => row[arm].elapsedMs), .5),
    p95Ms: quantile(paired.map(row => row[arm].elapsedMs), .95),
    failures: paired.filter(row => row[arm].diagnostics?.fallback || row[arm].diagnostics?.status === 'request_error').length,
    repairedOutputs: paired.filter(row => row[arm].diagnostics?.status === 'repaired').length,
    truncatedOutputs: paired.filter(row => row[arm].diagnostics?.truncated === true).length }]));
  const consistency = Object.fromEntries(['llm', 'cross'].map(arm => {
    const groups = suite.cases.map(c => paired.filter(row => row.id === c.id)).filter(group => group.length > 1);
    return [arm, { questions: groups.length,
      top1Agreement: groups.length ? mean(groups.map(group => group.every(row => row[arm].order[0] === group[0][arm].order[0]) ? 1 : 0)) : null }];
  }));
  return { pairedTrials: paired.length, uniqueQuestions: new Set(paired.map(row => row.id)).size, arms,
    llmMinusCross: Object.fromEntries(metrics.map(metric => [metric, pairedBootstrap(differences(metric))])),
    categories, latency, consistency, successfulOnly: { pairedTrials: valid.length,
      llmNdcg5: valid.length ? mean(valid.map(row => row.llm.score.ndcg5)) : null,
      crossNdcg5: valid.length ? mean(valid.map(row => row.cross.score.ndcg5)) : null },
    wins: { llm: paired.filter(row => row.llm.score.ndcg5 > row.cross.score.ndcg5 + 1e-10).length,
      cross: paired.filter(row => row.cross.score.ndcg5 > row.llm.score.ndcg5 + 1e-10).length,
      ties: paired.filter(row => Math.abs(row.llm.score.ndcg5 - row.cross.score.ndcg5) <= 1e-10).length } };
}
async function api(base, path) {
  const response = await fetch(base + path, { signal: AbortSignal.timeout(15000) });
  if (!response.ok) throw Error('Read-only application status request failed');
  const result = await response.json();
  if (result.code !== 200 && result.code !== 0) throw Error('Application status returned an error');
  return result.data;
}
async function routedConfig(base, container, database) {
  const routing = await api(base, '/api/model/routing'), rerank = await api(base, '/api/kb/rerank');
  const selected = routing.find(row => row.task === 'rerank');
  if (!selected || !/^[A-Za-z0-9_-]+$/.test(selected.target) || !/^[A-Za-z0-9_-]+$/.test(database)) throw Error('Invalid selected profile');
  const sql = `SELECT JSON_OBJECT('baseUrl',base_url,'apiKey',api_key,'model',model,'provider',provider) FROM model_profile WHERE id='${selected.target}';`;
  const result = spawnSync('docker', ['exec', '-i', container, 'sh', '-c',
    `MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B -D ${database}`], { input: sql, encoding: 'utf8', maxBuffer: 1024 * 1024, windowsHide: true });
  if (result.status !== 0) throw Error('Read-only routed profile lookup failed');
  const config = JSON.parse(result.stdout.trim());
  if (config.provider !== 'deepseek' || config.model !== rerank.llm.model || !config.apiKey || !config.baseUrl) throw Error('Expected existing DeepSeek rerank profile with configured credentials');
  return { config, before: { enabled: rerank.enabled, backend: rerank.backend, active: rerank.active,
    model: rerank.llm.model, snippet: rerank.llm.snippet, target: selected.target } };
}
async function startProbe(config, directory) {
  const dependencies = (await readFile(join(ROOT, 'backend/target/rerank-ab-classpath.txt'), 'utf8')).trim();
  const classpath = [join(ROOT, 'backend/target/classes'), join(directory, 'java'), dependencies].join(delimiter);
  const child = spawn('java', ['-Dfile.encoding=UTF-8', '-cp', classpath, 'RerankAbProbe'], { cwd: ROOT, windowsHide: true });
  const reader = createInterface({ input: child.stdout });
  const queue = []; let failed = false;
  reader.on('line', line => { if (!line.startsWith('{')) return; const waiter = queue.shift();
    if (waiter) { try { waiter.resolve(JSON.parse(line)); } catch { waiter.reject(Error('Invalid probe JSON')); } } });
  child.stderr.on('data', () => {}); // No provider error bodies or credentials are persisted.
  child.on('error', () => { failed = true; while (queue.length) queue.shift().reject(Error('Probe launch failed')); });
  child.on('exit', () => { failed = true; while (queue.length) queue.shift().reject(Error('Probe stopped')); });
  const send = input => new Promise((resolve, reject) => {
    if (failed) return reject(Error('Probe unavailable'));
    const timer = setTimeout(() => { failed = true; child.kill(); reject(Error('Probe request timed out')); }, 45000);
    queue.push({ resolve: value => { clearTimeout(timer); resolve(value); }, reject: error => { clearTimeout(timer); reject(error); } });
    child.stdin.write(JSON.stringify(input) + '\n');
  });
  const ready = await send(config);
  if (ready.status !== 'ready') { child.kill(); throw Error('Probe initialization failed'); }
  return { send, close: () => child.stdin.end(), kill: () => child.kill() };
}
function report(metadata, summary, rows) {
  const fmt = value => value.toFixed(4), pct = value => (value * 100).toFixed(1) + '%';
  const table = ['| 指标 | LLM | Cross-Encoder |', '|---|---:|---:|',
    `| NDCG@5（主指标） | ${fmt(summary.arms.llm.ndcg5)} | ${fmt(summary.arms.cross.ndcg5)} |`,
    `| 首位直接证据命中率 | ${pct(summary.arms.llm.top1)} | ${pct(summary.arms.cross.top1)} |`,
    `| MRR（首个 grade=2） | ${fmt(summary.arms.llm.mrr)} | ${fmt(summary.arms.cross.mrr)} |`,
    `| Recall@5（grade=2） | ${pct(summary.arms.llm.recall5)} | ${pct(summary.arms.cross.recall5)} |`,
    `| 耗时中位数 | ${(summary.latency.llm.medianMs / 1000).toFixed(3)} 秒 | ${(summary.latency.cross.medianMs / 1000).toFixed(3)} 秒 |`,
    `| 耗时 P95 | ${(summary.latency.llm.p95Ms / 1000).toFixed(3)} 秒 | ${(summary.latency.cross.p95Ms / 1000).toFixed(3)} 秒 |`,
    `| 调用失败 | ${summary.latency.llm.failures} | ${summary.latency.cross.failures} |`,
    `| 修复编号／输出截断 | ${summary.latency.llm.repairedOutputs} / ${summary.latency.llm.truncatedOutputs} | ${summary.latency.cross.repairedOutputs} / ${summary.latency.cross.truncatedOutputs} |`].join('\n');
  const ci = summary.llmMinusCross.ndcg5;
  const disagreements = rows.filter(row => row.llm.score.top1 !== row.cross.score.top1).slice(0, 10).map(row =>
    `- ${row.id} / 排列 ${row.trial + 1}：LLM首位 ${row.llm.order[0]}（命中=${row.llm.score.top1}），Cross首位 ${row.cross.order[0]}（命中=${row.cross.score.top1}）。`).join('\n');
  const categoryTable = ['| 类别 | 配对次数 | LLM NDCG@5 | Cross NDCG@5 |', '|---|---:|---:|---:|',
    ...Object.entries(summary.categories).map(([category, m]) => `| ${category} | ${m.pairs} | ${fmt(m.llmNdcg5)} | ${fmt(m.crossNdcg5)} |`)].join('\n');
  return `# 重排 A/B 测试\n\n时间：${metadata.startedAt}\n\nA：${metadata.llmModel}，生产 LlmListwiseReranker；B：${metadata.crossModel}，生产 CrossEncoderReranker 调用真实本地模型。\n\n${summary.uniqueQuestions} 个独立合成问题，每题 ${metadata.trials} 种冻结候选排列，共 ${summary.pairedTrials} 次配对比较。两组问题、标题、原文片段和候选顺序相同；片段使用同一生产 QueryAwareExcerpt，预算 ${metadata.snippet} 字符。候选数 ${metadata.candidateCounts.join('/')}。标注在调用前冻结，不发送给模型。\n\n${table}\n\nLLM−Cross 的 NDCG@5 差值 ${fmt(ci.delta)}；按问题聚类的配对 bootstrap 95% 区间 [${fmt(ci.ci95[0])}, ${fmt(ci.ci95[1])}]（${ci.iterations} 次重采样）。NDCG@5 单次胜/负/平：${summary.wins.llm}/${summary.wins.cross}/${summary.wins.ties}。\n\n${categoryTable}\n\n输入随机排列的 NDCG@5：${fmt(summary.arms.input.ndcg5)}。这只是随机顺序参照，不是线上 RRF 基线。\n\n跨排列首位一致率：LLM ${pct(summary.consistency.llm.top1Agreement ?? 1)}，Cross ${pct(summary.consistency.cross.top1Agreement ?? 1)}。成功配对 ${summary.successfulOnly.pairedTrials} 次；成功调用的 LLM/Cross NDCG@5：${fmt(summary.successfulOnly.llmNdcg5 ?? 0)} / ${fmt(summary.successfulOnly.crossNdcg5 ?? 0)}。\n\n## 方法与局限\n\n- grade=2：直接满足问题要求；grade=1：有用但不足以完整回答；grade=0：无关、错误对象或不满足条件。NDCG 使用增益 2^grade−1；Top1/MRR/Recall只计算grade=2。\n- 跨排列首位一致率同时包含输入顺序与LLM采样变化；同题可能有多个同等正确的grade=2候选，首位变化本身不表示质量下降。本次12候选不覆盖超过20候选的多批交错。\n- 每题不同排列先取平均再bootstrap，避免把重复排列当成独立问题。小型合成集由智能体按规则撰写、另一智能体复核，未经用户逐条标注；结果仅适用于该合成候选集，不代表真实语料分布或答案正确率。\n- 此处隔离重排阶段，没有评价召回、Wiki/图谱生成或最终答案。两组片段预算均按当前配置固定；未给Cross单独增加上下文。\n- A调用云端，B在本机CPU；两后端并行，各自仅一请求在途；启动顺序交替，预热不计入耗时。网络与硬件不同，耗时只表示本次部署的观察值。\n- 直接调用具体后端；Cross失败不会回退LLM。LLM内部失败按生产行为保留候选原序并单独记录，失败样本未静默删除。\n- 冻结题集SHA-256：\`${metadata.suiteHash}\`；来源代码哈希、每次候选与摘录哈希、排序及诊断见 metadata.json / results.json。线上配置未修改。\n\n## 首位命中有差异的样本\n\n${disagreements || '没有首位命中差异。'}\n`;
}
async function main() {
  const args = process.argv.slice(2), at = key => { const i = args.indexOf(key); return i < 0 ? null : args[i + 1]; };
  const base = at('--base') || 'http://localhost:18080';
  const directory = resolve(at('--out') || join(ROOT, 'output/rerank-ab-2026-10-10'));
  const suiteText = await readFile(resolve(at('--suite') || join(ROOT, 'tools/fixtures/rerank-ab.synthetic.json')), 'utf8');
  const suite = validateSuite(JSON.parse(suiteText.replace(/^\uFEFF/, '')));
  const trials = Number(at('--trials') || 2);
  if (!Number.isInteger(trials) || trials < 1 || trials > 3) throw Error('trials must be 1..3');
  const crossUrl = at('--cross') || 'http://127.0.0.1:8093/rerank';
  const crossHealth = await fetch(crossUrl.replace(/\/rerank$/, '/health'), { signal: AbortSignal.timeout(10000) }).then(r => r.json());
  if (crossHealth.status !== 'ok' || !crossHealth.loaded) throw Error('Real Cross-Encoder not healthy; A/B cannot use a fallback');
  const cachedModel = /models--([^\\/]+)--([^\\/]+)/u.exec(crossHealth.model);
  const crossModelName = cachedModel ? `${cachedModel[1]}/${cachedModel[2]}` : crossHealth.model;
  const { config, before } = await routedConfig(base, at('--container') || 'learn-hub-mysql', at('--database') || 'learn_hub');
  await mkdir(directory, { recursive: true });
  const sources = ['tools/RerankAbProbe.java', 'tools/run-rerank-ab.mjs', 'tools/rerank-server.py',
    'backend/src/main/java/org/dyh/learnhub/service/rerank/LlmListwiseReranker.java',
    'backend/src/main/java/org/dyh/learnhub/service/rerank/CrossEncoderReranker.java',
    'backend/src/main/java/org/dyh/learnhub/service/rerank/QueryAwareExcerpt.java',
    'backend/src/main/java/org/dyh/learnhub/ai/DeepSeekClient.java'];
  const sourceHashes = Object.fromEntries(await Promise.all(sources.map(async file => [file, sha(await readFile(join(ROOT, file), 'utf8'))])));
  let metadata = { startedAt: new Date().toISOString(), suiteHash: sha(suiteText), suiteVersion: suite.version,
    llmModel: config.model, crossModel: crossModelName, crossModelIdentity: crossHealth.model,
    snippet: before.snippet, trials,
    candidateCounts: [...new Set(suite.cases.map(c => c.items.length))], sourceHashes, before,
    method: 'Paired frozen synthetic candidates; no retrieval or answer generation; gold excluded from model input; concrete backends without facade fallback; arms run concurrently with one request in flight per arm and counterbalanced launch order; clustered question bootstrap.' };
  let rows = [];
  try {
    const saved = JSON.parse(await readFile(join(directory, 'metadata.json'), 'utf8'));
    for (const field of ['suiteHash', 'llmModel', 'crossModel', 'crossModelIdentity', 'snippet', 'trials', 'sourceHashes', 'before']) {
      if (JSON.stringify(saved[field]) !== JSON.stringify(metadata[field])) throw Error('Frozen evaluation state changed; use a fresh output directory');
    }
    rows = JSON.parse(await readFile(join(directory, 'results.json'), 'utf8'));
    metadata = saved;
  } catch (error) { if (error.code !== 'ENOENT') throw error; }
  await writeFile(join(directory, 'metadata.json'), JSON.stringify(metadata, null, 2));
  await writeFile(join(directory, 'suite-frozen.json'), suiteText);
  const probes = {};
  const resultsFile = join(directory, 'results.json');
  try {
    for (const arm of ['llm', 'cross']) probes[arm] = await startProbe({ baseUrl: config.baseUrl, apiKey: config.apiKey,
      model: config.model, snippet: before.snippet, crossUrl }, directory);
    config.apiKey = ''; // Never serialize or log credentials.
    const warmup = { id: 'warmup', question: '示例服务何时重试？', items: [
      { key: 'w1', title: '服务手册', text: '示例服务只在暂时性故障时重试。' },
      { key: 'w2', title: '权限手册', text: '示例服务不重试权限错误。' },
      { key: 'w3', title: '部署手册', text: '镜像启动后进行健康检查。' }] };
    for (const arm of ['cross', 'llm']) {
      const result = await probes[arm].send({ ...warmup, arm });
      if (result.diagnostics?.status !== 'ok') throw Error(`Warmup ${arm} failed; no comparison started`);
    }
    if (!rows.length) await writeFile(join(directory, 'progress.jsonl'), '');
    for (let index = 0; index < suite.cases.length; index++) {
      const c = suite.cases[index];
      for (let trial = 0; trial < trials; trial++) {
        const items = shuffled(c.items, `rerank-ab-v1:${c.id}:${trial}`);
        const previous = rows.find(row => row.id === c.id && row.trial === trial);
        if (previous) {
          if (previous.candidateHash !== sha(JSON.stringify(inferenceItems(items)))) throw Error('Frozen candidates changed on resume');
          continue;
        }
        const row = { id: c.id, category: c.category, question: c.question, trial,
          candidateHash: sha(JSON.stringify(inferenceItems(items))), input: { order: items.map(item => item.key), score: scoreRanking(items, items.map(item => item.key)) } };
        const excerpt = await probes.cross.send({ id: c.id, question: c.question, arm: 'excerpt', items: inferenceItems(items) });
        row.excerpts = excerpt.excerpts;
        const launchOrder = (index + trial) % 2 ? ['llm', 'cross'] : ['cross', 'llm'];
        await Promise.all(launchOrder.map(async arm => {
          const result = await probes[arm].send({ id: c.id, question: c.question, arm, items: inferenceItems(items) });
          if (!result.order?.length) {
            await appendFile(join(directory, 'errors.jsonl'), JSON.stringify({ id: c.id, trial, arm, result }) + '\n');
            throw Error(`Backend ${arm} failed on ${c.id}; comparison stopped and diagnostics saved`);
          }
          row[arm] = { ...result, score: scoreRanking(items, result.order) };
        }));
        if (row.llm.excerptsHash !== row.cross.excerptsHash) throw Error('A/B excerpts changed between arms');
        if (row.llm.excerptsHash !== excerpt.excerptsHash) throw Error('Recorded excerpts differ from the model input');
        rows.push(row);
        await appendFile(join(directory, 'progress.jsonl'), JSON.stringify(row) + '\n');
        await writeFile(resultsFile, JSON.stringify(rows, null, 2));
        console.log(JSON.stringify({ done: rows.length, total: suite.cases.length * trials, id: c.id,
          llmNdcg5: row.llm.score.ndcg5, crossNdcg5: row.cross.score.ndcg5,
          llmMs: row.llm.elapsedMs, crossMs: row.cross.elapsedMs }));
      }
    }
    const statusAfter = await api(base, '/api/kb/rerank');
    const routeAfter = (await api(base, '/api/model/routing')).find(row => row.task === 'rerank');
    const after = { enabled: statusAfter.enabled, backend: statusAfter.backend, active: statusAfter.active,
      model: statusAfter.llm.model, snippet: statusAfter.llm.snippet, target: routeAfter.target };
    if (JSON.stringify(after) !== JSON.stringify(before)) throw Error('Application rerank configuration changed during evaluation');
    metadata.completedAt = new Date().toISOString(); metadata.after = after;
    const summary = summarize(suite, rows);
    await writeFile(join(directory, 'metadata.json'), JSON.stringify(metadata, null, 2));
    await writeFile(join(directory, 'summary.json'), JSON.stringify(summary, null, 2));
    await writeFile(join(directory, 'report.md'), report(metadata, summary, rows));
    console.log(JSON.stringify({ complete: true, directory, summary }));
  } finally { for (const probe of Object.values(probes)) probe.close(); }
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href)
  main().catch(() => { console.error('Evaluation failed. Check service health, compiled probe, and sanitized progress files. Credentials and provider error bodies are intentionally omitted.'); process.exitCode = 1; });
