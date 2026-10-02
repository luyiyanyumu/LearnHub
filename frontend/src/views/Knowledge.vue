<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MdPreview } from 'md-editor-v3'
import '../utils/mdEditorSetup'
import { fileApi, kbApi, knowledgeApi, kgApi, saveBlob, wikiApi } from '../api'
import { fixHtmlQuotes } from '../utils/htmlQuotes'
import { isDark } from '../composables/useTheme'
import KnowledgeGraph from '../components/KnowledgeGraph.vue'

/**
 * 知识库：三个视图
 * <ul>
 *   <li><b>知识图谱</b>：结构关系（分类/标签）实时现算 + 模型推断的语义关联；按版本号轮询，变了才重画</li>
 *   <li><b>LLM Wiki</b>：按主题（分类/标签）由模型整理成结构化长文，落库缓存，素材变了自动增量重生成</li>
 *   <li><b>检索</b>：默认走**融合检索**（词面 + 语义，跨笔记/速查卡/资料），可切回词面精确匹配</li>
 * </ul>
 */
const router = useRouter()

const tab = ref('graph')

// ------------------------------------------------------------------
// 一、检索：默认「融合检索」（词面 + 语义，后端 /api/kb/search），可切回「词面检索」
//
// 为什么默认融合：词面走的是 SQL LIKE，要求你用的词和资料里的字面完全一致。
// 实测搜「大量字符串拼接用哪个类性能更好」词面 0 命中，而融合检索能找回
// 《String / StringBuilder / StringBuffer 区别》速查卡 —— 语义能力不该只服务智能体和"检索体检"。
// 但精确找一个类名/关键字时词面更利落（且不依赖向量索引是否重建过），所以保留为可切换项。
// ------------------------------------------------------------------
const MODE_KEY = 'lh-kb-search-mode'
/** 检索方式：fusion=融合（默认，词面+语义） / keyword=词面（精确匹配） */
const searchMode = ref(localStorage.getItem(MODE_KEY) === 'keyword' ? 'keyword' : 'fusion')
const kw = ref('')
const loading = ref(false)
const searched = ref(false)
const items = ref([])
const keyword = ref('')
/** 上面这批结果**实际**来自哪种检索（融合模式下输入为空时会回落到词面的"最近知识"） */
const lastMode = ref('fusion')
/** 检索失败的可见原因（为空表示没出错） */
const searchError = ref('')

/** 命中片段截断长度：后端不返回 snippet，只给整段 text，太长会把列表撑成一屏一条 */
const SNIPPET_MAX = 120
const SOURCE_LABEL = { note: '笔记', quick_ref: '速查卡', file: '资料' }
const sourceLabel = (t) => SOURCE_LABEL[t] || t
const modeLabel = (m) => (m === 'fusion' ? '融合检索' : '词面检索')
const modeDesc = (m) =>
  m === 'fusion' ? '用一句话描述也能找回（词面 + 语义合并排序）' : '按字符串精确匹配，适合搜类名 / 关键字'

/** 融合模式下输入为空 → 已回落到词面的「最近知识」（后端拒绝空 q，直接 500） */
const emptyFallback = computed(() => searchMode.value === 'fusion' && lastMode.value === 'keyword' && !kw.value.trim())

/**
 * 结果分组：融合是一张**排序好的混合列表**（保持后端的相关度排名，不能再按类型分组），
 * 词面模式沿用原来的「笔记 / 资料 / 速查卡」三组。
 */
const resultGroups = computed(() => {
  if (lastMode.value === 'fusion') {
    return items.value.length ? [{ key: 'fusion', label: '融合检索', items: items.value }] : []
  }
  return [
    { key: 'note', label: '笔记', items: items.value.filter((i) => i.type === 'note') },
    { key: 'file', label: '资料', items: items.value.filter((i) => i.type === 'file') },
    { key: 'quick_ref', label: '速查卡', items: items.value.filter((i) => i.type === 'quick_ref') },
  ].filter((g) => g.items.length)
})

/**
 * 命中片段：后端融合检索只回整段 `text`（无 snippet 字段），这里截到 ~120 字；
 * 顺手把换行/连续空白压平 —— PDF 抽出来的正文满屏换行，不压平两行只显示得下几个字。
 */
function hitSnippet(r) {
  const flat = String(r?.snippet ?? r?.text ?? '').replace(/\s+/g, ' ').trim()
  return flat.length > SNIPPET_MAX ? flat.slice(0, SNIPPET_MAX) + '…' : flat
}

/** 切换检索方式：记住选择，并**立刻按新方式重搜一次**，避免"切了没反应" */
function switchMode(m) {
  if (searchMode.value === m) return
  searchMode.value = m
  try {
    localStorage.setItem(MODE_KEY, m)
  } catch (e) {
    /* 隐私模式写不了，忽略：不影响本次会话的切换 */
  }
  doSearch()
}

async function doSearch() {
  const q = kw.value.trim()
  loading.value = true
  searchError.value = ''
  searched.value = true
  try {
    if (searchMode.value === 'fusion' && q) {
      lastMode.value = 'fusion'
      // 字段名按后端实测：sourceType / sourceId / title / category / text / score（没有 snippet、updatedAt）
      const list = await kbApi.search(q, 10)
      items.value = (list || []).map((r) => ({
        type: r.sourceType,
        id: r.sourceId,
        title: r.title,
        snippet: hitSnippet(r),
        categoryName: r.category,
        score: typeof r.score === 'number' ? r.score : null,
      }))
      keyword.value = q
    } else {
      // 词面检索：用户选了词面，或融合模式下查询为空（空 q 后端会 500，这里回落到"最近知识"）
      lastMode.value = 'keyword'
      const res = await knowledgeApi.search(kw.value)
      items.value = res.items || []
      keyword.value = res.keyword || ''
    }
  } catch (e) {
    // 不静默失败：页面上留一条错误说明，同时弹一次可见提示
    items.value = []
    keyword.value = q
    lastMode.value = searchMode.value === 'fusion' && q ? 'fusion' : 'keyword'
    searchError.value = e?.response?.data?.msg || e?.message || '未知错误'
    ElMessage.error(
      `${modeLabel(lastMode.value)}失败：${searchError.value}` +
        (searchMode.value === 'fusion' ? ' —— 可切到「词面」再试' : ''),
    )
  } finally {
    loading.value = false
  }
}

/**
 * 打开一条结果：跳回原文。
 * 项目现有路由只有列表页（速查卡 /refs、资料 /files，都不带"打开某一条"的参数），
 * 所以能精确定位的只有笔记（/notes/:id）。
 */
function open(item) {
  if (item.type === 'note') {
    router.push(`/notes/${item.id}`)
    return
  }
  if (item.type === 'quick_ref') {
    router.push('/refs')
    return
  }
  // 资料：跳到资料库（原来的行为是直接下载，现在统一"跳回来源"；行尾仍保留「下载」）
  router.push('/files')
}

/** 把关键词高亮成 <mark>：先整体转义再替换，避免用户输入被当 HTML 执行 */
function hl(text) {
  const safe = String(text ?? '')
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  const k = keyword.value.trim()
  if (!k) return safe
  const pattern = k.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return safe.replace(new RegExp(pattern, 'gi'), (m) => `<mark>${m}</mark>`)
}

function clearSearch() {
  kw.value = ''
  doSearch()
}

// ------------------------------------------------------------------
// 一.5 语义索引状态与体检（"资料能不能问什么都知道"的可验证依据）
// ------------------------------------------------------------------
const kb = ref(null)
const kbJob = ref(null)
const kbBusy = ref(false)
const probeQ = ref('')
const probeResult = ref(null)
const probeBusy = ref(false)

/**
 * ③ 局部重编译：先做影响分析（模型判断该更新哪些页），再只重建受影响的页面。
 * <p>这是"摄入时局部重编译"的实用版：改完东西点一下，比整库重来省得多。
 */
const reBusy = ref(false)
const reJob = ref(null)

async function recompileAffected() {
  reBusy.value = true
  try {
    const started = await wikiApi.recompile()
    reJob.value = started
    for (let i = 0; i < 900; i++) {
      await new Promise((r) => setTimeout(r, 1000))
      const j = await wikiApi.job(started.jobId)
      reJob.value = j
      if (j.status !== 'running') break
    }
    if (reJob.value?.status === 'done') {
      ElMessage.success('已重建受影响的页面：' + (reJob.value.detail || ''))
      await loadTopicsQuiet()
    } else if (reJob.value?.status === 'failed') {
      ElMessage.error('重建失败：' + (reJob.value.error || ''))
    }
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    reBusy.value = false
    setTimeout(() => { reJob.value = null }, 1500)
  }
}

/** ④ 语义自检：代码检查（红链/质量/过短）+ 模型检查（矛盾/过时/缺口） */
const lintBusy = ref(false)

async function runLint() {
  lintBusy.value = true
  try {
    const r = await wikiApi.lint()
    ElMessage.success(`自检完成：代码问题 ${r.codeIssues} 条，模型发现 ${r.modelIssues} 条`)
    await loadTopicsQuiet()
    await openTopic('lint')
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    lintBusy.value = false
  }
}

async function loadKb() {
  try {
    kb.value = await kbApi.status()
  } catch (e) {
    kb.value = null
  }
}

async function rebuildIndex() {
  kbBusy.value = true
  try {
    const started = await kbApi.rebuild()
    kbJob.value = started
    for (let i = 0; i < 600; i++) {
      await new Promise((r) => setTimeout(r, 1000))
      const j = await kbApi.job(started.jobId)
      kbJob.value = j
      if (j.status !== 'running') break
    }
    if (kbJob.value?.status === 'done') {
      ElMessage.success(`索引已重建：${kbJob.value.chunks} 块 / ${kbJob.value.chars} 字`)
    } else if (kbJob.value?.status === 'failed') {
      ElMessage.error('索引重建失败：' + (kbJob.value.error || ''))
    }
    await loadKb()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    kbBusy.value = false
    setTimeout(() => {
      kbJob.value = null
    }, 1500)
  }
}

