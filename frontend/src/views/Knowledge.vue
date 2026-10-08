<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MdPreview } from 'md-editor-v3'
import '../utils/mdEditorSetup'
import { fileApi, kbApi, knowledgeApi, kgApi, saveBlob, wikiApi } from '../api'
import { fixHtmlQuotes } from '../utils/htmlQuotes'
import { isDark } from '../composables/useTheme'
import KnowledgeGraph from '../components/KnowledgeGraph.vue'
import KnowledgeSearchPanel from '../components/KnowledgeSearchPanel.vue'
import WikiTopicNavigator from '../components/WikiTopicNavigator.vue'
import { Search, Share, Reading, Setting, ArrowRight } from '@element-plus/icons-vue'
import { knowledgeWorkspaceQuery, knowledgeWorkspaceTab } from '../utils/knowledgeWorkspace'

import { expandGraphIds, filterConceptGraph, findGraphNodes, graphSource } from '../utils/knowledgeGraphView'
import { retrievalSourcePath } from '../utils/retrievalDisplay'
import { createKnowledgeSearchLoader } from '../utils/knowledgeSearch'
import { findWikiHeading, wikiHeadingId, wikiRouteTarget } from '../utils/wikiNavigation'
import { createWikiDependencyLoader, wikiDependencyView } from '../utils/wikiDependencyDisplay'

/**
 * 知识库：三个视图
 * <ul>
 *   <li><b>知识图谱</b>：结构关系（分类/标签）实时现算 + 模型推断的语义关联；按版本号轮询，变了才重画</li>
 *   <li><b>LLM Wiki</b>：按主题（分类/标签）由模型整理成结构化长文，落库缓存，素材变了自动增量重生成</li>
 *   <li><b>检索</b>：默认走**融合检索**（关键词 + 语义 + 图谱，跨笔记/速查卡/资料），可切回词面精确匹配</li>
 * </ul>
 */
const router = useRouter()
const route = useRoute()

const tab = ref(knowledgeWorkspaceTab(route.query))
const maintenanceOpen = ref(false)
const workspaceButtons = ref([])
const workspaces = [
  { key: 'search', title: '检索知识', description: '从问题找到原文', icon: Search },
  { key: 'graph', title: '探索图谱', description: 'GraphRAG · 概念与关系', icon: Share },
  { key: 'wiki', title: '阅读 Wiki', description: '按主题整理知识', icon: Reading },
]

function chooseTab(next, topic = '') {
  tab.value = next
  const query = knowledgeWorkspaceQuery(route.query, next, topic || (next === 'wiki' ? activeTopic.value : ''))
  if (topic && router.resolve({ query }).fullPath === route.fullPath) openTopic(topic)
  else router.replace({ query })
}

function moveWorkspaceTab(event, index) {
  const keys = ['ArrowLeft', 'ArrowRight', 'Home', 'End']
  if (!keys.includes(event.key)) return
  event.preventDefault()
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? 2 : (index + (event.key === 'ArrowRight' ? 1 : -1) + 3) % 3
  chooseTab(workspaces[next].key)
  nextTick(() => workspaceButtons.value[next]?.focus())
}

function selectWikiTopic(key) {
  const query = knowledgeWorkspaceQuery(route.query, 'wiki', key)
  if (router.resolve({ query }).fullPath === route.fullPath) openTopic(key)
  else router.replace({ query })
}


// ------------------------------------------------------------------
// 一、检索：默认「融合检索」（关键词 + 语义 + 图谱，后端 /api/kb/search），可切回「词面检索」
//
// 为什么默认融合：词面走的是 SQL LIKE，要求你用的词和资料里的字面完全一致。
// 实测搜「大量字符串拼接用哪个类性能更好」词面 0 命中，而融合检索能找回
// 《String / StringBuilder / StringBuffer 区别》速查卡 —— 语义能力不该只服务智能体和"检索体检"。
// 但精确找一个类名/关键字时词面更利落（且不依赖向量索引是否重建过），所以保留为可切换项。
// ------------------------------------------------------------------
const MODE_KEY = 'lh-kb-search-mode'
/** 检索方式：fusion=融合（默认，关键词+语义+图谱） / keyword=词面（精确匹配） */
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

const modeLabel = (m) => (m === 'fusion' ? '融合检索' : '词面检索')

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

const searchLoader = createKnowledgeSearchLoader({
  loadFusion: (q, topK) => kbApi.search(q, topK, { silentError: true }),
  loadKeyword: q => knowledgeApi.search(q, { silentError: true }),
  onState(state) {
    loading.value = state.loading
    searched.value = state.searched
    searchError.value = state.error
    if ('items' in state) items.value = state.items
    if ('keyword' in state) keyword.value = state.keyword
    if ('lastMode' in state) lastMode.value = state.lastMode
  },
  onError(message, mode) {
    ElMessage.error(`${modeLabel(mode)}失败：${message}` + (mode === 'fusion' ? ' —— 可切到「词面」再试' : ''))
  },
})

function doSearch() {
  return searchLoader.search({ query: kw.value, mode: searchMode.value })
}

/** 打开检索命中的具体笔记、速查卡或资料，便于核对证据。 */
function open(item) {
  const path = retrievalSourcePath(item)
  if (!path) return
  if (router.resolve(path).fullPath === route.fullPath && item.type === 'wiki') {
    openWikiFromReference({ detail: item })
  } else router.push(path)
}

function clearSearch() {
  kw.value = ''
  doSearch()
}

// ------------------------------------------------------------------
// 一.5 语义索引状态与体检（"资料能不能问什么都知道"的可验证依据）
// ------------------------------------------------------------------
const kb = ref(null)
const kbLoading = ref(true)
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
    maintenanceOpen.value = false
    chooseTab('wiki', 'lint')
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    lintBusy.value = false
  }
}

