// 校验 docs/ui-design-2026-10-10.md 里引用的每个 `文件:行` 锚点：
//   1) 文件存在、行号范围在文件长度内；
//   2) 关键论断处的行内容与文中说法一致（claims 表逐条断言）。
// 用法：node tools/ui-verify/check-anchors.mjs   （在仓库根目录执行）
import { readFileSync, existsSync } from 'node:fs'

const DOC = 'docs/ui-design-2026-10-10.md'
const doc = readFileSync(DOC, 'utf8')

// 约定：`file:12-18,26,45-50` —— 冒号后是「行号或范围」的逗号列表，`-` 只表示范围
const refRe = /([A-Za-z0-9_./-]+\.(?:vue|js|css|md)):(\d+(?:-\d+)?(?:,\d+(?:-\d+)?)*)/g
const cache = new Map()
const read = (p) => {
  if (!cache.has(p)) {
    const abs = existsSync(p) ? p : `frontend/src/${p}`
    cache.set(p, existsSync(abs) ? readFileSync(abs, 'utf8').split(/\r?\n/) : null)
  }
  return cache.get(p)
}

const seen = new Set()
let bad = 0, checked = 0
for (const m of doc.matchAll(refRe)) {
  const [, file, spec] = m
  for (const token of spec.split(',')) {
    const full = `${file}:${token}`
    if (seen.has(full)) continue
    seen.add(full)
    const lines = read(file)
    if (!lines) { console.error(`MISSING FILE  ${full}`); bad++; continue }
    const [a, b] = token.split('-')
    const from = Number(a), to = Number(b || a)
    if (from < 1 || to > lines.length || from > to) {
      console.error(`OUT OF RANGE   ${full}  (文件共 ${lines.length} 行)`); bad++; continue
    }
    checked++
  }
}
console.log(`锚点存在性：${checked}/${seen.size} 通过，${bad} 处有问题`)
// 这一项到底验证了什么，说清楚——免得 93/93 被读成「文内每个行号都还指向原处」：
// 它只验证「文件存在 + 行号不越界」。§1–§8 的行号是 P0 实施前写的坐标，
// 落地后被改动文件里的那些锚点已整体位移（详见 §9.1）。
console.log('  （本项只验证「文件存在 + 行号不越界」，不验证该行内容是否仍与文中说法一致；')
console.log('    §1–§8 的行号是 P0 实施前的快照，被改动文件里那些锚点已整体位移）')
console.log('')

// ---- 2) 关键论断逐条断言 ----
//
// 匹配策略（2026-10-12 重写）：**按内容 / 符号定位，不按行号**。
//
// 为什么改：本表原来写的是 `seg('views/NoteList.vue', 152, 170)` 这类硬编码行号区间。
// 源码一改，行号整体位移，断言却仍去取「第 152~170 行」——于是「代码变了」被误报成
// 「论断不成立」；反过来，论断真的坏了也可能因为恰好取到一段无关代码而误报成通过。
// P0 一轮提交就让 30 条里的 16 条变红（其中 9 条是纯行号位移误报），脚本因此丧失回归信号能力。
//
// 现在每条论断都先定位符号（函数签名 / 关键文本），再在**定位到的区间内**断言；
// 定位不到就抛 MissingAnchor，输出 `MISS 锚点缺失：…` 并让退出码为 1——
// 绝不退化成「按行号取一段无关代码，再静默判个通过/失败」。
// 每条断言后会打印它实际检查的位置 `[file:from-to]`，可直接对照复核。
const SRC = 'frontend/src'
const srcCache = new Map()
function src(p) {
  if (!srcCache.has(p)) {
    const abs = existsSync(p) ? p : (existsSync(`${SRC}/${p}`) ? `${SRC}/${p}` : null)
    srcCache.set(p, abs ? readFileSync(abs, 'utf8').split(/\r?\n/) : null)
  }
  return srcCache.get(p)
}

/** 定位失败必须响亮：静默取错行号，等于把「锚点没了」糊过去 */
class MissingAnchor extends Error {}

/** 本次断言检查过的位置，打印出来供人工复核 */
let trail = []
const note = (p, from, to) => trail.push(`${p}:${from}${to && to !== from ? `-${to}` : ''}`)

function must(v, what, p) {
  if (v === null || v === undefined || (typeof v === 'number' && !v)) {
    throw new MissingAnchor(`锚点缺失：${p} 中找不到 ${what}`)
  }
  return v
}