/** 体检：同一个问题，词面与语义各命中什么（换一种说法最能看出差别） */
async function runProbe() {
  const q = probeQ.value.trim() || kw.value.trim()
  if (!q) {
    ElMessage.info('先写一个问句 —— 用「换一种说法」的问法最能看出差别')
    return
  }
  probeBusy.value = true
  try {
    probeResult.value = await kbApi.probe(q)
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    probeBusy.value = false
  }
}

// ------------------------------------------------------------------
// 二、知识图谱
// ------------------------------------------------------------------
const graph = ref({ nodes: [], edges: [], stat: {} })
const graphLoading = ref(false)
const selected = ref(null)
const detailRef = ref(null)
const rebuilding = ref(false)
const kgVersion = ref('')
const semanticEdges = ref(0)

// 概念层（知识图谱）：与文档层是两套数据、两个选中态，切层时互不干扰
const layer = ref('doc')
const concept = ref({ nodes: [], edges: [], ontology: [], stat: null, rule: null })
const conceptLoading = ref(false)
const conceptSelected = ref(null)
const relationFilter = ref([])
/** 聚焦集合：展开 N 跳后只显示这一片；null = 显示全图 */
const focus = ref(null)
const kgBuilding = ref(false)
const kgJob = ref(null)
const kgBusy = ref(false)
const deletingConcept = ref('')

/** 选中节点的语义关联（用来在详情里列出"和谁有关、为什么"） */
const selectedLinks = computed(() => {
  const n = selected.value
  if (!n) return []
  const byId = new Map(graph.value.nodes.map((x) => [x.id, x]))
  return graph.value.edges
    .filter((e) => e.kind === 'semantic' && (e.source === n.id || e.target === n.id))
    .map((e) => {
      const otherId = e.source === n.id ? e.target : e.source
      return { title: byId.get(otherId)?.label || otherId, relation: e.relation, reason: e.reason, other: byId.get(otherId) }
    })
})

async function loadGraph() {
  graphLoading.value = true
  try {
    const res = await kgApi.graph()
    graph.value = res
    kgVersion.value = res.version
    semanticEdges.value = res.stat?.semanticEdges || 0
    if (selected.value) {
      // 数据刷新后把选中项换成新对象，避免详情面板还显示旧引用
      selected.value = res.nodes.find((n) => n.id === selected.value.id) || null
    }
  } catch (e) {
    /* 提示已由拦截器统一弹出 */
  } finally {
    graphLoading.value = false
  }
}

/**
 * 实时刷新：每 5 秒问一次版本指纹，变了才重新拉图。
 * 选轮询而不是 SSE：后端那四个 COUNT/MAX 走索引、代价可忽略，
 * 也不必担心代理把长连接掐掉。只在图谱这一屏开着时才轮询。
 */
let pollTimer = null
async function checkVersion() {
  try {
    const v = await kgApi.version()
    if (v.version && v.version !== kgVersion.value) {
      await loadGraph()
    }
  } catch (e) {
    /* 轮询失败静默：下一轮会再试，不要在界面上刷错误提示 */
  }
}
function startPoll() {
  stopPoll()
  pollTimer = setInterval(checkVersion, 5000)
}
function stopPoll() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function rebuild() {
  rebuilding.value = true
  try {
    const r = await kgApi.rebuild()
    ElMessage.success(`已重建：${r.edges} 条关联（参与 ${r.items} 条，丢弃 ${r.rejected} 条）`)
    await loadGraph()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    rebuilding.value = false
  }
}

/**
 * 详情栏现在在画布**上方**，所以只需要处理"它被滚到视口上方看不见"这一种情况。
 * <p>
 * 图谱 tab 是整列撑满的布局（.graph-fill + 画布 flex:1），正常情况下整屏可见、不需要滚动；
 * 只有当详情栏的关系列表很长、把整列顶出视口时才会用到这里。
 */
function ensureDetailVisible() {
  nextTick(() => {
    const el = detailRef.value
    if (!el) return
    const scroller = el.closest('.content') || document.scrollingElement
    if (!scroller) return
    const er = el.getBoundingClientRect()
    const sr = scroller.getBoundingClientRect()
    if (er.top < sr.top + 8) {
      scroller.scrollTop -= sr.top + 8 - er.top
    }
  })
}

function onSelectNode(n) {
  if (layer.value === 'concept') {
    conceptSelected.value = n
  } else {
    selected.value = n
  }
  if (n) {
    ensureDetailVisible()
  }
}

// ------------------------------------------------------------------
// 概念层（真正的知识图谱）：实体 + 三元组 + 规则推导
// ------------------------------------------------------------------

/** 当前层画布上的节点/边：文档层直接用图接口，概念层按关系过滤 + 聚焦子图 */
const canvasNodes = computed(() => (layer.value === 'doc' ? graph.value.nodes : shownConceptNodes.value))
const canvasEdges = computed(() => (layer.value === 'doc' ? graph.value.edges : shownConceptEdges.value))

/** 详情栏里显示的是哪一层的选中项 */
const selectedNode = computed(() => (layer.value === 'doc' ? selected.value : conceptSelected.value))

/** 概念层：先按关系过滤，再按"聚焦集合"裁剪（展开邻居后只显示关注的那一片） */
const shownConceptEdges = computed(() => {
  const all = concept.value.edges || []
  const byRel = relationFilter.value?.length
    ? all.filter((e) => relationFilter.value.includes(e.relation))
    : all
  if (!focus.value) return byRel
  return byRel.filter((e) => focus.value.has(e.source) && focus.value.has(e.target))
})

const shownConceptNodes = computed(() => {
  const wanted = new Set()
  for (const e of shownConceptEdges.value) {
    wanted.add(e.source)
    wanted.add(e.target)
  }
  return (concept.value.nodes || []).filter((n) => wanted.has(n.id))
})

/** 选中概念的关系清单（入边 + 出边，标出哪条是推导来的） */
const conceptRelList = computed(() => {
  const cur = conceptSelected.value
  if (!cur || layer.value !== 'concept') return []
  const byId = new Map((concept.value.nodes || []).map((n) => [n.id, n]))
  const out = []
  for (const e of concept.value.edges || []) {
    if (e.source === cur.id) {
      out.push({ ...e, out: true, other: byId.get(e.target)?.label || e.target })
    } else if (e.target === cur.id) {
      out.push({ ...e, out: false, other: byId.get(e.source)?.label || e.source })
    }
  }
  // 直接抽取的排前面，推导的在后（人先看原文里的，再看推出来的）
  out.sort((a, b) => (a.origin === 'derived' ? 1 : 0) - (b.origin === 'derived' ? 1 : 0))
  return out
})

async function loadConcept() {
  conceptLoading.value = true
  try {
    concept.value = await kgApi.concept()
    // 默认只勾选"有代数性质"的关系会让图太空，所以默认全选 —— 过滤是给人收窄用的
    if (!relationFilter.value?.length) {
      relationFilter.value = (concept.value.ontology || []).map((r) => r.id)
    }
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    conceptLoading.value = false
  }
}

/** 重建概念图谱：素材 → 抽三元组 → 链接入库 → 规则推理 → 实体向量化 */
async function buildConcept() {
  try {
    const r = await kgApi.buildConcept()
    kgJob.value = r
    kgBuilding.value = true
    await pollConceptJob(r.jobId)
  } catch (e) {
    kgBuilding.value = false
  }
}

async function pollConceptJob(jobId) {
  const deadline = Date.now() + 15 * 60 * 1000
  while (Date.now() < deadline) {
    await new Promise((res) => setTimeout(res, 1500))
    let j
    try {
      j = await kgApi.conceptJob(jobId)
    } catch (e) {
      break
    }
    kgJob.value = j
    if (j.status !== 'running') {
      kgBuilding.value = false
      if (j.status === 'done') {
        ElMessage.success(`概念图谱已重建：实体 ${j.entities} 个 · 新增三元组 ${j.triples} 条`)
        focus.value = null
        await loadConcept()
      } else {
        ElMessage.error('重建失败：' + (j.error || ''))
      }
      return
    }
  }
  kgBuilding.value = false
}

/** 只跑规则推理：不调模型，所以免费、可反复点 */
async function reasonConcept() {
  kgBusy.value = true
  try {
    const r = await kgApi.reason()
    ElMessage.success(`推理完成：直接事实 ${r.direct} 条 → 新增隐含事实 ${r.derivedAdded} 条`)
    await loadConcept()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    kgBusy.value = false
  }
}

/** 展开 N 跳：把可达子图设为"聚焦集合"，画布只显示这一片（这就是多跳遍历的可视化） */
async function expandConcept(id, hops) {
  kgBusy.value = true
  try {
    const r = await kgApi.neighbors(id, hops)
    if (!r.found) {
      ElMessage.warning('图谱里没有这个概念')
      return
    }
    const set = new Set([id])
    for (const t of r.triples) {
      const from = (concept.value.nodes || []).find((n) => n.label === t.from)
      const to = (concept.value.nodes || []).find((n) => n.label === t.to)
      if (from) set.add(from.id)
      if (to) set.add(to.id)
    }
    focus.value = set
    ElMessage.info(`已聚焦 ${set.size} 个概念（${hops} 跳可达 ${r.reachable} 个）—— 点「显示全部」回到全图`)
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    kgBusy.value = false
  }
}

/** 删除概念：连同它的三元组一起删（删的是图的结论，原始笔记一行不动） */
async function removeConceptNode(n) {
  try {
    await ElMessageBox.confirm(
      `删除概念「${n.label}」？<br><br>` +
        '<span style="color:#6b7280">· 它的所有三元组会一起删掉<br>' +
        '· 原始笔记 / 速查卡 / 资料一行不动，但**不会自动重建** —— 除非你重新点「重建概念图谱」</span>',
      '删除概念',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', dangerouslyUseHTMLString: true }
    )
  } catch {
    return
  }
  deletingConcept.value = n.id
  try {
    const r = await kgApi.removeConceptNode(n.id)
    ElMessage.success(`已删除「${r.name}」及其 ${r.edgesDeleted} 条三元组`)
    if (conceptSelected.value?.id === n.id) {
      conceptSelected.value = null
    }
    focus.value = null
    await loadConcept()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    deletingConcept.value = ''
  }
}

