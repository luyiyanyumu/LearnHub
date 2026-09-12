<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MdEditor, MdPreview } from 'md-editor-v3'
// 编辑器全局初始化 + 样式（原来在 main.js，为了不占首屏挪到这里；
// 本页是路由懒加载的，静态 import 不会影响首屏）
import '../utils/mdEditorSetup'
import { aiApi, categoryApi, tagApi, noteApi, saveBlob } from '../api'
import { fixHtmlQuotes } from '../utils/htmlQuotes'
import { isDark } from '../composables/useTheme'
import { focusMode } from '../composables/useViewMode'
import { FORMAT_PRESETS, stripInline } from '../utils/richFormat'
import { findUnsupported, previewHtmlToMd } from '../utils/htmlToMd'
import FormatBar from '../components/FormatBar.vue'

const route = useRoute()
const router = useRouter()

const id = computed(() => route.params.id)
const isNew = computed(() => !id.value)
const loading = ref(false)
const saving = ref(false)
const editorRef = ref(null)
const editorWrapRef = ref(null)
const categories = ref([])
const tags = ref([])
const form = ref({ title: '', content: '', categoryId: undefined, tagIds: [] })

/**
 * 表单指纹：用来判断「有没有未保存的改动」。
 * 加载成功 / 保存成功后都会刷新 savedSnapshot，所以刚打开时不会误报脏数据。
 */
function formFingerprint() {
  const f = form.value
  return JSON.stringify({
    title: f.title || '',
    content: f.content || '',
    categoryId: f.categoryId ?? null,
    tagIds: [...(f.tagIds || [])].sort(),
  })
}
let savedSnapshot = ''
const dirty = computed(() => formFingerprint() !== savedSnapshot)

/** 刚创建并跳转过来的笔记 id：用来跳过随之而来的那次重复加载 */
let justCreatedId = null

// ---- 格式条（RGB 颜色 / 语雀风格内联格式）----
/** 把选中文字包进对应标签；md-editor 的 insert() 会自动保留撤销历史 */
function applyFormat({ kind, value }) {
  if (previewEditing.value) return previewApplyFormat({ kind, value })
  const ed = editorRef.value
  if (!ed || typeof ed.insert !== 'function') {
    ElMessage.warning('编辑器尚未就绪，请稍后再试')
    return
  }
  if (kind === 'clear') {
    ed.insert((selected) => {
      const cleaned = stripInline(selected)
      if (cleaned === selected) return { targetValue: selected, select: false }
      return { targetValue: cleaned, select: false }
    })
    return
  }
  const preset = FORMAT_PRESETS[kind]
  if (preset) ed.insert(preset(value))
}

// ---- 预览编辑（所见即所得，改完反推回 Markdown 源码）----
const previewEditing = ref(false)
/** 进入编辑模式时的源码快照，用于「放弃改动」 */
let previewSnapshot = ''

/** 预览区最近一次选区：点工具条会夺走焦点，靠它恢复后再执行格式命令 */
let savedPreviewRange = null

/** 用户在预览里选中文字时记住选区；点工具条失焦后据此恢复 */
function onPreviewSelectionChange() {
  if (!previewEditing.value) return
  const el = previewEl()
  const sel = window.getSelection()
  if (el && sel && sel.rangeCount > 0 && sel.anchorNode && el.contains(sel.anchorNode)) {
    savedPreviewRange = sel.getRangeAt(0).cloneRange()
  }
}

/** 预览区 DOM（限定在本页编辑容器内，避开 AI 弹窗/全局面板里的 MdPreview） */
function previewEl() {
  return editorWrapRef.value?.querySelector('.pane-preview .md-editor-preview') || null
}

function setEditable(on) {
  const el = previewEl()
  if (!el) return false
  if (on) {
    el.setAttribute('contenteditable', 'true')
    el.classList.add('lh-preview-editing')
    el.addEventListener('paste', onPreviewPaste)
    el.addEventListener('mouseover', onPreviewBlockHover)
  } else {
    el.removeAttribute('contenteditable')
    el.classList.remove('lh-preview-editing')
    el.removeEventListener('paste', onPreviewPaste)
    el.removeEventListener('mouseover', onPreviewBlockHover)
    hideBlockHandle()
  }
  return true
}

/** 预览区粘贴：统一按纯文本插入，避免把外部网页/Word 的样式噪音带进来 */
function onPreviewPaste(e) {
  const raw = e.clipboardData?.getData('text/plain')
  if (raw == null) return
  e.preventDefault()
  // execCommand 虽已标记废弃，但仍是 contenteditable 下保留撤销历史的最简方案
  document.execCommand('insertText', false, raw)
}

async function togglePreviewEdit() {
  if (previewEditing.value) {
    // 再点一次 = 退出，但**先同步**：以前这里是直接退出，
    // 预览里改的内容会静默消失（旁边虽然有个「放弃改动」，但「退出」不该等于「放弃」）。
    syncPreviewToSource(true)
    return
  }
  previewSnapshot = form.value.content || ''
  previewEditing.value = true
  savedPreviewRange = null
  document.addEventListener('selectionchange', onPreviewSelectionChange)
  document.addEventListener('mousedown', onDocMouseDown)
  // 等 Markdown 渲染完成再把预览区设为可编辑
  for (let i = 0; i < 40; i++) {
    await nextTick()
    await new Promise((r) => requestAnimationFrame(r))
    if (setEditable(true)) {
      document.addEventListener('keydown', onPreviewKeydown)
      return
    }
  }
  previewEditing.value = false
  ElMessage.error('预览区没能就绪，请稍后重试')
}

function onPreviewKeydown(e) {
  if (!previewEditing.value) return
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
    e.preventDefault()
    syncPreviewToSource()
  }
  // Esc 同样是「同步后退出」。要丢弃请用操作条上的「↺ 放弃改动」，
  // 这样任何一条退出路径都不会悄悄吃掉用户的改动。
  if (e.key === 'Escape') syncPreviewToSource(true)
}

/**
 * 离开预览编辑模式（纯退出，不做同步）。
 * 只应由 syncPreviewToSource / abandonPreviewEdit 调用。
 */
function exitPreviewEdit() {
  document.removeEventListener('keydown', onPreviewKeydown)
  document.removeEventListener('selectionchange', onPreviewSelectionChange)
  document.removeEventListener('mousedown', onDocMouseDown)
  clearTimeout(hideHandleTimer)
  savedPreviewRange = null
  setEditable(false)
  previewEditing.value = false
}

/** 放弃预览里的改动：还原进入编辑模式前的源码 */
function abandonPreviewEdit() {
  form.value.content = previewSnapshot
  exitPreviewEdit()
  ElMessage.info('已放弃预览里的改动')
}

/**
 * 把预览区当前的富文本反推回 Markdown 源码。
 * @param {boolean} silent 静默「无需同步」的提示（出错仍然会提示）
 * @returns {boolean} 是否成功（失败时已给出提示，且**保持在编辑态**，不会丢改动）
 */
function syncPreviewToSource(silent = false) {
  const el = previewEl()
  if (!el) {
    // 这是异常状态（预览区都没了就无从反推），无论 silent 都要报出来，
    // 否则用户点「退出」会毫无反应。
    ElMessage.error('预览区不存在，无法同步')
    return false
  }
  const bad = findUnsupported(el.innerHTML)
  if (bad.length) {
    ElMessage.warning(`预览里有 ${bad.join('、')}，无法安全反推成源码，请改用源码模式编辑`)
    return false
  }
  const md = fixHtmlQuotes(previewHtmlToMd(el.innerHTML))
  if (md === (form.value.content || '').trim()) {
    exitPreviewEdit()
    if (!silent) ElMessage.info('预览内容与源码一致，无需同步')
    return true
  }
  form.value.content = md
  exitPreviewEdit()
  ElMessage.success('已把预览里的修改同步回 Markdown 源码，确认后点「保存」')
  return true
}

// ---- AI 处理（润色 / 整理格式）----
const aiBusy = ref(false)
const aiDialog = ref(false)
const aiLabel = ref('')
const aiResult = ref('')

async function aiProcess(mode) {
  const content = form.value.content || ''
  if (!content.trim()) {
    ElMessage.warning('正文为空，先写点内容再让 AI 处理')
    return
  }
  aiBusy.value = true
  aiLabel.value = mode === 'format' ? 'AI 整理格式' : 'AI 润色'
  try {
    const out = await aiApi.polish({ text: content, mode })
    // 原样返回 = 模型判断无可改动，明确告知而不是让人以为没生效
    if (out.trim() === content.trim()) {
      ElMessage.info('AI 检查后认为当前内容已足够规范，未做改动')
      return
    }
    aiResult.value = out
    aiDialog.value = true
  } catch (e) {
    /* 拦截器已提示错误 */
  } finally {
    aiBusy.value = false
  }
}

/** 用 AI 结果替换正文（不自动保存，用户再点一次「保存」把控结果） */
function aiApply() {
  form.value.content = fixHtmlQuotes(aiResult.value)
  aiDialog.value = false
  ElMessage.success('已用 AI 结果替换正文，确认无误后点「保存」')
}

/** 唤起全局智能体抽屉（可围绕当前笔记提问） */
function aiOpenChat() {
  window.dispatchEvent(new CustomEvent('lh-agent-open'))
}

async function loadMeta() {
  categories.value = flatten(await categoryApi.tree())
  tags.value = await tagApi.list()
}
function flatten(nodes, depth = 0, out = []) {
  for (const n of nodes || []) {
    out.push({ ...n, depth })
    if (n.children?.length) flatten(n.children, depth + 1, out)
  }
  return out
}

/**
 * 加载序号：路由从 A 切到 B 时，A 的请求可能后到。
 * 用序号把过期响应丢掉，避免「B 的表单被 A 的响应覆盖」。
 */
let loadSeq = 0

async function loadNote() {
  if (isNew.value) {
    // 「编辑 A → 新建」时组件同样会被复用：必须把表单清空，
    // 否则新建的笔记里会残留上一篇文章的正文。
    loadSeq++
    form.value = { title: '', content: '', categoryId: undefined, tagIds: [] }
    loading.value = false
    savedSnapshot = formFingerprint()
    return
  }
  const seq = ++loadSeq
  loading.value = true
  try {
    const n = await noteApi.detail(id.value)
    if (seq !== loadSeq) return // 已经切到别的笔记了，丢弃这次响应
    form.value = {
      title: n.title,
      // 修复粘贴内容里 HTML 标签内的弯引号，否则预览会把 <font style=”…”> 当纯文本显示
      content: fixHtmlQuotes(n.content || ''),
      categoryId: n.categoryId ?? undefined,
      tagIds: (n.tags || []).map((t) => t.id),
    }
  } finally {
    if (seq === loadSeq) loading.value = false
  }
  if (seq !== loadSeq) return
  savedSnapshot = formFingerprint()
}