/** 按内容取第一处匹配行 */
function hit(p, re, what = String(re)) {
  const ls = must(src(p), '文件', p)
  const i = ls.findIndex((l) => re.test(l))
  must(i >= 0 ? i + 1 : null, what, p)
  note(p, i + 1)
  return {
    no: i + 1,
    text: ls[i],
    /** 含自身、前后各 r 行 */
    around: (r = 4) => ls.slice(Math.max(0, i - r), i + r + 1).join('\n'),
    /** 含自身、向后 n 行 */
    then: (n) => ls.slice(i, i + n).join('\n'),
  }
}

/** 只要「能定位到」就成立（断言某文案/符号存在时用） */
function found(p, re, what = String(re)) {
  hit(p, re, what)
  return true
}

/** 按签名定位一段被 opener/closer 包裹的块（函数体 / 数组字面量），返回块文本 */
function block(p, sigRe, what = String(sigRe), opener = '{', closer = '}') {
  const ls = must(src(p), '文件', p)
  const start = ls.findIndex((l) => sigRe.test(l))
  must(start >= 0 ? start + 1 : null, what, p)
  let depth = 0
  let opened = false
  for (let i = start; i < ls.length; i++) {
    for (const ch of ls[i]) {
      if (ch === opener) { depth++; opened = true } else if (ch === closer && opened) {
        depth--
        if (depth === 0) { note(p, start + 1, i + 1); return ls.slice(start, i + 1).join('\n') }
      }
    }
  }
  throw new MissingAnchor(`锚点缺失：${p} 第 ${start + 1} 行起 ${what} 的 ${closer} 找不到配对`)
}