/** 从概念跳到它的 wiki 概念页（图上看到关系，长文在那边） */
function openConceptWiki(wikiKey) {
  tab.value = 'wiki'
  nextTick(() => openTopic(wikiKey))
}

/** 带着概念名去问智能体 */
function askAgentConcept(name) {
  window.dispatchEvent(new CustomEvent('lh-ask-agent', {
    detail: { message: `「${name}」这个概念和王库里相关概念之间是什么关系？按「属于/前置/易混」讲清楚。` },
  }))
}

function askAgent(kind, id, title) {
  const isNote = kind === 'note'
  const isFile = kind === 'file'
  window.dispatchEvent(new CustomEvent('lh-ask-agent', {
    detail: {
      message: isNote
        ? `「${title}」这篇笔记我想再确认一遍：它的核心结论是什么？有没有容易记错或漏掉的地方？`
        : isFile
          ? `资料「${title}」里有哪些关键点？帮我提炼一下要点。`
          : `速查卡「${title}」我该怎么用？有哪些易错点或常见变体？`,
      // 只有笔记才把 id 当"笔记上下文"传过去：资料/速查卡靠问题里的名字命中检索
      noteId: isNote ? id : undefined,
      noteTitle: title,
    },
  }))
}

function parseNodeId(nodeId) {
  const i = String(nodeId).indexOf('-')
  return { type: nodeId.slice(0, i), id: Number(nodeId.slice(i + 1)) }
}

function openNode(n) {
  if (!n) return
  if (n.type === 'note') router.push(`/notes/${parseNodeId(n.id).id}`)
  else if (n.type === 'ref') router.push('/refs')
  else if (n.type === 'category') router.push('/notes')
  else if (n.type === 'file') downloadFile(parseNodeId(n.id).id, n.label)
}

/** 资料节点/检索项的下载（没有在线预览，落地后用本机程序打开） */
async function downloadFile(id, name) {
  try {
    const resp = await fileApi.download(id)
    saveBlob(resp.data, name || `资料-${id}`)
  } catch (e) {
    ElMessage.error('下载失败')
  }
}

function nodeTypeLabel(t) {
  return { note: '笔记', ref: '速查卡', category: '分类', tag: '标签', file: '资料' }[t] || t
}

// ------------------------------------------------------------------
// 三、LLM Wiki
// ------------------------------------------------------------------
const topics = ref([])
const topicsLoading = ref(false)
const activeTopic = ref('')
const wikiPage = ref(null)
const wikiLoading = ref(false)

/** 生成任务（进度）与可选模型目标 */
const job = ref(null)
const wikiTarget = ref('')
const wikiTargets = ref([])
const wikiGenerationChars = ref(0)
const generating = ref(false)
const autoRefresh = ref(true)
let staleTimer = null

async function loadTopics() {
  topicsLoading.value = true
  try {
    topics.value = await wikiApi.topics()
    const cur = topics.value.find((t) => t.topicKey === activeTopic.value)
    if (!cur && topics.value.length) {
      // 默认选第一个"有条目"的主题
      const first = topics.value.find((t) => t.itemCount > 0) || topics.value[0]
      if (first) await openTopic(first.topicKey)
    } else if (cur) {
      await openTopic(cur.topicKey, true)
    }
  } finally {
    topicsLoading.value = false
  }
}

async function openTopic(key, silent = false) {
  if (!key) return
  activeTopic.value = key
  wikiLoading.value = true
  stopStaleWatch()
  try {
    wikiPage.value = await wikiApi.page(key)
    // 过期且开着自动更新：后台会自己重生成，这里盯一会儿把结果取回来
    if (wikiPage.value.stale && autoRefresh.value && !silent) {
      startStaleWatch(key)
    }
  } finally {
    wikiLoading.value = false
  }
}

/** 自动更新是后端后台跑的，前端轮询等它落地（最多约 1 分钟） */
function startStaleWatch(key) {
  let n = 0
  staleTimer = setInterval(async () => {
    n++
    if (n > 12 || activeTopic.value !== key) {
      stopStaleWatch()
      return
    }
    try {
      const p = await wikiApi.page(key)
      if (!p.stale) {
        wikiPage.value = p
        stopStaleWatch()
        loadTopicsQuiet()
        ElMessage.success('wiki 已按最新内容自动更新')
      }
    } catch (e) {
      stopStaleWatch()
    }
  }, 5000)
}
function stopStaleWatch() {
  if (staleTimer) {
    clearInterval(staleTimer)
    staleTimer = null
  }
}

async function loadTopicsQuiet() {
  try {
    topics.value = await wikiApi.topics()
  } catch (e) {
    /* 静默 */
  }
}

/**
 * 生成 wiki：**异步任务 + 轮询进度**。
 * <p>
 * 本地小模型出一页要 20~35 秒（大主题更久），同步等待时界面只能转圈；
 * 现在能显示真实阶段（调用模型 / 校验内容 / 生成中…）与真实进度（流式累计的生成字数）。
 */
async function generate() {
  if (!activeTopic.value) return
  generating.value = true
  job.value = null
  try {
    const started = await wikiApi.generate(activeTopic.value, wikiTarget.value || undefined)
    job.value = started
    // 轮询直到结束：1 秒一次，失败/完成即停
    for (let i = 0; i < 320; i++) {
      await new Promise((r) => setTimeout(r, 1000))
      const j = await wikiApi.job(started.jobId)
      job.value = j
      if (j.status !== 'running') break
    }
    const last = job.value
    if (last?.status === 'done') {
      if (last.quality === 'warn') {
        ElMessage.warning('wiki 已生成，但质量校验有提示（已自动重生成一次）')
      } else {
        ElMessage.success('wiki 已生成并通过校验')
      }
      await openTopic(activeTopic.value, true)
      await loadTopicsQuiet()
      if (last.chars) wikiGenerationChars.value = last.chars
    } else if (last?.status === 'failed') {
      ElMessage.error('生成失败：' + (last.error || '未知错误'))
    }
  } catch (e) {
    /* 拦截器已提示（含"AI 未配置"与素材为空两种情况） */
  } finally {
    generating.value = false
    setTimeout(() => {
      job.value = null
    }, 1500)
  }
}

/**
 * 编译实体/概念页 + 索引页（全库操作，后台任务 + 进度）。
 * <p>这是"摄入时编译"的跨页那一半：把素材里的概念抽出来一页一个、互相 [[双链]]，
 * 并生成确定性的 index.md 作为入口。
 */
const entJob = ref(null)
const entBusy = ref(false)

async function compileEntities() {
  entBusy.value = true
  try {
    const started = await wikiApi.compileEntities()
    entJob.value = started
    for (let i = 0; i < 900; i++) {
      await new Promise((r) => setTimeout(r, 1000))
      const j = await wikiApi.entityJob(started.jobId)
      entJob.value = j
      if (j.status !== 'running') break
    }
    if (entJob.value?.status === 'done') {
      ElMessage.success(`知识页编译完成：${entJob.value.pages} 页`)
      await loadTopicsQuiet()
      await openTopic('index')
    } else if (entJob.value?.status === 'failed') {
      ElMessage.error('编译失败：' + (entJob.value.error || ''))
    }
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    entBusy.value = false
    setTimeout(() => {
      entJob.value = null
    }, 1500)
  }
}

/** 拉取可选的生成目标（主模型 / 本地或自建），并沿用上次的选择 */
async function loadWikiTargets() {
  try {
    const r = await wikiApi.models()
    wikiTargets.value = r.options || []
    wikiTarget.value = r.selected || (wikiTargets.value[0]?.id ?? '')
  } catch (e) {
    wikiTargets.value = []
  }
}

async function loadAutoRefresh() {  try {
    const r = await wikiApi.autoRefresh()
    autoRefresh.value = !!r.enabled
  } catch (e) {
    autoRefresh.value = true
  }
}

async function toggleAutoRefresh(v) {
  try {
    const r = await wikiApi.setAutoRefresh(v)
    autoRefresh.value = !!r.enabled
    ElMessage.success(r.enabled ? '已开启自动更新：改完笔记会自动重生成对应主题' : '已关闭自动更新')
  } catch (e) {
    autoRefresh.value = !v
  }
}

/**
 * 把模型写的来源标记 [笔记#3] / [速查卡#5] 变成可点击链接。
 * 直接渲染成普通文本用户没法跳回去核对，而"能追到原文"正是 wiki 可信的前提。
 */
/**
 * 把 wiki 正文里的来源标记变成可点链接。
 * <p>
 * 正则刻意**宽容**：本地小模型（如 qwen3:8b）偶尔会把标记写坏 ——
 * 实测出现过 `[速查、卡#8]`（中文词里多一个顿号）。用 `[^\]]{1,8}` 兜住这类噪声，
 * 再按包含关系判断类型，否则那一条引用就静默变成纯文本（看着像写错了，其实是没渲染）。
 */
/**
 * 编译产物页（实体 / 索引 / 自检报告）的计数口径和"素材"完全不同：
 * 自检页的 itemCount 是**问题条数**、索引页是**收录页数**、实体页是**来源证据数**。
 * 统称"N 条素材"会让数字说谎，所以按类型分别措辞。
 */
const isCompiledPage = computed(() => ['entity', 'index', 'lint'].includes(wikiPage.value?.topicType))

const wikiCountLabel = computed(() => {
  const p = wikiPage.value
  if (!p) return ''
  const n = p.itemCount ?? 0
  if (p.topicType === 'lint') return `检测到 ${n} 条问题`
  if (p.topicType === 'index') return `收录 ${n} 个页面`
  if (p.topicType === 'entity') return n > 0 ? `${n} 条来源证据` : '跨页编译生成'
  return `${n} 条素材`
})

/**
 * 主题总数 ≠ 已生成页数。以前工具条直接把 `topics.length` 写成「N 页编译产物」，
 * 把**还没生成页的主题**也算进去了（实测：48 个主题里只有 38 个有页）。
 * 后端 topics 接口返回 `generated` 字段，这里据此分开显示。
 */