async function loadKb() {
  kbLoading.value = true
  try {
    kb.value = await kbApi.status()
  } catch (e) {
    kb.value = null
  } finally {
    kbLoading.value = false
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
const rebuilding = ref(false)
const kgVersion = ref('')
const semanticEdges = ref(0)

// 概念层（知识图谱）：与文档层是两套数据、两个选中态，切层时互不干扰
const layer = ref('concept')
const concept = ref({ nodes: [], edges: [], ontology: [], stat: null, rule: null })
const conceptLoading = ref(false)
const conceptSelected = ref(null)
const relationFilter = ref(null)
const originFilter = ref('direct')
const communityFilter = ref('')
const showIsolated = ref(false)
const graphQuery = ref('')
const graphRef = ref(null)
const inspectorTab = ref('explore')
const graphProbeQuestion = ref('')
const graphProbeMode = ref('auto')
const graphProbeBusy = ref(false)
const graphProbeResult = ref(null)
const graphProbeError = ref('')
const summaryStatus = ref(null)
const summaryBusy = ref(false)
/** 聚焦集合：展开 N 跳后只显示这一片；null = 显示全图 */
const focus = ref(null)
// Reading another node's details must not move the neighborhood's layout center.
const focusRootId = ref('')
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

/** 选中节点时在独立滚动的侧栏显示证据，不改变画布高度。 */
function onSelectNode(n) {
  if (layer.value === 'concept') {
    conceptSelected.value = n
  } else {
    selected.value = n
  }
  if (n) inspectorTab.value = 'explore'
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
const visibleConceptGraph = computed(() => filterConceptGraph(concept.value.nodes || [], concept.value.edges || [], {
  relations: relationFilter.value, origin: originFilter.value, community: communityFilter.value,
  communities: communityOf.value, focus: focus.value, showIsolated: showIsolated.value,
}))
const shownConceptEdges = computed(() => visibleConceptGraph.value.edges)
const shownConceptNodes = computed(() => visibleConceptGraph.value.nodes)
const graphMatches = computed(() => findGraphNodes(layer.value === 'concept' ? concept.value.nodes || [] : graph.value.nodes || [], graphQuery.value))
const visibleDerivedCount = computed(() => shownConceptEdges.value.filter(e => e.origin === 'derived').length)
const communityOptions = computed(() => communityGroups.value.map(g => ({ ...g,
  title: (g.nodeIds || []).map(id => concept.value.nodes.find(n => n.id === id)).filter(Boolean)
    .sort((a, b) => (b.degree || 0) - (a.degree || 0)).slice(0, 2).map(n => n.label).join(' / ') || `社区 #${g.communityId}`,
})))

function resetGraphFilters() {
  focus.value = null
  communityFilter.value = ''
  relationFilter.value = (concept.value.ontology || []).map(r => r.id)
  originFilter.value = 'direct'
  showIsolated.value = false
  graphQuery.value = ''
  conceptSelected.value = null
  selected.value = null
  nextTick(() => graphRef.value?.resetView())
}

function locateNode(node) {
  if (layer.value === 'concept') {
    communityFilter.value = ''
    relationFilter.value = (concept.value.ontology || []).map(r => r.id)
    const edges = (concept.value.edges || []).filter(e => originFilter.value === 'all' || (originFilter.value === 'derived' ? e.origin === 'derived' : e.origin !== 'derived'))
    focus.value = expandGraphIds(node.id, edges, 1)
    focusRootId.value = node.id
  }
  onSelectNode(node)
  graphQuery.value = ''
  nextTick(() => graphRef.value?.focusNode(node.id))
}

function focusCommunity(id) {
  communityFilter.value = String(id)
  focus.value = null
  conceptSelected.value = null
  graphQuery.value = ''
  nextTick(() => graphRef.value?.resetView())
}

function sourceLinks(sources) {
  const values = Array.isArray(sources) ? sources : String(sources || '').split(/[,，]/)
  return values.map(s => graphSource(s)).filter(Boolean)
}

async function runGraphProbe() {
  const q = graphProbeQuestion.value.trim()
  if (!q || graphProbeBusy.value) return
  graphProbeBusy.value = true
  graphProbeResult.value = null
  graphProbeError.value = ''
  try { graphProbeResult.value = await kgApi.graphSearch(q, graphProbeMode.value) }
  catch { graphProbeError.value = '检索失败，请检查服务后重试。' }
  finally { graphProbeBusy.value = false }
}

async function generateCommunitySummaries() {
  summaryBusy.value = true
  try {
    if (summaryStatus.value?.communitiesStale) {
      await kgApi.recomputeCommunities()
      await loadCommunities()
    }
    const result = await kgApi.summarizeCommunities()
    const message = `已生成 ${result.written} 段，复用 ${result.cached} 段摘要${result.failed ? `，${result.failed} 段生成失败` : ''}`
    if (result.failed) ElMessage.warning(message)
    else ElMessage.success(message)
    summaryStatus.value = await kgApi.communityStatus()
    if (graphProbeResult.value) await runGraphProbe()
  } catch { /* 请求拦截器显示错误 */ }
  finally { summaryBusy.value = false }
}

/** 选中概念的关系清单（入边 + 出边，标出哪条是推导来的） */
const conceptRelList = computed(() => {
  const cur = conceptSelected.value
  if (!cur || layer.value !== 'concept') return []
  const byId = new Map((concept.value.nodes || []).map((n) => [n.id, n]))
  const out = []
  for (const e of shownConceptEdges.value) {
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
    await loadCommunities()
    // 默认只勾选"有代数性质"的关系会让图太空，所以默认全选 —— 过滤是给人收窄用的
    if (relationFilter.value == null) {
      relationFilter.value = (concept.value.ontology || []).map((r) => r.id)
    }
    if (conceptSelected.value) conceptSelected.value = concept.value.nodes.find(n => n.id === conceptSelected.value.id) || null
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    conceptLoading.value = false
  }
}

// ---- GraphRAG 社区层 ----
const communityOf = ref({})            // 节点 id → 社区编号（给图谱着色）
const communityGroups = ref([])        // 按社区聚合（规模 + 成员，给侧栏用）
const communityModularity = ref(0)
const communityLoading = ref(false)

/**
 * 读社区划分。**失败不打断概念图**：社区是叠加信息，拿不到就按节点类型着色，
 * 不该让整页因为"社区算过没有"而空着。
 */
async function loadCommunities() {
  try {
    const rows = await kgApi.communities()
    const map = {}
    let modularity = 0
    for (const r of rows || []) {
      map[r.nodeId] = r.communityId
      modularity = Number(r.modularity) || modularity
    }
    communityOf.value = map
    communityModularity.value = modularity
    communityGroups.value = await kgApi.groupedCommunities()
    try { summaryStatus.value = await kgApi.communityStatus() } catch { summaryStatus.value = null }
  } catch (e) {
    /* 拦截器已提示；保持原样着色 */
  }
}

/** 与 KnowledgeGraph 内同一套色相算法（黄金角步进），图例色块才和节点颜色对得上 */
function communityColor(id) {
  return `hsl(${Math.round((Number(id) * 137.508) % 360)} 46% 48%)`
}

/** 重算社区：纯本地计算（Leiden），不花 token，所以可以放心给按钮 */
async function recomputeCommunities() {
  communityLoading.value = true
  try {
    const r = await kgApi.recomputeCommunities()
    await loadCommunities()
    ElMessage.success(`社区已重算：${r.communities} 个社区 · 模块度 ${Number(r.modularity).toFixed(3)} · ${r.ms}ms`)
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    communityLoading.value = false
  }
}

/** 点社区里的某个成员 → 选中它（右栏就展开它的关系） */
function selectCommunityMember(nodeId) {
  const node = concept.value.nodes.find((n) => n.id === nodeId)
  if (node) locateNode(node)
}

/**
 * 社区接口只给节点 id；侧栏要给人看名字。
 *
 * <p>要查**两份**：`canvasNodes` 只是"当前关系过滤后仍在图上"的节点，社区成员常常被过滤掉，
 * 只查它就会退化成显示 id（实测踩到：侧栏整列都是 e-7debe…）。完整列表在 `concept.nodes`。
 */
function shortName(nodeId) {
  const inCanvas = canvasNodes.value.find((n) => n.id === nodeId)
  const inAll = inCanvas || (concept.value?.nodes || []).find((n) => n.id === nodeId)
  return inAll?.name || inAll?.label || String(nodeId).slice(0, 6)
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
        ElMessage.success(`GraphRAG 已重建：实体 ${j.entities} 个 · 新增三元组 ${j.triples} 条`)
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
  communityFilter.value = ''
  const edges = filterConceptGraph(concept.value.nodes, concept.value.edges, { relations: relationFilter.value, origin: originFilter.value }).edges
  focus.value = expandGraphIds(id, edges, hops)
  focusRootId.value = id
  nextTick(() => graphRef.value?.resetView())
}

/** 删除概念：连同它的三元组一起删（删的是图的结论，原始笔记一行不动） */
async function removeConceptNode(n) {
  try {
    await ElMessageBox.confirm(
      `删除概念「${n.label}」？<br><br>` +
        '<span style="color:#6b7280">· 它的所有三元组会一起删掉<br>' +
        '· 原始笔记 / 速查卡 / 资料一行不动，但**不会自动重建** —— 除非你重新点「重建 GraphRAG」</span>',
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
  chooseTab('wiki', wikiKey)
}

/** 带着概念名去问智能体 */
function askAgentConcept(name) {
  window.dispatchEvent(new CustomEvent('lh-ask-agent', {
    detail: { message: `「${name}」这个概念和知识库里相关概念之间是什么关系？按「属于/前置/易混」讲清楚，并给出原文依据。` },
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
  else if (n.type === 'ref') router.push(`/refs?read=${parseNodeId(n.id).id}`)
  else if (n.type === 'category') router.push('/notes')
  else if (n.type === 'file') router.push(`/files?read=${parseNodeId(n.id).id}`)
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
  return { note: '笔记', ref: '速查卡', category: '分类', tag: '标签', file: '资料', concept: '概念' }[t] || t
}

// ------------------------------------------------------------------
// 三、LLM Wiki
// ------------------------------------------------------------------
const topics = ref([])
const topicsLoading = ref(false)
const topicsReady = ref(false)
const activeTopic = ref('')
const wikiPage = ref(null)
const wikiLoading = ref(false)
const wikiBody = ref(null)
const pendingWikiLocation = ref(null)
let wikiLoadGeneration = 0
const wikiDependencyState = ref({ topicKey: '', loading: false, error: '', data: null })
const wikiDependencies = computed(() => wikiDependencyView(wikiDependencyState.value.data))
const wikiDependencyVisible = computed(() => !!wikiPage.value?.contentMd
  && !['index', 'lint'].includes(wikiPage.value?.topicType))
const wikiDependencyLoader = createWikiDependencyLoader({
  load: key => wikiApi.dependencies(key),
  onState: state => { wikiDependencyState.value = state },
})

function loadWikiDependencies() {
  if (wikiDependencyVisible.value && wikiPage.value?.topicKey === activeTopic.value) {
    wikiDependencyLoader.read(activeTopic.value)
  }
}

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
    topicsReady.value = true
    // A retrieval deep link or a manual page load already selected its exact topic.
    if (activeTopic.value && (wikiLoading.value || wikiPage.value?.topicKey === activeTopic.value
      || wikiRouteTarget(route.query)?.topicKey === activeTopic.value)) return
    const cur = topics.value.find((t) => t.topicKey === activeTopic.value)
    if (!cur && topics.value.length) {
      // 默认选第一个"有条目"的主题
      const first = topics.value.find((t) => t.itemCount > 0) || topics.value[0]
      if (first) selectWikiTopic(first.topicKey)
    } else if (cur) {
      await openTopic(cur.topicKey, true)
    }
  } finally {
    topicsLoading.value = false
  }
}

async function openTopic(key, silent = false, location = null) {
  if (!key) return
  const generation = ++wikiLoadGeneration
  if (location) pendingWikiLocation.value = location
  else if (pendingWikiLocation.value?.topicKey !== key) pendingWikiLocation.value = null
  if (wikiPage.value?.topicKey !== key) wikiPage.value = null
  activeTopic.value = key
  wikiLoading.value = true
  wikiDependencyLoader.clear()
  stopStaleWatch()
  try {
    const page = await wikiApi.page(key)
    if (generation !== wikiLoadGeneration) return
    wikiPage.value = page
    loadWikiDependencies()
    // 过期且开着自动更新：后台会自己重生成，这里盯一会儿把结果取回来
    if (wikiPage.value.stale && autoRefresh.value && !silent) {
      startStaleWatch(key)
    }
    nextTick(locateWikiSection)
  } catch (error) {
    if (generation === wikiLoadGeneration) ElMessage.error('知识页加载失败，请重试')
  } finally {
    if (generation === wikiLoadGeneration) wikiLoading.value = false
  }
}

async function locateWikiSection() {
  await nextTick()
  const target = pendingWikiLocation.value
  if (!target || target.topicKey !== wikiPage.value?.topicKey) return
  if (target.sectionKey === 'section-0' && wikiBody.value) {
    pendingWikiLocation.value = null
    wikiBody.value.scrollIntoView({ block: 'start', behavior: 'smooth' })
    return
  }
  const heading = findWikiHeading(wikiBody.value?.querySelectorAll('h1, h2, h3, h4, h5, h6'), target)
  if (!heading) return
  pendingWikiLocation.value = null
  wikiBody.value?.querySelectorAll('.wiki-section-target').forEach(item => item.classList.remove('wiki-section-target'))
  heading.classList.add('wiki-section-target')
  heading.scrollIntoView({ block: 'start', behavior: 'smooth' })
}

function openWikiFromReference(event) {
  const hit = event?.detail
  const target = wikiRouteTarget({ tab: 'wiki', topic: hit?.topicKey, section: hit?.sectionKey, heading: hit?.heading })
  if (!target) return
  const query = { ...knowledgeWorkspaceQuery(route.query, 'wiki', target.topicKey), section: target.sectionKey || undefined, heading: target.heading || undefined }
  if (router.resolve({ query }).fullPath === route.fullPath) {
    tab.value = 'wiki'
    openTopic(target.topicKey, false, target)
  } else router.push({ query })
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
      if (activeTopic.value !== key) return
      if (!p.stale) {
        wikiPage.value = p
        loadWikiDependencies()
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
    topicsReady.value = true
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
  const topicKey = activeTopic.value
  generating.value = true
  job.value = null
  try {
    const started = await wikiApi.generate(topicKey, wikiTarget.value || undefined)
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
        ElMessage.warning('wiki 已生成，结构与引用检查有提示')
      } else {
        ElMessage.success('wiki 已生成，结构与引用检查通过')
      }
      if (activeTopic.value === topicKey) await openTopic(topicKey, true)
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
      maintenanceOpen.value = false
      chooseTab('wiki', 'index')
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
const wikiCountLabel = computed(() => {
  const p = wikiPage.value
  if (!p) return ''
  const n = p.itemCount ?? 0
  if (p.topicType === 'lint') return `检测到 ${n} 条问题`
  if (p.topicType === 'index') return `收录 ${n} 个页面`
  if (p.topicType === 'entity') return n > 0 ? `${n} 个关联来源` : '跨页编译生成'
  return `当前 ${n} 条来源`
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
const TYPE_LABEL = { category: '分类', tag: '标签', entity: '实体', index: '索引', lint: '自检' }

function typeLabel(t) {
  return TYPE_LABEL[t] || '主题'
}

/** 正在删除的 topicKey（用于按钮 loading），空串表示没有 */
const deletingKey = ref('')

/**
 * 删除一页编译产物。确认框里把**会发生什么**讲清楚：
 * 删的只是编译结果、原始素材不动、而且下次生成还会回来。
 */
async function removeTopic(t) {
  if (!t || !t.topicKey) return
  const isCompiled = ['entity', 'index', 'lint'].includes(t.topicType)
  const safeTitle = String(t.title || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;')
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
      `删除「${safeTitle}」这一页编译结果？<br><br>` +
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
      wikiLoadGeneration++
      activeTopic.value = ''
      wikiPage.value = null
      pendingWikiLocation.value = null
      wikiDependencyLoader.clear()
      await router.replace({ query: knowledgeWorkspaceQuery(route.query, 'wiki') })
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
    const path = retrievalSourcePath({ type: 'note', id: href.slice(6) })
    if (path) router.push(path)
  } else if (href.startsWith('#file-')) {
    const path = retrievalSourcePath({ type: 'file', id: href.slice(6) })
    if (path) router.push(path)
  } else if (href.startsWith('#entity-') || href === '#index') {
    // 双链跳转：切到那个知识页（页面 key 就是 topicKey，去掉 # 即可）
    selectWikiTopic(href.slice(1))
  } else {
    const path = retrievalSourcePath({ type: 'ref', id: href.slice(5) })
    if (path) router.push(path)
  }
}

// ------------------------------------------------------------------
// 生命周期
// ------------------------------------------------------------------
const staleTopicCount = computed(() => topics.value.filter(t => t.stale).length)
const maintenanceBusy = computed(() => kbBusy.value || kgBuilding.value || rebuilding.value || entBusy.value || reBusy.value || lintBusy.value || summaryBusy.value || kgBusy.value || communityLoading.value)
const indexState = computed(() => kbLoading.value ? '读取索引…' : !kb.value ? '索引状态不可用' : kb.value.stale ? '索引待更新' : kb.value.chunks ? '索引可用' : '等待建立索引')
const formatCount = value => value == null ? '—' : Number(value).toLocaleString('zh-CN')

watch(tab, (v) => {
  if (v === 'graph') {
    if (!graph.value.nodes.length) loadGraph()
    startPoll()
  } else {
    stopPoll()
  }
  if (v === 'wiki' && (!topics.value.length || !activeTopic.value)) loadTopics()
  else if (v === 'wiki' && !wikiLoading.value) loadWikiDependencies()
})

watch(() => [route.query.tab, route.query.topic, route.query.section, route.query.heading], () => {
  const target = wikiRouteTarget(route.query)
  tab.value = knowledgeWorkspaceTab(route.query)
  if (target) openTopic(target.topicKey, false, target)
}, { immediate: true })

onMounted(() => {
  window.addEventListener('lh-open-wiki', openWikiFromReference)
  doSearch()
  if (tab.value === 'graph') { loadGraph(); startPoll() }
  if (tab.value === 'wiki') loadTopics()
  else loadTopicsQuiet()
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
  window.removeEventListener('lh-open-wiki', openWikiFromReference)
  wikiLoadGeneration++
  wikiDependencyLoader.dispose()
  searchLoader.dispose()
  stopPoll()
  stopStaleWatch()
})
</script>

<template>
  <div class="page knowledge-page" :class="{ 'knowledge-page-graph': tab === 'graph' }">
    <header class="knowledge-header">
      <div class="knowledge-heading">
        <h1>知识库</h1>
        <div class="knowledge-header-actions">
          <span class="index-indicator" :class="{ stale: kb?.stale, unavailable: !kb && !kbLoading }"><i />{{ indexState }}</span>
          <el-button size="small" :icon="Setting" @click="maintenanceOpen = true">知识库维护<span v-if="maintenanceBusy" class="maintenance-running">进行中</span></el-button>
        </div>
      </div>
      <div class="knowledge-navigation">
        <nav class="workspace-tabs" role="tablist" aria-label="知识库工作区">
          <button v-for="(workspace, index) in workspaces" :id="'knowledge-tab-' + workspace.key" :key="workspace.key" :ref="el => workspaceButtons[index] = el" type="button" role="tab" :title="workspace.description" :aria-selected="tab === workspace.key" :aria-controls="'knowledge-panel-' + workspace.key" :tabindex="tab === workspace.key ? 0 : -1" :class="{ active: tab === workspace.key }" @click="chooseTab(workspace.key)" @keydown="moveWorkspaceTab($event, index)">
            <el-icon><component :is="workspace.icon" /></el-icon><b>{{ workspace.title }}</b>
          </button>
        </nav>
        <dl class="knowledge-summary" aria-label="知识库概况">
          <div><dt>原文来源</dt><dd>{{ formatCount(kb?.currentSources) }}<small>份</small></dd></div>
          <div><dt>索引片段</dt><dd>{{ formatCount(kb?.chunks) }}<small>块</small></dd></div>
          <div><dt>图谱概念</dt><dd>{{ formatCount(concept.stat?.nodes) }}<small>个</small></dd></div>
          <div><dt>已生成 Wiki</dt><dd>{{ topicsReady ? formatCount(generatedTopicCount) : '—' }}<small>页</small><span v-if="staleTopicCount" class="summary-stale">{{ staleTopicCount }} 待更新</span></dd></div>
        </dl>
      </div>
    </header>
    <div :id="'knowledge-panel-' + tab" class="workspace-content" role="tabpanel" :aria-labelledby="'knowledge-tab-' + tab">
    <!-- ============ 知识图谱 ============ -->
    <template v-if="tab === 'graph'">
      <div class="graph-fill">
        <div class="graph-toolbar">
          <div class="layer" aria-label="图谱层级">
            <button type="button" :class="{ on: layer === 'concept' }" @click="layer = 'concept'">概念层<template v-if="concept.stat">（{{ concept.stat.nodes }}）</template></button>
            <button type="button" :class="{ on: layer === 'doc' }" @click="layer = 'doc'">文档层</button>
          </div>
          <span class="graph-description">{{ layer === 'concept' ? '从概念出发，沿关系找到原文依据' : '查看笔记、资料与速查卡之间的联系' }}</span>
          <div class="graph-actions">
            <el-button size="small" @click="resetGraphFilters">重置视图</el-button>
<el-button size="small" @click="maintenanceOpen = true">管理图谱</el-button>
          </div>
        </div>
        <div v-if="layer === 'concept'" class="graph-filters">
          <label class="graph-select-label">社区
            <!-- 与检索方式同样的问题：原生 <select> 的弹层由系统绘制，主题色/圆角都进不去 -->
            <el-select
              v-model="communityFilter"
              aria-label="筛选社区"
              :placeholder="'全部社区（' + communityGroups.length + '）'"
              size="small"
              style="width: 190px"
              @change="focus = null; conceptSelected = null"
            >
              <el-option :label="'全部社区（' + communityGroups.length + '）'" :value="''" />
              <el-option
                v-for="g in communityOptions"
                :key="g.communityId"
                :label="g.title + ' · ' + g.size + ' 个概念'"
                :value="String(g.communityId)"
              />
            </el-select>
          </label>
          <el-select v-model="relationFilter" aria-label="筛选关系" multiple collapse-tags collapse-tags-tooltip size="small" placeholder="选择关系" class="graph-relation-filter">
            <el-option v-for="r in concept.ontology || []" :key="r.id" :label="r.label" :value="r.id" />
          </el-select>
          <label class="graph-select-label">依据
            <el-select v-model="originFilter" aria-label="筛选关系依据" size="small" style="width: 130px">
              <el-option label="原文抽取" value="direct" />
              <el-option label="抽取 + 推导" value="all" />
              <el-option label="仅规则推导" value="derived" />
            </el-select>
          </label>
          <label class="graph-checkbox"><input v-model="showIsolated" type="checkbox" />显示孤立概念</label>
          <button v-if="focus" type="button" class="text-action" @click="focus = null">退出邻域聚焦</button>
        </div>
        <div v-if="layer === 'concept' && kgBuilding && kgJob" class="kb-progress">
          <el-progress :percentage="kgJob.percent || 0" :stroke-width="6" :show-text="false" />
          <div class="hint">{{ kgJob.stage }} · {{ kgJob.detail }}</div>
        </div>

        <div class="graph-workspace">
          <section class="graph-stage" aria-label="知识图谱">
            <div class="graph-stage-head">
              <div><b>{{ focus ? '概念邻域' : communityFilter !== '' ? '社区视图' : '知识地图' }}</b><span class="hint">{{ canvasNodes.length }} 个{{ layer === 'concept' ? '概念' : '节点' }} · {{ canvasEdges.length }} 条关系</span></div>
              <span v-if="layer === 'concept'" class="hint">{{ visibleDerivedCount ? `包含 ${visibleDerivedCount} 条推导` : '仅展示原文抽取' }}</span>
            </div>
            <div class="kg-box" v-loading="layer === 'doc' ? graphLoading : conceptLoading">
              <KnowledgeGraph v-if="canvasNodes.length" ref="graphRef" :key="layer" :nodes="canvasNodes" :edges="canvasEdges" :communities="layer === 'concept' ? communityOf : {}" :active-id="selectedNode?.id || ''" :layout-root-id="layer === 'concept' && focus ? focusRootId : ''" @select="onSelectNode" @open="openNode" />
              <el-empty v-else :description="layer === 'doc' ? '还没有内容，先写几篇笔记' : concept.nodes.length ? '当前筛选下没有关联，可调整筛选或显示孤立概念' : '还没有概念，重建 GraphRAG 后即可探索'" />
            </div>
            <div class="graph-stage-foot">
              <template v-if="layer === 'concept'"><span class="legend"><i class="line line-direct" />原文抽取</span><span class="legend"><i class="line line-derived" />规则推导</span><span class="hint">颜色表示社区 · 点选查看依据</span></template>
              <template v-else><span v-for="t in ['category', 'note', 'ref', 'file', 'tag']" :key="t" class="legend"><i class="dot" :class="`dot-${t === 'category' ? 'cat' : t}`" />{{ nodeTypeLabel(t) }}</span></template>
            </div>
          </section>

          <aside class="graph-inspector" aria-label="图谱探索与检索依据">
            <div class="inspector-tabs">
              <button type="button" :class="{ on: inspectorTab === 'explore' }" @click="inspectorTab = 'explore'">探索图谱</button>
              <button type="button" :class="{ on: inspectorTab === 'retrieve' }" @click="inspectorTab = 'retrieve'">检索验证</button>
            </div>
            <div class="inspector-scroll">
              <template v-if="inspectorTab === 'explore'">
                <input v-model="graphQuery" class="graph-search" :placeholder="layer === 'concept' ? '搜索概念或别名…' : '搜索文档或标签…'" aria-label="搜索图谱节点" @keydown.enter="graphMatches[0] && locateNode(graphMatches[0])" />
                <template v-if="!selectedNode || graphQuery">
                  <div class="inspector-label">{{ graphQuery ? '匹配结果' : '从一个概念开始' }}</div>
                  <p v-if="!graphMatches.length" class="hint">没有匹配的节点，请换一个关键词。</p>
                  <div class="graph-node-list">
                    <button v-for="n in graphMatches" :key="n.id" type="button" @click="locateNode(n)"><span>{{ n.label }}</span><small>{{ n.degree || 0 }} 条关联 →</small></button>
                  </div>
                </template>

                <div v-if="selectedNode" class="node-inspector">
                  <div class="node-inspector-heading"><span class="kg-kind">{{ nodeTypeLabel(selectedNode.type) }}</span><button class="text-action" type="button" @click="onSelectNode(null)">关闭详情</button></div>
                  <h3>{{ selectedNode.label }}</h3>
                  <p v-if="selectedNode.aliases?.length" class="hint">别名：{{ selectedNode.aliases.join('、') }}</p>
                  <p v-if="selectedNode.brief" class="kg-brief">{{ selectedNode.brief }}</p>
                  <div class="node-actions">
                    <template v-if="layer === 'concept'">
                      <el-button size="small" @click="expandConcept(selectedNode.id, 1)">1 跳邻域</el-button><el-button size="small" @click="expandConcept(selectedNode.id, 2)">2 跳邻域</el-button>
                      <el-button v-if="selectedNode.wikiKey" size="small" @click="openConceptWiki(selectedNode.wikiKey)">读知识页</el-button>
                      <el-button size="small" @click="askAgentConcept(selectedNode.label)">问智能体</el-button>
                    </template>
                    <template v-else>
                      <el-button v-if="['note', 'ref', 'file'].includes(selectedNode.type)" size="small" @click="openNode(selectedNode)">打开原文</el-button>
                      <el-button v-if="['note', 'ref', 'file'].includes(selectedNode.type)" size="small" @click="askAgent(selectedNode.type, parseNodeId(selectedNode.id).id, selectedNode.label)">问智能体</el-button>
                    </template>
                  </div>
                  <div class="inspector-label">{{ layer === 'concept' ? `关联与证据 · ${conceptRelList.length}` : '文档关联' }}</div>
                  <template v-if="layer === 'concept'">
                    <article v-for="(r, i) in conceptRelList" :key="r.id || i" class="graph-evidence-card">
                      <div class="evidence-heading"><span class="evidence-kind" :class="{ derived: r.origin === 'derived' || !r.evidence }">{{ r.origin === 'derived' ? '规则推导' : r.evidence ? '原文抽取' : '待核对' }}</span><span>{{ r.label }}</span></div>
                      <button type="button" class="related-node" @click="selectCommunityMember(r.out ? r.target : r.source)">{{ r.out ? '→ ' : '← ' }}{{ r.other }}</button>
                      <p>{{ r.evidence || (r.origin === 'derived' ? '根据本体规则推导，请结合原文核对。' : '暂缺原文证据，这条关系不会作为自动回答依据。') }}</p>
                      <div class="evidence-sources"><router-link v-for="s in sourceLinks(r.sources)" :key="s.type + s.id" :to="s.path">{{ s.label }} ↗</router-link></div>
                    </article>
                    <p v-if="!conceptRelList.length" class="hint">这个概念暂时没有关联。</p>
                    <details class="graph-maintenance"><summary>管理这个概念</summary><el-button size="small" :loading="deletingConcept === selectedNode.id" @click="removeConceptNode(selectedNode)">删除概念</el-button></details>
                  </template>
                  <article v-for="(l, i) in layer === 'doc' ? selectedLinks : []" :key="i" class="graph-evidence-card"><b>{{ l.title }}</b><p>{{ l.reason }}</p></article>
                </div>

                <template v-if="layer === 'concept' && !selectedNode && !graphQuery">
                  <div class="inspector-label">按社区探索 <span>{{ communityGroups.length }}</span></div>
                  <div class="community-list">
                    <button v-for="g in communityOptions.slice(0, 8)" :key="g.communityId" type="button" :class="{ active: String(g.communityId) === communityFilter }" @click="focusCommunity(g.communityId)"><i :style="{ background: communityColor(g.communityId) }" /><span>{{ g.title }}</span><small>{{ g.size }}</small></button>
                  </div>
                  <p class="hint">顶部筛选可选择全部社区。</p>
                </template>
              </template>

              <template v-else>
                <h3 class="probe-heading">看看图谱找到了什么</h3>
                <p class="hint">具体问题查看概念关系；整体问题查看社区摘要。这里只检索材料。</p>
                <form class="graph-probe-form" @submit.prevent="runGraphProbe">
                  <textarea v-model="graphProbeQuestion" aria-label="GraphRAG 检索问题" placeholder="例如：ReAct 和 Planning 有什么关系？" rows="3" />
                  <!-- 检索方式用**分段按钮**而不是原生 <select>：原生弹层由操作系统绘制，
                       直角、默认蓝底、无留白，改不动，跟主体的青绿圆角完全是两套东西；
                       而 Element 的分段按钮直接继承 --el-color-primary（本仓库已主题化），零额外 CSS。 -->
                  <div style="display: flex; align-items: center; gap: 8px; flex-wrap: wrap">
                    <el-radio-group v-model="graphProbeMode" size="small" aria-label="GraphRAG 检索方式">
                      <el-radio-button label="auto">自动选择</el-radio-button>
                      <el-radio-button label="local">局部检索</el-radio-button>
                      <el-radio-button label="global">全局概览</el-radio-button>
                    </el-radio-group>
                    <el-button type="primary" size="small" native-type="submit" :disabled="!graphProbeQuestion.trim()" :loading="graphProbeBusy">检索材料</el-button>
                  </div>
                </form>
                <div class="probe-examples"><button type="button" @click="graphProbeQuestion = 'ReAct 和 Planning 有什么关系？'; runGraphProbe()">概念关系</button><button type="button" @click="graphProbeQuestion = '知识库整体涵盖哪些学习方向？'; runGraphProbe()">学习概览</button></div>
                <p v-if="graphProbeError" class="probe-empty" role="alert">{{ graphProbeError }}</p>
                <template v-if="graphProbeResult">
                  <div class="probe-result-head"><b>{{ graphProbeResult.mode === 'global' ? '全局概览' : '局部检索' }}</b><span class="hint">{{ graphProbeResult.conceptHits || 0 }} 个概念命中</span></div>
                  <p class="hint">{{ graphProbeResult.routeReason }}</p>
                  <div v-if="graphProbeResult.emptyReason" class="probe-empty">{{ graphProbeResult.emptyReason }}<button v-if="graphProbeResult.fallback === 'document_search'" type="button" class="text-action" @click="kw = graphProbeQuestion; chooseTab('search'); doSearch()">转到正文检索 →</button></div>
                  <div v-if="graphProbeResult.concepts?.length" class="probe-concepts"><button v-for="n in graphProbeResult.concepts" :key="n.id" type="button" @click="layer = 'concept'; selectCommunityMember(n.id)">{{ n.name }}</button></div>
                  <article v-for="(r, i) in graphProbeResult.relations || []" :key="r.id || i" class="graph-evidence-card"><div class="evidence-heading"><span class="evidence-kind" :class="{ derived: r.origin === 'derived' }">{{ r.origin === 'derived' ? '规则推导' : '原文抽取' }}</span></div><b>{{ r.headName }} → {{ r.label || r.relation }} → {{ r.tailName }}</b><p>{{ r.evidence || '规则推导，请核对前提。' }}</p><div class="evidence-sources"><router-link v-for="s in sourceLinks(r.sources)" :key="s.type + s.id" :to="s.path">{{ s.label }} ↗</router-link></div></article>
                  <article v-for="s in graphProbeResult.summaries || []" :key="s.communityId" class="graph-evidence-card"><div class="evidence-heading"><b>社区 #{{ s.communityId }}</b><span class="hint">{{ s.size }} 个概念</span></div><p>{{ s.summary }}</p><div class="evidence-sources"><router-link v-for="src in sourceLinks(s.sources)" :key="src.type + src.id" :to="src.path">{{ src.label }} ↗</router-link></div></article>
                  <p v-if="graphProbeResult.truncated" class="hint">已按材料预算截断，未展示全部关系。</p>
                </template>

              </template>

            </div>
          </aside>
        </div>
      </div>
    </template>

    <!-- ============ LLM Wiki ============ -->
    <template v-else-if="tab === 'wiki'">
      <div class="wiki-view-header">
        <div><h2>主题知识页</h2><p>把笔记与资料整理成可阅读、可追溯的知识导览。</p></div>
        <span>{{ topics.length }} 个主题 · {{ generatedTopicCount }} 页已生成<template v-if="staleTopicCount"> · {{ staleTopicCount }} 页待更新</template></span>
      </div>
      <div class="wiki">
        <WikiTopicNavigator :topics="topics" :active-topic="activeTopic" :loading="topicsLoading" :deleting-key="deletingKey" @select="selectWikiTopic" @delete="removeTopic" />
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
                  <span v-if="wikiPage.generatedAt" class="meta-chip">生成于 {{ wikiPage.generatedAt }}</span>
                  <span v-if="wikiPage.model" class="meta-chip">{{ wikiPage.model }}</span>
                  <el-tooltip
                    v-if="wikiPage.quality"
                    :content="wikiPage.qualityNote || '结构与引用标记检查通过；生成内容仍需结合原文核对。'"
                    placement="top"
                  >
                    <span class="badge" :class="wikiPage.quality === 'ok' ? 'badge-ok' : 'badge-warn'">
                      {{ wikiPage.quality === 'ok' ? '结构与引用检查通过' : '结构与引用检查有提示' }}
                    </span>
                  </el-tooltip>
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
                  :disabled="wikiPage.topicType !== 'entity' && !wikiPage.itemCount"
                  @click="generate"
                >{{ wikiPage.topicType === 'entity' ? '重新生成此页' : wikiPage.generated ? '重新生成' : '生成 wiki' }}</el-button>
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

            <details v-if="wikiDependencyVisible" :key="wikiPage.topicKey" class="wiki-dependencies">
              <summary>
                <span class="dependency-title">来源依赖</span>
                <span v-if="wikiDependencyState.loading" class="hint">正在读取…</span>
                <template v-else-if="!wikiDependencyState.error">
                  <span class="dependency-status" :class="wikiDependencies.status">{{ wikiDependencies.statusLabel }}</span>
                  <span class="hint dependency-summary">{{ wikiDependencies.summary }}</span>
                </template>
                <span v-else class="hint">读取失败</span>
              </summary>
              <div class="dependency-body">
                <p class="dependency-notice">以下是生成时实际读取的原文片段。来源指纹用于检测内容变化，不代表结论已通过语义核验。</p>
                <p v-if="wikiDependencyState.loading" class="hint">正在读取来源依赖…</p>
                <div v-else-if="wikiDependencyState.error" class="dependency-error" role="status">
                  <span>{{ wikiDependencyState.error }}</span>
                  <el-button size="small" @click="loadWikiDependencies">重试</el-button>
                </div>
                <template v-else>
                  <p v-for="reason in wikiDependencies.reasons" :key="reason" class="dependency-reason">{{ reason }}</p>
                  <p v-if="wikiDependencies.material" class="dependency-material">
                    {{ wikiDependencies.material }}
                    <span v-if="wikiDependencies.partialMaterial"> · 使用了部分素材</span>
                  </p>
                  <div v-if="wikiDependencies.sourceCoverage.length" class="dependency-coverage">
                    <div v-for="source in wikiDependencies.sourceCoverage" :key="source.key" class="dependency-coverage-row">
                      <span>{{ source.label }}</span><span>{{ source.material }}</span>
                    </div>
                    <p class="hint">以上范围记录本次生成实际读取的素材。字数包含片段重叠，不作正文覆盖率。</p>
                  </div>
                  <ul v-if="wikiDependencies.rows.length" class="dependency-list">
                    <li v-for="dependency in wikiDependencies.rows" :key="dependency.key" class="dependency-row">
                      <div class="dependency-row-head">
                        <span class="dependency-source">{{ dependency.sourceLabel }}<template v-if="dependency.title"> · {{ dependency.title }}</template></span>
                        <el-tooltip v-if="dependency.hashDescription" :content="dependency.hashDescription" placement="top" popper-class="dependency-hash-tooltip">
                          <span class="dependency-status" :class="dependency.tone">{{ dependency.status }}</span>
                        </el-tooltip>
                        <span v-else class="dependency-status" :class="dependency.tone">{{ dependency.status }}</span>
                        <router-link v-if="dependency.path" :to="dependency.path" class="dependency-link">查看原文 ↗</router-link>
                      </div>
                      <p v-if="dependency.position || dependency.heading" class="dependency-position">
                        {{ [dependency.position, dependency.heading].filter(Boolean).join(' · ') }}
                      </p>
                      <details v-if="dependency.chunkText" class="dependency-excerpt">
                        <summary>生成时读取的片段</summary>
                        <pre>{{ dependency.chunkText }}</pre>
                      </details>
                    </li>
                  </ul>
                </template>
              </div>
            </details>
            <p v-if="wikiPage.contentMd" class="wiki-guide-note">模型生成的知识导览，关键结论请点击来源核对原文。</p>
            <div v-if="wikiPage.contentMd" ref="wikiBody" class="wiki-body md-doc" @click="onWikiClick">
              <MdPreview :modelValue="wikiMd" :theme="isDark ? 'dark' : 'light'" previewTheme="github" :mdHeadingId="wikiHeadingId" @onHtmlChanged="locateWikiSection" />
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

    <KnowledgeSearchPanel v-else v-model:query="kw" :mode="searchMode" :loading="loading" :searched="searched" :items="items" :keyword="keyword" :last-mode="lastMode" :error="searchError" @mode="switchMode" @search="doSearch" @clear="clearSearch" @open="open" @download="item => downloadFile(item.id ?? item.sourceId, item.title)" />
    </div>

    <el-drawer v-model="maintenanceOpen" title="知识库维护" size="min(580px, 100vw)" append-to-body class="knowledge-maintenance-drawer">
      <div class="maintenance-content">
        <p class="maintenance-intro">查看索引状态，更新知识结构，验证检索效果。已开始的任务会在关闭面板后继续。</p>
        <details open class="maintenance-section"><summary><el-icon><Search /></el-icon><span>检索索引与体检</span></summary><div class="maintenance-section-body">
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
          aria-label="检索体检问题"
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


        </div></details>
        <details class="maintenance-section"><summary><el-icon><Share /></el-icon><span>图谱与社区</span><small>{{ formatCount(concept.stat?.nodes) }} 个概念</small></summary><div class="maintenance-section-body">
          <p class="hint">重建 GraphRAG 使用已配置的模型抽取概念和关系。规则推理与社区划分在本地执行。</p>
          <div class="maintenance-actions"><el-button :loading="kgBuilding" @click="buildConcept">重建 GraphRAG</el-button><el-button :loading="rebuilding" @click="rebuild">重建文档关联</el-button></div>
          <div v-if="kgBuilding && kgJob" class="kb-progress"><el-progress :percentage="kgJob.percent || 0" :stroke-width="6" /><p class="hint">{{ kgJob.stage }} · {{ kgJob.detail }}</p></div>
          <div class="maintenance-metrics"><span>{{ communityGroups.length }} 个社区</span><span>模块度 {{ communityModularity.toFixed(3) }}</span></div>
          <div class="maintenance-actions"><el-button :loading="communityLoading" @click="recomputeCommunities">重算社区</el-button><el-button :loading="kgBusy" @click="reasonConcept">规则推理</el-button></div>
<div class="summary-status" v-if="summaryStatus"><b>社区摘要</b><p class="hint">{{ summaryStatus.fresh || 0 }} 段可用 · {{ summaryStatus.stale || 0 }} 段过期 · {{ summaryStatus.missing || 0 }} 段待生成</p><p v-if="summaryStatus.communitiesStale" class="hint">图谱已变化，生成前会先更新社区划分。</p><el-button size="small" :loading="summaryBusy" @click="generateCommunitySummaries">生成 / 更新摘要（最多 3 段）</el-button><p class="hint">按需调用已配置的模型；输入变化后重新生成。</p></div>
        </div></details>
        <details class="maintenance-section"><summary><el-icon><Reading /></el-icon><span>Wiki 更新与自检</span><small>{{ staleTopicCount }} 页待更新</small></summary><div class="maintenance-section-body">
          <p class="hint">批量编译和自检会调用模型。单页的生成、模型选择与删除在 Wiki 阅读区操作。</p>
          <div class="maintenance-actions"><el-button :loading="entBusy" @click="compileEntities">编译知识页</el-button><el-button :loading="reBusy" @click="recompileAffected">重建受影响页</el-button><el-button :loading="lintBusy" @click="runLint">自检</el-button></div>
          <div v-if="entBusy && entJob" class="kb-progress"><el-progress :percentage="entJob.percent || 0" :stroke-width="6" /><p class="hint">{{ entJob.stage }} · {{ entJob.done }}/{{ entJob.total }} · 已写 {{ entJob.pages }} 页</p></div>
          <div v-if="reBusy && reJob" class="kb-progress"><el-progress :percentage="reJob.percent || 0" :stroke-width="6" /><p class="hint">{{ reJob.stage }} · {{ reJob.detail }}</p></div>
          <div class="maintenance-toggle"><div><b>自动更新知识页</b><p class="hint">原文变化后，自动重新生成受影响的页面。</p></div><el-switch v-model="autoRefresh" aria-label="原文变化后自动更新 Wiki" @change="toggleAutoRefresh" /></div>
          <el-button @click="maintenanceOpen = false; chooseTab('wiki')">前往 Wiki 阅读区<el-icon><ArrowRight /></el-icon></el-button>
        </div></details>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.knowledge-page { min-width: 0; max-width: 1800px; margin: 0 auto; padding: 16px 24px; }
.knowledge-page-graph { display: flex; flex-direction: column; height: 100%; min-height: 640px; box-sizing: border-box; }
.knowledge-header { flex-shrink: 0; margin-bottom: 12px; }
.knowledge-heading { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px 16px; }
.knowledge-heading h1 { margin: 0; font-size: 22px; line-height: 32px; letter-spacing: -.4px; font-weight: 700; }
.knowledge-header-actions { display: flex; align-items: center; gap: 12px; flex-shrink: 0; margin-left: auto; }
.index-indicator { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--app-text-2); }
.index-indicator i { width: 6px; height: 6px; border-radius: 50%; background: var(--app-brand); }
.index-indicator.stale i { background: #c58519; }
.index-indicator.unavailable i { background: var(--app-text-3); }
.maintenance-running { margin-left: 8px; font-size: 12px; color: var(--app-brand-deep); }
.knowledge-navigation { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px 16px; margin-top: 8px; border-bottom: 1px solid var(--app-border); }
.knowledge-summary { display: flex; flex-wrap: wrap; gap: 6px 16px; margin: 0; padding: 0 0 4px; }
.knowledge-summary > div { display: flex; align-items: baseline; gap: 5px; white-space: nowrap; }
.knowledge-summary dt { color: var(--app-text-3); font-size: 12px; }
.knowledge-summary dd { margin: 0; font-size: 14px; font-weight: 650; line-height: 1.5; font-variant-numeric: tabular-nums; color: var(--app-text-1); }
.knowledge-summary small { margin-left: 4px; font-weight: 400; color: var(--app-text-3); font-size: 12px; }
.summary-stale { font-size: 11px; font-weight: 400; color: var(--app-text-3); margin-left: 6px; }
.workspace-tabs { display: flex; align-items: stretch; gap: 4px; flex-shrink: 0; }
.workspace-tabs button { position: relative; display: flex; align-items: center; gap: 8px; min-height: 36px; padding: 8px 14px; border: 0; border-radius: 6px 6px 0 0; background: transparent; text-align: left; color: var(--app-text-2); font: inherit; cursor: pointer; transition: background var(--dur-fast); }
.workspace-tabs button:hover { background: var(--app-card); }
.workspace-tabs button.active { color: var(--app-brand-deep); background: color-mix(in srgb, var(--app-brand-soft) 55%, transparent); }
.workspace-tabs button.active::after { content: ''; position: absolute; bottom: -1px; left: 12px; right: 12px; height: 2px; border-radius: 2px 2px 0 0; background: var(--app-brand); }
.workspace-tabs .el-icon { font-size: 17px; }
.workspace-tabs b { font-size: 13px; font-weight: 600; white-space: nowrap; }
.workspace-content { min-width: 0; }
.knowledge-page-graph .workspace-content { display: flex; flex-direction: column; flex: 1; min-height: 0; }
.wiki-view-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.wiki-view-header h2 { margin: 0; font-size: 20px; }
.wiki-view-header p { margin: 4px 0 0; font-size: 12px; color: var(--app-text-3); }
.wiki-view-header > span { color: var(--app-text-3); font-size: 12px; }
.maintenance-intro { margin: 0 0 24px; color: var(--app-text-2); line-height: 1.8; font-size: 14px; }
.maintenance-section { border: 1px solid var(--app-border); border-radius: var(--radius-lg); margin-bottom: 16px; color: var(--app-text-1); background: var(--app-card); }
.maintenance-section > summary { display: flex; align-items: center; gap: 10px; padding: 16px; font-weight: 600; cursor: pointer; list-style: none; }
.maintenance-section > summary::-webkit-details-marker { display: none; }
.maintenance-section > summary::after { content: '+'; color: var(--app-text-3); margin-left: auto; font-size: 18px; font-weight: 400; }
.maintenance-section[open] > summary::after { content: '−'; }
.maintenance-section > summary .el-icon { color: var(--app-brand-deep); font-size: 18px; }
.maintenance-section > summary small { font-size: 12px; font-weight: 400; color: var(--app-text-3); }
.maintenance-section-body { border-top: 1px solid var(--app-border-weak); padding: 16px; }
.maintenance-section-body > .hint { line-height: 1.8; margin-top: 0; }
.maintenance-actions { display: flex; flex-wrap: wrap; gap: 8px; margin: 12px 0; }
.maintenance-actions .el-button + .el-button { margin-left: 0; }
.maintenance-metrics { display: flex; gap: 20px; margin: 20px 0 8px; color: var(--app-text-3); font-size: 12px; }
.maintenance-toggle { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 16px 0; margin-top: 20px; border-top: 1px solid var(--app-border); }
.maintenance-toggle b { font-size: 14px; }
.maintenance-toggle p { margin: 4px 0 0; line-height: 1.7; }
.maintenance-content .kb-bar { align-items: flex-start; flex-direction: column; gap: 10px; padding: 0; margin-bottom: 20px; }
.maintenance-content .kb-bar .hint { line-height: 1.8; overflow-wrap: anywhere; }
.maintenance-content .probe-bar { gap: 8px; flex-wrap: wrap; padding: 0; }
.maintenance-content .probe-bar .search-input { flex: 1 1 100%; }
.maintenance-content .probe-result { margin-top: 12px; grid-template-columns: 1fr; gap: 16px; }
.maintenance-content .summary-status { margin-bottom: 0; }
.maintenance-content .el-button .el-icon { margin-left: 8px; }
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
  grid-template-columns: 280px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}
.wiki > .wiki-navigator { position: sticky; top: 16px; height: calc(100dvh - 32px); max-height: 960px; overflow: hidden; border: 1px solid var(--app-border); border-radius: var(--radius-lg); background: var(--app-card); }
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
  padding: 24px;
  min-width: 0;
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
.wiki-dependencies { margin: 12px 0; border: 1px solid var(--app-border-weak); border-radius: 8px; font-size: 12px; }
.wiki-dependencies > summary { padding: 10px 12px; cursor: pointer; color: var(--app-text-2); line-height: 1.8; }
.dependency-title { margin-right: 8px; font-weight: 600; color: var(--app-text-1); }
.dependency-summary { margin-left: 8px; }
.dependency-status { display: inline-block; border-radius: 4px; padding: 1px 6px; background: var(--app-bg); color: var(--app-text-3); line-height: 1.6; }
.dependency-status.current { background: var(--app-brand-soft); color: var(--app-brand-deep); }
.dependency-status.stale, .dependency-status.changed, .dependency-status.missing { background: color-mix(in srgb, #d97706 10%, transparent); color: #b45309; }
.dependency-body { padding: 0 12px 12px; border-top: 1px solid var(--app-border-weak); }
.dependency-notice { color: var(--app-text-2); line-height: 1.7; margin: 10px 0; }
.dependency-reason { color: var(--app-text-2); line-height: 1.7; margin: 8px 0; }
.dependency-material { margin: 8px 0; color: var(--app-text-3); }
.dependency-coverage { margin: 10px 0; padding: 8px 10px; border-radius: 5px; background: var(--app-bg); }
.dependency-coverage-row { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 4px 16px; padding: 5px 0; line-height: 1.6; color: var(--app-text-2); overflow-wrap: anywhere; }
.dependency-coverage-row > span:last-child { color: var(--app-text-3); }
.dependency-coverage > p { margin: 7px 0 0; line-height: 1.7; }
.dependency-list { list-style: none; padding: 0; margin: 10px 0 0; }
.dependency-row { padding: 10px 0; border-top: 1px solid var(--app-border-weak); }
.dependency-row-head { display: flex; align-items: center; flex-wrap: wrap; gap: 6px 10px; }
.dependency-source { min-width: 0; overflow-wrap: anywhere; font-weight: 600; color: var(--app-text-1); }
.dependency-link { color: var(--app-brand-deep); text-decoration: none; margin-left: auto; white-space: nowrap; }
.dependency-link:hover { text-decoration: underline; }
.dependency-position { margin: 6px 0; color: var(--app-text-3); overflow-wrap: anywhere; }
.dependency-excerpt { margin-top: 7px; color: var(--app-text-2); }
.dependency-excerpt > summary { cursor: pointer; }
.dependency-excerpt pre { margin: 8px 0 0; padding: 10px; background: var(--app-bg); border-radius: 5px; white-space: pre-wrap; overflow-wrap: anywhere; font-family: inherit; font-size: 12px; line-height: 1.65; max-height: 240px; overflow-y: auto; }
.dependency-error { display: flex; align-items: center; gap: 12px; margin-top: 10px; color: var(--app-text-2); }
:global(.dependency-hash-tooltip) { white-space: pre-wrap; overflow-wrap: anywhere; max-width: min(520px, 85vw); }
.wiki-guide-note, .kitem-guide-note { color: var(--app-text-2); font-size: 12px; line-height: 1.6; }
.wiki-guide-note { margin: 8px 0; }
.wiki-body :deep(.wiki-section-target) { scroll-margin-top: 24px; background: var(--app-brand-soft); border-radius: 4px; }
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
/* Graph exploration: the canvas stays stable while evidence scrolls independently. */

.graph-fill { min-height: 0; gap: 8px; }
.head { flex-shrink: 0; }
.graph-toolbar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; flex-shrink: 0; }
.graph-description { font-size: 12px; color: var(--app-text-3); }
.graph-actions { display: flex; gap: 8px; margin-left: auto; }
.graph-filters { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; padding: 6px 10px; border: 1px solid var(--app-border-weak); border-radius: 8px; background: var(--app-card); flex-shrink: 0; }
.graph-fill > .kb-progress { flex-shrink: 0; }
.graph-select-label { display: inline-flex; align-items: center; gap: 8px; font-size: 12px; color: var(--app-text-3); min-width: 0; }
.graph-select-label select { max-width: 235px; }
.graph-filters select, .graph-probe-form select { height: 28px; padding: 2px 24px 2px 8px; color: var(--app-text-1); border: 1px solid var(--app-border); border-radius: 6px; background: var(--app-card); font-size: 12px; }
.graph-relation-filter { width: 164px; }
.graph-checkbox { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; color: var(--app-text-2); cursor: pointer; }
.graph-checkbox input { accent-color: var(--app-brand); }
.text-action { border: 0; padding: 2px 0; background: none; color: var(--app-brand-deep); font-size: 12px; cursor: pointer; }
.text-action:hover { text-decoration: underline; }
.graph-workspace { display: grid; grid-template-columns: minmax(0, 1fr) 320px; gap: 12px; flex: 1; min-height: 410px; }
.graph-stage { display: flex; flex-direction: column; min-width: 0; min-height: 0; border: 1px solid var(--app-border-weak); border-radius: 12px; background: var(--app-card); overflow: hidden; }
.graph-stage-head { padding: 8px 12px; border-bottom: 1px solid var(--app-border-weak); display: flex; align-items: center; gap: 6px; justify-content: space-between; flex-wrap: wrap; flex-shrink: 0; }
.graph-stage-head > div { display: flex; gap: 12px; align-items: center; }
.graph-stage-head b { font-size: 13px; color: var(--app-text-1); }
.graph-stage .kg-box { border: 0; border-radius: 0; min-height: 320px; flex: 1 1 0; }
.graph-stage-foot { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; border-top: 1px solid var(--app-border-weak); padding: 6px 12px; flex-shrink: 0; }
.line-direct { border-top-color: var(--app-brand); }
.line-derived { border-top-color: var(--app-text-3); }
.graph-inspector { display: flex; flex-direction: column; min-width: 0; min-height: 0; border: 1px solid var(--app-border-weak); border-radius: 12px; background: var(--app-card); overflow: hidden; }
.inspector-tabs { display: flex; padding: 7px; gap: 4px; border-bottom: 1px solid var(--app-border-weak); }
.inspector-tabs button { flex: 1; border: 0; border-radius: 6px; background: transparent; padding: 8px; color: var(--app-text-3); cursor: pointer; font-size: 12px; }
.inspector-tabs button.on { background: var(--app-brand-soft); color: var(--app-brand-deep); font-weight: 600; }
.inspector-scroll { padding: 14px; overflow-y: auto; flex: 1; min-height: 0; overscroll-behavior: contain; }
.graph-search { width: 100%; box-sizing: border-box; border: 1px solid var(--app-border); border-radius: 8px; color: var(--app-text-1); background: var(--app-bg); padding: 9px 11px; font: inherit; font-size: 12px; }
.graph-search:focus, .graph-probe-form textarea:focus { outline: 2px solid var(--app-brand); outline-offset: 2px; }
.inspector-label { display: flex; justify-content: space-between; color: var(--app-text-3); font-size: 11px; margin: 18px 0 9px; }
.graph-node-list { display: flex; flex-direction: column; gap: 3px; }
.graph-node-list button { display: flex; gap: 8px; align-items: center; justify-content: space-between; width: 100%; min-height: 38px; border: 0; border-radius: 6px; background: transparent; color: var(--app-text-1); font-size: 13px; text-align: left; padding: 8px; cursor: pointer; }
.graph-node-list button:hover { background: var(--app-brand-soft); }
.graph-node-list span { overflow-wrap: anywhere; }
.graph-node-list small { white-space: nowrap; color: var(--app-text-3); font-size: 10px; }
.node-inspector { margin-top: 16px; }
.node-inspector-heading { display: flex; align-items: center; justify-content: space-between; }
.node-inspector h3 { margin: 12px 0 8px; font-size: 19px; line-height: 1.4; color: var(--app-text-1); overflow-wrap: anywhere; }
.node-inspector .hint { line-height: 1.6; overflow-wrap: anywhere; }
.node-actions { display: flex; flex-wrap: wrap; gap: 7px; margin: 12px 0; }
.node-actions .el-button + .el-button { margin-left: 0; }
.graph-evidence-card { padding: 11px 12px; margin: 8px 0; border: 1px solid var(--app-border-weak); border-radius: 8px; background: var(--app-bg); font-size: 12px; line-height: 1.6; overflow-wrap: anywhere; }
.graph-evidence-card b { color: var(--app-text-1); font-size: 12px; }
.evidence-heading { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 7px; color: var(--app-text-3); font-size: 11px; }
.evidence-kind { color: var(--app-brand-deep); background: var(--app-brand-soft); padding: 1px 5px; border-radius: 4px; }
.evidence-kind.derived { border: 1px dashed var(--app-border); background: transparent; color: var(--app-text-3); }
.related-node { display: block; border: 0; background: none; color: var(--app-text-1); font-weight: 600; font-size: 13px; text-align: left; padding: 0; cursor: pointer; }
.related-node:hover { color: var(--app-brand-deep); }
.graph-evidence-card p { color: var(--app-text-2); margin: 6px 0; white-space: pre-wrap; }
.evidence-sources { display: flex; flex-wrap: wrap; gap: 5px 10px; margin-top: 7px; }
.evidence-sources a { font-size: 11px; color: var(--app-brand-deep); text-decoration: none; }
.evidence-sources a:hover { text-decoration: underline; }
.community-list { display: flex; flex-direction: column; gap: 5px; }
.community-list button { display: grid; grid-template-columns: 8px 1fr auto; align-items: center; gap: 8px; border: 1px solid transparent; border-radius: 7px; padding: 8px; background: var(--app-bg); text-align: left; font-size: 12px; color: var(--app-text-2); cursor: pointer; }
.community-list button:hover, .community-list button.active { border-color: var(--app-brand); }
.community-list i { width: 8px; height: 8px; border-radius: 50%; }
.community-list span { overflow-wrap: anywhere; }
.community-list small { font-size: 11px; color: var(--app-text-3); }
.graph-maintenance { border-top: 1px solid var(--app-border-weak); margin-top: 20px; padding-top: 12px; color: var(--app-text-3); font-size: 12px; }
.graph-maintenance summary { cursor: pointer; }
.graph-maintenance .hint { line-height: 1.6; }
.probe-heading { font-size: 15px; margin: 0 0 8px; color: var(--app-text-1); }
.probe-heading + p { line-height: 1.7; margin-bottom: 14px; }
.graph-probe-form textarea { width: 100%; box-sizing: border-box; resize: vertical; min-height: 82px; padding: 9px; border: 1px solid var(--app-border); border-radius: 8px; color: var(--app-text-1); background: var(--app-bg); font: inherit; font-size: 12px; line-height: 1.6; }
.graph-probe-form > div { display: flex; justify-content: space-between; gap: 10px; margin-top: 8px; }
.probe-examples { display: flex; gap: 8px; margin: 10px 0; }
.probe-examples button, .probe-concepts button { font-size: 11px; padding: 4px 8px; border: 1px solid var(--app-border); border-radius: 5px; background: transparent; color: var(--app-text-2); cursor: pointer; }
.probe-examples button:hover, .probe-concepts button:hover { border-color: var(--app-brand); color: var(--app-brand-deep); }
.probe-concepts { display: flex; gap: 5px; flex-wrap: wrap; margin: 10px 0; }
.probe-result-head { display: flex; align-items: center; justify-content: space-between; margin: 18px 0 6px; font-size: 13px; }
.probe-empty { background: var(--app-bg); border-radius: 8px; padding: 12px; color: var(--app-text-2); font-size: 12px; line-height: 1.7; }
.probe-empty button { display: block; margin-top: 8px; }
.summary-status { padding-top: 16px; margin-top: 16px; border-top: 1px solid var(--app-border-weak); font-size: 12px; }
.summary-status .hint { line-height: 1.7; }
@media (min-width: 1600px) { .graph-workspace { grid-template-columns: minmax(0, 1fr) 350px; } }
@media (max-width: 1050px) { .graph-description { display: none; } .graph-workspace { grid-template-columns: minmax(0, 1fr) 280px; gap: 10px; } .graph-select-label select { max-width: 180px; } .graph-stage-head .hint { font-size: 11px; } }
@media (max-width: 1100px) {
  .knowledge-header-actions { gap: 8px; }
  .wiki { grid-template-columns: 260px minmax(0, 1fr); }
  .page-head { grid-template-columns: 1fr; }
  .page-acts { flex-wrap: wrap; }
  .wiki-view-header { flex-wrap: wrap; }
}
@media (max-width: 860px) {
  .knowledge-page { padding: 14px 18px; }
  .knowledge-page-graph { height: auto; min-height: 100%; }
  .knowledge-navigation { gap: 6px; }
  .knowledge-summary { width: 100%; padding-bottom: 8px; }
  .graph-workspace { grid-template-columns: 1fr; flex: none; }
  .graph-stage { height: 500px; }
  .graph-inspector { height: 460px; }
  .graph-toolbar, .graph-filters { gap: 8px; }
  .graph-stage-foot { gap: 10px; }
  .wiki { grid-template-columns: 1fr; }
  .wiki > .wiki-navigator { position: static; height: auto; max-height: 520px; }
  .wiki > .wiki-navigator :deep(.navigator-list) { max-height: 300px; }
}
@media (max-width: 560px) {
  .knowledge-page { padding: 12px 14px; }
  .knowledge-heading h1 { font-size: 20px; }
  .knowledge-summary { display: grid; grid-template-columns: 1fr 1fr; gap: 4px 12px; }
  .knowledge-summary dt, .knowledge-summary small { font-size: 11px; }
  .workspace-tabs { gap: 0; width: 100%; }
  .workspace-tabs button { flex: 1; justify-content: center; padding: 8px 6px; gap: 5px; }
  .workspace-tabs .el-icon { font-size: 16px; }
  .graph-stage-head > div { flex-wrap: wrap; gap: 4px 12px; }
  .graph-actions { margin-left: 0; }
  .graph-stage-foot { padding: 6px 10px; }
  .graph-filters .graph-select-label { flex-wrap: wrap; }
  .graph-stage { height: 440px; }
  .wiki-main { padding: 16px; }
  .page-acts { gap: 8px; }
  .page-acts .el-button + .el-button { margin-left: 0; }
  .page-title { overflow-wrap: anywhere; }
  .maintenance-section > summary small { display: none; }
}

</style>
