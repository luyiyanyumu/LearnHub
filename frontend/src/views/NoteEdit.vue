<script setup>
import { computed, h, nextTick, render, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MdEditor, MdPreview } from 'md-editor-v3'
import MarkdownIt from 'markdown-it'
// 编辑器全局初始化 + 样式（原来在 main.js，为了不占首屏挪到这里；
// 本页是路由懒加载的，静态 import 不会影响首屏）
import '../utils/mdEditorSetup'
import { aiApi, categoryApi, tagApi, noteApi, saveBlob } from '../api'
import { fixHtmlQuotes } from '../utils/htmlQuotes'
import { stripLeadingDocTitle } from '../utils/mdTitle'
import {
  AGENT_NOTE_CONTEXT_EVENT,
  AGENT_NOTE_MERGE_EVENT,
  AGENT_NOTE_MERGE_RESULT_EVENT,
} from '../utils/agentNoteMerge'
import { isDark } from '../composables/useTheme'
import { focusMode } from '../composables/useViewMode'
import { FORMAT_PRESETS, stripInline } from '../utils/richFormat'
import { findUnsupported, previewHtmlToMd } from '../utils/htmlToMd'
import FormatBar from '../components/FormatBar.vue'
import CodeBlockEditor from '../components/CodeBlockEditor.vue'
import BlockPreview from '../components/BlockPreview.vue'
import { findTable, addRow, deleteRow, addCol, deleteCol, setHeaderRow, alignColumn, deleteTable } from '../utils/mdTable'
import { ensureColgroup, findColgroup } from '../utils/tableResize'
import { boundScrollAnchors, mapScrollPosition, normalizeScrollAnchors } from '../utils/scrollAnchors'
import { createSourceInsert } from '../utils/sourceToolbar'
import { AGENT_NOTE_UPDATED_EVENT, matchesOpenNote } from '../utils/agentNoteEdit'

const route = useRoute()
const router = useRouter()

const id = computed(() => route.params.id)
const isNew = computed(() => !id.value)
const loading = ref(false)
const saving = ref(false)
const editorRef = ref(null)
const blockPreviewRef = ref(null)
const editorWrapRef = ref(null)
const categories = ref([])
const tags = ref([])
const form = ref({ title: '', content: '', categoryId: undefined, tagIds: [] })
const titleInputRef = ref(null)
// Tool popovers can take focus; retain the editor that last held the caret.
let toolbarSide = 'preview'

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
const savedSnapshot = ref('')
const agentEditConflict = ref(false)
/**
 * 「有未保存改动」= 表单与上次保存的指纹不同，**或者**预览里还有没反推回源码的改动。
 * 后半句是单栏阅读模式（默认模式）的安全网：那一栏里敲的字先落在 DOM 上、失焦才反推回 Markdown，
 * 只看指纹的话用户敲完还没离开正文时状态仍是「已保存」，点返回不会被拦，改动会静默丢掉。
 */
const dirty = computed(() => previewUnsynced.value || formFingerprint() !== savedSnapshot.value)

/** 刚创建并跳转过来的笔记 id：用来跳过随之而来的那次重复加载 */
let justCreatedId = null

/**
 * 新建笔记的骨架模板：把《美化规范》直接摆成「可填空」的形式。
 * 内容与 skills/markdown-beautify/REFERENCE.md 第 4 节样例保持一致，改这里要同步那份参考。
 * 注意 FENCE 不能直接写成三个反引号 —— 模板本身是 JS 模板字符串，反引号会提前闭合。
 */
const FENCE = '```'
const NEW_NOTE_TEMPLATE = `## 一、核心概念

先给结论，再给依据。关键术语用 **加粗**，命令与字段名用 \`行内代码\`，风险点用 <font style="color: rgb(245, 34, 45)">红色</font> 标出。

:::tip
小技巧放这里 —— 一个提示块只讲一件事，收尾的 ::: 不能漏。
:::

### 1. 示例代码

${FENCE}java
// 代码块必须写语言名，否则不高亮
public class Hello {
    public static void main(String[] args) {
        System.out.println("Hello");
    }
}
${FENCE}

### 2. 对比与结论

| 对比项 | 方案 A | 方案 B |
| --- | :---: | :---: |
| 复杂度 | 低 | 高 |
| 适用场景 | 简单流程 | 高并发 |

<details>
<summary>延伸阅读与推导过程（点击展开）</summary>

展开内容按正常 Markdown 写，注意前后各留一个空行，否则不会被解析。

</details>

- [x] 已完成
- [ ] 待办

---

<font style="font-size: 12px">注：脚注与补充说明用 12px 小字；正文不要设字号。</font>
`

// ---- 格式条（RGB 颜色 / 语雀风格内联格式）----
/** 把选中文字包进对应标签；md-editor 的 insert() 会自动保留撤销历史 */
function applyFormat({ kind, value }) {
  return captureToolbarContext().applyFormat({ kind, value })
}

function applySourceFormat({ kind, value }) {
  if (['details', 'callout'].includes(kind)) return runSourceTool(kind, value)
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

// ---- 预览区直接编辑（所见即所得，改完反推回 Markdown 源码）----
// 取舍说明：不再有「进入 / 退出预览编辑」这个模式开关，右侧预览区常驻可编辑，
// 由「焦点」决定工具条作用于哪一侧：
//   · 焦点在预览区 → previewEditing = true → 工具条 / 格式刷 / 表格栏作用于预览 DOM
//   · 焦点离开预览区 → 自动静默同步回 Markdown 源码
// 为什么同步只放在「失焦」时：同步会写 form.content，MdPreview 随之重渲染，
// 正在输入的光标会丢；失焦时用户已不在预览里输入，重渲染才是安全的。
const previewEditing = ref(false)

/** 预览区最近一次选区：点工具条会夺走焦点（工具条带 @mousedown.prevent，实际不会失焦），靠它恢复后再执行格式命令 */
let savedPreviewRange = null

/** 失焦后延迟判定用：点击取色器 / 段落菜单等浮层时焦点可能瞬时离开，
 *  等一拍再用 document.activeElement 复核，避免把「点浮层」误判成「离开编辑」 */
let previewBlurTimer = null

/**
 * 预览里是否有「尚未反推回源码」的改动。
 * 不能再用 previewEditing 判断：失焦后它已经变回 false，
 * 但改动可能因为反推失败（含不支持的内联标签）还留在预览 DOM 里 ——
 * 那种情况必须拦住保存，否则改动会静默丢失。
 * <p>必须是 **ref**：它直接参与 {@code dirty} 判定。单栏阅读模式下预览就是唯一的编辑面，
 * 用户敲完字还没失焦时就必须显示「未保存」，否则点返回不会提醒、改动会悄悄丢掉。
 */
const previewUnsynced = ref(false)

/** 用户在预览里选中文字时记住选区；点工具条失焦后据此恢复 */
function onPreviewSelectionChange() {
  if (!previewEditing.value) return
  const el = previewEl()
  const sel = window.getSelection()
  if (el && sel && sel.rangeCount > 0 && sel.anchorNode && el.contains(sel.anchorNode)) {
    savedPreviewRange = sel.getRangeAt(0).cloneRange()
  }
  // 表格操作栏与行列高亮跟随光标
  updateTableState()
}

/** 预览区 DOM（限定在本页编辑容器内，避开 AI 弹窗/全局面板里的 MdPreview） */
function previewEl() {
  return editorWrapRef.value?.querySelector('.pane-preview .md-editor-preview') || null
}

/**
 * 让预览区可编辑。**只挂一次，且不再撤销**。
 * 以前「退出编辑」会 removeAttribute('contenteditable')，普通 div 随即失去可聚焦性，
 * 于是「点击预览就进入编辑」再也触发不了 —— 这正是改成常驻可编辑的原因。
 * @returns {boolean} 是否已就绪（预览区 DOM 还没渲染出来时返回 false）
 */
function attachPreviewEditable() {
  const el = previewEl()
  if (!el) return false
  // 这些属性每次都对齐（幂等）：本函数是懒触发的，而 HMR 热更新后原 DOM 可能还在、
  // dataset 标记也还在，若直接提前 return，新加的属性就永远补不上。
  el.setAttribute('contenteditable', 'true')
  // 关掉浏览器拼写检查：笔记里全是 spring_factories / AutoConfiguration.imports 这类标识符，
  // contenteditable 默认开启拼写检查，于是它们被英文词典逐条标红波浪线（中文不查，只有这些"疑似单词"被误报）。
  // 源码区不受影响 —— CodeMirror 自己设了 spellcheck=false。
  el.setAttribute('spellcheck', 'false')
  el.setAttribute('autocorrect', 'off')
  el.setAttribute('autocapitalize', 'off')
  if (el.dataset.lhEditable === '1') return true
  el.dataset.lhEditable = '1'
  el.classList.add('lh-preview-editing')
  el.addEventListener('paste', onPreviewPaste)
  el.addEventListener('mouseover', onPreviewBlockHover)
  el.addEventListener('beforeinput', onPreviewBeforeInput)
  return true
}

/**
 * 预览区的可编辑性是**常驻**的（attachPreviewEditable 只挂不摘）——
 * 两个模式都允许直接改正文，所以这里不需要"还原成只读"的收尾动作。
 * 历史里的 detachPreviewEditable() 已删除：它会摘掉 contenteditable，
 * 让普通 div 失去可聚焦性，「点进去就能写」随即失效（见 attachPreviewEditable 的注释）。
 */

/** 预览区粘贴：统一按纯文本插入，避免把外部网页/Word 的样式噪音带进来 */
function onPreviewPaste(e) {
  const raw = e.clipboardData?.getData('text/plain')
  if (raw == null) return
  e.preventDefault()
  // execCommand 虽已标记废弃，但仍是 contenteditable 下保留撤销历史的最简方案
  document.execCommand('insertText', false, raw)
}

/**
 * 焦点进入预览区 = 开始编辑（自动进入，不再需要任何模式开关）。
 * 可编辑性由 @mouseenter / @click 提前挂好，这里只负责切状态。
 */
function onPreviewFocusIn() {
  toolbarSide = 'preview'
  scrollSyncSource = 'preview'
  if (useBlockPreview) return
  if (previewEditing.value) return
  if (!attachPreviewEditable()) return
  pvResetHistory()
  previewEditing.value = true
  savedPreviewRange = null
  document.addEventListener('selectionchange', onPreviewSelectionChange)
  document.addEventListener('mousedown', onDocMouseDown)
  document.addEventListener('keydown', onPreviewKeydown)
  // 把光标放进可编辑区：空正文时用户点的是「占位提示」，不这样做还得再点一次
  previewEl()?.focus()
}

/** 焦点离开预览区 = 结束编辑，并把改动静默同步回 Markdown 源码 */
function onPreviewFocusOut() {
  if (!previewEditing.value) return
  clearTimeout(previewBlurTimer)
  previewBlurTimer = setTimeout(() => {
    // 复核一次：焦点可能仍在编辑面内（例如刚点开取色器、段落菜单、块菜单）
    const ae = document.activeElement
    const el = previewEl()
    if (el && ae && el.contains(ae)) return
    if (ae && ae.closest && ae.closest('.ed-tools, .tb-menu, .block-menu, .color-picker')) return
    if (document.querySelector('.tb-menu, .block-menu, .color-picker')) return
    // 没有改动就不必反推（反推会重写 form.content，导致预览区白重渲染一次）
    if (!previewUnsynced.value) {
      exitPreviewEdit()
      return
    }
    syncPreviewToSource(true)
  }, 160)
}

/**
 * 点击预览区：兜住「点在不可聚焦区域」的情况（空正文占位、块之间的空白等）。
 * 这些地方点下去不产生 focusin，不补这一下用户会以为右侧不能编辑。
 */
function onPreviewClick(e) {
  // 点代码块左上角的语言名 = 换语言（要在"进入编辑态"之前处理，否则会先落光标）
  if (e && onPreviewCodeLangClick(e)) return
  if (previewEditing.value) return
  onPreviewFocusIn()
}

/**
 * 单栏（阅读）模式下工具条只可能作用于预览这一栏，但用户完全可能没点进正文就先按了「加粗」。
 * 这里先补一次「点进正文」（进入可编辑态并聚焦）再执行命令 ——
 * 否则命令会落到已经藏起来的源码栏上：内容真的改了，可见的正文里却毫无反应。
 * @returns {boolean} 现在是否处于预览可编辑态
 */
function ensurePreviewEditing() {
  if (previewEditing.value) return true
  onPreviewFocusIn()
  return previewEditing.value
}

function onPreviewKeydown(e) {
  if (!previewEditing.value) return
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
    e.preventDefault()
    syncPreviewToSource()
  }
  // 撤销/重做走快照栈（浏览器原生撤销对 DOM 直接替换无效，且与快照栈会打架）
  if ((e.ctrlKey || e.metaKey) && !e.altKey) {
    const k = e.key.toLowerCase()
    if (k === 'z') {
      e.preventDefault()
      previewUndoRedo(e.shiftKey)
      return
    }
    if (k === 'y') {
      e.preventDefault()
      previewUndoRedo(true)
      return
    }
  }
  // Esc 同样是「同步后退出」。要丢弃请用操作条上的「↺ 放弃改动」，
  // 这样任何一条退出路径都不会悄悄吃掉用户的改动。
  if (e.key === 'Escape') syncPreviewToSource(true)
}

/**
 * 结束编辑态。**只清状态，不再撤销可编辑性** —— 预览区常驻可编辑，
 * 否则普通 div 失去可聚焦性，「再点进去就编辑」会失效（见 attachPreviewEditable）。
 */
function exitPreviewEdit() {
  document.removeEventListener('keydown', onPreviewKeydown)
  document.removeEventListener('selectionchange', onPreviewSelectionChange)
  document.removeEventListener('mousedown', onDocMouseDown)
  clearTimeout(hideHandleTimer)
  clearTimeout(previewBlurTimer)
  savedPreviewRange = null
  pvClearTableHl()
  hideBlockHandle()
  previewEditing.value = false
}

/**
 * 把预览区当前的富文本反推回 Markdown 源码。
 * @param {boolean} silent 静默模式：正常路径不弹提示（出错仍然会提示）
 * @returns {boolean} 是否成功（失败时已给出提示，且不会改动源码，也不会丢预览里的改动）
 */
function syncPreviewToSource(silent = false) {
  // 块编辑器模式（默认）：编辑面就是 Tiptap，改动已由 @update 实时写回 form.content，
  // 不存在「从预览 DOM 反推」这一步 —— 直接返回成功，避免误报「预览区不存在」。
  if (useBlockPreview) return true
  const el = previewEl()
  if (!el) {
    // 这是异常状态（预览区都没了就无从反推），无论 silent 都要报出来，
    // 否则用户点了操作却毫无反应。
    ElMessage.error('预览区不存在，无法同步')
    return false
  }
  const bad = findUnsupported(el.innerHTML)
  if (bad.length) {
    ElMessage.warning(`预览里有 ${bad.join('、')}，无法安全反推成源码，请改用源码模式编辑`)
    return false
  }
  const md = fixHtmlQuotes(previewHtmlToMd(el.innerHTML))
  const changed = md !== (form.value.content || '').trim()
  if (changed) form.value.content = md
  previewUnsynced.value = false
  exitPreviewEdit()
  if (!silent) {
    if (changed) ElMessage.success('已把预览里的修改同步回 Markdown 源码，确认后点「保存」')
    else ElMessage.info('预览内容与源码一致，无需同步')
  }
  return true
}

// ---- AI 处理（润色 / 整理格式）----
const aiBusy = ref(false)
const aiDialog = ref(false)
const aiLabel = ref('')
const aiResult = ref('')
/** 弹窗里的说明文字：润色/融入的等待方式不同（分段 vs 整篇），各说各的 */
const aiHint = ref('')

/**
 * 处理进度：**真实进度**，不是假走条。
 * 后端按 4000 字分段串行处理，每段开始前推一个 SSE 事件，这里据此显示「第 i/N 段 + 已用时间」。
 * 单段的耗时不可预估（思考型模型单段可能 1–3 分钟），所以：
 *   · total > 1 → 用「已完成段数」算百分比（每段开始前更新，段内不动，故不会假涨）
 *   · total = 1 → 用不确定态（indeterminate），因为没有可推进的刻度
 */
const aiProgress = ref({ total: 0, index: 0, percent: 0, elapsed: 0, phase: '' })
let aiTimer = null
let aiAbort = null

function startAiProgress() {
  aiProgress.value = { total: 0, index: 0, percent: 0, elapsed: 0, phase: '正在连接 AI…' }
  clearInterval(aiTimer)
  const t0 = Date.now()
  // 秒级计时器：长等待时「已用 42s」比一个静止的转圈更能安抚人
  aiTimer = setInterval(() => {
    aiProgress.value.elapsed = Math.floor((Date.now() - t0) / 1000)
  }, 1000)
}

function stopAiProgress() {
  clearInterval(aiTimer)
  aiTimer = null
}