const generatedTopicCount = computed(() => topics.value.filter((t) => t.generated).length)

/**
 * 侧栏按**类型分组**：以前是一锅平铺的列表，`cat-0`（早期版本写歪的索引页）和真正的索引页
 * 会以同样的"知识索引"标题并排出现，根本分不清谁是谁。分组后类型一眼可见。
 */
const TYPE_ORDER = ['category', 'tag', 'entity', 'index', 'lint']
const TYPE_LABEL = { category: '分类', tag: '标签', entity: '实体', index: '索引', lint: '自检' }

function typeLabel(t) {
  return TYPE_LABEL[t] || '主题'
}

const topicGroups = computed(() => {
  const groups = []
  for (const type of TYPE_ORDER) {
    const items = topics.value.filter((t) => (t.topicType || 'category') === type)
    if (items.length) {
      groups.push({ type, label: TYPE_LABEL[type] || type, items })
    }
  }
  // 兜底：出现未知类型时也别忘了显示，否则页面会"消失"
  const known = new Set(TYPE_ORDER)
  const rest = topics.value.filter((t) => !known.has(t.topicType || 'category'))
  if (rest.length) {
    groups.push({ type: 'other', label: '其他', items: rest })
  }
  return groups
})

/** 正在删除的 topicKey（用于按钮 loading），空串表示没有 */
const deletingKey = ref('')

/**
 * 删除一页编译产物。确认框里把**会发生什么**讲清楚：
 * 删的只是编译结果、原始素材不动、而且下次生成还会回来。
 */
async function removeTopic(t) {
  if (!t || !t.topicKey) return
  const isCompiled = ['entity', 'index', 'lint'].includes(t.topicType)
  // 实测：抽取本身不确定，同一份素材两次跑选出的概念集合就不同 ——
  // 所以实体页删掉之后**不保证**下次还抽得到它。这里必须说实话，别让用户以为删了没代价。
  const backHint = t.topicType === 'lint'
    ? '下次点「自检」会重新生成'
    : t.topicType === 'index'
      ? '下次点「编译知识页」会重新生成'
      : isCompiled
        ? '实体页来自模型抽取：下次「编译知识页」只有再次抽到这个概念才会回来'
        : '下次点「生成 wiki」会重新生成'
  try {
    // 用 HTML 换行：纯文本里的 \n 会被元素折叠成一个空格，三行说明会糊成一段
    await ElMessageBox.confirm(
      `删除「${t.title}」这一页编译结果？<br><br>` +
        '<span style="color:#6b7280">· 原始笔记 / 速查卡 / 资料一行都不会动<br>' +
        `· ${backHint}<br>` +
        '· 指向它的 [[双链]] 会变成红链，下次「自检」会列出来</span>',
      '删除知识页',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        dangerouslyUseHTMLString: true,
      }
    )
  } catch {
    return // 用户取消
  }
  deletingKey.value = t.topicKey
  try {
    // 拦截器直接返回 Result.data，所以这里拿不到 .data 那一层
    const r = await wikiApi.removePage(t.topicKey)
    const n = r?.deleted ?? 0
    ElMessage.success(n ? `已删除「${t.title}」` : `「${t.title}」本来就不存在`)
    // 删的是当前打开的页 → 清空右侧，避免显示一个已经不存在的页
    if (activeTopic.value === t.topicKey) {
      activeTopic.value = ''
      wikiPage.value = null
    }
    await loadTopics()
  } catch (e) {
    // 拦截器已提示
  } finally {
    deletingKey.value = ''
  }
}

const wikiMd = computed(() => {
  const md = wikiPage.value?.contentMd || ''
  // [[名字]] → 实体页链接。名字到页面 key 的映射由**后端**给出（正是为了避免前后端各自 slug 化而不一致）；
  // 找不到对应页时保留原文 —— 对应 wiki 里的"红链"：它本身是有用信号（说明这个概念还没成页）。
  const withLinks = md.replace(/\[\[([^\[\]]{1,40})\]\]/g, (m, name) => {
    const key = wikiPage.value?.entityIndex?.[String(name).trim()]
    return key ? `[${String(name).trim()}](#${key})` : m
  })
  return fixHtmlQuotes(
    withLinks.replace(/\[([^\]]{1,8})#(\d+)\]/g, (m, rawKind, id) => {
      const kind = String(rawKind).replace(/[^\u4e00-\u9fa5]/g, '')
      const anchor = kind.includes('资料') ? 'file' : kind.includes('速查') ? 'ref' : kind.includes('笔记') ? 'note' : null
      if (!anchor) return m
      return `[${kind}#${id}](#${anchor}-${id})`
    }),
  )
})

function onWikiClick(e) {
  const a = e.target?.closest?.('a[href^="#note-"], a[href^="#ref-"], a[href^="#file-"], a[href^="#entity-"], a[href="#index"]')
  if (!a) return
  e.preventDefault()
  const href = a.getAttribute('href')
  if (href.startsWith('#note-')) {
    router.push(`/notes/${href.slice(6)}`)
  } else if (href.startsWith('#file-')) {
    downloadFile(Number(href.slice(6)), `资料-${href.slice(6)}`)
  } else if (href.startsWith('#entity-') || href === '#index') {
    // 双链跳转：切到那个知识页（页面 key 就是 topicKey，去掉 # 即可）
    openTopic(href.slice(1))
  } else {
    router.push('/refs')
  }
}

// ------------------------------------------------------------------
// 生命周期
// ------------------------------------------------------------------
watch(tab, (v) => {
  if (v === 'graph') {
    if (!graph.value.nodes.length) loadGraph()
    startPoll()
  } else {
    stopPoll()
  }
  if (v === 'wiki' && !topics.value.length) loadTopics()
})

onMounted(() => {
  doSearch()
  loadGraph()
  startPoll()
  loadAutoRefresh()
  loadWikiTargets()
  loadKb()
  loadConcept()
})

// 切到概念层时才确保数据是新的（进页面就查一次是为了让标签上的实体数先显示出来）
watch(layer, (v) => {
  if (v === 'concept' && !concept.value.nodes.length) {
    loadConcept()
  }
})

onBeforeUnmount(() => {
  stopPoll()
  stopStaleWatch()
})
</script>