const claims = [
  ['笔记列表 load() 已有 catch 并写入 loadError', () => {
    const b = block('views/NoteList.vue', /async function load\s*\(/, 'async function load()')
    return /catch\s*\(/.test(b) && /loadError\.value\s*=/.test(b)
  }],
  ['笔记列表失败时不再渲染「还没有内容」空态', () => {
    const h = hit('views/NoteList.vue', /<el-empty\b/, '<el-empty>')
    return /v-if="!loadError"/.test(h.text) && /还没有内容/.test(h.text)
  }],
  ['速查卡 load() 已有 catch 并写入 loadError', () => {
    const b = block('views/QuickRefs.vue', /async function load\s*\(/, 'async function load()')
    return /catch\s*\(/.test(b) && /loadError\.value\s*=/.test(b)
  }],
  ['速查卡空态区分「库为空」与「筛选后没有」', () => {
    const h = hit('views/QuickRefs.vue', /没有匹配的速查卡/, '没有匹配的速查卡')
    return /hasFilter/.test(h.text) && /还没有速查卡/.test(h.text)
  }],
  ['智能体会话读取失败不再吞成空数组', () => {
    const b = block('views/AgentSessions.vue', /async function loadAll\s*\(/, 'async function loadAll()')
    return /loadError\.value\s*=/.test(b) && !/sessions\.value\s*=\s*\[\]/.test(b)
  }],
  ['智能体会话空态文案「选择或新建一个会话」', () => found('views/AgentSessions.vue', /选择或新建一个会话/)],
  ['智能体空态与加载文案仍共用同一变量（已知未修项，文档 P1-7）', () => {
    const h = hit('views/AgentSessions.vue', /const emptyText\s*=\s*computed/, 'emptyText computed')
    // 这条**不是在断言「已修好」**：文档 P1-7 要求拆成「空态」与「加载态」两个变量，P0 不含它。
    // 通过 = 缺陷如实仍在；哪天拆开了这条会变红，提示该同步更新 P1-7 与本表。
    return /正在读取会话/.test(h.text) && /选择或新建一个会话/.test(h.text)
  }],
  ['总览统计请求已有 catch 并写入 statsError', () => {
    const b = block('views/Dashboard.vue', /async function load\s*\(/, 'async function load()')
    return /catch/.test(b) && /statsError\.value\s*=/.test(b)
  }],
  ['总览热力图请求有 catch', () => {
    const b = block('views/Dashboard.vue', /async function loadActivity\s*\(/, 'async function loadActivity()')
    return /catch/.test(b)
  }],
  ['热力图失败态给文案与重新加载', () => {
    const s = hit('components/ActivityHeatmap.vue', /class="activity-error"/, 'activity-error').then(8)
    return /加载失败/.test(s) && /重新加载/.test(s)
  }],
  ['笔记编辑有 loadError', () => found('views/NoteEdit.vue', /loadError\s*=\s*ref/, 'loadError ref')],
  ['笔记编辑加载失败有重试', () => found('views/NoteEdit.vue', /重试加载/)],
  ['笔记保存已有 catch 并写入 saveError', () => {
    const b = block('views/NoteEdit.vue', /async function save\s*\(/, 'async function save()')
    return /catch\s*\(/.test(b) && /saveError\.value\s*=/.test(b)
  }],
  ['保存状态位有 failed 档且可重试', () => {
    const b = block('views/NoteEdit.vue', /const saveState\s*=\s*computed/, 'saveState computed')
    return /failed/.test(b) && found('views/NoteEdit.vue', /重试保存/)
  }],
  ['资料库空态区分三档文案', () => {
    const b = block('views/FileLibrary.vue', /const emptyMessage\s*=\s*computed/, 'emptyMessage computed')
    return /hasFilter/.test(b) && /没有匹配/.test(b)
  }],
  ['资料库阅读器失败态降级文案', () => found('views/FileLibrary.vue', /排版还原失败/) && found('views/FileLibrary.vue', /检索层正文/)],
  ['知识检索失败有重试与切换方式', () => {
    const s = hit('components/KnowledgeSearchPanel.vue', /此次检索未完成/).then(25)
    return /重试/.test(s) && /切换/.test(s)
  }],
  ['知识检索有骨架屏', () => found('components/KnowledgeSearchPanel.vue', /result-skeleton\b/)],
  ['知识库检索失败态自己接管（silentError）', () => {
    const h = hit('api/index.js', /\/knowledge\/search/, 'knowledgeApi.search')
    return /silentError/.test(h.text)
  }],
  ['知识图谱探测失败有 role=alert', () => found('views/Knowledge.vue', /graphProbeError.*role="alert"/)],
  ['axios 拦截器已有链路降级登记（不再只弹 toast）', () => {
    const s = hit('api/index.js', /request\.interceptors\.response\.use/, 'response 拦截器').then(45)
    return /ElMessage\.error/.test(s) && /markBackendDown/.test(s)
  }],
  ['代码库检索失败仍用 el-alert 且无重试按钮（已知未修项，文档 P1-2）', () => {
    // 同上：这条断言的是「未修项仍存在」——文档 P1-2 才要求规范化失败文案。
    const h = hit('views/CodeLibrary.vue', /v-if="searchError"/, 'searchError 的 el-alert')
    return /el-alert/.test(h.text) && !/重试/.test(h.around(4))
  }],
  ['代码库有内联加载提示', () => {
    const h = hit('views/CodeLibrary.vue', /正在检索/, '内联加载提示')
    return /role="status"/.test(h.text)
  }],
  ['融入笔记失败会恢复按钮（失败原因由笔记页给出）', () => {
    const b = block('components/AgentPanel.vue', /function mergeResultFromEvent\s*\(/, 'mergeResultFromEvent()')
    return /mergePending\s*=\s*false/.test(b) && found('views/NoteEdit.vue', /reply\(false,/)
  }],
  ['样式 token 定义在同一处', () => found('style.css', /^\s*--app-bg\s*:/) && found('style.css', /^\s*--font-body\s*:/)],
  ['按需引入 Element Plus', () => found('frontend/vite.config.js', /resolvers:\s*\[ElementPlusResolver/) && found('main.js', /element-plus\/theme-chalk\/base\.css/)],
  ['路由有 chunk 失败恢复', () => found('router/index.js', /installRouteLoadRecovery\s*\(/)],
  ['笔记编辑有离开拦截', () => found('views/NoteEdit.vue', /onBeforeRouteLeave\s*\(/) && found('views/NoteEdit.vue', /beforeunload/)],
  ['笔记编辑有写冲突处理', () => found('views/NoteEdit.vue', /agentEditConflict\s*=\s*ref/) && found('views/NoteEdit.vue', /加载最新正文/)],
  ['导航有 7 个一级入口', () => {
    const b = block('layout/AppLayout.vue', /const navItems\s*=/, 'navItems', '[', ']')
    return (b.match(/index\s*:\s*'/g) || []).length === 7
  }],
]

console.log('说明：以下 30 条全部按内容/符号定位（不按行号）；名字里标「已知未修项」的 2 条，')
console.log('      通过 = 该未修项仍如实存在，不代表已修好。\n')

let wrong = 0, missing = 0
for (const [name, fn] of claims) {
  trail = []
  let tag = 'PASS', why = ''
  try {
    if (!fn()) { tag = 'FAIL'; wrong++ }
  } catch (e) {
    if (e instanceof MissingAnchor) { tag = 'MISS'; missing++ } else { tag = 'FAIL'; wrong++ }
    why = `  ${e.message || e}`
  }
  const where = trail.length ? `   [${trail.join(' ')}]` : ''
  console.log(`${tag}  ${name}${where}${why}`)
}
const pass = claims.length - wrong - missing
console.log(`\n论断核对：${pass}/${claims.length} 通过${missing ? `（另有 ${missing} 处锚点缺失）` : ''}`)
process.exit(bad || wrong || missing ? 1 : 0)