/**
 * 粘贴时清洗：粘贴进编辑器的内容不过 loadNote 管线，
 * 在这里拦下剪贴板文本过一遍 fixHtmlQuotes（弯引号/反引号包font/星号混排），
 * 有变化才拦截默认粘贴并用 CodeMirror API 写入（保留撤销历史）。
 */
function onPasteCapture(e) {
  // 预览编辑模式下，粘贴由预览区的 onPreviewPaste 按纯文本处理
  if (previewEditing.value) return
  const raw = e.clipboardData?.getData('text/plain')
  if (!raw) return
  const cleaned = fixHtmlQuotes(raw)
  if (cleaned === raw) return
  e.preventDefault()
  e.stopPropagation()
  const view = editorRef.value?.getEditorView?.()
  if (view) {
    view.dispatch(view.state.replaceSelection(cleaned))
    view.focus()
  }
}

/** 最近一次保存成功的时刻（「✓ 已保存 13:42」用） */
const lastSavedAt = ref(null)

async function save() {
  if (!form.value.title.trim()) {
    ElMessage.warning('标题不能为空')
    return
  }
  // 预览编辑模式下直接保存会丢掉预览里的改动，先自动同步回源码
  if (previewEditing.value && !syncPreviewToSource(true)) return
  saving.value = true
  try {
    const payload = {
      title: form.value.title.trim(),
      content: form.value.content,
      categoryId: form.value.categoryId || null,
      tagIds: form.value.tagIds || [],
    }
    if (isNew.value) {
      const created = await noteApi.add(payload)
      // 先让表单与刚存下的内容对齐，再刷新指纹，
      // 否则「新建 → 跳转到详情」会被自己的未保存提醒拦下来
      form.value.title = payload.title
      savedSnapshot = formFingerprint()
      justCreatedId = String(created.id)
      lastSavedAt.value = new Date()
      ElMessage.success('笔记已创建')
      router.replace(`/notes/${created.id}`)
    } else {
      await noteApi.update(id.value, payload)
      savedSnapshot = formFingerprint()
      lastSavedAt.value = new Date()
      ElMessage.success('已保存')
    }
  } finally {
    saving.value = false
  }
}

async function onBack() {
  router.push('/notes')
}

/** 导出当前笔记：md 或自包含 html（html 渲染器懒加载，不拖首屏） */
async function exportNote(fmt = 'md') {
  if (!form.value.title.trim()) {
    ElMessage.warning('请先填写标题')
    return
  }
  const title = form.value.title.trim()
  const safe = title.replace(/[\\/:*?"<>|]/g, '_')
  try {
    if (fmt === 'html') {
      const { renderNoteHtml } = await import('../utils/mdToHtml')
      const html = renderNoteHtml({ title, content: form.value.content || '' })
      const blob = new Blob([html], { type: 'text/html;charset=utf-8' })
      saveBlob(blob, `${safe}.html`)
      ElMessage.success('已导出为 HTML 网页文件')
    } else {
      const content = `# ${title}\n\n${form.value.content || ''}\n`
      const blob = new Blob([content], { type: 'text/markdown;charset=utf-8' })
      saveBlob(blob, `${safe}.md`)
      ElMessage.success('已导出为 Markdown 文件')
    }
  } catch (e) {
    /* 拦截器已提示 */
  }
}

/** Ctrl/⌘+S：整页接管浏览器的「保存网页」，统一走保存笔记 */
function onGlobalKeydown(e) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
    e.preventDefault()
    if (!saving.value) save()
  }
}

// ==================================================================
// 布局：三栏（源码 / 预览 / 大纲）+ 模式（专注 / 阅读 / 预览编辑）
// ==================================================================

/** 源码栏宽度百分比（预览栏 = 100 - 源码 - 大纲在剩余空间内占比） */
const editorPct = ref(Number(localStorage.getItem('lh-editor-pct') || 42))
const outlineOpen = ref(true)
const readingMode = ref(false)
const pvScrollRef = ref(null)

watch(editorPct, (v) => localStorage.setItem('lh-editor-pct', String(Math.round(v))))

/** 响应式档位：宽(三栏) / 中(两栏,隐大纲) / 窄(Tab 切换编辑/预览) */
const layoutMode = ref('wide') // wide | mid | tab
let resizeObserver = null

function measureLayout() {
  const w = editorWrapRef.value?.clientWidth || window.innerWidth
  layoutMode.value = w >= 1280 ? 'wide' : w >= 860 ? 'mid' : 'tab'
  if (layoutMode.value !== 'wide') outlineOpen.value = false
}

/** 三栏里预览区随源码栏联动；大纲栏固定 260px（max 15%）由 flex 基准控制 */
const editorStyle = computed(() => {
  if (previewEditing.value || readingMode.value || layoutMode.value === 'tab') return {}
  return { flex: `0 0 ${editorPct.value}%` }
})

/** 当前左栏是否显示：Tab 模式下由编辑/预览 Tab 决定 */
const editorTab = ref('edit')
const showEditorPane = computed(() => {
  if (previewEditing.value || readingMode.value) return false
  if (layoutMode.value === 'tab') return editorTab.value === 'edit'
  return true
})
const showPreviewPane = computed(() => {
  if (readingMode.value) return true
  if (layoutMode.value === 'tab') return editorTab.value === 'preview' || previewEditing.value
  return true
})

// ---- 拖拽调宽 ----
let dragging = false
function startDrag(e) {
  if (layoutMode.value === 'tab') return
  dragging = true
  e.preventDefault()
  const move = (ev) => {
    if (!dragging || !editorWrapRef.value) return
    const rect = editorWrapRef.value.getBoundingClientRect()
    const pct = ((ev.clientX - rect.left) / rect.width) * 100
    // 两栏时预览占剩余全部；留出最小可读宽度
    const max = layoutMode.value === 'wide' ? 78 : 72
    editorPct.value = Math.min(max, Math.max(24, pct))
  }
  const up = () => {
    dragging = false
    window.removeEventListener('mousemove', move)
    window.removeEventListener('mouseup', up)
    document.body.classList.remove('is-col-resizing')
  }
  window.addEventListener('mousemove', move)
  window.addEventListener('mouseup', up)
  document.body.classList.add('is-col-resizing')
}

// ---- 大纲：解析标题 / 平滑滚动 / 滚动同步 ----
const outline = computed(() => {
  const items = []
  let inFence = false
  const lines = (form.value.content || '').split('\n')
  for (const line of lines) {
    if (/^\s*(```|~~~)/.test(line)) {
      inFence = !inFence
      continue
    }
    if (inFence) continue
    const m = line.match(/^(#{1,3})\s+(.+?)\s*#*$/)
    if (m) items.push({ level: m[1].length, text: m[2].replace(/<[^<>]{0,200}>/g, '').replace(/[*`~]/g, '').trim() })
  }
  return items
})

const activeIdx = ref(-1)

function scrollToHeading(idx) {
  const el = pvScrollRef.value
  if (!el) return
  const heads = el.querySelectorAll('.md-editor-preview h1, .md-editor-preview h2, .md-editor-preview h3')
  const target = heads[idx]
  if (target) el.scrollTo({ top: target.offsetTop - 24, behavior: 'smooth' })
}

let scrollRaf = 0
function onPreviewScroll() {
  if (scrollRaf) return
  scrollRaf = requestAnimationFrame(() => {
    scrollRaf = 0
    const el = pvScrollRef.value
    if (!el) return
    // 反向联动：预览滚 → 源码跟（互锁窗口内的是程序触发的滚动，跳过；锚点对齐）
    if (Date.now() - scrollSyncLock >= SCROLL_SYNC_LOCK_MS) {
      const ed = editorScrollEl()
      if (ed && bothScrollable(el, ed)) {
        scrollSyncLock = Date.now()
        scrollSyncSource = 'preview'
        syncFromPreview(el, ed)
        scheduleScrollResync()
      }
    }
    const heads = el.querySelectorAll('.md-editor-preview h1, .md-editor-preview h2, .md-editor-preview h3')
    let cur = -1
    for (let i = 0; i < heads.length; i++) {
      if (heads[i].offsetTop - el.scrollTop - 40 <= 0) cur = i
      else break
    }
    activeIdx.value = cur
  })
}

// ---- 源码区 ↔ 预览区 滚动联动（data-line 锚点对齐） ----
// 三栏重构弃用了 md-editor 内部分屏，它自带的编辑/预览滚动同步随之失效。
// 按比例同步在代码块多/行高差异大时会错位（两侧内容"长度分布"不一致），
// 改用锚点对齐：markdown-it 渲染时给预览块级元素标注 data-line=源码行号，
// CodeMirror 每个逻辑行是一个 .cm-line（DOM 顺序即行号），
// 双向把"滚动位置"翻译成"源码行号（带小数）"，再映射到对方的对应元素位置。
const SCROLL_SYNC_LOCK_MS = 120
/** 互锁时间戳：程序设置 scrollTop 会触发对方的 scroll 事件，窗口期内忽略，防来回抖动 */
let scrollSyncLock = 0

/** 源码编辑器（CodeMirror）的滚动容器 */
function editorScrollEl() {
  return editorWrapRef.value?.querySelector('.pane-editor .cm-scroller') || null
}

/** 元素顶部相对滚动容器视口的偏移（rect 差值法，不依赖 offsetParent 链） */
function topWithin(el, scroller) {
  return el.getBoundingClientRect().top - scroller.getBoundingClientRect().top + scroller.scrollTop
}

/** CodeMirror 内部 EditorView（.cm-content 上挂有非公开的 cmTile.view）。
 *  CodeMirror 虚拟化渲染（DOM 只有视口附近的行，未渲染区域用 .cm-gap 占位），
 *  DOM 行元素的 index 不等于行号；行高也因软换行不均（20/40/260px…）。
 *  lineBlockAtHeight/lineBlockAt 是官方 API，能精确做「滚动位置 ↔ 行号」换算。 */
function cmView(ed) {
  const v = ed.querySelector('.cm-content')?.cmTile?.view
  return v && typeof v.lineBlockAtHeight === 'function' && v.state?.doc ? v : null
}

/** content 顶在滚动坐标系中的偏移（rect 差值法实时测量） */
function contentScrollOffset(ed) {
  const content = ed.querySelector('.cm-content')
  if (!content) return 0
  return content.getBoundingClientRect().top - ed.getBoundingClientRect().top + ed.scrollTop
}

/** 源码区顶部行号（带小数 = 行内滚动比例）。优先 CM 内部 view（精确），失败回退固定行高换算 */
function editorTopLine(ed) {
  const v = cmView(ed)
  if (v) {
    try {
      const docY = Math.max(ed.scrollTop - contentScrollOffset(ed), 0)
      const blk = v.lineBlockAtHeight(docY)
      const ln = v.state.doc.lineAt(blk.from)
      let n = ln.number - 1
      let frac = (docY - blk.top) / Math.max(blk.height, 1)
      // 行边界归属：视口顶恰在行 n+1 顶部时 lineBlockAtHeight 返回行 n 且 frac=1，进位
      if (frac >= 0.999) {
        n += 1
        frac = 0
      }
      return n + Math.min(Math.max(frac, 0), 1)
    } catch {
      /* 回退到几何换算 */
    }
  }
  const content = ed.querySelector('.cm-content')
  const lineEl = ed.querySelector('.cm-line')
  if (!content || !lineEl) return null
  const h = parseFloat(getComputedStyle(lineEl).lineHeight) || 20
  const padTop = contentScrollOffset(ed) + (parseFloat(getComputedStyle(content).paddingTop) || 0)
  return Math.max(ed.scrollTop - padTop, 0) / h
}

