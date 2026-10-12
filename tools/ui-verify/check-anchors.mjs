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
console.log(`锚点存在性：${checked}/${seen.size} 通过，${bad} 处有问题\n`)

// ---- 2) 关键论断逐条断言 ----
const src = (p) => readFileSync(`frontend/src/${p}`, 'utf8').split(/\r?\n/)
const at = (p, line) => src(p)[line - 1]
const seg = (p, f, t) => src(p).slice(f - 1, t).join('\n')

const claims = [
  ['笔记列表 load() 是 try/finally 且无 catch', () => {
    const s = seg('views/NoteList.vue', 152, 170)
    return /try\s*\{/.test(s) && /finally\s*\{/.test(s) && !/catch/.test(s)
  }],
  ['笔记列表空态文案把失败场景也说成"还没有内容"', () => /还没有内容/.test(at('views/NoteList.vue', 446))],
  ['速查卡 load() 无 catch', () => {
    const s = seg('views/QuickRefs.vue', 99, 110)
    return /try\s*\{/.test(s) && !/catch/.test(s)
  }],
  ['速查卡空态固定文案', () => /还没有速查卡/.test(at('views/QuickRefs.vue', 216))],
  ['智能体会话读取失败被吞成空数组', () => /sessions\.value\s*=\s*\[\]/.test(seg('views/AgentSessions.vue', 48, 50))],
  ['智能体空态文案"选择或新建一个会话"', () => /选择或新建一个会话/.test(at('views/AgentSessions.vue', 31))],
  ['智能体把加载文案塞进空态变量', () => /正在读取会话/.test(at('views/AgentSessions.vue', 31))],
  ['总览统计请求无 catch', () => {
    const s = seg('views/Dashboard.vue', 48, 55)
    return /try\s*\{/.test(s) && !/catch/.test(s)
  }],
  ['总览热力图请求有 catch', () => /catch/.test(seg('views/Dashboard.vue', 57, 69))],
  ['热力图有失败文案', () => /failText|加载失败/.test(seg('components/ActivityHeatmap.vue', 50, 53))],
  ['笔记编辑有 loadError', () => /loadError/.test(at('views/NoteEdit.vue', 943))],
  ['笔记编辑加载失败有重试', () => /重试加载/.test(seg('views/NoteEdit.vue', 3692, 3696))],
  ['笔记保存 try/finally 无 catch', () => {
    const s = seg('views/NoteEdit.vue', 1017, 1071)
    return /try\s*\{/.test(s) && /finally\s*\{/.test(s) && !/catch/.test(s)
  }],
  ['保存状态位只有三档，无 failed', () => {
    const s = seg('views/NoteEdit.vue', 3237, 3245)
    return !/failed/.test(s) && /saving|saved|dirty|unsaved/i.test(s)
  }],
  ['资料库空态区分三档文案', () => {
    const s = seg('views/FileLibrary.vue', 53, 56)
    return /hasFilter/.test(s) && /没有匹配/.test(s)
  }],
  ['资料库阅读器失败态降级文案', () => /版面还原失败|检索层正文/.test(seg('views/FileLibrary.vue', 831, 834))],
  ['知识检索失败有重试与切换方式', () => {
    const s = seg('components/KnowledgeSearchPanel.vue', 96, 105)
    return /重试/.test(s) && /切换|换一种|换个/.test(s)
  }],
  ['知识检索有骨架屏', () => {
    const s = seg('components/KnowledgeSearchPanel.vue', 70, 110)
    return /skeleton|Skeleton|骨架/.test(s)
  }],
  ['知识库检索失败态自己接管（silentError）', () => /silentError/.test(readFileSync('frontend/src/api/index.js', 'utf8').split(/\r?\n/)[80])],
  ['知识图谱探测失败有 role=alert', () => /role="alert"/.test(at('views/Knowledge.vue', 1350))],
  ['axios 拦截器只弹 toast', () => {
    const s = readFileSync('frontend/src/api/index.js', 'utf8').split(/\r?\n/).slice(8, 30).join('\n')
    return /ElMessage\.error/.test(s) && !/横幅|banner|offline/i.test(s)
  }],
  ['代码库检索失败用 el-alert 且无重试按钮', () => {
    const s = seg('views/CodeLibrary.vue', 575, 578)
    return /el-alert/.test(s) && !/重试/.test(s)
  }],
  ['代码库有内联加载提示', () => /正在检索/.test(at('views/CodeLibrary.vue', 576))],
  ['融入笔记失败会恢复按钮并给原因', () => /融入失败|失败/.test(seg('components/AgentPanel.vue', 497, 502))],
  ['样式 token 定义在同一处', () => /--app-|--font-/.test(seg('style.css', 14, 120))],
  ['按需引入 Element Plus', () => /element-plus|El[A-Z]/.test(seg('main.js', 13, 19))],
  ['路由有 chunk 失败恢复', () => /routeLoadRecovery|chunk/i.test(seg('router/index.js', 26, 39))],
  ['笔记编辑有离开拦截', () => /beforeunload|onBeforeRouteLeave|beforeEach|confirm/.test(seg('views/NoteEdit.vue', 3537, 3551))],
  ['笔记编辑有写冲突处理', () => /冲突|conflict/i.test(seg('views/NoteEdit.vue', 3805, 3820))],
  ['导航有 7 个一级入口', () => (seg('layout/AppLayout.vue', 57, 66).match(/[^\s]+\s*:\s*['"]?\//g) || []).length >= 6],
]

let fail = 0
for (const [name, fn] of claims) {
  let ok = false, err = ''
  try { ok = fn() } catch (e) { err = ` (${e.message})` }
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${ok ? '' : err}`)
  if (!ok) fail++
}
console.log(`\n论断核对：${claims.length - fail}/${claims.length} 通过`)
process.exit(bad || fail ? 1 : 0)