/** SSE 事件 → 进度状态 */
function onAiProgress(event, payload) {
  if (event !== 'progress') return
  if (payload.stage === 'start') {
    aiProgress.value.total = payload.total
    aiProgress.value.phase = payload.total > 1 ? `共 ${payload.total} 段，逐段处理` : '正在处理'
    return
  }
  if (payload.stage === 'chunk') {
    const total = payload.total || aiProgress.value.total
    aiProgress.value.total = total
    aiProgress.value.index = payload.index
    // 段内不推进：第 i 段开始时已完成 i-1 段
    aiProgress.value.percent = total > 1 ? Math.round(((payload.index - 1) / total) * 100) : 0
    aiProgress.value.phase = total > 1
      ? `正在处理第 ${payload.index}/${total} 段`
      : '正在处理（整篇一次完成）'
  }
}

/** 取消处理：中断流式请求（后端任务会在当前段结束后自然收尾） */
function cancelAiProcess() {
  if (aiAbort) {
    aiAbort.abort()
    aiAbort = null
  }
  stopAiProgress()
  aiBusy.value = false
  aiDialog.value = false
  ElMessage.info('已取消 AI 处理')
}

/** 弹窗关闭：处理中关掉等于取消，避免请求在后台白跑 */
function onAiDialogClose() {
  if (aiBusy.value) cancelAiProcess()
}

async function aiProcess(mode) {
  const content = form.value.content || ''
  if (!content.trim()) {
    ElMessage.warning('正文为空，先写点内容再让 AI 处理')
    return
  }
  aiBusy.value = true
  aiLabel.value = mode === 'format' ? 'AI 整理格式' : 'AI 润色'
  aiHint.value = '长文按 4000 字分段逐段处理，思考型模型单段可能耗时 1–3 分钟；'
    + '进度按「已完成段数」推进，段内不动属正常。可继续等待，或点「取消处理」中止。'
  aiResult.value = ''
  // 先开弹窗：进度就显示在弹窗里，而不是让用户对着一个不动的按钮等三分钟
  aiDialog.value = true
  startAiProgress()
  aiAbort = new AbortController()
  try {
    const out = await aiApi.polishStream({ text: content, mode }, onAiProgress, aiAbort.signal)
    aiProgress.value.percent = 100
    // 原样返回 = 模型判断无可改动，明确告知而不是让人以为没生效
    if (out.trim() === content.trim()) {
      aiDialog.value = false
      ElMessage.info('AI 检查后认为当前内容已足够规范，未做改动')
      return
    }
    aiResult.value = out
  } catch (e) {
    aiDialog.value = false
    // 流式绕过了 axios 拦截器，错误提示要在这里补上（文案风格与拦截器保持一致）
    const msg = e?.message || 'AI 处理失败'
    if (msg !== '已取消处理') ElMessage.error(msg)
  } finally {
    aiAbort = null
    stopAiProgress()
    aiBusy.value = false
  }
}

/** 用 AI 结果替换正文（不自动保存，用户再点一次「保存」把控结果） */
function aiApply() {
  form.value.content = fixHtmlQuotes(aiResult.value)
  aiDialog.value = false
  ElMessage.success('已用 AI 结果替换正文，确认无误后点「保存」')
}

// 注：打开 AI 对话的唯一入口是右下角常驻的智能体悬浮按钮（顶栏原来的「打开 AI 对话」菜单项
// 与之重复，已移除）。若将来需要在页面内程序化唤起它，派发下面这个 window 事件即可：
//   window.dispatchEvent(new CustomEvent('lh-agent-open'))   // AgentPanel 监听此事件

/**
 * 告诉悬浮面板「当前开着哪篇笔记」。
 *
 * 有了它，面板里回答后的主操作会从「保存为笔记」换成「融入当前笔记」，
 * 并把这篇笔记当提问背景一起发给模型（见 utils/agentNoteMerge.js 里的取舍说明）。
 *
 * 监听 id、标题与 dirty 状态切换；加载和保存成功后也广播最新节选。
 * 正文每次输入无需广播；第一次变为未保存时通知面板，阻止它编辑库中的旧正文。
 * 广播里的 context 只是"提问背景"，后端还会再截到 1500 字注入。
 */
function publishAgentNote() {
  window.dispatchEvent(new CustomEvent(AGENT_NOTE_CONTEXT_EVENT, {
    detail: {
      noteId: id.value ? Number(id.value) : null,
      title: form.value.title || '',
      isNew: isNew.value,
      context: (form.value.content || '').slice(0, 4000),
      dirty: dirty.value,
    },
  }))
}
watch([id, () => form.value.title, dirty], publishAgentNote)

async function onAgentNoteUpdated(e) {
  const noteId = e?.detail?.noteId
  if (!matchesOpenNote(id.value, noteId)) return
  if (dirty.value) {
    agentEditConflict.value = true
    ElMessage.warning('智能体已更新库中正文，本地草稿已保留，请加载最新正文后再编辑')
    return
  }
  try {
    const latest = await noteApi.detail(noteId)
    if (!matchesOpenNote(id.value, noteId)) return
    // 请求期间敲了字也保留草稿，不让网络响应覆盖本地编辑。
    if (dirty.value) {
      agentEditConflict.value = true
      return
    }
    form.value.content = fixHtmlQuotes(latest.content || '')
    savedSnapshot.value = formFingerprint()
    lastSavedAt.value = new Date()
    publishAgentNote()
  } catch {
    if (matchesOpenNote(id.value, noteId)) agentEditConflict.value = true
  }
}

async function reloadAfterAgentEdit() {
  const expectedId = id.value
  if (dirty.value) {
    try {
      await ElMessageBox.confirm('加载会替换当前未保存的正文，请先复制需要保留的草稿。', '加载最新笔记', {
        confirmButtonText: '加载最新', cancelButtonText: '保留草稿', type: 'warning',
      })
    } catch { return }
  }
  if (!matchesOpenNote(id.value, expectedId)) return
  previewUnsynced.value = false
  previewEditing.value = false
  await loadNote()
}

/**
 * 面板要把智能体的回答融入这篇笔记。
 *
 * 这里**不是**往文末追加一段，而是让模型读完**整篇**（`form.content` 是唯一权威的那一份，
 * 可能包含未保存的改动）再产出"把新知识放到合适位置"的新正文，然后在弹窗里给用户预览，
 * 点「替换正文」才写进编辑器 —— 落库仍由用户点「保存」决定。
 *
 * 无论成功失败都要回报面板（见 agentNoteMerge.js 的职责划分）：失败时面板把按钮恢复成可重试，
 * 而不是永远显示"已融入"。失败时**正文保持原样**，不做降级追加。
 */