/** 预览区 data-line 锚点列表（markdown-it 标注的源码行号，DOM 顺序即升序） */
function previewAnchors(pv) {
  const els = pv.querySelectorAll('.md-editor-preview [data-line]')
  const list = []
  for (const el of els) {
    const n = parseInt(el.dataset.line, 10)
    if (Number.isFinite(n)) list.push({ line: n, el })
  }
  return list
}

/** 源码行号（带小数）→ 预览滚动位置：≤行号的最大锚点对齐视口顶；
 *  相邻锚点行差 ≤ MAX_INTERP_LINES 时按行差插值（滚动连续），更大跨度直接吸附锚点
 *  （锚点段内的内容分布与行数不成比例，大跨度插值会失真数百像素） */
const MAX_INTERP_LINES = 8
function previewScrollForLine(pv, anchors, line) {
  let lo = 0
  let hi = anchors.length - 1
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1
    if (anchors[mid].line <= line) lo = mid
    else hi = mid - 1
  }
  const a = anchors[lo]
  const top = topWithin(a.el, pv)
  const b = anchors[lo + 1]
  if (!b || b.line <= a.line || b.line - a.line > MAX_INTERP_LINES) return top
  const frac = Math.min(Math.max((line - a.line) / (b.line - a.line), 0), 1)
  return top + frac * (topWithin(b.el, pv) - top)
}

/** 预览顶部 → 源码行号（带小数）：可见顶部锚点的行号 + 相邻锚点间的位置插值 */
function previewTopLine(pv, anchors) {
  if (!anchors.length) return null
  let lo = 0
  let hi = anchors.length - 1
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1
    if (topWithin(anchors[mid].el, pv) <= pv.scrollTop + 1) lo = mid
    else hi = mid - 1
  }
  const a = anchors[lo]
  const top = topWithin(a.el, pv)
  const b = anchors[lo + 1]
  if (!b) return a.line
  if (b.line <= a.line || b.line - a.line > MAX_INTERP_LINES) return a.line
  const h = Math.max(topWithin(b.el, pv) - top, 1)
  const frac = Math.min(Math.max((pv.scrollTop - top) / h, 0), 1)
  return a.line + frac * (b.line - a.line)
}

/** 源码行号（带小数）→ 源码区滚动位置（与 editorTopLine 互逆；优先 CM 内部 view） */
function editorScrollForLine(ed, line) {
  const v = cmView(ed)
  if (v) {
    try {
      const off = contentScrollOffset(ed)
      const total = v.state.doc.lines
      const i = Math.max(Math.min(Math.floor(line), total - 1), 0)
      const ln = v.state.doc.line(i + 1) // doc.line 是 1-based
      const blk = v.lineBlockAt(ln.from)
      if (i === total - 1) return blk.top + blk.height + off
      const frac = Math.min(Math.max(line - i, 0), 1)
      return blk.top + frac * blk.height + off
    } catch {
      /* 回退到几何换算 */
    }
  }
  const content = ed.querySelector('.cm-content')
  const lineEl = ed.querySelector('.cm-line')
  if (!content || !lineEl) return line * 20
  const h = parseFloat(getComputedStyle(lineEl).lineHeight) || 20
  const padTop = contentScrollOffset(ed) + (parseFloat(getComputedStyle(content).paddingTop) || 0)
  return padTop + line * h
}

/** 任一方不可滚动/被隐藏（阅读模式、Tab 单栏）时不联动 */
function bothScrollable(a, b) {
  return a.scrollHeight - a.clientHeight > 1 && b.scrollHeight - b.clientHeight > 1
}

/** 按比例同步（兜底：预览无锚点时使用） */
function syncScrollRatio(fromEl, toEl) {
  const fromMax = fromEl.scrollHeight - fromEl.clientHeight
  const toMax = toEl.scrollHeight - toEl.clientHeight
  if (fromMax <= 1 || toMax <= 1) return
  toEl.scrollTop = (fromEl.scrollTop / fromMax) * toMax
}

/** 源码区顶部行号 → 预览（锚点对齐；无锚点退回比例兜底） */
function syncFromEditor(ed, pv) {
  const anchors = previewAnchors(pv)
  const line = editorTopLine(ed)
  if (line == null || !anchors.length) {
    syncScrollRatio(ed, pv)
    return
  }
  pv.scrollTop = previewScrollForLine(pv, anchors, line)
}

/** 预览顶部 → 源码区（锚点对齐；无锚点退回比例兜底） */
function syncFromPreview(pv, ed) {
  const anchors = previewAnchors(pv)
  const line = previewTopLine(pv, anchors)
  if (line == null) {
    syncScrollRatio(pv, ed)
    return
  }
  ed.scrollTop = editorScrollForLine(ed, line)
}

/** 最近一次用户滚动的来源侧（决定尾随重同步的方向） */
let scrollSyncSource = 'editor'
let scrollSyncTimer = 0
/**
 * 尾随重同步：跳滚到未渲染区域时，CodeMirror 的高度图是估算值（行渲染后实际位置会偏移），
 * 初次联动可能落在估算位置上；锁窗口过期后再按原方向校正一次，用渲染后的精确高度收敛。
 */
function scheduleScrollResync() {
  clearTimeout(scrollSyncTimer)
  scrollSyncTimer = setTimeout(() => {
    const ed = editorScrollEl()
    const pv = pvScrollRef.value
    if (!ed || !pv || !bothScrollable(ed, pv)) return
    scrollSyncLock = Date.now() // 程序触发的滚动不吃回对方的 scroll 事件
    if (scrollSyncSource === 'editor') syncFromEditor(ed, pv)
    else syncFromPreview(pv, ed)
  }, SCROLL_SYNC_LOCK_MS + 60)
}

/** 源码区滚动 → 预览区跟随（锚点对齐） */
function onEditorScroll() {
  if (Date.now() - scrollSyncLock < SCROLL_SYNC_LOCK_MS) return
  const ed = editorScrollEl()
  const pv = pvScrollRef.value
  if (!ed || !pv || !bothScrollable(ed, pv)) return
  scrollSyncLock = Date.now()
  scrollSyncSource = 'editor'
  syncFromEditor(ed, pv)
  scheduleScrollResync()
}

/** CodeMirror 滚动容器可能比页面晚一帧才出现，绑定失败返回 false 供重试 */
function bindEditorScroll() {
  const el = editorScrollEl()
  if (!el) return false
  el.addEventListener('scroll', onEditorScroll, { passive: true })
  return true
}

// ---- 模式切换 ----
function toggleFocus() {
  focusMode.value = !focusMode.value
  if (focusMode.value) outlineOpen.value = false
}
function toggleReading() {
  readingMode.value = !readingMode.value
  if (readingMode.value) exitPreviewEdit()
}
/** AI 助手下拉：润色/格式/对话/预览编辑 */
function aiCommand(cmd) {
  if (cmd === 'chat') aiOpenChat()
  else if (cmd === '__edit') togglePreviewEdit()
  else aiProcess(cmd)
}
/** 「更多」菜单：导出（函数命令）+ 模式切换（字符串命令） */
function moreCommand(cmd) {
  if (typeof cmd === 'function') {
    cmd()
    return
  }
  if (cmd === 'toggleOutline') outlineOpen.value = !outlineOpen.value
  else if (cmd === 'toggleFocus') toggleFocus()
  else if (cmd === 'toggleReading') toggleReading()
}

// ---- Markdown 插入（第二行工具条；走 md-editor insert 保留撤销历史）----
function edInsert(builder) {
  const ed = editorRef.value
  if (!ed || typeof ed.insert !== 'function') {
    ElMessage.warning('编辑器尚未就绪，请稍后再试')
    return
  }
  ed.insert(builder)
}
const mdBtns = {
  bold: () => mdWrap('**', '**'),
  italic: () => mdWrap('*', '*'),
  strike: () => mdWrap('~~', '~~'),
  h2: () => mdLinePrefix('## '),
  h3: () => mdLinePrefix('### '),
  quote: () => mdLinePrefix('> '),
  ul: () => mdLinePrefix('- '),
  ol: () => mdLinePrefix('1. '),
  inlineCode: () => mdWrap('`', '`'),
  codeBlock: () => edInsert(() => ({ targetValue: '\n```java\n\n```\n', select: 9 })),
  link: () => mdWrap('[', '](https://)'),
  image: () => edInsert(() => ({ targetValue: '![图片描述](https://)', select: 3 })),
  table: () => edInsert(() => ({ targetValue: '\n| 列A | 列B |\n| --- | --- |\n|  |  |\n', select: 2 })),
}
/** 行内包裹：有选中时包住选中文字并保持其选中，无选中时插入「文本」占位 */
function mdWrap(before, after) {
  edInsert((selected) => {
    const inner = selected || '文本'
    return {
      targetValue: before + inner + after,
      select: selected ? [before.length, before.length + inner.length] : before.length,
    }
  })
}
/** 行前缀（标题/引用/列表）：只在行首插入 */
function mdLinePrefix(prefix) {
  edInsert((selected) => {
    const inner = selected || ''
    return { targetValue: prefix + inner, select: selected ? [prefix.length, prefix.length + inner.length] : prefix.length }
  })
}
function mdTool(name) {
  if (previewEditing.value) return previewMdTool(name)
  mdBtns[name]?.()
}

// ---- 预览编辑：直接在可编辑预览区套格式（execCommand/insertHTML，反推时由 Turndown 还原）----