<template>
  <div class="page">
    <div class="head">
      <div>
        <h2 class="page-h2">知识库</h2>
        <p class="head-sub">笔记、速查卡与资料，一处检索、一张图谱、一页 wiki。</p>
      </div>
      <div class="tabs">
        <button type="button" :class="{ on: tab === 'graph' }" @click="tab = 'graph'">知识图谱</button>
        <button type="button" :class="{ on: tab === 'wiki' }" @click="tab = 'wiki'">LLM Wiki</button>
        <button type="button" :class="{ on: tab === 'search' }" @click="tab = 'search'">检索</button>
      </div>
    </div>

    <!-- ============ 知识图谱 ============ -->
    <template v-if="tab === 'graph'">
      <div class="graph-fill">
        <div class="bar">
          <!-- 两层是两件事：文档层是相似度图（哪几篇相关），概念层才是知识图谱（概念之间是什么关系） -->
          <div class="layer">
            <button type="button" :class="{ on: layer === 'doc' }" @click="layer = 'doc'">文档层</button>
            <button type="button" :class="{ on: layer === 'concept' }" @click="layer = 'concept'">
              概念层<template v-if="concept.stat">（{{ concept.stat.nodes }}）</template>
            </button>
          </div>

          <template v-if="layer === 'doc'">
            <span class="legend"><i class="dot dot-cat" />分类</span>
            <span class="legend"><i class="dot dot-note" />笔记</span>
            <span class="legend"><i class="dot dot-ref" />速查卡</span>
            <span class="legend"><i class="dot dot-file" />资料</span>
            <span class="legend"><i class="dot dot-tag" />标签</span>
            <span class="legend"><i class="line" />结构关系</span>
            <span class="legend"><i class="line line-sem" />语义关联（模型推断）</span>
          </template>
          <template v-else>
            <span class="legend"><i class="line line-sem" />模型抽取</span>
            <span class="legend"><i class="line line-derived" />规则推导（隐含事实）</span>
          </template>

          <div class="spacer" />
          <template v-if="layer === 'doc'">
            <span class="hint">{{ graph.stat?.nodes || 0 }} 个节点 · {{ graph.stat?.edges || 0 }} 条边（语义 {{ semanticEdges }}）</span>
            <el-button size="small" :loading="rebuilding" @click="rebuild">重建关联</el-button>
          </template>
          <template v-else>
            <span class="hint">
              {{ shownConceptNodes.length }}/{{ concept.nodes?.length || 0 }} 个概念 · {{ shownConceptEdges.length }} 条三元组
              <template v-if="concept.stat">（推导 {{ concept.stat.derived }}）</template>
            </span>
            <el-select
              v-model="relationFilter"
              multiple
              collapse-tags
              collapse-tags-tooltip
              size="small"
              placeholder="按关系过滤"
              style="width: 190px"
            >
              <el-option
                v-for="r in (concept.ontology || [])"
                :key="r.id"
                :label="r.label"
                :value="r.id"
              />
            </el-select>
            <el-button v-if="focus" size="small" @click="focus = null">显示全部</el-button>
            <el-button size="small" :loading="kgBusy" @click="reasonConcept">规则推理</el-button>
            <el-button size="small" type="primary" :loading="kgBuilding" @click="buildConcept">重建概念图谱</el-button>
          </template>
        </div>

        <!-- 构建进度：抽三元组要花 token，必须让人看得见它在干什么 -->
        <div v-if="layer === 'concept' && kgBuilding && kgJob" class="kb-progress">
          <el-progress :percentage="kgJob.percent || 0" :stroke-width="6" :show-text="false" />
          <div class="hint">{{ kgJob.stage }} · {{ kgJob.detail }}</div>
        </div>

        <!-- 选中节点的详情栏：放在画布**上面**。
             以前在画布下面：点节点后要往下找才能看到"我点的是什么"，而且它一出现就把画布往上顶，
             画布高度是视口算出来的、不由容器决定，于是底部空出一大块（实测空 107px）。 -->
        <div v-if="selectedNode" ref="detailRef" class="kg-detail">
          <div class="kg-detail-main">
            <span class="kg-kind">{{ nodeTypeLabel(selectedNode.type) }}</span>
            <b class="kg-name">{{ selectedNode.label }}</b>
            <span class="hint">{{ selectedNode.degree || 0 }} 条关联<template v-if="selectedNode.updatedAt"> · 更新于 {{ selectedNode.updatedAt }}</template></span>
            <span v-if="layer === 'concept' && selectedNode.aliases?.length" class="hint">
              别名：{{ selectedNode.aliases.join('、') }}
            </span>
          </div>
          <div class="kg-detail-acts">
            <template v-if="layer === 'doc'">
              <el-button v-if="selectedNode.type === 'note'" size="small" @click="openNode(selectedNode)">打开笔记</el-button>
              <el-button v-if="selectedNode.type === 'file'" size="small" @click="openNode(selectedNode)">下载资料</el-button>
              <el-button
                v-if="selectedNode.type === 'note' || selectedNode.type === 'ref' || selectedNode.type === 'file'"
                size="small"
                type="primary"
                @click="askAgent(selectedNode.type, parseNodeId(selectedNode.id).id, selectedNode.label)"
              >问智能体</el-button>
            </template>
            <template v-else>
              <el-button v-if="selectedNode.wikiKey" size="small" @click="openConceptWiki(selectedNode.wikiKey)">读概念页</el-button>
              <el-button size="small" @click="expandConcept(selectedNode.id, 2)">展开 2 跳</el-button>
              <el-button size="small" @click="askAgentConcept(selectedNode.label)">问智能体</el-button>
              <el-button
                size="small"
                :loading="deletingConcept === selectedNode.id"
                @click="removeConceptNode(selectedNode)"
              >删除概念</el-button>
            </template>
          </div>
          <p v-if="layer === 'concept' && selectedNode.brief" class="kg-brief">{{ selectedNode.brief }}</p>
          <ul v-if="layer === 'doc' && selectedLinks.length" class="kg-links">
            <li v-for="(l, i) in selectedLinks" :key="i">
              <span class="rel">{{ l.relation }}</span>
              <span class="rel-title">{{ l.title }}</span>
              <span class="rel-reason">{{ l.reason }}</span>
            </li>
          </ul>
          <ul v-else-if="layer === 'concept' && conceptRelList.length" class="kg-links">
            <li v-for="(t, i) in conceptRelList" :key="i">
              <span class="rel" :class="{ 'rel-derived': t.origin === 'derived' }">{{ t.label }}</span>
              <span class="rel-title">{{ t.out ? '→ ' + t.other : '← ' + t.other }}</span>
              <span class="rel-reason">{{ t.origin === 'derived' ? '按本体规则推导（不是直接写着的事实）' : (t.evidence || '') }}</span>
            </li>
          </ul>
        </div>

        <!-- 画布：吃掉剩余高度，下边界始终贴着页面底部 -->
        <div class="kg-box" v-loading="layer === 'doc' ? graphLoading : conceptLoading">
          <KnowledgeGraph
            v-if="canvasNodes.length"
            :nodes="canvasNodes"
            :edges="canvasEdges"
            :active-id="selectedNode?.id || ''"
            @select="onSelectNode"
          />
          <el-empty
            v-else
            :description="layer === 'doc'
              ? '还没有内容，先写几篇笔记'
              : '概念图谱还是空的 —— 点「重建概念图谱」从你的笔记与资料里抽三元组'"
          />
        </div>
      </div>
    </template>

    <!-- ============ LLM Wiki ============ -->
    <template v-else-if="tab === 'wiki'">
      <!-- 工具条：以前三个按钮 + 自动开关全挤在侧栏标题行里，挤成一团；现在单独占一行 -->
      <div class="wiki-bar">
        <span class="wiki-bar-title">知识页</span>
        <span class="hint">
          共 {{ topics.length }} 个主题，已生成 {{ generatedTopicCount }} 页 · 删除只清这一页，原始笔记/速查卡/资料一行不动
        </span>
        <span class="wiki-bar-gap" />
        <el-button size="small" :loading="entBusy" @click="compileEntities">编译知识页</el-button>
        <el-button size="small" :loading="reBusy" @click="recompileAffected">重建受影响页</el-button>
        <el-button size="small" :loading="lintBusy" @click="runLint">自检</el-button>
        <span class="wiki-bar-sep" />
        <span class="hint">改完笔记自动重生成</span>
        <el-switch v-model="autoRefresh" size="small" @change="toggleAutoRefresh" />
      </div>

      <div class="wiki">
        <aside class="wiki-side" v-loading="topicsLoading">
          <div v-for="g in topicGroups" :key="g.type" class="wiki-group">
            <div class="wiki-group-head">
              <span class="topic-dot" :class="'dot-' + g.type" />
              <span class="wiki-group-name">{{ g.label }}</span>
              <span class="wiki-group-count">{{ g.items.length }}</span>
            </div>
            <div
              v-for="t in g.items"
              :key="t.topicKey"
              class="topic"
              :class="{ on: t.topicKey === activeTopic }"
              @click="openTopic(t.topicKey)"
            >
              <span class="topic-name" :title="t.title + ' · ' + t.topicKey">{{ t.title }}</span>
              <span class="topic-status">
                <span v-if="t.stale" class="badge badge-stale">待更新</span>
                <span v-else-if="!t.generated" class="topic-nogene">未生成</span>
              </span>
              <span class="topic-count">{{ t.itemCount }}</span>
              <button
                class="topic-del"
                type="button"
                :title="'删除「' + t.title + '」这一页'"
                @click.stop="removeTopic(t)"
              >✕</button>
            </div>
          </div>
          <p v-if="!topics.length && !topicsLoading" class="hint side-hint">还没有分类或标签，先去写笔记</p>
        </aside>

        <section class="wiki-main" v-loading="wikiLoading">
          <div v-if="reBusy && reJob" class="kb-progress">
            <el-progress :percentage="reJob.percent || 0" :stroke-width="6" :show-text="false" />
            <div class="hint">{{ reJob.stage }} · {{ reJob.detail }}</div>
          </div>
          <div v-if="entBusy && entJob" class="kb-progress">
            <el-progress :percentage="entJob.percent || 0" :stroke-width="6" :show-text="false" />
            <div class="hint">
              {{ entJob.stage }} · {{ entJob.done }}/{{ entJob.total }} · 已写 {{ entJob.pages }} 页 · {{ entJob.chars }} 字
              <template v-if="entJob.detail"> · {{ entJob.detail }}</template>
            </div>
          </div>
          <template v-if="wikiPage">
            <!-- 页头分三区：标题+类型 / 元信息一行 / 右侧动作。以前全塞在一行 flex 里会挤到换行 -->
            <header class="page-head">
              <div class="page-head-main">
                <div class="page-title-row">
                  <h3 class="page-title">{{ wikiPage.title }}</h3>
                  <span class="type-tag" :class="'tag-' + (wikiPage.topicType || '')">
                    {{ typeLabel(wikiPage.topicType) }}
                  </span>
                  <span v-if="wikiPage.stale" class="badge badge-stale">内容已变，待更新</span>
                </div>
                <div class="page-meta">
                  <span class="meta-chip">{{ wikiCountLabel }}</span>
                  <template v-if="!isCompiledPage && wikiPage.sentItems < wikiPage.itemCount">
                    <span class="meta-chip">送模型 {{ wikiPage.sentItems }} 条</span>
                  </template>
                  <span v-if="wikiPage.generatedAt" class="meta-chip">生成于 {{ wikiPage.generatedAt }}</span>
                  <span v-if="wikiPage.model" class="meta-chip">{{ wikiPage.model }}</span>
                  <el-tooltip
                    v-if="wikiPage.quality"
                    :content="wikiPage.qualityNote || '已通过质量校验（引用有效、结构完整）'"
                    placement="top"
                  >
                    <span class="badge" :class="wikiPage.quality === 'ok' ? 'badge-ok' : 'badge-warn'">
                      {{ wikiPage.quality === 'ok' ? '已校验' : '校验有提示' }}
                    </span>
                  </el-tooltip>
                  <!-- 素材覆盖率：直接回答"这份 wiki 到底覆盖了源文档多少"。
                       低于 60% 时标出来并悬浮展示逐条明细（实测大文档/长笔记只有个位数百分点）。 -->
                  <el-tooltip
                    v-if="wikiPage.coveragePercent != null"
                    placement="top"
                    :content="'本页素材实际用了 ' + (wikiPage.materialChars || 0) + ' 字，源文档合计约 ' + (wikiPage.sourceChars || 0) + ' 字'"
                  >
                    <span
                      class="badge"
                      :class="wikiPage.coveragePercent < 60 ? 'badge-warn' : 'badge-ok'"
                    >素材覆盖 {{ wikiPage.coveragePercent }}%</span>
                  </el-tooltip>
                  <el-popover v-if="wikiPage.coverage?.length" placement="bottom-end" :width="360" trigger="click">
                    <template #reference>
                      <span class="cov-more">明细</span>
                    </template>
                    <div class="cov-list">
                      <div v-for="c in wikiPage.coverage" :key="c.type + '-' + c.id" class="cov-row">
                        <span class="cov-name">{{ c.title }}</span>
                        <span class="cov-num">
                          {{ c.usedChars }} / {{ c.sourceChars || '?' }} 字<template v-if="c.percent != null">（{{ c.percent }}%）</template>
                        </span>
                      </div>
                      <p class="hint cov-hint">「用/源」= 这次生成实际送进模型多少字 / 该条源文档共多少字。</p>
                    </div>
                  </el-popover>
                </div>
              </div>
              <div class="page-acts">
                <el-select
                  v-if="wikiTargets.length > 1"
                  v-model="wikiTarget"
                  size="small"
                  style="width: 190px"
                  :disabled="generating"
                >
                  <el-option v-for="t in wikiTargets" :key="t.id" :label="t.label" :value="t.id" />
                </el-select>
                <el-button
                  size="small"
                  type="primary"
                  :loading="generating"
                  :disabled="!wikiPage.itemCount"
                  @click="generate"
                >{{ wikiPage.generated ? '重新生成' : '生成 wiki' }}</el-button>
                <el-button
                  size="small"
                  :loading="deletingKey === wikiPage.topicKey"
                  @click="removeTopic(wikiPage)"
                >删除</el-button>
              </div>
            </header>

            <!-- 生成进度：阶段 + 百分比 + 已生成字数（本地小模型时这段等待很关键） -->
            <div v-if="generating" class="wiki-progress">
              <el-progress :percentage="job?.percent || 0" :stroke-width="6" :show-text="false" />
              <div class="wiki-progress-text">
                <b>{{ job?.stage || '准备中' }}</b>
                <span class="hint">
                  {{ job?.model || '' }}
                  <template v-if="job?.chars"> · 已生成 {{ job.chars }} 字</template>
                  <template v-if="job?.elapsedMs"> · {{ Math.round(job.elapsedMs / 1000) }}s</template>
                </span>
              </div>
            </div>

            <div v-if="wikiPage.contentMd" class="wiki-body md-doc" @click="onWikiClick">
              <MdPreview :modelValue="wikiMd" :theme="isDark ? 'dark' : 'light'" previewTheme="github" />
            </div>
            <div v-else class="wiki-empty">
              <p>这个主题还没有 wiki 页。</p>
              <p class="hint">
                点「生成 wiki」让模型把该主题下的 {{ wikiPage.itemCount }} 条笔记/速查卡整理成一页结构化长文；
                生成一次要几秒到几十秒，结果会缓存下来，之后只有内容变了才需要重新生成。
              </p>
            </div>
          </template>
          <el-empty v-else description="选择左侧一个主题" />
        </section>
      </div>
    </template>

    <!-- ============ 检索（原样保留） ============ -->
    <template v-else>
      <!-- 语义索引状态：资料能不能"问什么都知道"取决于索引新不新 -->
      <div class="kb-bar">
        <span class="kb-title">语义索引</span>
        <template v-if="kb">
          <span class="hint">
            {{ kb.chunks }} 块 · {{ kb.indexedChars }} 字 · {{ kb.model }}
            <template v-if="kb.stale"> · <b class="kb-stale">已过期，建议重建</b></template>
          </span>
          <el-button size="small" :loading="kbBusy" @click="rebuildIndex">重建索引</el-button>
          <span class="hint">当前来源 {{ kb.currentSources }} 个</span>
        </template>
        <span v-else class="hint">索引状态不可用（检查嵌入服务 {{ kb?.baseUrl || 'Ollama' }} 是否在跑）</span>
      </div>

      <!-- 重建进度 -->
      <div v-if="kbBusy && kbJob" class="kb-progress">
        <el-progress :percentage="kbJob.percent || 0" :stroke-width="6" :show-text="false" />
        <div class="hint">
          {{ kbJob.stage }} · 来源 {{ kbJob.done }}/{{ kbJob.total }} · {{ kbJob.chunks }} 块 · {{ kbJob.chars }} 字
        </div>
      </div>

      <!-- 检索体检：同一问题，词面 vs 语义 -->
      <div class="probe-bar">
        <el-input
          v-model="probeQ"
          class="search-input"
          placeholder="体检用：写一个「换一种说法」的问句，例如 撤销暂存区的改动"
          clearable
          @keyup.enter="runProbe"
        />
        <el-button :loading="probeBusy" @click="runProbe">检索体检</el-button>
        <el-button v-if="probeResult" @click="probeResult = null">关闭对比</el-button>
      </div>
      <div v-if="probeResult" class="probe-result">
        <div class="probe-col">
          <h4>词面检索（{{ probeResult.keywordCount }} 条）</h4>
          <p v-if="!probeResult.keywordCount" class="hint">0 条 —— 用词与资料不一致时就是这样</p>
          <div v-for="it in probeResult.keyword" :key="'k' + it.type + it.id" class="probe-item">
            {{ it.type }}#{{ it.id }} 《{{ it.title }}》
          </div>
        </div>
        <div class="probe-col">
          <h4>语义检索（{{ probeResult.vectorCount }} 条）</h4>
          <p v-if="!probeResult.vectorCount" class="hint">0 条 —— 与库里的内容都不相关</p>
          <div v-for="it in probeResult.vector" :key="'v' + it.type + it.id" class="probe-item">
            <b>{{ it.score }}</b> {{ it.type }}#{{ it.id }} 《{{ it.title }}》
          </div>
        </div>
      </div>

      <div class="search-card" v-loading="loading">
        <el-input
          v-model="kw"
          class="search-input"
          :placeholder="searchMode === 'fusion'
            ? '用一句话描述你要找什么，例如 大量字符串拼接用哪个类性能更好（留空看最近知识）'
            : '搜字符串，例如 StringBuilder（留空看最近知识）'"
          clearable
          @keyup.enter="doSearch"
          @clear="clearSearch"
        >
          <template #suffix>
            <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
              <circle cx="11" cy="11" r="7" />
              <path d="m20 20-3.8-3.8" />
            </svg>
          </template>
        </el-input>
        <el-button type="primary" @click="doSearch">检索</el-button>
        <!-- 融合 / 词面 切换：默认融合（语义能找回词面 0 命中的内容）；要精确搜字符串时切词面 -->
        <div class="mode" role="group" aria-label="检索方式">
          <button type="button" :class="{ on: searchMode === 'fusion' }" @click="switchMode('fusion')">融合</button>
          <button type="button" :class="{ on: searchMode === 'keyword' }" @click="switchMode('keyword')">词面</button>
        </div>
      </div>

      <!-- 状态必须可见：现在用的是哪种方式、上面这批结果是谁出的 -->
      <div class="search-status">
        <span class="hint">当前：<b>{{ modeLabel(searchMode) }}</b> · {{ modeDesc(searchMode) }}</span>
        <span v-if="searched && !loading" class="hint">
          本次结果来自「{{ modeLabel(lastMode) }}」，共 {{ items.length }} 条<template v-if="lastMode === 'fusion' && items.length"> · 按相关度从高到低</template>
        </span>
        <span v-if="emptyFallback" class="hint">输入为空：融合检索需要一句话，已回落到词面的「最近知识」</span>
      </div>

      <div v-if="searchError" class="search-error">
        <b>{{ modeLabel(lastMode) }}失败</b>
        <span class="hint">
          {{ searchError }} —— 可切到「{{ searchMode === 'fusion' ? '词面' : '融合' }}」，或点「检索」重试
        </span>
      </div>

      <template v-if="resultGroups.length">
        <section v-for="g in resultGroups" :key="g.key" class="group">
          <h3 class="group-title">{{ g.label }}<span class="count">{{ g.items.length }}</span></h3>
          <div
            v-for="it in g.items"
            :key="g.key + '-' + it.type + '-' + it.id"
            class="kitem"
            role="button"
            tabindex="0"
            @click="open(it)"
            @keydown.enter.prevent="open(it)"
            @keydown.space.prevent="open(it)"
          >
            <div class="kitem-main">
              <div class="kitem-title">
                <!-- 融合是一张混合列表，来源类型必须每条都标出来 -->
                <span v-if="lastMode === 'fusion'" class="ktag ktag-type" :class="'kt-' + it.type">{{ sourceLabel(it.type) }}</span>
                <span v-html="hl(it.title)"></span>
              </div>
              <div class="kitem-snippet" v-html="hl(it.snippet)"></div>
            </div>
            <div class="kitem-meta">
              <span v-if="it.categoryName" class="ktag">{{ it.categoryName }}</span>
              <span v-if="it.type === 'file' && it.ext" class="ktag">{{ it.ext }}<template v-if="it.textChars"> · {{ it.textChars }} 字</template></span>
              <span v-if="it.score != null" class="ktag" title="融合得分：词面与向量两路合并后的相关度">相关度 {{ it.score.toFixed(3) }}</span>
              <span v-if="it.updatedAt" class="ktime">{{ it.updatedAt }}</span>
              <span v-if="it.type === 'file'" class="klink" title="下载到本机打开" @click.stop="downloadFile(it.id, it.title)">下载</span>
            </div>
          </div>
        </section>
      </template>

      <el-empty
        v-else-if="searched && !loading && !searchError"
        :description="keyword ? `没有与「${keyword}」相关的知识` : '工作台还是空的，先写一篇笔记吧'"
      />
    </template>
  </div>