async function onAgentMerge(e) {
  const d = e?.detail || {}
  const noteId = d.noteId || null
  const answer = (d.answer || '').trim()
  const reply = (ok, message) => window.dispatchEvent(new CustomEvent(AGENT_NOTE_MERGE_RESULT_EVENT, {
    detail: { noteId, ok, message },
  }))
  if (!answer) {
    reply(false, '这条回答是空的，没有可融入的内容')
    return
  }
  // 融入结果回来时如果用户已经切走笔记，就别把 A 的内容应用到 B 上（本文件开头那类事故）
  const expectId = id.value
  aiBusy.value = true
  aiLabel.value = '融入当前笔记'
  aiHint.value = '整篇一次重写：模型会把回答按结构并进对应小节（或新增合适的小节），产出完整新正文；'
    + '确认无误后再点「替换正文」，然后点「保存」才会落库。'
  aiResult.value = ''
  aiDialog.value = true
  startAiProgress()
  aiAbort = new AbortController()
  try {
    const out = await aiApi.mergeNoteStream(
      {
        noteId: noteId ? Number(noteId) : undefined,
        title: form.value.title || '',
        note: form.value.content || '',
        question: d.question || '',
        answer,
      },
      onAiProgress,
      aiAbort.signal,
    )
    aiProgress.value.percent = 100
    if (String(expectId) !== String(id.value)) {
      aiDialog.value = false
      reply(false, '期间切换了笔记，已放弃本次融入（正文未改动）')
      return
    }
    if ((out || '').trim() === (form.value.content || '').trim()) {
      aiDialog.value = false
      ElMessage.info('模型认为这条内容已经在笔记里了，未做改动')
      reply(false, '模型认为这条内容已经在笔记里，未做改动')
      return
    }
    aiResult.value = out
    // 只回报"预览已生成"：真正替换要等用户点弹窗里的「替换正文」
    reply(true, '已在笔记页打开融入预览，确认后点「替换正文」')
  } catch (err) {
    aiDialog.value = false
    const msg = err?.message || 'AI 处理失败'
    if (msg !== '已取消处理') ElMessage.error(msg + '（正文保持原样）')
    reply(false, msg)
  } finally {
    aiAbort = null
    stopAiProgress()
    aiBusy.value = false
  }
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
  agentEditConflict.value = false
  // 每次点进一篇笔记都从「单栏阅读」开始（用户要求）：源码对照需要显式进
  readingMode.value = true
  if (isNew.value) {
    // 「编辑 A → 新建」时组件同样会被复用：必须把表单清空，
    // 否则新建的笔记里会残留上一篇文章的正文。
    loadSeq++
    // 新笔记预置骨架模板：等于把《美化规范》变成可填空的表单，
    // 顺手也就有了「新建笔记模板」这个入口（老笔记可用「更多 → 插入模板」）。
    form.value = { title: '', content: NEW_NOTE_TEMPLATE, categoryId: undefined, tagIds: [] }
    loading.value = false
    // 注意顺序：先铺模板再取指纹，这样刚建的空笔记不算「有未保存改动」，不会一进来就弹离开确认
    savedSnapshot.value = formFingerprint()
    // 新建时自动聚焦标题，省一次手点（同时覆盖「编辑 A → 新建」的复用场景）
    nextTick(() => titleInputRef.value?.focus())
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
  savedSnapshot.value = formFingerprint()
  publishAgentNote()
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
  if (agentEditConflict.value) {
    ElMessage.warning('库中正文已更新，请先点击「加载最新正文」，避免旧草稿覆盖智能体的改动')
    return
  }
  if (!form.value.title.trim()) {
    ElMessage.warning('标题不能为空')
    return
  }
  // 预览里还有没反推回源码的改动时，直接保存会把它丢掉 —— 先自动同步（失败则中止保存）
  if (previewUnsynced.value && !useBlockPreview && !syncPreviewToSource(true)) return
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
      savedSnapshot.value = formFingerprint()
      justCreatedId = String(created.id)
      lastSavedAt.value = new Date()
      ElMessage.success('笔记已创建')
      router.replace(`/notes/${created.id}`)
    } else {
      await noteApi.update(id.value, payload)
      savedSnapshot.value = formFingerprint()
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
    // 标题只出现一次：正文首行若还是同一个 `# 标题`（历史笔记 / 没保存的手写行），先剥掉
    const body = stripLeadingDocTitle(form.value.content, title)
    if (fmt === 'html') {
      const { renderNoteHtml } = await import('../utils/mdToHtml')
      const html = renderNoteHtml({ title, content: body })
      const blob = new Blob([html], { type: 'text/html;charset=utf-8' })
      saveBlob(blob, `${safe}.html`)
      ElMessage.success('已导出为 HTML 网页文件')
    } else {
      const content = `# ${title}\n\n${body}\n`
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
// 布局：单栏「阅读」（默认，可编辑）⇄ 源码对照（源码 / 预览 / 大纲）+ 专注模式
// ==================================================================

/** 源码栏宽度百分比（预览栏 = 100 - 源码 - 大纲在剩余空间内占比） */
const editorPct = ref(Number(localStorage.getItem('lh-editor-pct') || 42))
const outlineOpen = ref(true)
/**
 * 单栏「阅读」模式 —— **默认就是这个**（用户要求：笔记点进去先进阅读模式，
 * 源码对照从「更多」/ 右上角按钮进）。它不再是"只看不能改"：预览栏就是编辑面，
 * 点进去直接写，顶部工具栏作用于这一栏。
 */
const readingMode = ref(true)
const pvScrollRef = ref(null)

watch(editorPct, (v) => localStorage.setItem('lh-editor-pct', String(Math.round(v))))

/** 响应式档位：宽(三栏) / 中(两栏,隐大纲) / 窄(Tab 切换编辑/预览) */
const layoutMode = ref('wide') // wide | mid | tab
let resizeObserver = null

// ---- 第二行工具条：窄屏横向滚动（滚轮横向滚动 + 左右边缘渐隐提示）----
const toolsRef = ref(null)
const toolsScroll = reactive({ left: false, right: false })
function updateToolsScroll() {
  const el = toolsRef.value
  if (!el) return
  const max = el.scrollWidth - el.clientWidth
  toolsScroll.left = el.scrollLeft > 1
  toolsScroll.right = el.scrollLeft < max - 1
}
/** 有横向溢出时把纵向滚轮转成横向滚动，避免窄屏下工具条被悄悄截断 */
function onToolsWheel(e) {
  const el = toolsRef.value
  if (!el || el.scrollWidth <= el.clientWidth) return
  if (Math.abs(e.deltaX) > Math.abs(e.deltaY)) return
  e.preventDefault()
  el.scrollLeft += e.deltaY
}

function measureLayout() {
  const w = editorWrapRef.value?.clientWidth || window.innerWidth
  const prev = layoutMode.value
  layoutMode.value = w >= 1280 ? 'wide' : w >= 860 ? 'mid' : 'tab'
  if (layoutMode.value === 'wide') {
    if (prev !== 'wide' && /^#{1,3}\s/m.test(form.value.content || '')) {
      // 由窄变宽：有标题就自动展开常驻大纲（窄屏时它是隐藏的）
      outlineOpen.value = true
    }
  } else if (prev === 'wide') {
    // 只在"从宽屏掉到窄屏"时收起常驻栏与抽屉。
    // 窄屏内部的窗口微调（滚动条出现/消失、缩放几像素）也会触发 resize，
    // 无条件在这里重置会把刚打开的抽屉立刻关掉。
    outlineOpen.value = false
    outlineDrawer.value = false
  }
  updateToolsScroll()
}

/** 三栏里预览区随源码栏联动；大纲栏固定 260px（max 15%）由 flex 基准控制 */
const editorStyle = computed(() => {
  // 注意：这里**不再**随 previewEditing 变化 —— 右侧已常驻可编辑，
  // 若点一下预览就改分栏比例，每次进入编辑都会重排一次，观感上像页面在抖。
  if (readingMode.value || layoutMode.value === 'tab') return {}
  return { flex: `0 0 ${editorPct.value}%` }
})

/** 当前左栏是否显示：Tab 模式下由编辑/预览 Tab 决定 */
const editorTab = ref('edit')
const showEditorPane = computed(() => {
  // 右侧可编辑时仍保留左侧源码栏：并排对照写作才是这个页面的主体形态
  if (readingMode.value) return false
  if (layoutMode.value === 'tab') return editorTab.value === 'edit'
  return true
})
const showPreviewPane = computed(() => {
  if (readingMode.value) return true
  if (layoutMode.value === 'tab') return editorTab.value === 'preview'
  return true
})

/** 工具条是否有内容可显示：Tab 模式下切到「预览」且非预览编辑时为空，避免出现空卡片
 *  （单栏阅读模式下永远显示：那一栏正文就是它的作用对象） */
const showTools = computed(() => readingMode.value || layoutMode.value !== 'tab'
  || editorTab.value === 'edit' || previewEditing.value)

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
const outlineMarkdown = new MarkdownIt({ html: true, breaks: true })
const outline = computed(() => {
  const items = []
  const tokens = outlineMarkdown.parse(form.value.content || '', {})
  const container = document.createElement('div')
  for (let i = 0; i < tokens.length; i++) {
    const token = tokens[i]
    if (token.type === 'heading_open' && /^h[1-3]$/.test(token.tag)) {
      const inline = tokens[i + 1]
      container.innerHTML = inline?.type === 'inline'
        ? outlineMarkdown.renderer.renderInline(inline.children || [], outlineMarkdown.options, {})
        : ''
      items.push({ level: Number(token.tag[1]), text: container.textContent.trim() })
    } else if (token.type === 'html_block') {
      // Linked or indented headings are saved as HTML to retain their metadata.
      container.innerHTML = token.content
      for (const heading of container.querySelectorAll('h1, h2, h3')) {
        items.push({ level: Number(heading.tagName[1]), text: heading.textContent.trim() })
      }
    }
  }
  return items
})

const activeIdx = ref(-1)
let outlineScrollTarget = null

/**
 * 窄屏（mid / tab）与阅读模式下的大纲抽屉。
 * <p>
 * 为什么需要：常驻大纲栏的显示条件是 `layoutMode === 'wide'`（编辑区 ≥1280px），
 * 而窄屏下**连「展开大纲」的浮标都没有**（那个浮标的 v-if 同样要求 wide）——
 * 笔记本上把左侧导航和右侧智能体面板一开就够不到 1280px，等于永远没有大纲入口。
 */
const outlineDrawer = ref(false)
function openOutlineDrawer() { outlineDrawer.value = true }
function closeOutlineDrawer() { outlineDrawer.value = false }
/** 抽屉里点条目：先关抽屉、下一帧再滚动，避免关闭动画与滚动打架导致目标位置算错 */
function jumpFromDrawer(idx) {
  outlineDrawer.value = false
  nextTick(() => scrollToHeading(idx))
}
/**
 * 大纲当前是否可见：宽屏看常驻侧栏，窄屏看抽屉。
 * 用于「更多 → 展开/收起大纲」的文案与切换 —— 这个菜单项在窄屏原本是个**哑按钮**：
 * 它只切 outlineOpen，而常驻侧栏要求 layoutMode === 'wide'，所以在窄屏点了没有任何反应，
 * 这也是"找不到大纲"的一部分原因。快捷键 toggleOutline 走同一入口。
 */
const outlineShown = computed(() => (layoutMode.value === 'wide' ? outlineOpen.value : outlineDrawer.value))
function toggleOutline() {
  if (layoutMode.value === 'wide') {
    outlineOpen.value = !outlineOpen.value
  } else {
    outlineDrawer.value = !outlineDrawer.value
  }
}

function scrollToHeading(idx) {
  const pv = pvScrollRef.value
  if (!pv) return
  const target = pv.querySelectorAll('.md-editor-preview h1, .md-editor-preview h2, .md-editor-preview h3, .tiptap h1, .tiptap h2, .tiptap h3')[idx]
  if (target) {
    onScrollIntent('preview')
    outlineScrollTarget = target
    pv.scrollTo({ top: topWithin(target, pv) - 24, behavior: 'smooth' })
  }
}

let scrollRaf = 0
function onPreviewScroll() {
  const pv = pvScrollRef.value
  if (pv && scrollSyncSource === 'preview') {
    queueScrollSync()
  }
  if (scrollRaf) return
  scrollRaf = requestAnimationFrame(() => {
    scrollRaf = 0
    const el = pvScrollRef.value
    if (!el) return
    const heads = el.querySelectorAll('.md-editor-preview h1, .md-editor-preview h2, .md-editor-preview h3, .tiptap h1, .tiptap h2, .tiptap h3')
    let cur = -1
    for (let i = 0; i < heads.length; i++) {
      if (topWithin(heads[i], el) - el.scrollTop - 40 <= 0) cur = i
      else break
    }
    activeIdx.value = cur
  })
}

// ---- 源码区 ↔ 预览区：按相同内容块在两侧的实际像素位置联动 ----
// 逻辑行号只用于配对。长段落软换行、代码块、表格的高度不同，不能按行数插值。
function editorScrollEl() {
  return editorRef.value?.getEditorView?.()?.scrollDOM
    || editorWrapRef.value?.querySelector('.pane-editor .cm-scroller') || null
}

/** 格式工具条与滚动同步共用编辑器公开 API。 */
function cmView(ed) {
  const view = editorRef.value?.getEditorView?.()
  return view && (!ed || view.scrollDOM === ed) ? view : null
}

/** 同一滚动坐标系，避免 offsetTop 受嵌套 offsetParent 影响。 */
function topWithin(el, scroller) {
  return el.getBoundingClientRect().top - scroller.getBoundingClientRect().top
    - scroller.clientTop + scroller.scrollTop
}

function scrollAnchors(ed, pv) {
  const view = editorRef.value?.getEditorView?.()
  if (!view || view.scrollDOM !== ed) return []
  // 已渲染行的几何位置精确；远处的行由 CodeMirror 高度图估计，滚入后再校正。
  const rendered = new Map()
  for (const el of view.contentDOM.querySelectorAll('.cm-line')) {
    const line = view.state.doc.lineAt(view.posAtDOM(el, 0)).number - 1
    rendered.set(line, topWithin(el, ed))
  }
  // documentTop 指向第一行，包含内容顶部 padding；.cm-content 的 rect 不包含这部分。
  const sourceOffset = view.documentTop - ed.getBoundingClientRect().top
    - ed.clientTop + ed.scrollTop
  const points = []
  for (const el of pv.querySelectorAll('.md-editor-preview [data-line], .tiptap [data-line]')) {
    const line = Number(el.dataset.line)
    if (!Number.isInteger(line) || line < 0 || line >= view.state.doc.lines) continue
    if (!el.getClientRects().length || el.getBoundingClientRect().height <= 0) continue
    const source = rendered.get(line)
      ?? view.lineBlockAt(view.state.doc.line(line + 1).from).top + sourceOffset
    points.push({ line, source, preview: topWithin(el, pv) })
  }
  return normalizeScrollAnchors(points)
}

/** 隐藏或没有滚动空间的单栏不联动。 */
function bothScrollable(a, b) {
  return a.clientHeight > 0 && b.clientHeight > 0
    && a.scrollHeight - a.clientHeight > 1 && b.scrollHeight - b.clientHeight > 1
}

// 方向由用户输入或大纲跳转决定，目标侧的滚动（含虚拟行高校正）不会夺走方向。
function onScrollIntent(side) {
  scrollSyncSource = side
  outlineScrollTarget = null
  clearTimeout(scrollSyncTimer)
}
function setSyncedScroll(el, top) {
  if (Math.abs(el.scrollTop - top) <= 0.5) return
  el.scrollTop = top
}

function syncPanes(ed, pv, from) {
  const source = from === 'editor' ? ed : pv
  const target = from === 'editor' ? pv : ed
  const sourceMax = source.scrollHeight - source.clientHeight
  const targetMax = target.scrollHeight - target.clientHeight
  const points = scrollAnchors(ed, pv)
  const ranged = points.length ? boundScrollAnchors(points,
    ed.scrollHeight - ed.clientHeight, pv.scrollHeight - pv.clientHeight) : []
  const mapped = mapScrollPosition(ranged, source.scrollTop, from === 'editor' ? 'source' : 'preview')
  setSyncedScroll(target, Math.max(0, Math.min(targetMax,
    mapped ?? source.scrollTop / sourceMax * targetMax)))
}

let scrollSyncSource = 'editor'
let scrollSyncTimer = 0
let scrollSyncRaf = 0
let scrollResizeObserver = null
function queueScrollSync() {
  clearTimeout(scrollSyncTimer)
  if (scrollSyncRaf) return
  scrollSyncRaf = requestAnimationFrame(() => {
    scrollSyncRaf = 0
    const ed = editorScrollEl()
    const pv = pvScrollRef.value
    if (!ed || !pv || !bothScrollable(ed, pv)) return
    syncPanes(ed, pv, scrollSyncSource)
    scheduleScrollResync()
  })
}

/** 跳到虚拟化区域后，等 CodeMirror 实际测量行高，再按原方向校正。 */
function scheduleScrollResync() {
  clearTimeout(scrollSyncTimer)
  scrollSyncTimer = setTimeout(() => {
    const ed = editorScrollEl()
    const pv = pvScrollRef.value
    // 大纲跳转期间，内嵌代码编辑器可能重新测量换行高度；以标题的新位置收尾。
    if (pv && outlineScrollTarget?.isConnected) {
      const target = outlineScrollTarget
      outlineScrollTarget = null
      setSyncedScroll(pv, topWithin(target, pv) - 24)
    }
    if (ed && pv && bothScrollable(ed, pv)) syncPanes(ed, pv, scrollSyncSource)
  }, 180)
}

function onEditorScroll() {
  const ed = editorScrollEl()
  if (!ed) return
  if (scrollSyncSource !== 'editor') {
    // 远距离反向跳转可能经历多轮虚拟行高测量；保持预览为来源，继续校正目标。
    scheduleScrollResync()
    return
  }
  queueScrollSync()
}

/** CodeMirror 初始化晚于页面时，供挂载过程重试。 */
function bindEditorScroll() {
  const el = editorScrollEl()
  if (!el) return false
  el.addEventListener('scroll', onEditorScroll, { passive: true })
  return true
}

// 切回双栏、拖动分隔条或编辑正文后，使用新的排版位置重新对齐。
watch([showEditorPane, showPreviewPane], () => {
  if (showEditorPane.value && showPreviewPane.value) {
    scrollSyncSource = 'preview'
    nextTick(queueScrollSync)
  }
})
watch(() => form.value.content, () => nextTick(queueScrollSync))

// ---- 模式切换 ----
function toggleFocus() {
  focusMode.value = !focusMode.value
  if (focusMode.value) outlineOpen.value = false
}
/**
 * 单栏「阅读」模式 ⇄ 「源码对照」模式。
 * <p>阅读模式是**默认**进入的那一栏：只有一栏正文，点进去就能改（所见即所得），
 * 顶部工具栏作用于这一栏；源码对照（左源码 / 右预览 / 大纲）从「更多」或右上角按钮进入 —— 相当于
 * 把原来的两个模式反了过来。
 * <p>切换前必须先把预览里没反推回 Markdown 的改动同步掉：否则切栏后看到的是旧正文，
 * 甚至保存时把改动弄丢。
 */
function toggleReading() {
  if (previewEditing.value || previewUnsynced.value) {
    syncPreviewToSource(true)
  }
  readingMode.value = !readingMode.value
  if (readingMode.value) {
    // 单栏下预览就是编辑面：保证一进来点一下就写（可编辑性是幂等挂载）
    nextTick(attachPreviewEditable)
  }
}

/** 「更多」菜单：导出（函数命令）+ 模式切换（字符串命令） */
function moreCommand(cmd) {
  if (typeof cmd === 'function') {
    cmd()
    return
  }
  if (cmd === 'toggleOutline') toggleOutline()
  else if (cmd === 'toggleFocus') toggleFocus()
  else if (cmd === 'toggleReading') toggleReading()
  else if (cmd === 'insertTemplate') insertTemplate()
}

/** 在光标处插入笔记骨架模板（走 md-editor insert，保留撤销历史） */
function insertTemplate() {
  if (captureToolbarContext().runTool('markdown', NEW_NOTE_TEMPLATE)) ElMessage.success('已插入模板，按需删改')
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
  undo: () => mdUndoRedo(false),
  redo: () => mdUndoRedo(true),
  bold: () => mdWrap('**', '**'),
  italic: () => mdWrap('*', '*'),
  strike: () => mdWrap('~~', '~~'),
  underline: () => mdWrap('<u>', '</u>'),
  para: () => mdLinePrefix(''), // 「正文」= 剥掉行首的标题/引用/列表前缀
  h1: () => mdLinePrefix('# '),
  h2: () => mdLinePrefix('## '),
  h3: () => mdLinePrefix('### '),
  h4: () => mdLinePrefix('#### '),
  quote: () => mdLinePrefix('> '),
  ul: () => mdLinePrefix('- '),
  ol: () => mdLinePrefix('1. '),
}
/** 行内包裹：有选中时包住选中文字并保持其选中，无选中时插入「文本」占位 */
function mdWrap(before, after) {
  edInsert((selected) => {
    const inner = selected || '文本'
    return {
      targetValue: before + inner + after,
      deviationStart: before.length,
      deviationEnd: -after.length,
    }
  })
}
/** 行前缀（标题/引用/列表/正文）：走 CM 内部 view 精确落到行首——
 *  选区可在行任意位置；先剥掉已有行前缀再套新的（切换级别/类型即替换而非叠加，
 *  前缀落不到行首会导致大纲/渲染都不认）。失败回退旧的选区前插入行为。 */
const LINE_PREFIX_RE = /^(#{1,6}\s+|>\s+|[-*+]\s+\[[ xX]\]\s+|[-*+]\s+|\d+\.\s+)/
function mdLinePrefix(prefix) {
  const ed = editorScrollEl()
  const v = ed ? cmView(ed) : null
  if (v) {
    try {
      const { from, to } = v.state.selection.main
      const startLine = v.state.doc.lineAt(from)
      const endLine = v.state.doc.lineAt(to)
      const changes = []
      for (let l = startLine.number; l <= endLine.number; l++) {
        const line = v.state.doc.line(l)
        const old = line.text.match(LINE_PREFIX_RE)
        if (old) changes.push({ from: line.from, to: line.from + old[0].length, insert: '' })
        if (prefix) changes.push({ from: line.from, insert: prefix })
      }
      if (changes.length) v.dispatch({ changes })
      v.contentDOM?.focus?.()
      return
    } catch {
      /* 回退旧逻辑 */
    }
  }
  edInsert((selected) => {
    const inner = selected || ''
    return { targetValue: prefix + inner, deviationStart: prefix.length, deviationEnd: 0 }
  })
}
function mdTool(name, arg) {
  if (name === 'undo' || name === 'redo') return mdUndoRedo(name === 'redo')
  const target = captureToolbarContext()
  if (['link', 'image'].includes(name) && arg === undefined) {
    askUrl(name === 'link' ? '链接地址' : '图片地址', 'https://').then(url => {
      if (url) target.runTool(name, url)
    }).finally(() => target.release?.())
    return
  }
  return target.runTool(name, arg)
}

function usesPreviewToolbar() {
  return readingMode.value || (!showEditorPane.value && showPreviewPane.value)
    || (showPreviewPane.value && toolbarSide === 'preview')
}

function runSourceTool(name, arg) {
  const view = cmView(editorScrollEl())
  if (!view) return false
  const plan = createSourceInsert(view.state.doc.toString(), view.state.selection.main, name, arg)
  if (plan) {
    view.dispatch({ ...plan, scrollIntoView: true })
    view.focus()
    return true
  }
  if (!mdBtns[name]) return false
  mdBtns[name](arg)
  return true
}

/** Freeze the target before opening a language/URL dialog; never use a hidden pane's caret. */
function captureToolbarContext() {
  if (usesPreviewToolbar()) {
    if (useBlockPreview) {
      const target = blockPreviewRef.value?.captureToolbarTarget?.()
      if (target) {
        const apply = operation => {
          const result = operation()
          if (result === false) ElMessage.info('当前位置不支持此操作，请在正文中放置光标后重试')
          return result
        }
        return {
          release: () => target.release(),
          runTool: (name, arg) => apply(() => target.runTool(name, arg)),
          applyFormat: format => apply(() => target.applyFormat(format)),
        }
      }
      return { runTool: () => false, applyFormat: () => false }
    }
    ensurePreviewEditing()
    previewSelText()
    const range = savedPreviewRange?.cloneRange()
    const restore = () => { if (range) savedPreviewRange = range.cloneRange() }
    return {
      runTool(name, arg) { restore(); return previewMdTool(name, arg) },
      applyFormat(format) { restore(); return previewApplyFormat(format) },
    }
  }
  const view = cmView(editorScrollEl())
  const doc = view?.state.doc
  const selection = view?.state.selection
  const restore = () => {
    if (!view || view.state.doc !== doc) {
      ElMessage.info('正文已改变，请重新选择插入位置')
      return false
    }
    view.dispatch({ selection })
    return true
  }
  return {
    runTool(name, arg) { return restore() && runSourceTool(name, arg) },
    applyFormat(format) { return restore() && applySourceFormat(format) },
  }
}

/**
 * 工具栏「代码块」：先选语言，再插入。
 *
 * <p>弹层会夺走焦点，所以**先**跑一次预览取词（它内部会把当前插入点记进
 * {@code savedPreviewRange}），弹层关闭后再执行插入 —— 否则插入点就丢了。
 */
function onCodeBlockClick() {
  const target = captureToolbarContext()
  askCodeLang().then((lang) => {
    if (lang) { lastCodeLang.value = lang; target.runTool('codeBlock', lang) }
  }).finally(() => target.release?.())
}

// ---- 撤销 / 重做 ----
/** 源码模式：CodeMirror 用自己的 history（execCommand 无效），
 *  合成 Ctrl+Z / Ctrl+Shift+Z 键盘事件交给 CM 的 keymap 处理 */
function mdUndoRedo(redo) {
  if (useBlockPreview && usesPreviewToolbar()) {
    return redo ? blockPreviewRef.value?.redo() : blockPreviewRef.value?.undo()
  }
  if (!useBlockPreview && (previewEditing.value || (readingMode.value && ensurePreviewEditing()))) {
    return previewUndoRedo(redo)
  }
  const content = editorScrollEl()?.querySelector('.cm-content')
  if (!content) return
  content.focus()
  const fire = (key, code, shiftKey) =>
    content.dispatchEvent(
      new KeyboardEvent('keydown', { key, code, ctrlKey: true, shiftKey, bubbles: true, cancelable: true })
    )
  if (!redo) {
    fire('z', 'KeyZ', false)
    return
  }
  // CM6 重做绑定：Windows/Linux 是 Ctrl+Y，macOS 才是 Cmd+Shift+Z —— 先试 Y，文档没变再补 Shift+Z
  const v = cmView(editorScrollEl())
  const before = v ? v.state.doc.toString() : null
  fire('y', 'KeyY', false)
  if (v && v.state.doc.toString() === before) fire('z', 'KeyZ', true)
}

// ---- 格式刷（语雀式：取源选区格式 → 刷到目标选区）----
const formatPainter = reactive({ active: false, before: '', after: '' })

/** 采集源码选区的最外层格式包裹（** * ~~ ` 或 <font>/<mark> 等内联标签） */
function collectSourceFormat() {
  const v = cmView(editorScrollEl())
  if (!v) return false
  const { from, to } = v.state.selection.main
  if (from === to) return false
  const text = v.state.sliceDoc(from, to)
  let m = text.match(/^(\*\*|~~|\*)([\s\S]+)\1$/) || text.match(/^(`)([\s\S]+)`$/)
  if (m) {
    formatPainter.before = m[1]
    formatPainter.after = m[1]
    return true
  }
  m = text.match(/^(<(font|mark|u|s|sup|sub|code)\b[^>]*>)([\s\S]+)<\/\2>$/)
  if (m) {
    formatPainter.before = m[1]
    formatPainter.after = `</${m[2]}>`
    return true
  }
  return false
}

/** 采集预览选区的内联格式标签链（font/mark/u/s/sup/sub/b/i/code 及其样式） */
function collectPreviewFormat() {
  const el = previewEl()
  const sel = window.getSelection()
  if (!el || !sel || sel.isCollapsed || !sel.anchorNode) return false
  let node = sel.anchorNode.nodeType === 1 ? sel.anchorNode : sel.anchorNode.parentElement
  const chain = []
  while (node && node !== el && el.contains(node)) {
    if (/^(FONT|MARK|U|S|STRIKE|DEL|SUP|SUB|B|STRONG|I|EM|CODE)$/.test(node.tagName)) chain.unshift(node)
    node = node.parentElement
  }
  if (!chain.length) return false
  formatPainter.before = chain.map((n) => n.outerHTML.slice(0, n.outerHTML.indexOf('>') + 1)).join('')
  formatPainter.after = chain.map((n) => `</${n.tagName.toLowerCase()}>`).reverse().join('')
  return true
}

/** 格式刷按钮：第一次点 = 取格式；第二次点（选中目标后）= 刷上并复位 */
function formatPainterClick() {
  const inPreview = previewEditing.value || (readingMode.value && ensurePreviewEditing())
  if (!formatPainter.active) {
    const got = inPreview ? collectPreviewFormat() : collectSourceFormat()
    if (!got) {
      ElMessage.info('先拖选一段带格式（加粗/颜色/高亮等）的文字')
      return
    }
    formatPainter.active = true
    ElMessage.success('已取格式，选中目标文字后再点一次格式刷')
    return
  }
  if (inPreview) previewWrap(formatPainter.before, formatPainter.after, '文本')
  else mdWrap(formatPainter.before, formatPainter.after)
  formatPainter.active = false
}

// ---- 工具条「正文∨」与「对齐∨」下拉（语雀式，自绘小浮层，风格同块菜单）----
const paraMenuOpen = ref(false)
const alignMenuOpen = ref(false)
/** 菜单 fixed 定位坐标（.ed-tools 有 overflow-x:auto，absolute 会被裁，必须 fixed） */
const toolMenuPos = ref({ x: 0, y: 0 })
function openToolMenu(which, e) {
  const willOpen = which === 'para' ? !paraMenuOpen.value : !alignMenuOpen.value
  if (willOpen) {
    const r = e.currentTarget.getBoundingClientRect()
    toolMenuPos.value = { x: r.left, y: r.bottom + 5 }
  }
  paraMenuOpen.value = which === 'para' ? willOpen : false
  alignMenuOpen.value = which === 'align' ? willOpen : false
}
const PARA_ITEMS = [
  { key: 'para', label: '正文' },
  { key: 'h1', label: '一级标题' },
  { key: 'h2', label: '二级标题' },
  { key: 'h3', label: '三级标题' },
  { key: 'h4', label: '四级标题' },
  { key: 'quote', label: '引用' },
]
const ALIGN_ITEMS = [
  { key: 'left', label: '左对齐', icon: '<path d="M4.5 6h15M4.5 12h9M4.5 18h13"/>' },
  { key: 'center', label: '居中', icon: '<path d="M4.5 6h15M7.5 12h9M5.5 18h13"/>' },
  { key: 'right', label: '右对齐', icon: '<path d="M4.5 6h15M10.5 12h9M9.5 18h11"/>' },
]
function pickPara(key) {
  paraMenuOpen.value = false
  mdTool(key)
}
function pickAlign(key) {
  alignMenuOpen.value = false
  applyFormat({ kind: 'align', value: key })
}
/** 点下拉以外区域收起（与块手柄的 onDocMouseDown 同一套路） */
function onDocDownToolMenus(e) {
  if (e.target.closest?.('.tb-dd') || e.target.closest?.('.tb-menu')) return
  paraMenuOpen.value = false
  alignMenuOpen.value = false
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
  pvPushUndo()
  try {
    document.execCommand(command, false, value)
  } catch (e) {
    ElMessage.warning('当前选择不支持该格式')
    return
  }
  const sel = window.getSelection()
  if (sel && sel.rangeCount > 0) savedPreviewRange = sel.getRangeAt(0).cloneRange()
}

// ---- 预览编辑撤销/重做：innerHTML 快照栈 ----
// 浏览器 execCommand('undo') 只能撤销它自己记录的命令，块手柄/工具条里大量操作是
// DOM 直接替换（replaceWith/innerHTML），不在浏览器撤销栈里 → 点撤销毫无反应。
// 所以预览编辑模式改为自己维护快照栈：任何修改前压栈，撤销/重做就是换快照。
const PV_UNDO_MAX = 40
let pvUndoStack = []
let pvRedoStack = []
let pvInputTimer = null

function pvSnapshotHtml() {
  const el = previewEl()
  if (!el) return null
  // 剥离表格行列高亮类，避免污染撤销快照
  el.querySelectorAll('.lh-cell-row, .lh-cell-col')
    .forEach((n) => n.classList.remove('lh-cell-row', 'lh-cell-col'))
  return el.innerHTML
}

/** 即将修改预览 DOM 前调用：把当前状态压入撤销栈（并清空重做栈） */
function pvPushUndo() {
  const html = pvSnapshotHtml()
  if (html == null) return
  if (pvUndoStack[pvUndoStack.length - 1] !== html) {
    pvUndoStack.push(html)
    if (pvUndoStack.length > PV_UNDO_MAX) pvUndoStack.shift()
  }
  pvRedoStack = []
  previewUnsynced.value = true // 有改动待反推回源码
}

/** 连续键盘输入合并为一个撤销单元（800ms 防抖，只在输入前记一次） */
function onPreviewBeforeInput() {
  if (pvInputTimer) return
  pvPushUndo()
  pvInputTimer = setTimeout(() => { pvInputTimer = null }, 800)
}

function previewUndoRedo(redo) {
  const cur = pvSnapshotHtml()
  if (cur == null) return
  const from = redo ? pvRedoStack : pvUndoStack
  const to = redo ? pvUndoStack : pvRedoStack
  let html = from.pop()
  while (html != null && html === cur) html = from.pop() // 跳过与当前相同的快照
  if (html == null) {
    ElMessage.info(redo ? '没有可重做的操作' : '没有可撤销的操作')
    return
  }
  to.push(cur)
  const el = previewEl()
  el.innerHTML = html
  el.querySelectorAll('.lh-block-hover').forEach((n) => n.classList.remove('lh-block-hover')) // 快照里可能带着 hover 高亮
  savedPreviewRange = null
  hideBlockHandle()
}

function pvResetHistory() {
  pvUndoStack = []
  pvRedoStack = []
  clearTimeout(pvInputTimer)
  pvInputTimer = null
  pvPushUndo() // 进入编辑时的初始快照作为撤销底线
  previewUnsynced.value = false // 初始快照不算「待同步的改动」
}

/** 在预览区插入 HTML 片段（表格/图片/块级等 execCommand 覆盖不到的） */
function previewInsertHtml(html) {
  if (!previewFocusAndRestore()) return false
  pvPushUndo()
  document.execCommand('insertHTML', false, html)
  const sel = window.getSelection()
  if (sel && sel.rangeCount > 0) savedPreviewRange = sel.getRangeAt(0).cloneRange()
  return true
}

/** 折叠块的占位文字（与源码路径 FORMAT_PRESETS.details 保持一致） */
const DETAILS_PLACEHOLDER = '折叠内容'

/**
 * 代码块可选的语言（value 必须是 highlight.js 认得的 id，会写进围栏/class）。
 * 命名与「代码库」页保持一致，避免同一门语言在两个页面叫法不同。
 */
const CODE_LANGS = [
  { value: 'java', label: 'Java' },
  { value: 'python', label: 'Python' },
  { value: 'javascript', label: 'JavaScript' },
  { value: 'typescript', label: 'TypeScript' },
  { value: 'vue', label: 'Vue' },
  { value: 'html', label: 'HTML' },
  { value: 'css', label: 'CSS' },
  { value: 'json', label: 'JSON' },
  { value: 'yaml', label: 'YAML' },
  { value: 'xml', label: 'XML' },
  { value: 'sql', label: 'SQL' },
  { value: 'shell', label: 'Shell / Bash' },
  { value: 'go', label: 'Go' },
  { value: 'rust', label: 'Rust' },
  { value: 'c', label: 'C' },
  { value: 'cpp', label: 'C++' },
  { value: 'csharp', label: 'C#' },
  { value: 'kotlin', label: 'Kotlin' },
  { value: 'php', label: 'PHP' },
  { value: 'ruby', label: 'Ruby' },
  { value: 'markdown', label: 'Markdown' },
  { value: 'diff', label: 'Diff' },
  { value: 'plaintext', label: '纯文本' },
]

/** 上次选的代码语言（本次会话内记住，默认 java = 改动前的行为） */
const lastCodeLang = ref('java')

/** Stage 0 开关：?editor=block 时用 Tiptap 只读渲染器（默认关闭，绝不影响日常使用） */
const useBlockPreview = new URLSearchParams(window.location.search).get('editor') !== 'md'
const blockLinkBase = computed(() => id.value
  ? new URL(router.resolve({ name: 'noteEdit', params: { id: id.value } }).href, window.location.origin).href
  : '')

/** 块编辑器（?editor=block）编辑回写：Tiptap 反推出的 Markdown 写回源码 */
function onBlockPreviewUpdate(markdown) {
  form.value.content = markdown
  previewUnsynced.value = true
}

function onBlockOutline(text) {
  const paragraph = typeof text === 'string' ? text.trim() : ''
  if (!paragraph) return
  const message = `请用大纲写作法整理并扩写以下目标段落：先列出层级清晰的提纲，再补充各项要点、示例与待核实信息。保持原意和当前笔记的语言风格，输出 Markdown，仅处理目标段落。\n\n当前笔记：${form.value.title.trim() || '无标题笔记'}\n\n目标段落：\n${paragraph}`
  window.dispatchEvent(new CustomEvent('lh-agent-compose', { detail: { message } }))
}

/**
 * 光标所在块之后，在**源码**里的插入偏移；拿不到（没进编辑态/找不到块）返回 null。
 *
 * <p>原理：md-editor 给预览里每个块标了 `data-line`（该块在源码里的起始行号）。
 * 光标所在块的"下一块"的起始行就是插入点 —— 没有下一块则插到文末（返回 null 由调用方兜底）。
 */
function caretInsertOffset() {
  const host = previewEl()
  if (!host) return null
  const sel = window.getSelection()
  if (!sel || !sel.rangeCount) return null
  const node = sel.getRangeAt(0).startContainer
  const start = node.nodeType === 1 ? node : node.parentElement
  if (!start || !host.contains(start)) return null
  const block = start.closest('[data-line]')
  if (!block) return null
  const blocks = [...host.querySelectorAll('[data-line]')]
  const next = blocks[blocks.indexOf(block) + 1]
  const line = next ? Number(next.getAttribute('data-line')) : NaN
  if (!Number.isInteger(line) || line < 0) return null
  const lines = (form.value.content || '').split('\n')
  return lines.slice(0, Math.min(line, lines.length)).join('\n').length + (line > 0 ? 1 : 0)
}

/**
 * 改第 index 个代码围栏的语言（index 从 0 开始，与预览里 .md-editor-code 的顺序一致）。
 * 用于"点代码块左上角的语言名换语言"。找不到第 index 个围栏返回 false。
 */
function setCodeFenceLang(index, lang) {
  const lines = (form.value.content || '').split('\n')
  let idx = -1
  let inFence = false
  let done = false
  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(/^(\s*)(`{3,}|~{3,})(.*)$/)
    if (!m) continue
    if (!inFence) {
      idx++
      inFence = true
      if (idx === index) {
        lines[i] = m[1] + m[2] + lang
        done = true
        break
      }
    } else {
      inFence = false // 闭合围栏
    }
  }
  if (!done) return false
  form.value.content = lines.join('\n')
  previewUnsynced.value = false
  return true
}

/**
 * 给预览里的代码块画行号（语雀式）。
 *
 * <p>为什么另起一栏而不是用 CSS 计数器：md-editor 把代码按 `white-space: pre` 排版，
 * 行与行之间只是**换行符**（实测 15 行代码只有 2 个 `.md-editor-code-block` span），
 * 没有"一行一个元素"，计数器无从数起。
 * 这一栏用与代码**完全相同**的 font-size / line-height，逐行写数字，
 * 因此对齐是"同款排版"自然得到的，不需要逐行测量像素。
 * 纯装饰节点，已加入 htmlToMd 的 DROP_SELECTOR —— 反推 Markdown 时会剔除，
 * 不会把数字写进代码里。
 */
/** 代码块编辑弹窗：双击代码块打开（CodeMirror 在弹窗里，不与"预览即编辑"抢焦点） */
const codeEdit = reactive({ open: false, index: -1, lang: '', text: '' })

/**
 * 双击代码块 → 打开编辑弹窗。
 * 为什么放弹窗：内嵌在预览里会和"预览即编辑"抢焦点（点预览就 focusin → 重渲染 →
 * 编辑器实例被重建 → 光标看不见、回车重复插入），弹窗与预览机制零冲突。
 */
function onPreviewDblClick(e) {
  const box = e.target?.closest?.('.md-editor-code')
  if (!box) return
  const host = previewEl()
  const index = host ? [...host.querySelectorAll('.md-editor-code')].indexOf(box) : -1
  if (index < 0) return
  const code = box.querySelector('pre code')
  if (!code) return
  codeEdit.index = index
  codeEdit.lang = (code.className.match(/language-([\w+#.-]+)/) || [, ''])[1]
  codeEdit.text = (code.textContent || '').replace(/\n$/, '')
  codeEdit.open = true
}

/** 把编辑结果写回源码里第 index 个围栏（index 与预览里 .md-editor-code 的顺序一致） */
function saveCodeEdit() {
  const lines = (form.value.content || '').split('\n')
  let idx = -1
  let inFence = false
  let start = -1
  let end = -1
  for (let i = 0; i < lines.length; i++) {
    if (!/^\s*(`{3,}|~{3,})/.test(lines[i])) continue
    if (!inFence) {
      idx++
      if (idx === codeEdit.index) {
        start = i
        inFence = true
      }
    } else if (start >= 0) {
      end = i
      break
    }
  }
  if (start < 0 || end < 0) {
    ElMessage.warning('这个代码块不是围栏写法（可能是内联 HTML），请在源码模式里改')
    return
  }
  const fenceMatch = lines[start].match(/^(\s*)(`{3,}|~{3,})/)
  const fence = fenceMatch ? fenceMatch[2] : '```'
  const body = codeEdit.text.replace(/\n+$/, '').split('\n')
  lines.splice(start, end - start + 1, fence + (codeEdit.lang || ''), ...body, fence)
  form.value.content = lines.join('\n')
  previewUnsynced.value = false
  codeEdit.open = false
  nextTick(() => ElMessage.success('代码块已更新'))
}

/**
 * 给预览里的代码块画行号：**按行拆成 .code-line，每行一个 .code-num**。
 *
 * <p>为什么不是内嵌编辑器（试过，放弃）：CodeMirror 内嵌在预览里会和"预览即编辑"机制
 * 抢焦点 —— 点一下预览就触发 focusin → 重渲染 → 编辑器实例被重建，
 * 表现为"光标看不见"+"回车加两行（实例重复挂载）"。
 * 编辑代码改走另外的入口（不在预览 DOM 里和它抢）。
 *
 * <p>行间换行放进 display:none 的 .code-br：视觉换行由 .code-line{display:block} 决定，
 * 而 code.textContent 与原文一字不差（复制代码 / 反推 Markdown 都读它）。
 */
/**
 * 取代码块里**排除行号元素**的纯文本。
 * 必须排除 .code-num：否则重排第二次时会把行号当代码文本吞进去
 * （实测后果很严重：14 行代码被渲染成 28 行 —— 行号混进了内容）。
 */
function codeTextEl(code) {
  let out = ''
  const walk = (n) => {
    n.childNodes.forEach((c) => {
      if (c.nodeType === 3) out += c.textContent
      else if (c.nodeType === 1 && !c.classList.contains('code-num')) walk(c)
    })
  }
  walk(code)
  return out
}

/** 把光标放到 root 内第 offset 个字符处（重排后恢复光标用） */
function setCaretAtOffset(root, offset) {
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  let acc = 0
  let node = walker.nextNode()
  while (node) {
    const len = node.textContent.length
    if (acc + len >= offset) {
      const range = document.createRange()
      range.setStart(node, Math.max(0, Math.min(offset - acc, len)))
      range.collapse(true)
      const sel = window.getSelection()
      sel.removeAllRanges()
      sel.addRange(range)
      return true
    }
    acc += len
    node = walker.nextNode()
  }
  return false
}
function decorateCodeRowNumbers() {
  // 预览里的自动行号已下线：后处理 DOM 的路子反复出问题（重排会把行号吞进内容、打字会丢光标）。
  // 行号改由「双击代码块」的 CodeMirror 弹窗提供；预览保持 md-editor 的原样渲染。
  return
  const codes = [...document.querySelectorAll('.pv-md .md-editor-code pre code')]
  if (!codes.length) return
  const lineCount = (c) => (c.textContent || '').replace(/\n$/, '').split('\n').length
  // 已拆好就零写入返回（本函数由 MutationObserver 调用，写 DOM 会自触发成死循环）
  if (codes.every((c) => c.querySelectorAll(':scope > .code-line').length === lineCount(c))) return

  codes.forEach((code) => {
    // 打字重排时**必须保住光标**：先记下光标在整段代码文本里的字符偏移，重排完再放回去。
    // 否则每输入一个字符就把光标弄丢（这也是之前"新行没有号"的真正原因 —— 不敢重排）。
    const sel0 = window.getSelection()
    let caretOffset = -1
    if (sel0 && sel0.rangeCount && code.contains(sel0.getRangeAt(0).startContainer)) {
      const r0 = sel0.getRangeAt(0)
      const pre0 = document.createRange()
      pre0.selectNodeContents(code)
      pre0.setEnd(r0.startContainer, r0.startOffset)
      caretOffset = pre0.toString().length
    }
    // 1) 按文档顺序摊平文本片段，记住每段各自的祖先链（标签+类名）→ 保住高亮
    const pieces = []
    const walk = (node, chain) => {
      [...node.childNodes].forEach((child) => {
        if (child.nodeType === 3) {
          if (child.textContent) pieces.push({ text: child.textContent, chain })
        } else if (child.nodeType === 1) {
          walk(child, chain.concat(child))
        }
      })
    }
    walk(code, [])

    const frag = document.createDocumentFragment()
    let line = null
    let chainEls = []
    let chainSrc = []
    let lineNo = 0
    const newLine = () => {
      line = document.createElement('span')
      line.className = 'code-line'
      // 行号直接写元素（CSS 计数器实测会被加两次 → 2,4,6,8…）
      const num = document.createElement('span')
      num.className = 'code-num'
      num.setAttribute('aria-hidden', 'true')
      num.textContent = String(++lineNo)
      line.appendChild(num)
      frag.appendChild(line)
      chainEls = []
      chainSrc = []
    }
    // 一行内**复用**同一套祖先元素：每个片段都重克隆一遍祖先链会把一行拆成多个块级元素
    const hostFor = (chain) => {
      let host = line
      for (let i = 0; i < chain.length; i++) {
        if (chainSrc[i] === chain[i] && chainEls[i] && chainEls[i].parentElement === host) {
          host = chainEls[i]
          continue
        }
        for (let j = i; j < chain.length; j++) {
          const clone = chain[j].cloneNode(false)
          host.appendChild(clone)
          chainEls[j] = clone
          chainSrc[j] = chain[j]
          host = clone
        }
        return host
      }
      return host
    }
    newLine()
    pieces.forEach(({ text, chain }) => {
      const parts = text.split('\n')
      parts.forEach((part, i) => {
        if (i > 0) {
          const br = document.createElement('span')
          br.className = 'code-br'
          br.textContent = '\n'
          line.appendChild(br)
          newLine()
        }
        if (!part) return
        hostFor(chain).appendChild(document.createTextNode(part))
      })
    })
    // 结尾换行会多造一个空行元素（多一个号）——去掉；那个 \n 仍留在上一行的 .code-br 里
    if (line && !line.textContent.replace(/^\d+$/, '') && frag.lastChild === line) line.remove()

    code.textContent = ''
    code.appendChild(frag)
    if (caretOffset >= 0) setCaretAtOffset(code, caretOffset)
  })
}


// 预览重渲染后补行号（内容变化 / 切换明暗主题都会重渲染，行号要跟着重画）
// flush: 'post' 很关键：默认 flush 在 DOM 更新**之前**跑，首次加载时预览还是空的，
// 而笔记内容只设置一次、之后不再变化 —— 那样行号永远画不出来（实测 hasGutter=false）。
watch(() => [form.value.content, isDark.value], () => nextTick(decorateCodeRowNumbers), {
  immediate: true,
  flush: 'post',
})

// 行号栏是**注入的装饰节点**，预览一旦被 Vue 重渲染就会连它一起抹掉。
// 只靠 watch(content) 不够：内容没变但组件重渲染的情况（切模式、主题、异步渲染）
// 会把行号弄丢 —— 实测同一次会话里能抓到 20 个行号栏，下一次查询就一个都不剩。
// 所以用 MutationObserver 盯着预览容器：子节点一变就重画。
// 只观察 childList（不观察 attributes），而 decorate 只在**首次**给某个块加节点，
// 之后只改 style.top/dataset —— 不会触发自身再次回调，不存在死循环。
let rowNumberObserver = null

onMounted(() => {
  const attach = () => {
    const host = document.querySelector('.pv-md')
    if (!host) {
      setTimeout(attach, 300)
      return
    }
    rowNumberObserver = new MutationObserver(() => decorateCodeRowNumbers())
    rowNumberObserver.observe(host, { childList: true, subtree: true })
    decorateCodeRowNumbers()
  }
  attach()
})

onBeforeUnmount(() => {
  rowNumberObserver?.disconnect()
  rowNumberObserver = null
})
/**
 * 点代码块左上角的语言名 → 换语言（语雀式：语言就在块上，不必回源码改围栏）。
 * 预览里 .md-editor-code 的顺序与源码里围栏的顺序一致，所以用**序号**对应。
 * 注意：md-editor 自己的代码块头部有个折叠箭头也在左上区域，这里只认语言名那个元素。
 */
function onPreviewCodeLangClick(e) {
  const el = e.target?.closest?.('.md-editor-code-lang')
  if (!el) return false
  e.preventDefault()
  e.stopPropagation()
  const host = previewEl()
  const box = el.closest('.md-editor-code')
  const index = host && box ? [...host.querySelectorAll('.md-editor-code')].indexOf(box) : -1
  if (index < 0) return true
  askCodeLang().then((lang) => {
    if (!lang) return
    if (setCodeFenceLang(index, lang)) ElMessage.success(`已把代码块语言改为 ${lang}`)
    else ElMessage.warning('这个代码块不是围栏写法（可能是内联 HTML），请在源码模式里改')
  })
  return true
}

/**
 * 选代码语言（方案 A：页内弹层，和链接/图片用的是同一种交互）。
 *
 * <p>为什么不做成工具栏下拉：试过 `el-dropdown`（`@command` 与菜单项直接绑 click 都试了），
 * 菜单能弹出但插入不触发；换成页内弹层这条路径与链接/图片一致，是已验证可用的形态。
 * 用"语言按钮网格"而不是 `el-select`：弹层里放下拉要处理响应式更新，按钮网格点一下就定，
 * 少一层不确定性。
 *
 * @returns {Promise<string|null>} 选中的语言 id；取消返回 null
 */
function askCodeLang() {
  return new Promise((resolve) => {
    let done = false
    const finish = (v) => {
      if (done) return
      done = true
      resolve(v)
    }
    const box = ElMessageBox({
      title: '插入代码块',
      customClass: 'code-lang-box',
      message: h(
        'div',
        { class: 'clp' },
        CODE_LANGS.map((l) =>
          h(
            'button',
            {
              class: ['clp-item', { cur: l.value === lastCodeLang.value }],
              type: 'button',
              onClick: () => {
                lastCodeLang.value = l.value
                finish(l.value)
                // 关闭必须用静态方法：ElMessageBox(...) 返回的是 Promise，
                // 不是实例 —— 原来写 box.close() 会抛
                // "TypeError: box.close is not a function"，弹层关不掉、盖在正文上，
                // 用户看到的就是"点了代码块没反应"。
                ElMessageBox.close()
              },
            },
            l.label,
          ),
        ),
      ),
      showCancelButton: true,
      showConfirmButton: false,
      cancelButtonText: '取消',
    }).catch(() => finish(null))
    void box
  })
}

/**
 * 让用户填一个地址（链接 / 图片）。
 *
 * <p>为什么不用 `window.prompt`（原来是它）：
 * <ol>
 *   <li>样式是浏览器原生弹窗，和这个界面完全不搭；</li>
 *   <li>部分浏览器/嵌入式 webview 会**直接屏蔽** prompt（返回 null），功能静默失效；</li>
 *   <li>在 headless/自动化环境里它还会**永久阻塞页面** —— 我自己写审计脚本时就被它卡死过一次。</li>
 * </ol>
 * 换成 Element Plus 的输入弹层（项目里其它确认框本来就用它），取消/空值一律当"没填"处理。
 *
 * @returns {Promise<string|null>} 去掉首尾空白的地址；取消或空值返回 null
 */
function askUrl(title, initial) {
  return ElMessageBox.prompt(title, title, {
    inputValue: initial,
    confirmButtonText: '插入',
    cancelButtonText: '取消',
    inputPlaceholder: 'https://…',
  })
    .then(({ value }) => {
      const v = (value || '').trim()
      return v === '' || v === initial ? null : v
    })
    .catch(() => null)
}

/** 预览区里能作为"块"的标签：块级插入要挂到它的兄弟位置 */
const BLOCK_SELECTOR = 'p,li,h1,h2,h3,h4,h5,h6,blockquote,pre,table,details,div,hr,ul,ol'

/**
 * 在预览区插入**块级元素**（折叠块 / 提示块 / 居中段落等）。
 *
 * <p>为什么不走 {@code execCommand('insertHTML')} —— 这是踩了两次的坑：
 * <ol>
 *   <li><b>正文会丢</b>：`<details><summary>…</summary>折叠内容</details>` 插进去只剩空壳
 *       （正文整段消失，用户反馈"没有实际功能"）；</li>
 *   <li><b>块会被降级</b>：`<div class="md-callout">` 插在段落中间时被浏览器改写成
 *       一串行内 `<span>`（只剩底色），还把原段落劈成两半 —— 提示块看起来"没生效"。</li>
 * </ol>
 * 现在统一：**直接构造 DOM**（textContent 赋值，不经过 HTML 解析）+ 插到**光标所在块之后**
 * 的兄弟位置（找不到块就追加到末尾）。@param placeholderSelected 是否把新块内的文字选中，
 * 方便用户直接打字替换（只在正文是占位文字时才选，避免覆盖用户选中的内容）。
 */
function previewInsertBlock(el, placeholderSelected = false) {
  const host = previewEl()
  if (!host || !previewFocusAndRestore()) return false
  pvPushUndo()
  const sel = window.getSelection()
  let block = null
  if (sel && sel.rangeCount > 0) {
    const node = sel.getRangeAt(0).startContainer
    const start = node.nodeType === 1 ? node : node.parentElement
    block = start ? start.closest(BLOCK_SELECTOR) : null
    if (block && !host.contains(block)) block = null
  }
  if (block && block.parentElement) block.parentElement.insertBefore(el, block.nextSibling)
  else host.appendChild(el)

  if (placeholderSelected) {
    const range = document.createRange()
    range.selectNodeContents(el)
    if (sel) {
      sel.removeAllRanges()
      sel.addRange(range)
      savedPreviewRange = range.cloneRange()
    }
  }
  return true
}

/**
 * 插入折叠块（预览路径）。
 *
 * <p>为什么不用 execCommand('insertHTML') 拼字符串（踩过的坑，两次）：
 * 原来写 `<details><summary>点击展开</summary>折叠内容</details>` ——
 * ① 裸文本正文经 contenteditable 的片段解析后**会整段丢掉**；
 * ② 改用 `<p>` 包住之后正文仍然丢（实测插入结果是 `<details><summary>点击展开</summary><p></p></details>`）。
 * 结果就是用户拿到一个空壳：点开「点击展开」什么都没有，反馈"只会有点击展开四个字，没有实际功能"。
 * 现在**直接构造 DOM**（textContent 赋值，不经过 HTML 解析），从根上绕开这两次踩坑，
 * 并且把占位文字选中（与源码路径 insertBlock 的行为对齐），用户可以直接打字替换。
 */
function previewInsertDetails(selectedText) {
  const el = previewEl()
  if (!el || !previewFocusAndRestore()) return
  const text = (selectedText || '').trim()

  const details = document.createElement('details')
  const summary = document.createElement('summary')
  summary.textContent = '点击展开'
  const body = document.createElement('p')
  body.textContent = text || DETAILS_PLACEHOLDER
  details.append(summary, body)

  // 复用统一的块级插入（构造 DOM + 插到光标块之后），并选中占位文字
  previewInsertBlock(details, !text)
}

/** 用标签包住预览里的选中文字（无选中时插入占位） */
function previewWrap(before, after, placeholder = '文本') {
  if (!previewFocusAndRestore()) return
  // 代码块里不给套内联格式：围栏代码在 Markdown 里**无法**表达颜色/字号，
  // 硬套 <font> 只会把代码文字弄丢（用户反馈"代码块里面的字改成红色会消失"）——
  // execCommand 在 <pre> 里替换内容时会把原文本吃掉。
  // 这里直接提示，不动内容：宁可"没反应"，也不能弄丢代码。
  const sel0 = window.getSelection()
  const startEl =
    sel0 && sel0.rangeCount
      ? (() => {
          const n = sel0.getRangeAt(0).startContainer
          return n.nodeType === 1 ? n : n.parentElement
        })()
      : null
  if (startEl && startEl.closest('pre, .md-editor-code')) {
    ElMessage.info('代码块内的文字不参与"颜色/字号"这类内联格式（Markdown 围栏代码不支持），可在源码里用其它方式标注')
    return
  }
  pvPushUndo()
  const sel = window.getSelection()
  const text = sel ? sel.toString() : ''
  document.execCommand('insertHTML', false, before + escapeHtml(text || placeholder) + after)
  const s2 = window.getSelection()
  if (s2 && s2.rangeCount > 0) savedPreviewRange = s2.getRangeAt(0).cloneRange()
}

/** Markdown 工具条 → 预览区等价操作（Turndown 可反向还原的标签/命令） */
function previewMdTool(name, arg) {
  switch (name) {
    case 'undo': return previewUndoRedo(false)
    case 'redo': return previewUndoRedo(true)
    case 'bold': return previewExec('bold')
    case 'italic': return previewExec('italic')
    case 'strike': return previewExec('strikeThrough')
    case 'underline': return previewExec('underline')
    case 'para': return previewExec('formatBlock', 'p')
    case 'h1': return previewExec('formatBlock', 'h1')
    case 'h2': return previewExec('formatBlock', 'h2')
    case 'h3': return previewExec('formatBlock', 'h3')
    case 'h4': return previewExec('formatBlock', 'h4')
    case 'quote': return previewExec('formatBlock', 'blockquote')
    case 'ul': return previewExec('insertUnorderedList')
    case 'ol': return previewExec('insertOrderedList')
    case 'todo': {
      // 同折叠块/提示块：块级内容必须直接构造 DOM 并挂成兄弟节点，
      // 用 insertHTML 插 `<ul>` 会被降级成行内内容、还会把光标所在段落劈开（实测）。
      const ul = document.createElement('ul')
      const li = document.createElement('li')
      const box = document.createElement('input')
      box.type = 'checkbox'
      box.disabled = true
      li.append(box, document.createTextNode(' 任务'))
      ul.append(li)
      return previewInsertBlock(ul, true)
    }
    case 'hr':
      return previewInsertBlock(document.createElement('hr'))
    case 'inlineCode': return previewWrap('<code>', '</code>', '代码')
    case 'link': {
      // 先取选区（会把选区存进 savedPreviewRange），弹层关掉后再插 —— 否则焦点在弹层上，
      // 插进去的位置就没了。原来用 window.prompt，现在换成页内弹层（见 askUrl 的说明）。
      const text = previewSelText()
      askUrl('链接地址', 'https://').then((url) => {
        if (!url) return
        // 链接是**行内**元素：插在光标处正是期望行为，继续走 execCommand
        previewInsertHtml(`<a href="${url}">${escapeHtml(text || url)}</a>`)
      })
      return
    }
    case 'image': {
      askUrl('图片地址', 'https://').then((url) => {
        if (!url) return
        previewInsertHtml(`<img src="${url}" alt="图片描述" />`)
      })
      return
    }
    case 'codeBlock': {
      const lang = arg || lastCodeLang.value
      lastCodeLang.value = lang
      // 直接改**源码**（form.content 是唯一真源），预览会自动重渲染成正式代码块。
      // 为什么不在预览里插 DOM：插进去的是裸 <pre>，md-editor 不认识它，
      // 渲染出来是没有语言头/复制/行号的空盒子，还要靠"反推回 Markdown"才能变正式块。
      //
      // 插入位置 = **光标所在块之后**：预览的每个块都带 data-line（md-editor 标的源码行号），
      // 取光标所在块、再看它后面那个块的 data-line，围栏就插在那之前（没有下一个块就追加到文末）。
      const src = form.value.content || ''
      // 围栏内**不留空行**：原来是 ```lang + 空行 + ```，
      // 那个空行会被行号算成第 1 行 —— 用户接着往下写代码，看到的就是"序号整体差一行"。
      const fence = '```' + lang + '\n```'
      const at = caretInsertOffset()
      form.value.content =
        at == null
          ? src.replace(/\s*$/, '') + '\n\n' + fence + '\n'
          : src.slice(0, at).replace(/\s*$/, '') + '\n\n' + fence + '\n\n' + src.slice(at).replace(/^\s*/, '')
      previewUnsynced.value = false
      nextTick(() => ElMessage.success(`已插入 ${lang} 代码块，点击代码块内部即可开始写`))
      return
    }
    case 'table': {
      const table = document.createElement('table')
      const thead = table.createTHead()
      const hr = thead.insertRow()
      for (const label of ['列A', '列B']) {
        const th = document.createElement('th')
        th.textContent = label
        hr.append(th)
      }
      const tbody = table.createTBody()
      const bodyRow = tbody.insertRow()
      bodyRow.insertCell().textContent = ' '
      bodyRow.insertCell().textContent = ' '
      return previewInsertBlock(table)
    }
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
      return previewInsertBlock(
        Object.assign(document.createElement('p'), {
          style: 'text-align: center',
          textContent: text || '居中文字',
        }),
        !text,
      )
    }
    case 'align': {
      // 段落对齐：作用于光标所在块（左对齐 = 移除对齐样式）
      if (!previewFocusAndRestore()) return
      const sel = window.getSelection()
      const blk = sel?.anchorNode ? findHoverBlock(sel.anchorNode) : null
      if (blk && !['UL', 'OL', 'TABLE', 'HR'].includes(blk.tagName)) {
        pvPushUndo()
        blk.style.textAlign = value === 'left' ? '' : value
      }
      return
    }
    case 'details': {
      return previewInsertDetails(previewSelText())
    }
    case 'callout': {
      const text = previewSelText()
      // 提示块必须插成**块级兄弟节点**（见 previewInsertBlock 的说明）：
      // 原来用 insertHTML 在段落中间插 `<div class="md-callout">`，实测被浏览器降级成
      // 一串行内 <span>（只剩个底色），还把原段落劈成两半 —— 用户看到的就是"提示框没生效"。
      const div = document.createElement('div')
      div.className = 'md-callout md-callout-tip'
      const p = document.createElement('p')
      p.textContent = text || '提示内容'
      div.append(p)
      return previewInsertBlock(div, !text)
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
  pvPushUndo()
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
  if (cmd !== 'copy') pvPushUndo() // 纯复制不改 DOM，不入撤销栈
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
  // 刚打开、还没在本会话里保存过：此时**已经和库里一致**，不能因为 lastSavedAt 是空就说「未保存」——
  // 那会让人以为"打开笔记就产生了改动"（实测四个笔记全这样）。只有真的改过才提示未保存。
  if (!dirty.value) return { cls: 'saved', text: '已保存' }
  return { cls: 'dirty', text: '未保存' }
})

// ---- 表格操作（源码 Markdown 表格 + 预览编辑渲染表格，共用一套操作栏）----
const tableInfo = ref(null)

/** 预览编辑：光标所在的表格单元格（td/th），不在表格内返回 null */
function pvActiveCell() {
  const el = previewEl()
  const sel = window.getSelection()
  if (!el || !sel?.anchorNode || !el.contains(sel.anchorNode)) return null
  const node = sel.anchorNode.nodeType === 1 ? sel.anchorNode : sel.anchorNode.parentElement
  return node?.closest?.('td, th') || null
}

/** 预览编辑：清除表格行列高亮 */
function pvClearTableHl() {
  previewEl()?.querySelectorAll('.lh-cell-row, .lh-cell-col')
    .forEach((n) => n.classList.remove('lh-cell-row', 'lh-cell-col'))
}

/** 预览编辑：高亮光标所在的行与列（跟随光标） */
function pvRefreshTableHl() {
  pvClearTableHl()
  const cell = pvActiveCell()
  if (!cell) return
  const col = cell.cellIndex
  for (const c of cell.parentElement.children) c.classList.add('lh-cell-row')
  const table = cell.closest('table')
  for (const r of Array.from(table.rows)) {
    if (r.cells[col]) r.cells[col].classList.add('lh-cell-col')
  }
}

/** 刷新表格操作栏显隐与可用性（源码 / 预览编辑两种模式） */
function updateTableState() {
  if (previewEditing.value) {
    const cell = pvActiveCell()
    if (!cell) {
      tableInfo.value = null
      pvClearTableHl()
      return
    }
    const table = cell.closest('table')
    tableInfo.value = {
      mode: 'preview',
      canDeleteRow: table.rows.length > 1,
      canDeleteCol: (table.rows[0]?.cells.length || 0) > 1,
      canSetHeader: false,
      canAlign: false,
    }
    pvRefreshTableHl()
    return
  }
  pvClearTableHl()
  const view = editorRef.value?.getEditorView?.()
  if (!view || !showEditorPane.value) {
    tableInfo.value = null
    return
  }
  const t = findTable(view, view.state.selection.main.head)
  const canDel = t ? t.sepIndex === -1 || t.cursorLine > t.sepIndex : false
  tableInfo.value = t
    ? {
        mode: 'source',
        colCount: t.colCount,
        canDeleteRow: canDel,
        canDeleteCol: t.colCount > 1,
        canSetHeader: canDel,
        canAlign: t.sepIndex >= 0,
      }
    : null
}

const TABLE_OPS = {
  rowAbove: (view, pos) => addRow(view, pos, 'above'),
  rowBelow: (view, pos) => addRow(view, pos, 'below'),
  rowDelete: (view, pos) => deleteRow(view, pos),
  colLeft: (view, pos) => addCol(view, pos, 'left'),
  colRight: (view, pos) => addCol(view, pos, 'right'),
  colDelete: (view, pos) => deleteCol(view, pos),
  setHeader: (view, pos) => setHeaderRow(view, pos),
  alignLeft: (view, pos) => alignColumn(view, pos, 'left'),
  alignCenter: (view, pos) => alignColumn(view, pos, 'center'),
  alignRight: (view, pos) => alignColumn(view, pos, 'right'),
  deleteTable: (view, pos) => deleteTable(view, pos),
}

/** 源码模式：执行表格操作（直接改 CodeMirror 文档，md-editor 内部同步回 v-model） */
function sourceTableOp(key) {
  const view = editorRef.value?.getEditorView?.()
  if (!view) return
  const fn = TABLE_OPS[key]
  if (!fn) return
  const edit = fn(view, view.state.selection.main.head)
  if (!edit) return
  view.dispatch({
    changes: { from: edit.from, to: edit.to, insert: edit.insert },
    selection: { anchor: edit.select },
  })
  view.focus()
  updateTableState()
}

// ---- 预览编辑：表格 DOM 操作（改渲染后的 <table>，同步时由 Turndown 反推回 Markdown）----
function pvCellTemplate(isHead) {
  const c = document.createElement(isHead ? 'th' : 'td')
  c.appendChild(document.createElement('br'))
  return c
}

function pvAddRow(where) {
  const cell = pvActiveCell()
  if (!cell) return
  const tr = cell.parentElement
  const isHead = tr.parentElement.tagName === 'THEAD'
  const colCount = tr.cells.length
  pvClearTableHl()
  pvPushUndo()
  const newTr = document.createElement('tr')
  for (let i = 0; i < colCount; i++) newTr.appendChild(pvCellTemplate(isHead))
  tr.parentElement.insertBefore(newTr, where === 'above' ? tr : tr.nextSibling)
}

function pvDeleteRow() {
  const cell = pvActiveCell()
  if (!cell) return
  const table = cell.closest('table')
  if (table.rows.length <= 1) return
  const tr = cell.parentElement
  const section = tr.parentElement
  pvClearTableHl()
  pvPushUndo()
  section.removeChild(tr)
  if (!section.children.length) section.remove()
}

function pvAddCol(where) {
  const cell = pvActiveCell()
  if (!cell) return
  const table = cell.closest('table')
  const insertAt = where === 'left' ? cell.cellIndex : cell.cellIndex + 1
  pvClearTableHl()
  pvPushUndo()
  for (const tr of Array.from(table.rows)) {
    const isHead = tr.parentElement.tagName === 'THEAD'
    tr.insertBefore(pvCellTemplate(isHead), tr.cells[insertAt] || null)
  }
}

function pvDeleteCol() {
  const cell = pvActiveCell()
  if (!cell) return
  const table = cell.closest('table')
  if ((table.rows[0]?.cells.length || 0) <= 1) return
  const col = cell.cellIndex
  pvClearTableHl()
  pvPushUndo()
  for (const tr of Array.from(table.rows)) {
    if (tr.cells[col]) tr.cells[col].remove()
  }
}

function pvDeleteTable() {
  const cell = pvActiveCell()
  if (!cell) return
  pvClearTableHl()
  pvPushUndo()
  cell.closest('table').remove()
  hideBlockHandle()
}

function previewTableOp(key) {
  switch (key) {
    case 'rowAbove': pvAddRow('above'); break
    case 'rowBelow': pvAddRow('below'); break
    case 'rowDelete': pvDeleteRow(); break
    case 'colLeft': pvAddCol('left'); break
    case 'colRight': pvAddCol('right'); break
    case 'colDelete': pvDeleteCol(); break
    case 'deleteTable': pvDeleteTable(); break
  }
  updateTableState()
}

/** 表格操作入口：按当前模式分发 */
function tableOp(key) {
  if (previewEditing.value) previewTableOp(key)
  else sourceTableOp(key)
}

// 切到「预览」标签 / 阅读模式时源码栏隐藏，及时收起表格操作栏
watch(showEditorPane, () => updateTableState())
watch(previewEditing, () => updateTableState())

// ---- 预览编辑：拖动调节表格列宽 / 行高 ----
let resizeCursor = ''
let resizeDrag = null

function setResizeCursor(c) {
  if (resizeCursor === c) return
  resizeCursor = c
  if (pvScrollRef.value) pvScrollRef.value.style.cursor = c || ''
}

/** 悬停检测：贴近单元格右缘 = 调列宽，下缘 = 调行高 */
function onTableResizeMove(e) {
  if (resizeDrag) {
    onTableResizeDrag(e)
    return
  }
  if (!previewEditing.value) {
    setResizeCursor('')
    return
  }
  const cell = e.target?.closest?.('td, th')
  const el = previewEl()
  if (!cell || !el || !el.contains(cell)) {
    setResizeCursor('')
    return
  }
  const r = cell.getBoundingClientRect()
  if (e.clientX >= r.right - 5 && e.clientX <= r.right + 2) setResizeCursor('col-resize')
  else if (e.clientY >= r.bottom - 5 && e.clientY <= r.bottom + 2) setResizeCursor('row-resize')
  else setResizeCursor('')
}

function onTableResizeDown(e) {
  if (!resizeCursor || !previewEditing.value) return
  const cell = e.target?.closest?.('td, th')
  if (!cell) return
  const type = resizeCursor === 'col-resize' ? 'col' : 'row'
  const table = cell.closest('table')
  const tr = cell.parentElement
  e.preventDefault()
  e.stopPropagation()
  // 一次拖拽 = 一个撤销单元：开始前压一次快照
  pvPushUndo()
  resizeDrag = {
    type, table, tr, col: cell.cellIndex,
    startX: e.clientX, startY: e.clientY,
    startSize: type === 'col' ? cell.getBoundingClientRect().width : tr.getBoundingClientRect().height,
  }
  window.addEventListener('mousemove', onTableResizeDrag)
  window.addEventListener('mouseup', onTableResizeUp)
  document.body.classList.add('is-col-resizing')
}

function onTableResizeDrag(e) {
  const d = resizeDrag
  if (!d) return
  if (d.type === 'col') {
    ensureColgroup(d.table)
    const colEl = findColgroup(d.table)?.children[d.col]
    if (colEl) colEl.style.width = Math.max(40, d.startSize + (e.clientX - d.startX)) + 'px'
  } else {
    d.tr.style.height = Math.max(24, d.startSize + (e.clientY - d.startY)) + 'px'
  }
}

function onTableResizeUp() {
  resizeDrag = null
  window.removeEventListener('mousemove', onTableResizeDrag)
  window.removeEventListener('mouseup', onTableResizeUp)
  document.body.classList.remove('is-col-resizing')
  updateTableState()
}

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
  if (previewEditing.value || previewUnsynced.value) {
    // 切笔记前先把预览里没反推的改动同步回 Markdown，否则它会被下一份内容覆盖掉
    syncPreviewToSource(true)
  }
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
  // 悬浮面板的「融入当前笔记」把 Markdown 追加进正文（见 onAgentMerge）
  window.addEventListener(AGENT_NOTE_MERGE_EVENT, onAgentMerge)
  window.addEventListener(AGENT_NOTE_UPDATED_EVENT, onAgentNoteUpdated)
  document.addEventListener('mousedown', onDocDownToolMenus)
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
  scrollResizeObserver = new ResizeObserver(queueScrollSync)
  const sourceScroller = editorScrollEl()
  const previewBody = pvScrollRef.value?.querySelector('.pv-inner')
  if (sourceScroller) scrollResizeObserver.observe(sourceScroller)
  if (previewBody) scrollResizeObserver.observe(previewBody)
  // 表格操作栏：跟随光标位置（键盘/鼠标移动后刷新是否处于表格内）
  editorWrapRef.value?.addEventListener('keyup', updateTableState)
  editorWrapRef.value?.addEventListener('mouseup', updateTableState)
  // 表格列宽/行高拖动调节（预览编辑模式）
  pvScrollRef.value?.addEventListener('mousemove', onTableResizeMove)
  pvScrollRef.value?.addEventListener('mousedown', onTableResizeDown)
  updateTableState()
})

onBeforeUnmount(() => {
  window.removeEventListener('lh-meta-changed', loadMeta)
  window.removeEventListener('keydown', onGlobalKeydown)
  window.removeEventListener('resize', measureLayout)
  window.removeEventListener(AGENT_NOTE_MERGE_EVENT, onAgentMerge)
  window.removeEventListener(AGENT_NOTE_UPDATED_EVENT, onAgentNoteUpdated)
  // 离开笔记页：广播"现在没开笔记"，面板的主操作随之回退成「保存为笔记」
  window.dispatchEvent(new CustomEvent(AGENT_NOTE_CONTEXT_EVENT, { detail: null }))
  document.removeEventListener('mousedown', onDocDownToolMenus)
  document.removeEventListener('keydown', onPreviewKeydown)
  const ed = editorScrollEl()
  if (ed) ed.removeEventListener('scroll', onEditorScroll)
  editorWrapRef.value?.removeEventListener('keyup', updateTableState)
  editorWrapRef.value?.removeEventListener('mouseup', updateTableState)
  pvScrollRef.value?.removeEventListener('mousemove', onTableResizeMove)
  pvScrollRef.value?.removeEventListener('mousedown', onTableResizeDown)
  window.removeEventListener('mousemove', onTableResizeDrag)
  window.removeEventListener('mouseup', onTableResizeUp)
  clearTimeout(scrollSyncTimer)
  scrollResizeObserver?.disconnect()
  if (scrollRaf) cancelAnimationFrame(scrollRaf)
  if (scrollSyncRaf) cancelAnimationFrame(scrollSyncRaf)
  // AI 处理：清掉进度计时器并中断流式请求，避免离开页面后请求与定时器继续跑
  stopAiProgress()
  if (aiAbort) {
    aiAbort.abort()
    aiAbort = null
  }
  focusMode.value = false // 专注模式是页面级状态，离开必须复位，否则侧栏消失
})
</script>

<template>
  <div class="edit-page" :class="{ 'is-focus': focusMode, 'is-reading': readingMode }" v-loading="loading">
    <!-- ======== 顶部第一行：返回 · 标题 · 分类 · 标签 · 保存状态 · AI助手 · 更多 · 保存 ========
         阅读模式**保留**这一行（用户要求：阅读时工具栏不消失，并冻结在顶部）；只把右端的
         「保存」旁边多一个「退出阅读」，因为原来的极简顶栏已经去掉了。 -->
    <div class="ed-top">
      <button class="icon-btn" type="button" title="返回列表" @click="onBack">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <path d="M19 12H5M11 18l-6-6 6-6" />
        </svg>
      </button>

      <input ref="titleInputRef" v-model="form.title" class="title-input" placeholder="输入笔记标题…" maxlength="200" />

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

      <!-- AI 动作直接放顶栏：原来是「点开 AI 助手 → 再选一项」，两步。
           「打开 AI 对话」已移除 —— 右下角本来就有常驻的智能体悬浮入口，属于重复入口。
           默认无边框保持安静，hover 才浮出底色；实心主操作只留给右侧「保存」。 -->
      <button
        class="ai-act"
        type="button"
        :disabled="aiBusy"
        title="AI 润色：修正错别字、语病、术语大小写与标点（长文自动分段，弹窗内显示进度）"
        @click="aiProcess('polish')"
      >
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M12 3.8l1.85 4.55L18.4 10.2l-4.55 1.85L12 16.6l-1.85-4.55L5.6 10.2l4.55-1.85z" />
          <path d="M18.6 16.2l.7 1.7 1.7.7-1.7.7-.7 1.7-.7-1.7-1.7-.7 1.7-.7z" />
        </svg>
        <span>润色</span>
      </button>

      <button
        class="ai-act"
        type="button"
        :disabled="aiBusy"
        title="整理格式：规范标题层级、列表、表格与代码块语言标注（长文自动分段，弹窗内显示进度）"
        @click="aiProcess('format')"
      >
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M5 19.5 14.6 9.9M13.2 8.5l3 3" />
          <path d="M18.4 3.4l.62 1.58 1.58.62-1.58.62-.62 1.58-.62-1.58-1.58-.62 1.58-.62z" />
        </svg>
        <span>整理格式</span>
      </button>

      <button
        class="read-exit"
        type="button"
        @click="toggleReading"
        :title="readingMode
          ? '切到源码对照模式：左源码 / 右预览 / 大纲，适合边改边看差异'
          : '切回单栏阅读模式：只有正文一栏，点进去就能直接写'"
      >
        <span class="btn-ico">
          <svg v-if="readingMode" viewBox="0 0 24 24">
            <path d="M5 4.5h5.5a2 2 0 0 1 2 2v13a2 2 0 0 0-2-2H5zM19 4.5h-5.5a2 2 0 0 0-2 2v13a2 2 0 0 1 2-2H19z" />
          </svg>
          <svg v-else viewBox="0 0 24 24">
            <path d="M7 3.5h7a3 3 0 0 1 3 3V20.5H7a3 3 0 0 1-3-3v-11a3 3 0 0 1 3-3Z" />
            <path d="M10 9h4M10 12h4M10 15h2" />
          </svg>
        </span>{{ readingMode ? '源码对照' : '阅读模式' }}
      </button>

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
            <el-dropdown-item command="insertTemplate" divided>
              <span class="dd-ico"><svg viewBox="0 0 24 24"><path d="M5 4.5h9l5 5v10a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-14a1 1 0 0 1 1-1Z" /><path d="M14 4.5v5h5M8 14h8M8 17h5" /></svg></span>插入 Markdown 模板
            </el-dropdown-item>
            <el-dropdown-item command="toggleOutline" divided>
              <span class="dd-ico"><svg viewBox="0 0 24 24"><path d="M5 6h14M5 12h9M5 18h12" /></svg></span>{{ outlineShown ? '收起大纲' : '展开大纲' }}
            </el-dropdown-item>
            <el-dropdown-item command="toggleFocus">
              <span class="dd-ico">
                <svg v-if="focusMode" viewBox="0 0 24 24"><path d="M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5" /></svg>
                <svg v-else viewBox="0 0 24 24"><path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5" /></svg>
              </span>{{ focusMode ? '退出专注模式' : '专注模式（隐藏侧栏与大纲）' }}
            </el-dropdown-item>
            <el-dropdown-item command="toggleReading">
              <span class="dd-ico"><svg viewBox="0 0 24 24"><path d="M5 4.5h5.5a2 2 0 0 1 2 2v13a2 2 0 0 0-2-2H5zM19 4.5h-5.5a2 2 0 0 0-2 2v13a2 2 0 0 1 2-2H19z" /></svg></span>{{ readingMode ? '源码对照模式（左源码 / 右预览）' : '回到单栏阅读模式' }}
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>

      <el-button type="primary" class="save-btn" :loading="saving" @click="save">{{ isNew ? '创建' : '保存' }}</el-button>
    </div>

    <el-alert v-if="agentEditConflict" type="warning" :closable="false" show-icon>
      <template #title>库中正文已更新，当前草稿已保留。<el-button link type="primary" @click="reloadAfterAgentEdit">加载最新正文</el-button></template>
    </el-alert>

    <!-- ======== 顶部第二行：语雀式工具条（单栏阅读模式也保留，作用于那一栏正文；源码对照下作用于当前有焦点的编辑区） ======== -->
    <div
      ref="toolsRef"
      class="ed-tools"
      :class="{ 'has-left': toolsScroll.left, 'has-right': toolsScroll.right }"
      v-if="showTools"
      @mousedown.prevent
      @scroll="updateToolsScroll"
      @wheel="onToolsWheel"
    >
      <template v-if="showTools">
        <!-- 撤销/重做/格式刷（语雀最左组） -->
        <button class="tb" type="button" title="撤销 (Ctrl+Z)" @click="mdTool('undo')">
          <svg viewBox="0 0 24 24"><path d="M8.5 5.5 4.5 9.5l4 4M4.5 9.5h9a5.5 5.5 0 0 1 0 11h-2" /></svg>
        </button>
        <button class="tb" type="button" title="重做 (Ctrl+Y)" @click="mdTool('redo')">
          <svg viewBox="0 0 24 24"><path d="m15.5 5.5 4 4-4 4M19.5 9.5h-9a5.5 5.5 0 0 0 0 11h2" /></svg>
        </button>
        <button class="tb tb-painter" type="button" :class="{ on: formatPainter.active }" title="格式刷：先选带格式文字点我，再选目标文字点我" @click="formatPainterClick">
          <!--
            刷子图标（Lucide `paintbrush`，MIT）。
            选它的过程：把 5 个候选按真实尺寸（16px）与放大尺寸渲染出来对比过 ——
            原来的自绘曲线在 16px 下像一个"小勾/音符"（用户："都不是刷子"）；
            Lucide `brush`（笔+颜料团）和自绘扁刷在 16px 下都像**笔**；`paintbrush-2` 像铲子。
            只有这一条是"斜置宽头刷 + 三根刷毛 + 手柄"，两个尺寸下都一眼认得出是刷子。
          -->
          <svg viewBox="0 0 24 24">
            <path d="m14.622 17.897-10.68-2.913" />
            <path d="M18.376 2.622a1 1 0 1 1 3.002 3.002L17.36 9.643a.5.5 0 0 0 0 .707l.944.944a2.41 2.41 0 0 1 0 3.408l-.944.944a.5.5 0 0 1-.707 0L8.354 7.348a.5.5 0 0 1 0-.707l.944-.944a2.41 2.41 0 0 1 3.408 0l.944.944a.5.5 0 0 0 .707 0z" />
            <path d="M9 8c-1.804 2.71-3.97 3.46-6.583 3.948a.507.507 0 0 0-.302.819l7.32 8.883a1 1 0 0 0 1.185.204C12.735 20.405 16 16.792 16 15" />
          </svg>
        </button>

        <i class="tb-sep" />

        <!-- 段落类型（语雀「正文 ∨」） -->
        <div class="tb-dd">
          <button class="tb tb-para" type="button" :class="{ on: paraMenuOpen }" title="段落类型"
            @click="openToolMenu('para', $event)">
            正文<svg class="tb-caret" viewBox="0 0 24 24"><path d="m7 10 5 5 5-5" /></svg>
          </button>
          <div v-if="paraMenuOpen" class="tb-menu" :style="{ left: toolMenuPos.x + 'px', top: toolMenuPos.y + 'px' }" @mousedown.prevent @click.stop>
            <button v-for="it in PARA_ITEMS" :key="it.key" class="tb-menu-item" type="button" @click="pickPara(it.key)">
              <span class="tbm-key" :class="{ txt: it.key === 'para' }">{{ it.key === 'para' ? 'T' : it.key === 'quote' ? '❝' : it.key.toUpperCase() }}</span>{{ it.label }}
            </button>
          </div>
        </div>

        <i class="tb-sep" />

        <button class="tb tb-txt" type="button" title="加粗" @click="mdTool('bold')"><b>B</b></button>
        <button class="tb tb-txt" type="button" title="斜体" @click="mdTool('italic')"><i>I</i></button>
        <button class="tb tb-txt" type="button" title="删除线" @click="mdTool('strike')"><s>S</s></button>
        <button class="tb tb-txt" type="button" title="下划线" @click="mdTool('underline')"><u>U</u></button>

        <!-- 颜色/字号/上下标/高亮（FormatBar：色条指示 + 字号∨） -->
        <i class="tb-sep" />
        <FormatBar bare class="ed-format" @apply="applyFormat" />
        <i class="tb-sep" />

        <!-- 对齐（语雀「对齐 ∨」） -->
        <div class="tb-dd">
          <button class="tb" type="button" :class="{ on: alignMenuOpen }" title="对齐方式"
            @click="openToolMenu('align', $event)">
            <svg viewBox="0 0 24 24"><path d="M4.5 6h15M7.5 12h9M5.5 18h13" /></svg>
            <svg class="tb-caret" viewBox="0 0 24 24"><path d="m7 10 5 5 5-5" /></svg>
          </button>
          <div v-if="alignMenuOpen" class="tb-menu" :style="{ left: toolMenuPos.x + 'px', top: toolMenuPos.y + 'px' }" @mousedown.prevent @click.stop>
            <button v-for="it in ALIGN_ITEMS" :key="it.key" class="tb-menu-item" type="button" @click="pickAlign(it.key)">
              <svg viewBox="0 0 24 24" v-html="it.icon"></svg>{{ it.label }}
            </button>
          </div>
        </div>
        <button class="tb" type="button" title="无序列表" @click="mdTool('ul')">
          <svg viewBox="0 0 24 24"><circle cx="5" cy="6.5" r="1.1" class="fill" /><circle cx="5" cy="12" r="1.1" class="fill" /><circle cx="5" cy="17.5" r="1.1" class="fill" /><path d="M9.5 6.5h10M9.5 12h10M9.5 17.5h10" /></svg>
        </button>
        <button class="tb" type="button" title="有序列表" @click="mdTool('ol')">
          <svg viewBox="0 0 24 24"><path d="M9.5 6.5h10M9.5 12h10M9.5 17.5h10" /><path d="M4 5.2 5.2 4.5V8M3.8 10.7c.2-.5.8-.8 1.3-.6.6.2.9.8.6 1.3l-1.9 2.4h2.4M3.9 16.5h1.3c.5 0 .9.4.9.9s-.4.8-.9.8H4.7c.5 0 .9.4.9.8 0 .5-.4.9-.9.9H3.9" /></svg>
        </button>
        <button class="tb" type="button" title="任务列表" @click="mdTool('todo')">
          <svg viewBox="0 0 24 24"><rect x="4" y="4.5" width="15" height="15" rx="2" /><path d="m8 12 2.5 2.5L16 9" /></svg>
        </button>

        <i class="tb-sep" />

        <button class="tb" type="button" title="行内代码" @click="mdTool('inlineCode')">
          <svg viewBox="0 0 24 24"><path d="m9 8.5-3.5 3.5L9 15.5M15 8.5l3.5 3.5L15 15.5" /></svg>
        </button>
        <button class="tb" type="button" title="代码块（可选择语言）" @click="onCodeBlockClick">
          <svg viewBox="0 0 24 24"><rect x="4" y="5" width="16" height="14" rx="2" /><path d="m9 10-1.8 2L9 14M15 10l1.8 2L15 14" /></svg>
        </button>
        <button class="tb" type="button" title="引用" @click="mdTool('quote')">
          <svg viewBox="0 0 24 24"><path d="M9.5 7.5c-2.6.6-4 2.3-4 5v4h5v-5h-3c0-1.6.7-2.7 2-3.2Zm9 0c-2.6.6-4 2.3-4 5v4h5v-5h-3c0-1.6.7-2.7 2-3.2Z" /></svg>
        </button>
        <button class="tb" type="button" title="分割线" @click="mdTool('hr')">
          <svg viewBox="0 0 24 24"><path d="M4 12h16" stroke-width="1.8" /></svg>
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
      </template>
    </div>

    <!-- 表格操作栏：光标位于表格内时出现（源码模式 & 预览编辑模式共用） -->
    <div v-if="tableInfo" class="table-bar">
      <span class="tbl-label">
        <svg viewBox="0 0 24 24"><rect x="4" y="5" width="16" height="14" rx="2" /><path d="M4 10h16M4 15h16M10 5v14M15 5v14" /></svg>
        表格
      </span>
      <button class="tbl-btn" type="button" @click="tableOp('rowAbove')">
        <svg viewBox="0 0 24 24"><path d="M4 17.5h16" /><path d="M12 17.5V6.5M8 10.5l4-4 4 4" /></svg>上方加行
      </button>
      <button class="tbl-btn" type="button" @click="tableOp('rowBelow')">
        <svg viewBox="0 0 24 24"><path d="M4 6.5h16" /><path d="M12 6.5v11M8 13.5l4 4 4-4" /></svg>下方加行
      </button>
      <button class="tbl-btn" type="button" :disabled="!tableInfo.canDeleteRow" @click="tableOp('rowDelete')">
        <svg viewBox="0 0 24 24"><path d="M4 12h16" /><path d="M8.5 8.5l7 7M15.5 8.5l-7 7" /></svg>删行
      </button>
      <i class="tbl-sep"></i>
      <button class="tbl-btn" type="button" @click="tableOp('colLeft')">
        <svg viewBox="0 0 24 24"><path d="M17.5 4v16" /><path d="M17.5 12H6.5M10 8.5 6.5 12l3.5 3.5" /></svg>左加列
      </button>
      <button class="tbl-btn" type="button" @click="tableOp('colRight')">
        <svg viewBox="0 0 24 24"><path d="M6.5 4v16" /><path d="M6.5 12h11M13.5 8.5l3.5 3.5-3.5 3.5" /></svg>右加列
      </button>
      <button class="tbl-btn" type="button" :disabled="!tableInfo.canDeleteCol" @click="tableOp('colDelete')">
        <svg viewBox="0 0 24 24"><path d="M12 4v16" /><path d="M8.5 9l7 6M15.5 9l-7 6" /></svg>删列
      </button>

      <template v-if="tableInfo.mode === 'source'">
        <i class="tbl-sep"></i>
        <button class="tbl-btn" type="button" :disabled="!tableInfo.canSetHeader" @click="tableOp('setHeader')">
          <svg viewBox="0 0 24 24"><path d="M6 4v16M18 4v16M6 12h12" /></svg>设为表头
        </button>
        <button class="tbl-btn tbl-icon" type="button" :disabled="!tableInfo.canAlign" title="左对齐" @click="tableOp('alignLeft')">
          <svg viewBox="0 0 24 24"><path d="M4.5 6h15M4.5 12h9M4.5 18h13" /></svg>
        </button>
        <button class="tbl-btn tbl-icon" type="button" :disabled="!tableInfo.canAlign" title="居中" @click="tableOp('alignCenter')">
          <svg viewBox="0 0 24 24"><path d="M4.5 6h15M7.5 12h9M5.5 18h13" /></svg>
        </button>
        <button class="tbl-btn tbl-icon" type="button" :disabled="!tableInfo.canAlign" title="右对齐" @click="tableOp('alignRight')">
          <svg viewBox="0 0 24 24"><path d="M4.5 6h15M10.5 12h9M9.5 18h11" /></svg>
        </button>
      </template>

      <i class="tbl-sep"></i>
      <button class="tbl-btn tbl-danger" type="button" @click="tableOp('deleteTable')">
        <svg viewBox="0 0 24 24"><path d="M5 7h14M10 7V5h4v2M8.5 7l.6 12h5.8l.6-12M10 11v4M14 11v4" /></svg>删除表格
      </button>
    </div>

    <!-- Tab 模式（窄屏）：编辑 / 预览 切换 -->
    <div class="ed-tabs" v-if="layoutMode === 'tab' && !readingMode && !previewEditing">
      <button :class="{ on: editorTab === 'edit' }" @click="editorTab = 'edit'">编辑</button>
      <button :class="{ on: editorTab === 'preview' }" @click="editorTab = 'preview'">预览</button>
    </div>

    <!-- ======== 三栏主体 ======== -->
    <div
      ref="editorWrapRef"
      class="editor-wrap"
      :class="{ 'is-preview-editing': previewEditing }"
      @paste.capture="onPasteCapture"
    >
      <!-- 源码栏 -->
      <section
        v-show="showEditorPane" class="pane pane-editor" :style="editorStyle"
        @focusin="scrollSyncSource = 'editor'; toolbarSide = 'editor'"
        @wheel.capture.passive="onScrollIntent('editor')"
        @pointerdown.capture="onScrollIntent('editor')"
        @touchstart.capture.passive="onScrollIntent('editor')"
        @keydown.capture="onScrollIntent('editor')"
      >
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

      <!-- 预览栏：常驻可编辑（鼠标移入即挂上可编辑，点击进入编辑，失焦自动同步回源码） -->
      <section
        v-show="showPreviewPane"
        class="pane pane-preview"
        @wheel.capture.passive="onScrollIntent('preview')"
        @pointerdown.capture="onScrollIntent('preview')"
        @touchstart.capture.passive="onScrollIntent('preview')"
        @keydown.capture="onScrollIntent('preview')"
        @mouseenter="attachPreviewEditable"
        @focusin="onPreviewFocusIn"
        @focusout="onPreviewFocusOut"
        @click="onPreviewClick"
      >
        <div ref="pvScrollRef" class="pv-scroll" @scroll="onPreviewScroll">
          <div class="pv-inner">
            <!-- 空正文时的引导占位（点右侧任意处即可开始编辑，占位随即让位） -->
            <div v-if="!previewEditing && !(form.content || '').trim()" class="pv-empty">
              <svg viewBox="0 0 24 24" width="34" height="34" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
                <path d="M7 3.5h7a3 3 0 0 1 3 3V20.5H7a3 3 0 0 1-3-3v-11a3 3 0 0 1 3-3Z" />
                <path d="M10 9h4M10 12h4M10 15h2" />
              </svg>
              <div class="pv-empty-t">在这里直接写</div>
              <div class="pv-empty-s">右侧所见即所得：选中文字后用上方工具条加粗 / 改色 / 设字号，写完点别处即自动同步回 Markdown</div>
            </div>

            <!-- Stage 0/1：块编辑器渲染器（?editor=block 时启用，默认关闭走 md-editor） -->
            <BlockPreview
              v-if="useBlockPreview"
              ref="blockPreviewRef"
              key="block-preview"
              :content="form.content"
              :link-base="blockLinkBase"
              @update="onBlockPreviewUpdate"
              @outline="onBlockOutline"
            />
            <MdPreview
              v-else
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
      <!--
        窄屏 / 阅读模式的大纲入口（宽屏走上面的常驻侧栏 + 收起浮标）。
        没有它的话，编辑区窄于 1280px 时大纲完全没有入口。
      -->
      <button
        v-if="layoutMode !== 'wide' && outline.length"
        class="outline-fab outline-fab-narrow"
        type="button"
        title="打开大纲"
        @click="openOutlineDrawer"
      >
        <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M5 6h14M5 12h9M5 18h12" /></svg>
        大纲
      </button>

      <!-- 大纲抽屉：点条目跳转后自动关闭 -->
      <div v-if="outlineDrawer" class="ol-mask" @click="closeOutlineDrawer"></div>
      <aside v-if="outlineDrawer" class="ol-drawer" role="dialog" aria-label="大纲">
        <div class="ol-head">
          <span>大纲</span>
          <button class="icon-btn sm" type="button" title="关闭大纲" @click="closeOutlineDrawer">
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M6 6l12 12M18 6L6 18" /></svg>
          </button>
        </div>
        <div class="ol-list">
          <div
            v-for="(h, i) in outline"
            :key="'drawer-' + i"
            class="ol-item"
            :class="[`lv${h.level}`, { active: i === activeIdx }]"
            :title="h.text"
            @click="jumpFromDrawer(i)"
          >{{ h.text }}</div>
          <div v-if="!outline.length" class="ol-empty">正文里写个标题（# 开头）<br />就会出现在这里</div>
        </div>
      </aside>
    </div>

    <!-- AI 处理：处理中显示真实分段进度，完成后转为结果预览 -->
    <el-dialog
      v-model="aiDialog"
      :title="aiBusy ? `${aiLabel}进行中` : `${aiLabel}结果预览`"
      width="760px"
      top="6vh"
      destroy-on-close
      append-to-body
      @close="onAiDialogClose"
    >
      <div v-if="aiBusy" class="ai-progress">
        <el-progress
          :percentage="aiProgress.total > 1 ? aiProgress.percent : 100"
          :indeterminate="aiProgress.total <= 1"
          :stroke-width="6"
          :show-text="false"
        />
        <div class="ai-progress-row">
          <span class="ai-progress-phase">{{ aiProgress.phase }}</span>
          <span class="ai-progress-time">已用 {{ aiProgress.elapsed }}s</span>
        </div>
        <p class="ai-progress-tip">{{ aiHint }}</p>
      </div>
      <div v-else class="ai-preview">
        <MdPreview :modelValue="fixHtmlQuotes(aiResult) || '*空内容*'" :theme="isDark ? 'dark' : 'light'" previewTheme="github" />
      </div>
      <template #footer>
        <el-button v-if="aiBusy" @click="cancelAiProcess">取消处理</el-button>
        <template v-else>
          <el-button @click="aiDialog = false">取消</el-button>
          <el-button type="primary" @click="aiApply">替换正文</el-button>
        </template>
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
  /* 冻结在顶部：阅读长笔记时工具栏不跟着正文滚走。
     页面本身通常不滚（正文在预览栏内部滚），sticky 是兜底：窄屏/专注模式下万一整页滚动，
     这两行也不会被卷走。背景必须不透明，否则会透出滚过去的正文。 */
  position: sticky;
  top: 0;
  z-index: 6;
  background: var(--app-bg);
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
/* P4：基础规则里的 outline:none 会抹掉键盘焦点环，这里为键盘导航补回 */
.title-input:focus-visible {
  outline: 2px solid color-mix(in srgb, var(--app-brand) 55%, transparent);
  outline-offset: 2px;
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

/* 顶栏 AI 动作按钮（润色 / 整理格式）：默认安静 —— 无边框、无底色，hover 才浮出。
   实心主操作只留给右侧「保存」：两个都抢眼的话，主次就没了。 */
.ai-act {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 30px;
  padding: 0 9px;
  flex-shrink: 0;
  font-size: 13px;
  font-family: inherit;
  color: var(--app-text-2);
  background: transparent;
  border: 1px solid transparent;
  border-radius: 8px;
  cursor: pointer;
  white-space: nowrap;
  transition: background-color var(--dur-fast) ease, color var(--dur-fast) ease;
}
.ai-act:hover:not(:disabled) {
  background: color-mix(in srgb, var(--app-text-1) 6%, transparent);
  color: var(--app-text-1);
}
.ai-act:active:not(:disabled) {
  background: color-mix(in srgb, var(--app-text-1) 10%, transparent);
}
.ai-act:disabled {
  opacity: 0.55;
  cursor: default;
}
/* 与顶栏其它图标同规格：15px 线性描边（返回、更多都是这一套） */
.ai-act svg {
  width: 15px;
  height: 15px;
  flex: none;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.save-btn {
  height: 32px;
  padding: 0 18px;
  border-radius: 8px;
}

/* ================= 第二行工具条 =================
   卡片式工具栏：与「预览编辑操作条」同一视觉语言（卡片底 + 细描边 + 圆角），
   让这一排按钮从「贴边的图标」变成一块有触感的控制面板。 */
.ed-tools {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 5px 6px;
  margin-bottom: 8px;
  overflow-x: auto;
  scrollbar-width: none;
  position: sticky;   /* 与第一行一起冻结在顶部（同一 z 轴，见 .ed-top 的说明） */
  top: 42px;
  z-index: 6;
  background: var(--app-card);
  border: 1px solid var(--app-border);
  border-radius: 10px;
}
.ed-tools::-webkit-scrollbar {
  display: none;
}
/* 窄屏溢出时左右边缘渐隐，提示「还有更多工具可横向滚动」 */
.ed-tools::before,
.ed-tools::after {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  width: 26px;
  pointer-events: none;
  z-index: 2;
  opacity: 0;
  transition: opacity var(--dur-fast) ease;
}
.ed-tools::before {
  left: 0;
  background: linear-gradient(90deg, var(--app-card), transparent);
}
.ed-tools::after {
  right: 0;
  background: linear-gradient(-90deg, var(--app-card), transparent);
}
.ed-tools.has-left::before {
  opacity: 1;
}
.ed-tools.has-right::after {
  opacity: 1;
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
  transition: background-color var(--dur-fast) ease, color var(--dur-fast) ease, transform var(--dur-fast) ease;
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
  background: color-mix(in srgb, var(--app-text-1) 8%, transparent);
  color: var(--app-text-1);
}
.tb:active {
  background: color-mix(in srgb, var(--app-text-1) 13%, transparent);
  transform: scale(0.93);
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
  height: 18px;
  background: var(--app-border-weak);
  margin: 0 7px;
  flex-shrink: 0;
}
.ed-format {
  flex: none;
}

/* 「正文∨」「对齐∨」下拉（语雀式小浮层，视觉与块菜单一致） */
.tb-dd {
  position: relative;
  display: inline-flex;
  flex: none;
}
.tb-para {
  gap: 3px;
  font-size: 13px;
  padding: 0 4px 0 9px;
}
.tb svg.tb-caret {
  width: 11px;
  height: 11px;
  stroke-width: 2;
  opacity: 0.65;
}
.tb.on {
  background: var(--app-brand-soft);
  color: var(--app-brand-deep);
}
html.dark .tb.on {
  color: var(--app-brand);
}
/*
  格式刷是**两步操作**（先取格式、再刷到目标），所以"已取格式"这个中间态必须看得见 ——
  只靠背景变色在满屏工具栏里容易被忽略，这里再补一圈细描边 + 右下角一个小圆点。
*/
.tb-painter {
  position: relative;
}
.tb-painter.on {
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--app-brand) 55%, transparent);
}
.tb-painter.on::after {
  content: '';
  position: absolute;
  right: 3px;
  bottom: 3px;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: currentColor;
}
.tb-menu {
  position: fixed; /* .ed-tools 有 overflow-x:auto，absolute 会被裁；fixed 按视口坐标定位 */
  min-width: 132px;
  padding: 5px;
  background: var(--app-card);
  border: 1px solid var(--app-border);
  border-radius: 10px;
  box-shadow: var(--shadow-md);
  z-index: 2000;
  animation: toolMenuIn var(--dur-fast) var(--ease) both;
  transform-origin: top left;
}
@keyframes toolMenuIn {
  from {
    opacity: 0;
    transform: translateY(-4px) scale(0.98);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
.tb-menu-item {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 7px 10px;
  font-size: 13px;
  color: var(--app-text-1);
  background: none;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  text-align: left;
  white-space: nowrap;
  transition: background var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}
.tb-menu-item:hover {
  background: var(--app-brand-soft);
  color: var(--app-brand-deep);
}
.tb-menu-item:hover svg {
  stroke: var(--app-brand-deep);
}
.tb-menu-item svg {
  width: 15px;
  height: 15px;
  flex: none;
  fill: none;
  stroke: var(--app-text-2);
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.tbm-key {
  width: 20px;
  height: 18px;
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 700;
  color: var(--app-text-3);
  letter-spacing: -0.2px;
  background: var(--app-bg);
  border-radius: 5px;
}
.tbm-key.txt {
  font-size: 13px;
}


/* ---- 表格操作栏（光标在表格内出现）---- */
.table-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  padding: 4px 8px;
  margin-bottom: 8px;
  background: var(--app-card);
  border: 1px solid var(--app-border);
  border-radius: 8px;
}
.tbl-label {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  font-weight: 600;
  color: var(--app-text-3);
  margin-right: 4px;
}
.tbl-label svg {
  width: 14px;
  height: 14px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.tbl-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 26px;
  padding: 0 8px;
  font-size: 12px;
  color: var(--app-text-2);
  background: transparent;
  border: 1px solid transparent;
  border-radius: 6px;
  cursor: pointer;
  font-family: inherit;
  transition: background-color var(--dur-fast) ease, color var(--dur-fast) ease;
}
.tbl-btn:hover {
  background: color-mix(in srgb, var(--app-text-1) 7%, transparent);
  color: var(--app-text-1);
}
.tbl-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.tbl-btn svg {
  width: 15px;
  height: 15px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.tbl-sep {
  width: 1px;
  height: 16px;
  background: var(--app-border-weak);
  margin: 0 4px;
  flex-shrink: 0;
}
.tbl-btn.tbl-icon {
  padding: 0 6px;
}
.tbl-danger {
  color: #c0392b;
}
.tbl-danger:hover {
  background: rgba(192, 57, 43, 0.08);
  color: #c0392b;
}
html.dark .tbl-danger {
  color: #e07b70;
}
html.dark .tbl-danger:hover {
  background: rgba(224, 123, 112, 0.12);
}

/* 预览编辑：表格当前行/列高亮（交点更深一档） */
.pane-preview :deep(td.lh-cell-row),
.pane-preview :deep(th.lh-cell-row) {
  background: color-mix(in srgb, var(--app-brand) 6%, transparent);
}
.pane-preview :deep(td.lh-cell-col),
.pane-preview :deep(th.lh-cell-col) {
  background: color-mix(in srgb, var(--app-brand) 6%, transparent);
}
.pane-preview :deep(td.lh-cell-row.lh-cell-col),
.pane-preview :deep(th.lh-cell-row.lh-cell-col) {
  background: color-mix(in srgb, var(--app-brand) 13%, transparent);
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
  flex: 0 0 9px;
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
/* 正文宽度：铺满预览栏。
   原来是居中 790px 的「阅读黄金区」，但右侧现在常驻可编辑，编辑时两侧大片留白很别扭，
   而聚焦/失焦来回切换限宽又会让正文左右跳动 —— 所以干脆统一铺满，不再区分阅读/编辑。 */
.pv-inner {
  max-width: 100%;
  margin: 0 auto;
  padding: 24px 26px 48px;
}

/* 空正文引导占位：弱化居中，与大纲空态同一视觉语言 */
.pv-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  color: var(--app-text-3);
  padding: 88px 20px 20px;
}
.pv-empty svg {
  opacity: 0.45;
}
.pv-empty-t {
  margin-top: 14px;
  font-size: 14px;
  font-weight: 600;
  color: var(--app-text-2);
}
.pv-empty-s {
  margin-top: 6px;
  font-size: 12.5px;
  line-height: 1.7;
  max-width: 320px;
}
/* 预览编辑：限宽已统一放开（见 .pv-inner），这里不再单独覆盖，避免聚焦时宽度跳变 */

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
  /* 库自带 .md-editor-preview { padding-inline: 20px; padding-block: 10px }，
     外层的 .md-editor-preview-wrapper / .md-editor-content 则没有内边距。
     这里归零，左右 gutter 统一由 .pv-inner 控制 —— 否则 26px + 20px 叠成 46px，就不是「铺满」了。 */
  padding: 0;
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

/* 预览区可编辑：**去掉「内嵌画布」**——原先这里有一圈描边 + 浅底 + 圆角 + 内边距，
   视觉上像是预览栏里又嵌了一张卡片，边框还很扎眼。现在右侧就是一个平铺的编辑面。
   注意两点：
   1) 不要再给它加 :focus-visible 焦点环 —— contenteditable 即使由鼠标点击获得焦点
      也会匹配 :focus-visible（浏览器把它当作支持键盘输入的元素），加了就会常驻一圈亮色描边。
      位置提示交给光标本身就够了。
   2) 底色靠 .pane-preview 的 --app-card，这里保持透明（基础规则已声明 transparent）。 */
.pane-preview :deep(.md-editor-preview.lh-preview-editing.lh-preview-editing) {
  outline: none;
  cursor: text;
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
/* ---- 窄屏 / 阅读模式的大纲入口与抽屉（宽屏用上面的常驻侧栏）---- */
.outline-fab-narrow {
  top: auto;
  right: 18px;
  bottom: 18px;
  z-index: 6;
}
.ol-mask {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.28);
  z-index: 20;
}
.ol-drawer {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: min(300px, 78%);
  display: flex;
  flex-direction: column;
  background: var(--app-card);
  border-left: 1px solid var(--app-border-weak);
  box-shadow: -10px 0 28px rgba(0, 0, 0, 0.12);
  z-index: 21;
}
.ol-drawer .ol-head {
  border-bottom: 1px solid var(--app-border-weak);
}
.editor-wrap {
  position: relative;
}

/* ================= 阅读模式（默认那一栏） =================
   单栏、可编辑：正文就在这一栏里改，顶部两行工具栏原样保留并冻结在顶部（用户要求）。
   右上角的圆角按钮用来在「单栏阅读」与「源码对照」之间来回切。 */
.read-exit {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 30px;
  padding: 0 12px;
  flex-shrink: 0;
  font-size: 12.5px;
  font-family: inherit;
  color: var(--app-text-2);
  background: var(--app-card);
  border: 1px solid var(--app-border);
  border-radius: 99px;
  cursor: pointer;
}
.read-exit .btn-ico svg {
  width: 14px;
  height: 14px;
  display: block;
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

/* ---- AI 处理进度（分段串行，按已完成段数推进）---- */
.ai-progress {
  padding: 8px 2px 4px;
}

.ai-progress-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
}

.ai-progress-phase {
  font-size: 14px;
  font-weight: 600;
  color: var(--app-text-1);
}

.ai-progress-time {
  font-size: 12px;
  color: var(--app-text-2);
  font-variant-numeric: tabular-nums; /* 秒数跳动时宽度不抖 */
}

.ai-progress-tip {
  margin: 12px 0 0;
  font-size: 12px;
  line-height: 1.8;
  color: var(--app-text-2);
}
</style>