/** HTML 转义：把纯文本安全塞进 <code>/<font> 等标签，避免被当成标签解析 */
function escapeHtml(s) {
  return String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

/** 聚焦预览区并恢复上一次保存的选区（点工具条会夺走焦点） */
function previewFocusAndRestore() {
  const el = previewEl()
  if (!el) return false
  el.focus()
  const sel = window.getSelection()
  if (sel && savedPreviewRange) {
    try {
      sel.removeAllRanges()
      sel.addRange(savedPreviewRange)
    } catch (_) { /* 选区可能已失效，忽略 */ }
  }
  return true
}

/** 恢复选区后取当前选中的纯文本（先恢复再读，否则失焦后读到的是空串） */
function previewSelText() {
  if (!previewFocusAndRestore()) return ''
  const sel = window.getSelection()
  return sel ? sel.toString() : ''
}

/** 在预览区执行一条 execCommand，随后刷新保存的选区方便连续操作 */
function previewExec(command, value = null) {
  if (!previewFocusAndRestore()) return
  try {
    document.execCommand(command, false, value)
  } catch (e) {
    ElMessage.warning('当前选择不支持该格式')
    return
  }
  const sel = window.getSelection()
  if (sel && sel.rangeCount > 0) savedPreviewRange = sel.getRangeAt(0).cloneRange()
}

/** 在预览区插入 HTML 片段（表格/图片/块级等 execCommand 覆盖不到的） */
function previewInsertHtml(html) {
  if (!previewFocusAndRestore()) return
  document.execCommand('insertHTML', false, html)
  const sel = window.getSelection()
  if (sel && sel.rangeCount > 0) savedPreviewRange = sel.getRangeAt(0).cloneRange()
}

/** 用标签包住预览里的选中文字（无选中时插入占位） */
function previewWrap(before, after, placeholder = '文本') {
  if (!previewFocusAndRestore()) return
  const sel = window.getSelection()
  const text = sel ? sel.toString() : ''
  document.execCommand('insertHTML', false, before + escapeHtml(text || placeholder) + after)
  const s2 = window.getSelection()
  if (s2 && s2.rangeCount > 0) savedPreviewRange = s2.getRangeAt(0).cloneRange()
}

/** Markdown 工具条 → 预览区等价操作（Turndown 可反向还原的标签/命令） */
function previewMdTool(name) {
  switch (name) {
    case 'bold': return previewExec('bold')
    case 'italic': return previewExec('italic')
    case 'strike': return previewExec('strikeThrough')
    case 'h2': return previewExec('formatBlock', 'h2')
    case 'h3': return previewExec('formatBlock', 'h3')
    case 'quote': return previewExec('formatBlock', 'blockquote')
    case 'ul': return previewExec('insertUnorderedList')
    case 'ol': return previewExec('insertOrderedList')
    case 'inlineCode': return previewWrap('<code>', '</code>', '代码')
    case 'link': {
      const text = previewSelText()
      const url = window.prompt('链接地址', 'https://')
      if (url == null || !url.trim()) return
      previewInsertHtml(`<a href="${url.trim()}">${escapeHtml(text || url.trim())}</a>`)
      return
    }
    case 'image': {
      const url = window.prompt('图片地址', 'https://')
      if (url == null || !url.trim()) return
      previewInsertHtml(`<img src="${url.trim()}" alt="图片描述" />`)
      return
    }
    case 'codeBlock': {
      const text = previewSelText()
      previewInsertHtml(`<pre><code class="language-java">${escapeHtml(text)}</code></pre>`)
      return
    }
    case 'table':
      return previewInsertHtml('<table><thead><tr><th>列A</th><th>列B</th></tr></thead><tbody><tr><td> </td><td> </td></tr></tbody></table>')
  }
}

/** 富文本格式条（颜色/字号/上下标等）→ 预览区等价操作 */
function previewApplyFormat({ kind, value }) {
  switch (kind) {
    case 'color': return previewWrap(`<font style="color: ${value}">`, '</font>')
    case 'bg': return previewWrap(`<font style="background-color: ${value}">`, '</font>')
    case 'size': return previewWrap(`<font style="font-size: ${value}px">`, '</font>')
    case 'mark': return previewWrap('<mark>', '</mark>', '高亮文字')
    case 'underline': return previewExec('underline')
    case 'strike': return previewExec('strikeThrough')
    case 'sup': return previewWrap('<sup>', '</sup>', '2')
    case 'sub': return previewWrap('<sub>', '</sub>', '2')
    case 'center': {
      const text = previewSelText()
      return previewInsertHtml(`<p style="text-align: center">${escapeHtml(text || '居中文字')}</p>`)
    }
    case 'details': {
      const text = previewSelText()
      return previewInsertHtml(`<details><summary>点击展开</summary>${escapeHtml(text || '折叠内容')}</details>`)
    }
    case 'callout': {
      const text = previewSelText()
      return previewInsertHtml(`<div class="md-callout md-callout-tip"><p>${escapeHtml(text || '提示内容')}</p></div>`)
    }
    case 'clear': {
      const text = previewSelText()
      if (text) previewInsertHtml(escapeHtml(text))
      return
    }
  }
}

// ---- 预览编辑：块级操作手柄（参考语雀：hover 块左侧 ⋮⋮ → 转化为/删除/复制/剪切/缩进/下方添加）----
// 块操作直接走 DOM 替换（不依赖选区，比 execCommand 可靠），产物沿用现有约定
// （pre>code.language-java / div.md-callout-tip / details…），同步时由 Turndown 反推。
const blockHandle = reactive({
  visible: false, // 手柄是否显示
  menuOpen: false, // 菜单是否打开
  convOpen: false, // 「转化为」副面板
  x: 0, // 手柄位置（相对 .pv-scroll 的内容坐标，随滚动自然跟随）
  y: 0,
  menuUp: false, // 菜单向上展开（块靠近底部时）
  kind: 'p', // 当前块标签名（小写），控制缩进项可用性
  canIndent: false,
  canOutdent: false,
})
/** 当前手柄指向的块元素（非响应式，避免 deep reactive 包装 DOM） */
let hoverBlockEl = null
let handleHovering = false
let hideHandleTimer = 0

/** 可被手柄操作的块级元素 */
const BLOCK_SEL = 'h1,h2,h3,h4,h5,h6,p,pre,blockquote,table,ul,ol,details,figure'

/** 从鼠标/选区目标向上找可操作块；列表内优先取最近的 li */
function findHoverBlock(target) {
  const el = previewEl()
  if (!el || !target) return null
  const node = target.nodeType === 1 ? target : target.parentElement
  if (!node || !el.contains(node)) return null
  const li = node.closest('li')
  if (li && el.contains(li)) return li
  const blk = node.closest(BLOCK_SEL)
  if (!blk || !el.contains(blk)) return null
  return blk
}

function clearBlockHover() {
  if (hoverBlockEl) hoverBlockEl.classList.remove('lh-block-hover')
  hoverBlockEl = null
}

function hideBlockHandle() {
  blockHandle.visible = false
  blockHandle.menuOpen = false
  blockHandle.convOpen = false
  clearBlockHover()
}

function hideBlockHandleSoon() {
  clearTimeout(hideHandleTimer)
  hideHandleTimer = setTimeout(() => {
    if (!blockHandle.menuOpen && !handleHovering) hideBlockHandle()
  }, 180)
}

/** 把 ⋮⋮ 手柄定位到块左缘外侧并高亮块 */
function showBlockHandle(blk) {
  const pv = pvScrollRef.value
  if (!pv) return
  clearBlockHover()
  hoverBlockEl = blk
  blk.classList.add('lh-block-hover')
  const r = blk.getBoundingClientRect()
  const pvr = pv.getBoundingClientRect()
  blockHandle.x = r.left - pvr.left - 30
  blockHandle.y = r.top - pvr.top + pv.scrollTop - 2
  blockHandle.menuUp = r.top - pvr.top > pv.clientHeight - 380
  blockHandle.kind = blk.tagName.toLowerCase()
  blockHandle.canIndent =
    blk.tagName === 'LI' && !!blk.previousElementSibling
  blockHandle.canOutdent =
    blk.tagName === 'LI' && !!blk.parentElement?.parentElement?.closest('li')
  blockHandle.visible = true
}

/** 预览区鼠标移动：识别 hover 的块并移动手柄 */
function onPreviewBlockHover(e) {
  if (!previewEditing.value) return
  if (blockHandle.menuOpen) return // 菜单开着时手柄固定
  const blk = findHoverBlock(e.target)
  if (!blk) {
    hideBlockHandleSoon()
    return
  }
  clearTimeout(hideHandleTimer)
  if (blk !== hoverBlockEl) showBlockHandle(blk)
}

function onHandleEnter() {
  handleHovering = true
  clearTimeout(hideHandleTimer)
}
function onHandleLeave() {
  handleHovering = false
  hideBlockHandleSoon()
}
function toggleBlockMenu() {
  blockHandle.menuOpen = !blockHandle.menuOpen
  blockHandle.convOpen = false
}
/** 点击菜单/手柄以外的任何地方 → 收起手柄 */
function onDocMouseDown(e) {
  if (!blockHandle.visible) return
  if (e.target.closest?.('.block-handle')) return
  hideBlockHandle()
}

// ---- 块「转化为」 ----

/** 「转化为」候选项（顺序对齐语雀） */
const CONV_ITEMS = [
  { key: 'h1', label: '一级标题', txt: 'H1' },
  { key: 'h2', label: '二级标题', txt: 'H2' },
  { key: 'h3', label: '三级标题', txt: 'H3' },
  { key: 'h4', label: '四级标题', txt: 'H4' },
  { key: 'h5', label: '五级标题', txt: 'H5' },
  { key: 'h6', label: '六级标题', txt: 'H6' },
  { key: 'p', label: '正文', txt: 'T' },
  { key: 'ul', label: '无序列表', icon: '<circle cx="5" cy="7" r="1.2" class="fill"/><circle cx="5" cy="12" r="1.2" class="fill"/><circle cx="5" cy="17" r="1.2" class="fill"/><path d="M9 7h10M9 12h10M9 17h10"/>' },
  { key: 'ol', label: '有序列表', icon: '<path d="M9.5 6.5h10M9.5 12h10M9.5 17.5h10"/><path d="M4 5.2 5.2 4.5V8M3.8 10.7c.2-.5.8-.8 1.3-.6.6.2.9.8.6 1.3l-1.9 2.4h2.4"/>' },
  { key: 'todo', label: '待办', icon: '<rect x="4" y="4.5" width="15" height="15" rx="2"/><path d="m8 12 2.5 2.5L16 9"/>' },
  { key: 'code', label: '代码块', icon: '<rect x="4" y="5" width="16" height="14" rx="2"/><path d="m9.5 10-1.8 2 1.8 2M14.5 10l1.8 2-1.8 2"/>' },
  { key: 'callout', label: '高亮块', icon: '<rect x="4" y="5" width="16" height="14" rx="2"/><path d="M8 9.5h8M8 12h8M8 14.5h5"/>' },
  { key: 'quote', label: '引用', icon: '<path d="M9.5 7.5c-2.6.6-4 2.3-4 5v4h5v-5h-3c0-1.6.7-2.7 2-3.2Zm9 0c-2.6.6-4 2.3-4 5v4h5v-5h-3c0-1.6.7-2.7 2-3.2Z"/>' },
  { key: 'details', label: '折叠块', icon: '<path d="M4 6h16M4 12h10M4 18h10"/><path d="m17 10 3 3-3 3"/>' },
]

/** 换标签（保留 innerHTML） */
function swapBlockTag(el, tag) {
  const n = document.createElement(tag)
  n.innerHTML = el.innerHTML
  el.replaceWith(n)
  return n
}

/** 把 li 从列表中摘出换成新块，列表其余部分按前后段保留 */
function splitListAround(li, newBlock) {
  const list = li.parentElement
  const sibs = [...list.children]
  const idx = sibs.indexOf(li)
  const frag = document.createDocumentFragment()
  if (idx > 0) {
    const l1 = document.createElement(list.tagName.toLowerCase())
    sibs.slice(0, idx).forEach((n) => l1.appendChild(n))
    frag.appendChild(l1)
  }
  frag.appendChild(newBlock)
  if (idx < sibs.length - 1) {
    const l2 = document.createElement(list.tagName.toLowerCase())
    sibs.slice(idx + 1).forEach((n) => l2.appendChild(n))
    frag.appendChild(l2)
  }
  list.replaceWith(frag)
}

/** 块 → 指定标签（li 摘出列表；pre 取纯文本） */
function convertBlockToTag(blk, tag) {
  if (blk.tagName === 'LI') {
    const n = document.createElement(tag)
    n.innerHTML = blk.innerHTML
    splitListAround(blk, n)
    return
  }
  if (blk.tagName === 'PRE' || blk.tagName === 'TABLE' || blk.tagName === 'DETAILS') {
    const n = document.createElement(tag)
    n.textContent = blk.innerText
    blk.replaceWith(n)
    return
  }
  swapBlockTag(blk, tag)
}

/** 块 → 列表（ul/ol/待办）；li 时只换父列表类型或补 checkbox */
function convertBlockToList(blk, tag, todo = false) {
  if (blk.tagName === 'LI') {
    const list = blk.parentElement
    if (todo) {
      if (!blk.querySelector(':scope > input[type="checkbox"]')) {
        const cb = document.createElement('input')
        cb.type = 'checkbox'
        cb.disabled = true
        blk.prepend(cb, ' ')
      }
      return
    }
    if (list.tagName.toLowerCase() !== tag) swapBlockTag(list, tag)
    return
  }
  const li = document.createElement('li')
  if (todo) {
    const cb = document.createElement('input')
    cb.type = 'checkbox'
    cb.disabled = true
    li.appendChild(cb)
    li.appendChild(document.createTextNode(' '))
  }
  if (blk.tagName === 'PRE' || blk.tagName === 'TABLE' || blk.tagName === 'DETAILS') {
    li.appendChild(document.createTextNode(blk.innerText))
  } else {
    li.innerHTML += blk.innerHTML
  }
  const list = document.createElement(tag)
  list.appendChild(li)
  blk.replaceWith(list)
}

/** 执行「转化为」 */
function blockConvert(key) {
  const blk = hoverBlockEl
  if (!blk) return
  switch (key) {
    case 'h1':
    case 'h2':
    case 'h3':
    case 'h4':
    case 'h5':
    case 'h6':
    case 'p':
      convertBlockToTag(blk, key)
      break
    case 'ul':
      convertBlockToList(blk, 'ul')
      break
    case 'ol':
      convertBlockToList(blk, 'ol')
      break
    case 'todo':
      convertBlockToList(blk, 'ul', true)
      break
    case 'code': {
      if (blk.tagName === 'PRE') break
      const pre = document.createElement('pre')
      const code = document.createElement('code')
      code.className = 'language-java'
      code.textContent = blk.innerText
      pre.appendChild(code)
      blk.replaceWith(pre)
      break
    }
    case 'quote': {
      if (blk.tagName === 'BLOCKQUOTE') break
      const q = document.createElement('blockquote')
      const inner = document.createElement(blk.tagName === 'PRE' || blk.tagName === 'TABLE' ? 'p' : blk.tagName.toLowerCase())
      if (inner.tagName === 'LI') inner.tagName = 'p'
      if (blk.tagName === 'PRE' || blk.tagName === 'TABLE' || blk.tagName === 'DETAILS') inner.textContent = blk.innerText
      else inner.innerHTML = blk.innerHTML
      q.appendChild(inner)
      blk.replaceWith(q)
      break
    }
    case 'callout': {
      const d = document.createElement('div')
      d.className = 'md-callout md-callout-tip'
      const p = document.createElement('p')
      if (blk.tagName === 'PRE' || blk.tagName === 'TABLE' || blk.tagName === 'DETAILS') p.textContent = blk.innerText
      else p.innerHTML = blk.innerHTML
      d.appendChild(p)
      blk.replaceWith(d)
      break
    }
    case 'details': {
      const det = document.createElement('details')
      const sum = document.createElement('summary')
      sum.textContent = (blk.innerText.split('\n')[0] || '').trim().slice(0, 30) || '点击展开'
      const p = document.createElement('p')
      if (blk.tagName === 'PRE' || blk.tagName === 'TABLE' || blk.tagName === 'DETAILS') p.textContent = blk.innerText
      else p.innerHTML = blk.innerHTML
      det.appendChild(sum)
      det.appendChild(p)
      blk.replaceWith(det)
      break
    }
  }
  hideBlockHandle()
}

/** 块操作：删除/复制/剪切/缩进/减少缩进/下方添加 */
async function blockOp(cmd) {
  const blk = hoverBlockEl
  if (!blk) return
  switch (cmd) {
    case 'delete':
      blk.remove()
      break
    case 'copy':
    case 'cut': {
      try {
        await navigator.clipboard.writeText(blk.innerText)
        ElMessage.success(cmd === 'copy' ? '已复制该块' : '已剪切该块')
      } catch {
        ElMessage.warning('剪贴板不可用')
      }
      if (cmd === 'cut') blk.remove()
      break
    }
    case 'indent': {
      if (blk.tagName !== 'LI') break
      const prev = blk.previousElementSibling
      if (prev && prev.tagName === 'LI') {
        let sub = prev.querySelector(':scope > ul, :scope > ol')
        if (!sub) {
          sub = document.createElement(blk.parentElement.tagName.toLowerCase())
          prev.appendChild(sub)
        }
        sub.appendChild(blk)
      }
      break
    }
    case 'outdent': {
      if (blk.tagName !== 'LI') break
      const list = blk.parentElement
      const parentLi = list.parentElement?.closest('li')
      if (parentLi) {
        parentLi.after(blk)
        if (!list.children.length) list.remove()
      }
      break
    }
    case 'addBelow':
      blk.insertAdjacentHTML('afterend', '<p><br></p>')
      break
  }
  hideBlockHandle()
}


// ---- 保存状态文案 ----
const fmtTime = (d) => `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
const saveState = computed(() => {
  if (saving.value) return { cls: 'saving', text: '正在保存…' }
  if (lastSavedAt.value && !dirty.value) return { cls: 'saved', text: `已保存 ${fmtTime(lastSavedAt.value)}` }
  return { cls: 'dirty', text: '未保存' }
})

// ==================================================================

/**
 * 路由参数变化时要重新加载表单。
 * 这是「跨笔记覆盖」的根因修复：/notes/1 → /notes/2 命中的是同一个路由记录，
 * Vue 会**复用**本组件（不会重新 mount），所以 before 之前只写 onMounted 是加载不到 B 的
 * —— URL 已经是 B、表单还是 A，点保存就把 A 的内容提交到 B 的 id 上。
 */
watch(id, async (now, before) => {
  if (now === before) return
  // 自己「新建成功后跳转」引发的变化：本地表单就是刚保存的内容，不必再拉一次
  if (justCreatedId && String(now) === justCreatedId) {
    justCreatedId = null
    return
  }
  if (previewEditing.value) exitPreviewEdit()
  await loadNote()
})

/** 有未保存改动时离开，先问一句（避免误点返回丢内容） */
onBeforeRouteLeave(async () => {
  if (!dirty.value || saving.value) return true
  try {
    await ElMessageBox.confirm('当前笔记有未保存的改动，确定离开吗？', '未保存的改动', {
      type: 'warning',
      confirmButtonText: '放弃改动并离开',
      cancelButtonText: '留下',
    })
    return true
  } catch (e) {
    return false
  }
})

onMounted(async () => {
  window.addEventListener('lh-meta-changed', loadMeta)
  window.addEventListener('keydown', onGlobalKeydown)
  window.addEventListener('resize', measureLayout)
  await loadMeta()
  loadNote()
  await nextTick()
  measureLayout()
  // 宽屏默认展开大纲（有标题才会显示内容，空内容时大纲区自然为空态）
  if (layoutMode.value === 'wide' && /^#{1,3}\s/m.test(form.value.content || '')) {
    outlineOpen.value = true
  }
  // 源码区 ↔ 预览区 滚动联动：CodeMirror 滚动容器可能晚一拍出现，重试几帧
  for (let i = 0; i < 10 && !bindEditorScroll(); i++) {
    await new Promise((r) => requestAnimationFrame(r))
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('lh-meta-changed', loadMeta)
  window.removeEventListener('keydown', onGlobalKeydown)
  window.removeEventListener('resize', measureLayout)
  document.removeEventListener('keydown', onPreviewKeydown)
  const ed = editorScrollEl()
  if (ed) ed.removeEventListener('scroll', onEditorScroll)
  clearTimeout(scrollSyncTimer)
  if (scrollRaf) cancelAnimationFrame(scrollRaf)
  focusMode.value = false // 专注模式是页面级状态，离开必须复位，否则侧栏消失
})
</script>

<template>
  <div class="edit-page" :class="{ 'is-focus': focusMode, 'is-reading': readingMode }" v-loading="loading">
    <!-- ======== 顶部第一行：返回 · 标题 · 分类 · 标签 · 保存状态 · AI助手 · 更多 · 保存 ======== -->
    <div class="ed-top" v-if="!readingMode">
      <button class="icon-btn" type="button" title="返回列表" @click="onBack">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <path d="M19 12H5M11 18l-6-6 6-6" />
        </svg>
      </button>

      <input v-model="form.title" class="title-input" placeholder="输入笔记标题…" maxlength="200" />

      <el-select v-model="form.categoryId" placeholder="分类" clearable class="mini-select cat" size="default">
        <el-option v-for="c in categories" :key="c.id" :label="'　'.repeat(c.depth) + c.name" :value="c.id" />
      </el-select>
      <el-select v-model="form.tagIds" multiple placeholder="标签" clearable collapse-tags collapse-tags-tooltip class="mini-select tag" size="default">
        <el-option v-for="t in tags" :key="t.id" :label="t.name" :value="t.id" />
      </el-select>

      <span class="flex-spacer"></span>

      <span class="save-state" :class="saveState.cls" :title="saveState.text">
        <i class="ss-dot"></i>{{ saveState.text }}
      </span>

      <el-dropdown trigger="click" @command="aiCommand">
        <button class="ai-trigger" type="button" :disabled="aiBusy">
          <span class="ai-spark">
            <svg viewBox="0 0 24 24"><path d="M12 3.8l1.85 4.55L18.4 10.2l-4.55 1.85L12 16.6l-1.85-4.55L5.6 10.2l4.55-1.85z" /></svg>
          </span>{{ aiBusy ? 'AI 处理中…' : 'AI 助手' }}
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="polish" :disabled="aiBusy">
              <span class="dd-ico"><svg viewBox="0 0 24 24"><path d="M12 3.8l1.85 4.55L18.4 10.2l-4.55 1.85L12 16.6l-1.85-4.55L5.6 10.2l4.55-1.85z" /><path d="M18.6 16.2l.7 1.7 1.7.7-1.7.7-.7 1.7-.7-1.7-1.7-.7 1.7-.7z" /></svg></span>AI 润色
            </el-dropdown-item>
            <el-dropdown-item command="format" :disabled="aiBusy">
              <span class="dd-ico"><svg viewBox="0 0 24 24"><path d="M5 19.5 14.6 9.9M13.2 8.5l3 3" /><path d="M18.4 3.4l.62 1.58 1.58.62-1.58.62-.62 1.58-.62-1.58-1.58-.62 1.58-.62z" /></svg></span>整理格式
            </el-dropdown-item>
            <el-dropdown-item command="chat" divided>
              <span class="dd-ico"><svg viewBox="0 0 24 24"><path d="M5 5.5h14a1 1 0 0 1 1 1v8.5a1 1 0 0 1-1 1h-7l-3.5 3v-3H5a1 1 0 0 1-1-1V6.5a1 1 0 0 1 1-1Z" /></svg></span>打开 AI 对话
            </el-dropdown-item>
            <el-dropdown-item command="__edit" divided>
              <span class="dd-ico">
                <svg v-if="previewEditing" viewBox="0 0 24 24"><path d="M5 12.5 10 17.5 19 7" /></svg>
                <svg v-else viewBox="0 0 24 24"><path d="M4 20h4L18.5 9.5l-4-4L4 16v4Z" /><path d="M13.5 6.5l4 4" /></svg>
              </span>{{ previewEditing ? '退出预览编辑' : '预览编辑（所见即所得）' }}
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>

      <el-dropdown trigger="click" @command="moreCommand">
        <button class="icon-btn" type="button" title="更多">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
            <circle cx="5" cy="12" r="0.6" /><circle cx="12" cy="12" r="0.6" /><circle cx="19" cy="12" r="0.6" />
          </svg>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-if="!isNew" command="() => exportNote('md')">
              <span class="dd-ico"><svg viewBox="0 0 24 24"><path d="M12 4v11M7.5 10.5 12 15l4.5-4.5M5 19h14" /></svg></span>导出 Markdown (.md)
            </el-dropdown-item>
            <el-dropdown-item v-if="!isNew" command="() => exportNote('html')">
              <span class="dd-ico"><svg viewBox="0 0 24 24"><path d="M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18Z" /><path d="M3.4 12h17.2M12 3c2.35 2.55 3.55 5.55 3.55 9S14.35 18.45 12 21c-2.35-2.55-3.55-5.55-3.55-9S9.65 5.55 12 3Z" /></svg></span>导出网页 HTML
            </el-dropdown-item>
            <el-dropdown-item command="toggleOutline" divided>
              <span class="dd-ico"><svg viewBox="0 0 24 24"><path d="M5 6h14M5 12h9M5 18h12" /></svg></span>{{ outlineOpen ? '收起大纲' : '展开大纲' }}
            </el-dropdown-item>
            <el-dropdown-item command="toggleFocus">
              <span class="dd-ico">
                <svg v-if="focusMode" viewBox="0 0 24 24"><path d="M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5" /></svg>
                <svg v-else viewBox="0 0 24 24"><path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5" /></svg>
              </span>{{ focusMode ? '退出专注模式' : '专注模式（隐藏侧栏与大纲）' }}
            </el-dropdown-item>
            <el-dropdown-item command="toggleReading">
              <span class="dd-ico"><svg viewBox="0 0 24 24"><path d="M5 4.5h5.5a2 2 0 0 1 2 2v13a2 2 0 0 0-2-2H5zM19 4.5h-5.5a2 2 0 0 0-2 2v13a2 2 0 0 1 2-2H19z" /></svg></span>阅读模式（只看正文）
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>

      <el-button type="primary" class="save-btn" :loading="saving" @click="save">{{ isNew ? '创建' : '保存' }}</el-button>
    </div>

    <!-- 阅读模式下的极简顶栏 -->
    <div class="read-top" v-if="readingMode">
      <button class="icon-btn" type="button" @click="onBack" title="返回列表">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M19 12H5M11 18l-6-6 6-6" /></svg>
      </button>
      <span class="read-title">{{ form.title || '无标题' }}</span>
      <button class="read-exit" type="button" @click="toggleReading">
        <span class="btn-ico"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6 6 18" /></svg></span>退出阅读
      </button>
    </div>

    <!-- ======== 顶部第二行：Markdown + 富文本工具（阅读模式隐藏；预览编辑时保留，作用于预览区） ======== -->
    <div class="ed-tools" v-if="!readingMode" @mousedown.prevent>
      <template v-if="layoutMode !== 'tab' || editorTab === 'edit' || previewEditing">
        <button class="tb tb-txt" type="button" title="加粗" @click="mdTool('bold')"><b>B</b></button>
        <button class="tb tb-txt" type="button" title="斜体" @click="mdTool('italic')"><i>I</i></button>
        <button class="tb tb-txt" type="button" title="删除线" @click="mdTool('strike')"><s>S</s></button>
        <button class="tb tb-h" type="button" title="二级标题" @click="mdTool('h2')">H2</button>
        <button class="tb tb-h" type="button" title="三级标题" @click="mdTool('h3')">H3</button>
        <i class="tb-sep" />
        <button class="tb" type="button" title="引用" @click="mdTool('quote')">
          <svg viewBox="0 0 24 24"><path d="M9.5 7.5c-2.6.6-4 2.3-4 5v4h5v-5h-3c0-1.6.7-2.7 2-3.2Zm9 0c-2.6.6-4 2.3-4 5v4h5v-5h-3c0-1.6.7-2.7 2-3.2Z" /></svg>
        </button>
        <button class="tb" type="button" title="无序列表" @click="mdTool('ul')">
          <svg viewBox="0 0 24 24"><circle cx="5" cy="6.5" r="1.1" class="fill" /><circle cx="5" cy="12" r="1.1" class="fill" /><circle cx="5" cy="17.5" r="1.1" class="fill" /><path d="M9.5 6.5h10M9.5 12h10M9.5 17.5h10" /></svg>
        </button>
        <button class="tb" type="button" title="有序列表" @click="mdTool('ol')">
          <svg viewBox="0 0 24 24"><path d="M9.5 6.5h10M9.5 12h10M9.5 17.5h10" /><path d="M4 5.2 5.2 4.5V8M3.8 10.7c.2-.5.8-.8 1.3-.6.6.2.9.8.6 1.3l-1.9 2.4h2.4M3.9 16.5h1.3c.5 0 .9.4.9.9s-.4.8-.9.8H4.7c.5 0 .9.4.9.8 0 .5-.4.9-.9.9H3.9" /></svg>
        </button>
        <i class="tb-sep" />
        <button class="tb" type="button" title="行内代码" @click="mdTool('inlineCode')">
          <svg viewBox="0 0 24 24"><path d="m9 8.5-3.5 3.5L9 15.5M15 8.5l3.5 3.5L15 15.5" /></svg>
        </button>
        <button class="tb" type="button" title="代码块" @click="mdTool('codeBlock')">
          <svg viewBox="0 0 24 24"><rect x="4" y="5" width="16" height="14" rx="2" /><path d="m9 10-1.8 2L9 14M15 10l1.8 2L15 14" /></svg>
        </button>
        <button class="tb" type="button" title="链接" @click="mdTool('link')">
          <svg viewBox="0 0 24 24"><path d="M10.5 13.5a3.5 3.5 0 0 0 5 0l3-3a3.5 3.5 0 1 0-5-5l-1.2 1.2M13.5 10.5a3.5 3.5 0 0 0-5 0l-3 3a3.5 3.5 0 1 0 5 5l1.2-1.2" /></svg>
        </button>
        <button class="tb" type="button" title="图片" @click="mdTool('image')">
          <svg viewBox="0 0 24 24"><rect x="4" y="5" width="16" height="14" rx="2" /><circle cx="9" cy="10" r="1.4" /><path d="m5.5 17.5 4.5-4.5 3 3 2.5-2.5 3 3" /></svg>
        </button>
        <button class="tb" type="button" title="表格" @click="mdTool('table')">
          <svg viewBox="0 0 24 24"><rect x="4" y="5" width="16" height="14" rx="2" /><path d="M4 10.2h16M4 14.6h16M10 5v14M15.2 5v14" /></svg>
        </button>

        <!-- 富文本格式（颜色/字号/上下标等，收在同一个工具行里） -->
        <FormatBar bare class="ed-format" @apply="applyFormat" />
      </template>
    </div>

    <!-- Tab 模式（窄屏）：编辑 / 预览 切换 -->
    <div class="ed-tabs" v-if="layoutMode === 'tab' && !readingMode && !previewEditing">
      <button :class="{ on: editorTab === 'edit' }" @click="editorTab = 'edit'">编辑</button>
      <button :class="{ on: editorTab === 'preview' }" @click="editorTab = 'preview'">预览</button>
    </div>

    <!-- 预览编辑模式操作条 -->
    <div v-if="previewEditing" class="preview-edit-bar">
      <span class="pe-tip">
        <span class="btn-ico"><svg viewBox="0 0 24 24"><path d="M4 20h4L18.5 9.5l-4-4L4 16v4Z" /><path d="M13.5 6.5l4 4" /></svg></span>预览编辑中：直接改右侧内容，上方工具条可加粗/标题/颜色等格式（Ctrl/⌘+S 同步并退出；Esc 也是「先同步再退出」）
      </span>
      <el-button size="small" @click="abandonPreviewEdit">
        <span class="btn-ico"><svg viewBox="0 0 24 24"><path d="M4.5 9.5h9a5 5 0 0 1 0 10H8M4.5 9.5 8 6M4.5 9.5 8 13" /></svg></span>放弃改动
      </el-button>
      <el-button size="small" type="primary" @click="syncPreviewToSource()">
        <span class="btn-ico"><svg viewBox="0 0 24 24"><path d="M5 12.5 10 17.5 19 7" /></svg></span>同步到源码
      </el-button>
    </div>

    <!-- ======== 三栏主体 ======== -->
    <div
      ref="editorWrapRef"
      class="editor-wrap"
      :class="{ 'is-preview-editing': previewEditing }"
      @paste.capture="onPasteCapture"
    >
      <!-- 源码栏 -->
      <section v-show="showEditorPane" class="pane pane-editor" :style="editorStyle">
        <MdEditor
          ref="editorRef"
          v-model="form.content"
          :placeholder="'支持 Markdown：代码块、表格、链接…\n# 一级标题\n```java\n// 代码示例\n```'"
          :preview="false"
          :toolbars="[]"
          :footers="['markdownTotal', 'scrollSwitch']"
          language="zh-CN"
          class="src-editor"
        />
      </section>

      <!-- 拖拽分隔条 -->
      <div
        v-show="showEditorPane && showPreviewPane && layoutMode !== 'tab'"
        class="gutter"
        :class="{ dragging }"
        @mousedown="startDrag"
      ><i></i></div>

      <!-- 预览栏 -->
      <section v-show="showPreviewPane" class="pane pane-preview">
        <div ref="pvScrollRef" class="pv-scroll" @scroll="onPreviewScroll">
          <div class="pv-inner">
            <MdPreview
              :modelValue="form.content"
              :theme="isDark ? 'dark' : 'light'"
              previewTheme="github"
              class="pv-md"
            />
          </div>

          <!-- 预览编辑：块操作手柄（语雀式 ⋮⋮） -->
          <div
            v-if="previewEditing && blockHandle.visible"
            class="block-handle"
            :style="{ top: blockHandle.y + 'px', left: blockHandle.x + 'px' }"
            @mouseenter="onHandleEnter"
            @mouseleave="onHandleLeave"
          >
            <button class="bh-btn" type="button" title="块操作" @mousedown.prevent @click.stop="toggleBlockMenu">
              <svg viewBox="0 0 24 24"><circle cx="9" cy="6" r="1.3" class="fill" /><circle cx="15" cy="6" r="1.3" class="fill" /><circle cx="9" cy="12" r="1.3" class="fill" /><circle cx="15" cy="12" r="1.3" class="fill" /><circle cx="9" cy="18" r="1.3" class="fill" /><circle cx="15" cy="18" r="1.3" class="fill" /></svg>
            </button>
            <div v-if="blockHandle.menuOpen" class="block-menu" :class="{ up: blockHandle.menuUp }" @mousedown.prevent @click.stop>
              <div class="bm-item" @click="blockHandle.convOpen = !blockHandle.convOpen">
                <svg viewBox="0 0 24 24"><path d="M4 8h11M15 8l-2.5-2.5M15 8l-2.5 2.5M20 16H9M9 16l2.5-2.5M9 16l2.5 2.5" /></svg>
                转化为<span class="bm-arrow">›</span>
              </div>
              <div class="bm-item" @click="blockOp('delete')">
                <svg viewBox="0 0 24 24"><path d="M5 7h14M10 7V5h4v2M8 7l.7 12h6.6L16 7" /></svg>
                删除
              </div>
              <div class="bm-item" @click="blockOp('copy')">
                <svg viewBox="0 0 24 24"><rect x="9" y="9" width="11" height="11" rx="2" /><path d="M5 15V6a2 2 0 0 1 2-2h9" /></svg>
                复制
              </div>
              <div class="bm-item" @click="blockOp('cut')">
                <svg viewBox="0 0 24 24"><circle cx="7" cy="7" r="2.2" /><circle cx="7" cy="17" r="2.2" /><path d="M8.8 8.8 20 19M8.8 15.2 20 5" /></svg>
                剪切
              </div>
              <div class="bm-item" :class="{ disabled: !blockHandle.canIndent }" @click="blockHandle.canIndent && blockOp('indent')">
                <svg viewBox="0 0 24 24"><path d="M4 6h16M10 11h10M10 16h10M4 21h16M4.5 9.5 7 12l-2.5 2.5" /></svg>
                缩进
              </div>
              <div class="bm-item" :class="{ disabled: !blockHandle.canOutdent }" @click="blockHandle.canOutdent && blockOp('outdent')">
                <svg viewBox="0 0 24 24"><path d="M4 6h16M10 11h10M10 16h10M4 21h16M7 9.5 4.5 12 7 14.5" /></svg>
                减少缩进
              </div>
              <div class="bm-item" @click="blockOp('addBelow')">
                <svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14" /></svg>
                在下方添加段落
              </div>

              <!-- 「转化为」副面板 -->
              <div v-if="blockHandle.convOpen" class="bm-conv">
                <button
                  v-for="c in CONV_ITEMS"
                  :key="c.key"
                  class="bm-conv-item"
                  type="button"
                  @click="blockConvert(c.key)"
                >
                  <span v-if="c.txt" class="bci bci-txt">{{ c.txt }}</span>
                  <svg v-else class="bci" viewBox="0 0 24 24" v-html="c.icon"></svg>
                  {{ c.label }}
                </button>
              </div>
            </div>
          </div>
        </div>
      </section>

      <!-- 大纲栏 -->
      <aside v-show="outlineOpen && layoutMode === 'wide'" class="pane pane-outline">
        <div class="ol-head">
          <span>大纲</span>
          <button class="icon-btn sm" type="button" title="收起大纲" @click="outlineOpen = false">
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M9 6l6 6-6 6" /></svg>
          </button>
        </div>
        <div class="ol-list">
          <div
            v-for="(h, i) in outline"
            :key="i"
            class="ol-item"
            :class="[`lv${h.level}`, { active: i === activeIdx }]"
            :title="h.text"
            @click="scrollToHeading(i)"
          >{{ h.text }}</div>
          <div v-if="!outline.length" class="ol-empty">正文里写个标题（# 开头）<br />就会出现在这里</div>
        </div>
      </aside>

      <!-- 大纲收起后的展开浮标 -->
      <button
        v-if="!outlineOpen && layoutMode === 'wide' && outline.length"
        class="outline-fab"
        type="button"
        title="展开大纲"
        @click="outlineOpen = true"
      >
        <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M15 6l-6 6 6 6" /></svg>
        大纲
      </button>
    </div>

    <!-- AI 处理结果预览：确认后再替换正文 -->
    <el-dialog v-model="aiDialog" :title="`${aiLabel}结果预览`" width="760px" top="6vh" destroy-on-close>
      <div class="ai-preview">
        <MdPreview :modelValue="fixHtmlQuotes(aiResult) || '*空内容*'" :theme="isDark ? 'dark' : 'light'" previewTheme="github" />
      </div>
      <template #footer>
        <el-button @click="aiDialog = false">取消</el-button>
        <el-button type="primary" @click="aiApply">替换正文</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 页面即卡片：满幅、无边框，像文档应用而不是后台系统 */
.edit-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 10px 14px 12px;
  gap: 0;
  background: var(--app-bg);
}