</template>

<style scoped>
/* ---- 顶栏与视图切换（沿用编辑页的分段按钮，避免 EP 组件高度不一致） ---- */
.head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}
.head-sub {
  margin: 5px 0 0;
  font-size: 13px;
  color: var(--app-text-3);
}
.tabs {
  display: flex;
  gap: 2px;
  padding: 3px;
  background: var(--app-bg);
  border: 1px solid var(--app-border-weak);
  border-radius: var(--radius);
  flex-shrink: 0;
}
.tabs button {
  border: 0;
  background: transparent;
  color: var(--app-text-2);
  font-size: 13px;
  padding: 5px 12px;
  border-radius: 6px;
  cursor: pointer;
  transition: all var(--dur-fast) ease;
}
.tabs button:hover {
  color: var(--app-text-1);
}
.tabs button.on {
  background: var(--app-card);
  color: var(--app-brand-deep);
  font-weight: 600;
  box-shadow: var(--shadow-sm);
}

/* ---- 图谱 ---- */
.bar {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
/* 层切换：文档层 / 概念层 是两个不同的东西，用分段控件而不是普通按钮，
   让"我现在看的是哪一层"一眼可见 */
.layer {
  display: inline-flex;
  padding: 2px;
  border-radius: 8px;
  background: var(--app-bg);
  border: 1px solid var(--app-border-weak);
}
.layer button {
  border: 0;
  background: transparent;
  padding: 3px 10px;
  border-radius: 6px;
  font-size: 12px;
  color: var(--app-text-2);
  cursor: pointer;
  transition: all var(--dur-fast) ease;
}
.layer button:hover {
  color: var(--app-text-1);
}
.layer button.on {
  background: var(--app-card);
  color: var(--app-brand-deep);
  font-weight: 600;
  box-shadow: 0 1px 2px color-mix(in srgb, var(--app-text-1) 10%, transparent);
}
/* 推导边的图例：与画布上的虚线对应 */
.line-derived {
  border-top-style: dashed;
}
/* 概念说明（详情栏里的一行） */
.kg-brief {
  grid-column: 1 / -1;
  margin: 0;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--app-text-2);
}
/* 推导关系：加一个"推导"标记，别让人把推断当成原文 */
.rel-derived {
  color: var(--app-text-3) !important;
  background: transparent !important;
  border: 1px dashed var(--app-border);
}
.bar .spacer {
  flex: 1;
}
.legend {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--app-text-3);
}
.dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  display: inline-block;
}
.dot-cat {
  background: var(--app-brand);
}
.dot-note {
  background: color-mix(in srgb, var(--app-brand) 62%, var(--app-text-1));
}
.dot-ref {
  background: color-mix(in srgb, var(--app-brand) 34%, var(--app-text-2));
}
/* 资料：比速查卡更深的青灰，和笔记/速查卡能一眼区分 */
.dot-file {
  background: color-mix(in srgb, var(--app-brand-deep) 72%, var(--app-text-2));
}
.dot-tag {
  background: var(--app-brand-soft);
  box-shadow: inset 0 0 0 1px var(--app-brand);
}
.line {
  width: 16px;
  height: 0;
  border-top: 1.4px solid var(--app-border);
  display: inline-block;
}
.line-sem {
  border-top: 1.6px dashed color-mix(in srgb, var(--app-brand) 60%, transparent);
}
.hint {
  font-size: 12px;
  color: var(--app-text-3);
}
.kg-box {
  /* 画布高度改为**由容器决定**：图谱 tab 是一列 flex，画布 flex:1 吃掉剩余高度，
     下边界因此总是贴着页面底部。原来用 clamp(320px, calc(100vh - 236px), 720px) 按视口算，
     实测 802px 视口只给到 566px，画布下方空出 107px —— 就是"边界没到底"的来源；
     而且那个值是死的，详情栏出现/消失时不会自适应。 */
  flex: 1 1 320px;
  min-height: 320px;
  height: auto;
  border: 1px solid var(--app-border-weak);
  border-radius: var(--radius);
  background: var(--app-bg);
  overflow: hidden;
}
/* 图谱整列撑满内容区，画布再吃掉剩余。
   用 flex 而不是 calc(100vh - 某个数)：减数要等于"页面上下 padding + 标题 + 标题下边距"，
   而这些值会随字号/换行变。实测按 100vh-48 算时画布反而溢出 45px（把标题那块 69px 漏掉了），
   整列就会滚动。让 .page 自己撑满视口、图谱区 flex:1，就不存在要维护的魔法数字。 */