/* ================= 顶部第一行 ================= */
.ed-top {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 2px 8px;
  /* 窄屏兜底：顶行内容优先保全，放不下时横向滚动而不是截断 */
  overflow-x: auto;
  scrollbar-width: none;
}
.ed-top::-webkit-scrollbar {
  display: none;
}

.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--app-text-2);
  cursor: pointer;
  transition: background-color var(--dur-fast) ease, color var(--dur-fast) ease;
}
.icon-btn:hover {
  background: color-mix(in srgb, var(--app-text-1) 6%, transparent);
  color: var(--app-text-1);
}
.icon-btn.sm {
  width: 24px;
  height: 24px;
}

/* 标题输入：无边框大字，像文档标题而不是表单控件 */
.title-input {
  flex: 0 1 340px;
  min-width: 140px;
  height: 34px;
  padding: 0 10px;
  font-size: 16px;
  font-weight: 650;
  letter-spacing: -0.01em;
  color: var(--app-text-1);
  background: transparent;
  border: 1px solid transparent;
  border-radius: 8px;
  outline: none;
  font-family: inherit;
  transition: border-color var(--dur-fast) ease, background-color var(--dur-fast) ease;
}
.title-input::placeholder {
  color: var(--app-text-3);
  font-weight: 400;
}
.title-input:hover {
  border-color: var(--app-border);
}
.title-input:focus {
  border-color: var(--app-brand);
  background: var(--app-card);
}

.mini-select {
  width: 118px;
  flex-shrink: 0;
}
.mini-select.tag {
  width: 150px;
}

.flex-spacer {
  flex: 1;
}

/* 保存状态：安静的圆点 + 文案，绿/灰/琥珀三态 */
.save-state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: var(--app-text-3);
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}
.ss-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--app-text-3);
}
.save-state.dirty .ss-dot {
  background: #e6a23c;
}
.save-state.dirty {
  color: #b88230;
}
.save-state.saving {
  color: var(--app-text-2);
}
.save-state.saving .ss-dot {
  background: var(--app-brand);
  animation: ss-pulse 1s ease-in-out infinite;
}
.save-state.saved .ss-dot {
  background: var(--app-brand);
}
.save-state.saved {
  color: var(--app-brand-deep);
}
@keyframes ss-pulse {
  50% { opacity: 0.35; }
}

/* AI 助手触发钮：文字钮 + 微品牌感 */
.ai-trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 12px;
  font-size: 13px;
  font-family: inherit;
  color: var(--app-text-1);
  background: var(--app-card);
  border: 1px solid var(--app-border);
  border-radius: 8px;
  cursor: pointer;
  transition: border-color var(--dur-fast) ease, color var(--dur-fast) ease, box-shadow var(--dur-fast) ease;
  white-space: nowrap;
}
.ai-trigger:hover {
  border-color: color-mix(in srgb, var(--app-brand) 45%, var(--app-border));
  color: var(--app-brand-deep);
  box-shadow: var(--shadow-sm);
}
.ai-trigger:disabled {
  opacity: 0.6;
  cursor: default;
}
.ai-spark {
  font-size: 12px;
}