.page:has(.graph-fill) {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}
.graph-fill {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-height: 340px;
}
.kg-detail {
  /* 详情栏已移到画布上方，所以只留"与画布之间"的下间距 */
  margin: 0 0 12px;
  padding: 12px 14px;
  background: var(--app-card);
  border: 1px solid var(--app-border-weak);
  border-radius: var(--radius);
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 8px 16px;
}
.kg-detail-main {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.kg-kind {
  font-size: 11.5px;
  color: var(--app-brand-deep);
  background: var(--app-brand-soft);
  border-radius: 5px;
  padding: 2px 7px;
  flex-shrink: 0;
}
.kg-name {
  font-size: 14px;
  color: var(--app-text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.kg-detail-acts {
  display: flex;
  gap: 8px;
  align-items: center;
}
.kg-links {
  grid-column: 1 / -1;
  list-style: none;
  margin: 4px 0 0;
  padding: 8px 0 0;
  border-top: 1px dashed var(--app-border);
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.kg-links li {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 12.5px;
}
.rel {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--app-text-3);
  border: 1px solid var(--app-border);
  border-radius: 4px;
  padding: 0 5px;
}
.rel-title {
  color: var(--app-text-1);
  font-weight: 600;
}
.rel-reason {
  color: var(--app-text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---- Wiki ---- */
.wiki {
  display: grid;
  /* 侧栏 210 → 236px：固定列对齐后名字列只剩 68px，"Spring Boot" 都会被截断；
     加宽后名字能完整显示（主区是 1fr，让出 26px 无感） */
  grid-template-columns: 236px 1fr;
  gap: 16px;
  align-items: start;
}
.wiki-side {
  border: 1px solid var(--app-border-weak);
  border-radius: var(--radius);
  background: var(--app-card);
  padding: 10px;
  position: sticky;
  top: 12px;
  /* 42 个条目比视口高，而 sticky 只把面板钉在顶部、不会让里面的内容可滚 ——
     结果就是"实体"组下半截永远点不到。给面板自己一个滚动条，全部条目都够得着。 */
  max-height: calc(100vh - 24px);
  overflow-y: auto;
}
/* 工具条：动作与"自动更新"各就各位，不再挤在窄侧栏里 */
.wiki-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  margin-bottom: 12px;
  border: 1px solid var(--app-border-weak);
  border-radius: var(--radius);
  background: var(--app-card);
}
.wiki-bar-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--app-text-1);
}
.wiki-bar-gap {
  flex: 1 1 auto;
}
.wiki-bar-sep {
  width: 1px;
  height: 18px;
  background: var(--app-border);
  margin: 0 2px;
}
/* 侧栏分组 */
.wiki-group + .wiki-group {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid var(--app-border-weak);
}
/* 分组表头和行共用同一套列宽，这样"分组数字"和"行数字"落在同一列上 */
.wiki-group-head {
  display: grid;
  grid-template-columns: 7px minmax(0, 1fr) 46px 18px 16px;
  align-items: center;
  gap: 5px;
  padding: 0 8px 5px;
  font-size: 11.5px;
  color: var(--app-text-3);
  letter-spacing: 0.02em;
}
.wiki-group-name {
  font-weight: 600;
}
.wiki-group-count {
  grid-column: 4;
  text-align: right;
  font-variant-numeric: tabular-nums;
  font-size: 11px;
}
/* 类型圆点：分类/标签/实体/索引/自检 各一色，一眼分清 */
.topic-dot {
  width: 7px;
  height: 7px;
  border-radius: 99px;
  flex-shrink: 0;
  background: var(--app-text-3);
}
.dot-category {
  background: #0e9f8e;
}
.dot-tag {
  background: #6366f1;
}
.dot-entity {
  background: #d97706;
}
.dot-index {
  background: #0891b2;
}
.dot-lint {
  background: #db2777;
}
.dot-other {
  background: var(--app-text-3);
}
.side-hint {
  padding: 4px;
}
/* 行用固定列网格：名字 | 状态 | 数字 | 删除。
   以前是 [名字][状态+数字] 两块两端对齐 —— 带"未生成"的行把数字顶右移，
   整列数字参差不齐（这就是"太乱"的来源）。固定列之后数字永远落在同一列。 */
.topic {
  position: relative;
  width: 100%;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 46px 18px 16px;
  align-items: center;
  gap: 5px;
  border: 0;
  background: transparent;
  text-align: left;
  font-size: 13px;
  color: var(--app-text-2);
  padding: 6px 8px;
  border-radius: 7px;
  cursor: pointer;
  transition: background var(--dur-fast) ease, color var(--dur-fast) ease;
}
.topic + .topic {
  margin-top: 1px;
}
.topic:hover {
  background: var(--app-bg);
  color: var(--app-text-1);
}
.topic.on {
  background: var(--app-brand-soft);
  color: var(--app-brand-deep);
  font-weight: 600;
}
/* 选中态的左侧竖条：给"当前在看哪一页"一个明确锚点 */
.topic.on::before {
  content: '';
  position: absolute;
  left: 0;
  top: 22%;
  height: 56%;
  width: 2.5px;
  border-radius: 0 2px 2px 0;
  background: var(--app-brand);
}
.topic-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.topic-status {
  text-align: right;
  overflow: hidden;
}
/* "未生成"用弱化纯文本而非描边徽标：一屏十几个徽标太吵，这层信息只需"扫一眼看得出来" */
.topic-nogene {
  font-size: 10.5px;
  color: var(--app-text-3);
  opacity: 0.72;
  white-space: nowrap;
}
/* 行内删除：固定占位（不挤压、不遮住计数），悬浮或选中时才显形 */
.topic-del {
  width: 18px;
  height: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--app-text-3);
  font-size: 11px;
  line-height: 1;
  cursor: pointer;
  visibility: hidden;
  transition: all var(--dur-fast) ease;
}
.topic:hover .topic-del,
.topic.on .topic-del {
  visibility: visible;
}
.topic-del:hover {
  background: color-mix(in srgb, #dc2626 14%, transparent);
  color: #dc2626;
}
.topic-count {
  font-size: 11.5px;
  color: var(--app-text-3);
  font-variant-numeric: tabular-nums;
  text-align: right;
}
.topic.on .topic-count {
  color: var(--app-brand-deep);
  opacity: 0.85;
}
.badge {
  font-size: 10.5px;
  border-radius: 99px;
  padding: 1px 6px;
  background: var(--app-bg);
  color: var(--app-text-3);
  border: 1px solid var(--app-border-weak);
  /* 侧栏的状态列是窄固定列，不禁止换行的话"待更新"会被折成两行 */
  white-space: nowrap;
}
.badge-stale {
  color: var(--app-brand-deep);
  border-color: color-mix(in srgb, var(--app-brand) 40%, transparent);
  background: var(--app-brand-soft);
}
/* 质量校验徽标：通过=绿系、有问题=橙系（都用令牌派生，深浅主题一致） */
.badge-ok {
  color: #15803d;
  border-color: color-mix(in srgb, #15803d 35%, transparent);
  background: color-mix(in srgb, #15803d 10%, transparent);
}
.badge-warn {
  color: #b45309;
  border-color: color-mix(in srgb, #b45309 35%, transparent);
  background: color-mix(in srgb, #b45309 10%, transparent);
}
/* 素材覆盖明细（点「明细」弹出） */
.cov-more {
  font-size: 11px;
  color: var(--app-brand-deep);
  cursor: pointer;
  border-bottom: 1px dashed color-mix(in srgb, var(--app-brand) 45%, transparent);
}
.cov-list {
  max-height: 300px;
  overflow: auto;
}
.cov-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 3px 0;
  font-size: 12px;
  border-bottom: 1px dashed var(--app-border-weak);
}
.cov-name {
  color: var(--app-text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cov-num {
  color: var(--app-text-3);
  flex-shrink: 0;
}
.cov-hint {
  margin: 8px 0 0;
}
/* 生成进度：本地小模型出一页 20~35 秒，这段反馈很关键 */
.wiki-progress {
  margin: 10px 0 4px;
}
.wiki-progress-text {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-top: 6px;
  font-size: 12px;
  color: var(--app-text-2);
}
.wiki-progress-text .hint {
  color: var(--app-text-3);
}
.wiki-main {
  border: 1px solid var(--app-border-weak);
  border-radius: var(--radius);
  background: var(--app-card);
  padding: 16px 18px;
  min-height: 320px;
}
/* 页头：左边"标题 + 类型 + 元信息"两行，右边动作整块垂直居中。
   以前是单个 flex 行，元信息长起来就把按钮挤到下一行（截图里就是那样）。 */
.page-head {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--app-border-weak);
  margin-bottom: 8px;
}
.page-head-main {
  min-width: 0;
}
.page-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.page-title {
  margin: 0;
  font-size: 17px;
  font-weight: 650;
  color: var(--app-text-1);
}
/* 类型标签：和侧栏圆点同色系，让"这页是什么"一眼可辨 */
.type-tag {
  font-size: 10.5px;
  line-height: 1.6;
  padding: 0 7px;
  border-radius: 99px;
  color: var(--app-text-3);
  border: 1px solid var(--app-border-weak);
  background: var(--app-bg);
}
.tag-category {
  color: #0b7f72;
  border-color: color-mix(in srgb, #0e9f8e 35%, transparent);
  background: color-mix(in srgb, #0e9f8e 10%, transparent);
}
.tag-tag {
  color: #4f46e5;
  border-color: color-mix(in srgb, #6366f1 35%, transparent);
  background: color-mix(in srgb, #6366f1 10%, transparent);
}
.tag-entity {
  color: #b45309;
  border-color: color-mix(in srgb, #d97706 35%, transparent);
  background: color-mix(in srgb, #d97706 10%, transparent);
}
.tag-index {
  color: #0e7490;
  border-color: color-mix(in srgb, #0891b2 35%, transparent);
  background: color-mix(in srgb, #0891b2 10%, transparent);
}
.tag-lint {
  color: #be185d;
  border-color: color-mix(in srgb, #db2777 35%, transparent);
  background: color-mix(in srgb, #db2777 10%, transparent);
}
.page-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 6px;
}
.meta-chip {
  font-size: 11.5px;
  color: var(--app-text-3);
  padding: 0 6px;
  line-height: 1.7;
  border-radius: 5px;
  background: var(--app-bg);
}
.page-acts {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.wiki-body {
  padding-top: 4px;
}
/* 来源引用（[笔记#3] / [速查卡#8] / [资料#2]）渲染成锚点后要看得出来能点：
   md-editor 默认不给 # 锚点加样式，实测看着跟纯文本一样，用户不知道能跳回原文核对 */
.wiki-body :deep(a[href^='#note-']),
.wiki-body :deep(a[href^='#ref-']),
.wiki-body :deep(a[href^='#file-']) {
  color: var(--app-brand-deep);
  background: var(--app-brand-soft);
  border-radius: 4px;
  padding: 0 4px;
  text-decoration: none;
  font-size: 0.92em;
  white-space: nowrap;
}
.wiki-body :deep(a[href^='#note-']:hover),
.wiki-body :deep(a[href^='#ref-']:hover),
.wiki-body :deep(a[href^='#file-']:hover) {
  text-decoration: underline;
}
.wiki-empty {
  padding: 26px 4px;
}
.wiki-empty p {
  margin: 0 0 8px;
  color: var(--app-text-2);
  font-size: 13.5px;
  line-height: 1.75;
}

/* ---- 检索（原有样式） ---- */
/* 语义索引状态与体检 */
.kb-bar,
.probe-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.kb-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--app-text-1);
}
.kb-stale {
  color: #b45309;
}
.kb-progress {
  margin-bottom: 10px;
}
.probe-result {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  padding: 12px 14px;
  margin-bottom: 12px;
  border: 1px solid var(--app-border-weak);
  border-radius: var(--radius);
  background: var(--app-card);
}
.probe-col h4 {
  margin: 0 0 6px;
  font-size: 12.5px;
  color: var(--app-text-2);
}
.probe-item {
  font-size: 12px;
  color: var(--app-text-1);
  padding: 2px 0;
  border-bottom: 1px dashed var(--app-border-weak);
}
.search-card {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}
/* 融合 / 词面 切换：沿用图谱"层切换"的分段控件观感，让当前方式一眼可见 */
.mode {
  display: inline-flex;
  padding: 2px;
  border-radius: 8px;
  background: var(--app-bg);
  border: 1px solid var(--app-border-weak);
  flex-shrink: 0;
}
.mode button {
  border: 0;
  background: transparent;
  padding: 3px 10px;
  border-radius: 6px;
  font-size: 12px;
  color: var(--app-text-2);
  cursor: pointer;
  transition: all var(--dur-fast) ease;
}
.mode button:hover {
  color: var(--app-text-1);
}
.mode button.on {
  background: var(--app-card);
  color: var(--app-brand-deep);
  font-weight: 600;
  box-shadow: 0 1px 2px color-mix(in srgb, var(--app-text-1) 10%, transparent);
}
/* 检索方式状态行：说明"现在用的是哪种、上面的结果是谁出的" */
.search-status {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  margin: 0 0 20px;
}
.search-status b {
  color: var(--app-text-2);
}
/* 出错时不静默：列表位置留一条可见说明（同时还会弹 ElMessage） */
.search-error {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
  padding: 10px 14px;
  margin-bottom: 18px;
  border: 1px solid color-mix(in srgb, #b45309 38%, transparent);
  background: color-mix(in srgb, #b45309 7%, transparent);
  border-radius: var(--radius);
  font-size: 12.5px;
  color: var(--app-text-1);
}
/* 行尾的次要动作（资料下载） */
.klink {
  font-size: 12px;
  color: var(--app-brand-deep);
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 2px;
}
.search-input {
  flex: 1;
  /* 与同一行的「检索」按钮同高。EP 给 input 的高度取 --el-component-size，
     而按需引入后 EP 组件 CSS 在 style.css 之后注入、会把它设回 40px，
     于是输入框 40px、旁边的按钮 32px，一行里错开一截（实测 40 vs 32）。
     详见 style.css 中 .toolbar .el-input 的同款说明。 */
  --el-component-size: var(--control-h-inline);
}
.search-input :deep(.el-input__wrapper) {
  border-radius: var(--radius);
  box-shadow: 0 0 0 1px var(--app-border) inset;
}
.search-input :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1.5px var(--el-color-primary) inset;
}

.group {
  margin-bottom: 26px;
}
.group-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--app-text-3);
  margin: 0 0 10px 2px;
}
.group-title .count {
  font-weight: 500;
  font-size: 11px;
  color: var(--app-text-3);
  background: var(--app-brand-soft);
  border-radius: 99px;
  padding: 1px 8px;
}

.kitem {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 14px 16px;
  background: var(--app-card);
  border: 1px solid var(--app-border-weak);
  border-radius: var(--radius);
  cursor: pointer;
  transition:
    border-color var(--dur-fast) var(--ease),
    transform var(--dur-fast) var(--ease),
    box-shadow var(--dur-fast) var(--ease);
}
.kitem + .kitem {
  margin-top: 8px;
}
.kitem:hover {
  border-color: color-mix(in srgb, var(--app-brand) 22%, var(--app-border));
  transform: translateY(-1px);
  box-shadow: var(--shadow-sm);
}
.kitem:focus-visible {
  outline: 2px solid color-mix(in srgb, var(--app-brand) 55%, transparent);
  outline-offset: 2px;
}
.kitem:active {
  transform: scale(0.995);
}

.kitem-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--app-text-1);
  letter-spacing: -0.01em;
}
/* 来源类型标签（融合列表每条都要有）：与图谱图例同一套配色，笔记/速查卡/资料一眼分开。
   .ktag.ktag-type 提高一级特异性：.ktag 的 padding/font-size 在样式表更靠后，不然会被压回去 */
.ktag.ktag-type {
  display: inline-flex;
  align-items: center;
  margin-right: 8px;
  vertical-align: 1px;
  font-size: 11px;
  font-weight: 500;
  padding: 1px 7px;
}
.kt-note {
  color: var(--app-brand-deep);
  background: var(--app-brand-soft);
  border-color: color-mix(in srgb, var(--app-brand) 30%, transparent);
}
.kt-quick_ref {
  color: var(--app-text-1);
  background: color-mix(in srgb, var(--app-brand) 13%, transparent);
  border-color: transparent;
}
.kt-file {
  color: var(--app-text-2);
  background: var(--app-bg);
  border-color: var(--app-border-weak);
}
.kitem-snippet {
  margin-top: 4px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--app-text-2);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.kitem :deep(mark) {
  background: color-mix(in srgb, var(--app-brand) 16%, transparent);
  color: var(--app-brand-deep);
  border-radius: 3px;
  padding: 0 1px;
}

.kitem-meta {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 10px;
}
.ktag {
  font-size: 12px;
  color: var(--app-text-2);
  background: var(--app-bg);
  border: 1px solid var(--app-border-weak);
  border-radius: 6px;
  padding: 2px 8px;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ktime {
  font-size: 12px;
  color: var(--app-text-3);
  font-variant-numeric: tabular-nums;
}

@media (max-width: 1000px) {
  .wiki {
    grid-template-columns: 1fr;
  }
  .wiki-side {
    position: static;
  }
}

html.dark .kitem :deep(mark) {
  color: var(--app-brand);
}
</style>