.save-btn {
  height: 32px;
  padding: 0 18px;
  border-radius: 8px;
}

/* ================= 第二行工具条 ================= */
.ed-tools {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 2px 2px 6px;
  overflow-x: auto;
  scrollbar-width: none;
}
.ed-tools::-webkit-scrollbar {
  display: none;
}
.tb {
  min-width: 28px;
  height: 28px;
  padding: 0 6px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  font-size: 13px;
  font-family: inherit;
  color: var(--app-text-2);
  background: transparent;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  white-space: nowrap;
  transition: background-color var(--dur-fast) ease, color var(--dur-fast) ease;
}
.tb svg {
  width: 16px;
  height: 16px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.tb svg .fill {
  fill: currentColor;
  stroke: none;
}
/* 字母类按钮（B/I/S/H2/H3）走文字，与图标按钮同视觉重量 */
.tb-txt b {
  font-size: 13.5px;
}
.tb-txt i {
  font-style: italic;
  font-family: Georgia, serif;
  font-size: 14px;
}
.tb-txt s {
  text-decoration-thickness: 1.2px;
}
.tb:hover {
  background: color-mix(in srgb, var(--app-text-1) 7%, transparent);
  color: var(--app-text-1);
}
.tb:active {
  background: color-mix(in srgb, var(--app-text-1) 11%, transparent);
}
.tb-h {
  font-size: 12px;
  font-weight: 700;
}
.tb code {
  font-family: ui-monospace, Consolas, monospace;
}
.tb-sep {
  width: 1px;
  height: 16px;
  background: var(--app-border);
  margin: 0 5px;
  flex-shrink: 0;
}
.ed-format {
  flex: none;
}

/* Tab 模式切换 */
.ed-tabs {
  display: inline-flex;
  gap: 2px;
  padding: 0 2px 8px;
}
.ed-tabs button {
  height: 30px;
  padding: 0 14px;
  font-size: 13px;
  font-family: inherit;
  color: var(--app-text-2);
  background: transparent;
  border: none;
  border-radius: 7px;
  cursor: pointer;
}
.ed-tabs button.on {
  background: var(--app-card);
  color: var(--app-text-1);
  font-weight: 600;
  box-shadow: var(--shadow-sm);
}

/* ================= 三栏主体 ================= */
.editor-wrap {
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: stretch;
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid var(--app-border);
  background: var(--app-card);
}

.pane {
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

/* 源码栏：极浅灰底，与白色预览形成「工作区 / 成品」的层次 */
.pane-editor {
  background: #fafbfb;
  flex: 1 1 auto;
}
.pane-editor :deep(.md-editor) {
  flex: 1;
  min-height: 0;
  height: auto;
  --md-bk-color: #fafbfb;
  box-shadow: none;
}
html.dark .pane-editor {
  background: var(--app-card);
}
html.dark .pane-editor :deep(.md-editor) {
  --md-bk-color: var(--app-card);
}
/* md-editor 自带工具栏已由页内第二行取代 */
.pane-editor :deep(.md-editor-toolbar-wrapper),
.pane-editor :deep(.md-editor-tabview) {
  display: none;
}

/* 拖拽分隔条：hover / 拖动中显形 */
.gutter {
  flex: 0 0 5px;
  cursor: col-resize;
  display: flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  transition: background-color var(--dur-fast) ease;
  z-index: 2;
}
.gutter i {
  width: 1px;
  height: 100%;
  background: var(--app-border);
  transition: background-color var(--dur-fast) ease, width var(--dur-fast) ease;
}
.gutter:hover i,
.gutter.dragging i {
  width: 2px;
  background: var(--app-brand);
}
:global(body.is-col-resizing) {
  cursor: col-resizing;
  user-select: none;
}

/* 预览栏：白纸 */
.pane-preview {
  flex: 1 1 0;
  background: var(--app-card);
}
.pv-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  scroll-behavior: auto;
}
/* 正文阅读宽度：居中 790px，长文阅读的黄金区 */
.pv-inner {
  max-width: 790px;
  margin: 0 auto;
  padding: 26px 34px 48px;
}
/* 预览编辑：编辑优先，放开阅读限宽占满预览栏，大屏不再两侧大片留白 */
.editor-wrap.is-preview-editing .pv-inner {
  max-width: 100%;
  padding: 28px 34px 56px;
}

/* ---- 预览编辑：块操作手柄（语雀式 ⋮⋮） ---- */
/* 手柄/菜单用 .pv-scroll 内容坐标绝对定位（relative 后随滚动自然跟随，无需监听重算） */
.pv-scroll {
  position: relative;
}
/* hover 块的浅色高亮（双写类名压过 .md-editor-preview 的背景声明） */
.pane-preview :deep(.lh-block-hover.lh-block-hover) {
  background: color-mix(in srgb, var(--app-brand) 6%, transparent);
  border-radius: 6px;
  transition: background var(--dur-fast) var(--ease);
}
.block-handle {
  position: absolute;
  z-index: 30;
}
.bh-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  border: 1px solid var(--app-border);
  border-radius: 6px;
  background: var(--app-card);
  color: var(--app-text-3);
  cursor: grab;
  box-shadow: var(--shadow-sm);
  transition: color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}
.bh-btn:hover {
  color: var(--app-text-1);
  background: color-mix(in srgb, var(--app-text-1) 5%, var(--app-card));
}
.bh-btn svg {
  width: 14px;
  height: 14px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
}
.bh-btn svg .fill {
  fill: currentColor;
  stroke: none;
}
.block-menu {
  position: absolute;
  top: 27px;
  left: 0;
  min-width: 172px;
  padding: 5px;
  background: var(--app-card);
  border: 1px solid var(--app-border);
  border-radius: 10px;
  box-shadow: var(--shadow-md);
  z-index: 31;
}
.block-menu.up {
  top: auto;
  bottom: 27px;
}
.bm-item {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 7px 10px;
  font-size: 13px;
  color: var(--app-text-1);
  border-radius: 6px;
  cursor: pointer;
  user-select: none;
  transition: background var(--dur-fast) var(--ease);
}
.bm-item:hover {
  background: var(--app-brand-soft);
}
.bm-item.disabled {
  opacity: 0.42;
  cursor: not-allowed;
}
.bm-item.disabled:hover {
  background: transparent;
}
.bm-item svg {
  width: 15px;
  height: 15px;
  flex: none;
  fill: none;
  stroke: var(--app-text-2);
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.bm-arrow {
  margin-left: auto;
  color: var(--app-text-3);
  font-size: 13px;
}
/* 「转化为」副面板：语雀同款网格，贴在主菜单右侧 */
.bm-conv {
  position: absolute;
  left: calc(100% + 8px);
  top: -1px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 2px;
  width: 264px;
  padding: 5px;
  background: var(--app-card);
  border: 1px solid var(--app-border);
  border-radius: 10px;
  box-shadow: var(--shadow-md);
}
.bm-conv-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 9px;
  font-size: 13px;
  color: var(--app-text-1);
  background: none;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  text-align: left;
  transition: background var(--dur-fast) var(--ease);
}
.bm-conv-item:hover {
  background: var(--app-brand-soft);
}
.bci {
  width: 16px;
  height: 16px;
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--app-text-2);
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.bci .fill {
  fill: currentColor;
  stroke: none;
}
.bci-txt {
  font-size: 12px;
  font-weight: 600;
  letter-spacing: -0.2px;
}
/* 副面板向上展开时贴主菜单底部对齐 */
.block-menu.up .bm-conv {
  top: auto;
  bottom: -1px;
}

.pane-preview :deep(.md-editor-preview.md-editor-preview) {
  background: transparent;
  line-height: 1.75;
}
/* 章节纵向间距加大：H2 是「换章」的呼吸点 */
.pane-preview :deep(.md-editor-preview.md-editor-preview h1) {
  margin: 1.7em 0 0.8em;
}
.pane-preview :deep(.md-editor-preview.md-editor-preview h2) {
  margin: 1.8em 0 0.8em;
}
.pane-preview :deep(.md-editor-preview.md-editor-preview h3) {
  margin: 1.5em 0 0.7em;
}
.pane-preview :deep(.md-editor-preview.md-editor-preview > *:first-child) {
  margin-top: 0;
}

/* 预览区可编辑：中性「编辑画布」——无品牌色，靠留白与圆角区分；
   内边距让内容与边框有呼吸（padding 是纯视觉，不影响 innerHTML 反推）。
   注意 .md-editor-preview.md-editor-preview 已声明 background:transparent（更高特异性），
   故这里双写 .lh-preview-editing 类名压回去。 */
.pane-preview :deep(.md-editor-preview.lh-preview-editing.lh-preview-editing) {
  outline: none;
  border: 1px solid var(--app-border);
  background: color-mix(in srgb, var(--app-text-1) 2.5%, var(--app-card));
  border-radius: 12px;
  padding: 24px 30px 32px;
  cursor: text;
  transition: border-color var(--dur-fast) ease, box-shadow var(--dur-fast) ease;
}
.pane-preview :deep(.md-editor-preview.lh-preview-editing.lh-preview-editing:hover) {
  border-color: color-mix(in srgb, var(--app-text-1) 24%, var(--app-border));
}
.pane-preview :deep(.md-editor-preview.lh-preview-editing.lh-preview-editing:focus) {
  border-color: color-mix(in srgb, var(--app-text-1) 42%, var(--app-border));
  box-shadow: var(--shadow-sm);
}

/* ================= 大纲栏 ================= */
.pane-outline {
  flex: 0 0 232px;
  border-left: 1px solid var(--app-border-weak);
  background: var(--app-card);
}
.ol-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 10px 6px 14px;
  font-size: 12.5px;
  font-weight: 600;
  letter-spacing: 0.03em;
  color: var(--app-text-3);
}
.ol-list {
  flex: 1;
  overflow-y: auto;
  padding: 2px 8px 14px;
}
.ol-item {
  padding: 5px 8px;
  font-size: 12.8px;
  line-height: 1.5;
  color: var(--app-text-2);
  border-radius: 6px;
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: background-color var(--dur-fast) ease, color var(--dur-fast) ease;
}
.ol-item:hover {
  background: color-mix(in srgb, var(--app-text-1) 5%, transparent);
  color: var(--app-text-1);
}
.ol-item.lv1 {
  font-weight: 600;
  color: var(--app-text-1);
  padding-left: 8px;
}
.ol-item.lv2 {
  padding-left: 22px;
}
.ol-item.lv3 {
  padding-left: 36px;
  font-size: 12.3px;
}
.ol-item.active {
  background: var(--app-brand-soft);
  color: var(--app-brand-deep);
  font-weight: 600;
}
html.dark .ol-item.active {
  color: var(--app-brand);
}
.ol-empty {
  padding: 18px 12px;
  font-size: 12px;
  line-height: 1.8;
  color: var(--app-text-3);
}

.outline-fab {
  position: absolute;
  right: 14px;
  top: 12px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 28px;
  padding: 0 10px;
  font-size: 12px;
  font-family: inherit;
  color: var(--app-text-3);
  background: var(--app-card);
  border: 1px solid var(--app-border);
  border-radius: 99px;
  cursor: pointer;
  box-shadow: var(--shadow-sm);
  transition: color var(--dur-fast) ease, border-color var(--dur-fast) ease;
  z-index: 3;
}
.outline-fab:hover {
  color: var(--app-brand-deep);
  border-color: color-mix(in srgb, var(--app-brand) 40%, var(--app-border));
}
.editor-wrap {
  position: relative;
}

/* ================= 预览编辑操作条 / 阅读模式 ================= */
.preview-edit-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 6px 12px;
  background: var(--app-card);
  border: 1px solid var(--app-border);
  border-radius: 8px;
  margin-bottom: 8px;
}
.pe-tip {
  flex: 1 1 260px;
  font-size: 12.5px;
  color: var(--app-text-2);
}

.read-top {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 4px 2px 10px;
}
.read-title {
  flex: 1;
  font-size: 16px;
  font-weight: 650;
  color: var(--app-text-1);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.read-exit {
  display: inline-flex;
  align-items: center;
  height: 30px;
  padding: 0 12px;
  font-size: 12.5px;
  font-family: inherit;
  color: var(--app-text-2);
  background: var(--app-card);
  border: 1px solid var(--app-border);
  border-radius: 99px;
  cursor: pointer;
}
.read-exit:hover {
  color: var(--app-brand-deep);
  border-color: color-mix(in srgb, var(--app-brand) 40%, var(--app-border));
}

/* 专注模式：编辑区整体浮起来一点，四周留白加大 */
.edit-page.is-focus {
  padding: 14px 26px 18px;
}

.ai-preview {
  border: 1px solid var(--app-border);
  border-radius: 10px;
  padding: 12px 16px;
  max-height: 62vh;
  overflow: auto;
  background: var(--app-bg);
}
.ai-preview :deep(.md-editor-preview) {
  background: transparent;
}
</style>
