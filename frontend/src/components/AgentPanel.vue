<script setup>
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { aiApi, categoryApi, noteApi, modelApi, settingsApi } from '../api'
import { fixHtmlQuotes } from '../utils/htmlQuotes'
import {
  AGENT_NOTE_CONTEXT_EVENT,
  AGENT_NOTE_MERGE_EVENT,
  AGENT_NOTE_MERGE_RESULT_EVENT,
} from '../utils/agentNoteMerge'
import { isDark } from '../composables/useTheme'
import { AGENT_NOTE_UPDATED_EVENT, matchesOpenNote, parseActionResult } from '../utils/agentNoteEdit'
import { retrievalChannels, retrievalGroups, retrievalKey, retrievalSourcePath, retrievalTitle, retrievalTooltip } from '../utils/retrievalDisplay'
import { groundingDisplay } from '../utils/groundingDisplay'
import { MODEL_CONFIGURATION_CHANGED_EVENT, chatProfiles, chatProfileSelection } from '../utils/modelProfileView'

/**
 * Markdown 预览（md-editor-v3）体积很大：整包 + 样式约 310 KB，
 * 而它只在「抽屉第一次打开 / 保存对话框第一次打开」时才用得到。
 *
 * 做成异步组件后，动态 import 是在组件**实例化**时才触发的 ——
 * el-drawer / el-dialog 的内容都是首次打开才渲染，所以首屏完全不会下载它。
 *
 * 加载顺序很重要：mdEditorSetup 里才会注册 markdown-it 的 ::: 提示块扩展，
 * 必须先 await 它，再返回 MdPreview，否则第一批渲染的 ::: 块认不出来。
 */
const MdPreview = defineAsyncComponent(() =>
  Promise.all([import('../utils/mdEditorSetup'), import('md-editor-v3')]).then(([, m]) => m.MdPreview),
)

const router = useRouter()

function openRetrievedSource(hit) {
  const path = retrievalSourcePath(hit)
  if (!path) return
  if (router.resolve(path).fullPath === router.currentRoute.value.fullPath && hit.type === 'wiki') {
    window.dispatchEvent(new CustomEvent('lh-open-wiki', { detail: hit }))
  } else router.push(path)
}

/**
 * 嵌入模式：不在抽屉里、而作为**左侧导航的"智能体"页**整页显示。
 * <p>为什么用 prop 而不是再写一个页面组件：对话逻辑（工具调用、待确认写操作、
 * 保存为笔记、流式事件…）有一千行，复制一份必然发散 —— 两处行为会慢慢不一样，
 * 而"智能体在哪都能用"恰恰要求行为完全一致。
 */
const props = defineProps({
  embedded: { type: Boolean, default: false },
  /**
   * 是否显示面板自带的会话条与列表。
   * <p>嵌入模式（智能体页）下会话有**自己的侧栏**，所以那边传 false，
   * 让"会话管理"只有侧栏一个入口；抽屉模式仍用面板内的折叠列表。
   */
  showSessionBar: { type: Boolean, default: true },
})

/** 抽屉模式下的开合；嵌入模式恒为 true（页面本身就是打开状态） */
const open = ref(false)
/** 真实生效的可见性：嵌入模式永远可见 */
const visible = computed(() => props.embedded || open.value)

/**
 * 嵌入模式（智能体页）下承载容器的形状：普通块级、铺满父容器。
 * <p>为什么用内联样式而不是 CSS 类：抽屉那套是 {@code position: fixed + width: 480px}，
 * 用 `:deep()` 覆盖时受样式表先后与优先级影响，实测没盖住（量出来仍是 fixed、高=视口高）。
 * 内联样式优先级最高，这里是"必须生效"的两条布局属性，值得用最确定的方式。
 */
const EMBEDDED_DOCK_STYLE = {
  position: 'static',
  width: '100%',
  height: '100%',
  transform: 'none',
  pointerEvents: 'auto',
}

/**
 * 嵌入模式下**根容器**的形状：从"右下角悬浮"变成普通块级铺满。
 * <p>根容器本身是 {@code position: fixed; right: 22px; bottom: 22px}（给悬浮球用的），
 * 只改里面的 dock 是没用的 —— 父级还是浮在视口右下角，子元素的高度百分比按视口算，
 * 量出来就是"高=视口高、宽=缩到内容宽"（实测 721×802，父容器却是 1162×680）。
 */
const EMBEDDED_ROOT_STYLE = {
  position: 'static',
  right: 'auto',
  bottom: 'auto',
  width: '100%',
  height: '100%',
  zIndex: 'auto',
}
const configured = ref(true)
const busy = ref(false)
const input = ref('')
const inputRef = ref(null)
const listRef = ref(null)
const messages = ref([])

/** 最后一条助手回答的下标：用于「最新一轮的思考过程默认展开」 */
const lastAssistantIndex = computed(() => {
  for (let i = messages.value.length - 1; i >= 0; i--) {
    if (messages.value[i].role === 'assistant') return i
  }
  return -1
})

/**
 * 「我现在开着哪篇笔记」——由笔记编辑页广播（见 utils/agentNoteMerge.js）。
 *
 * 为什么要有它：在笔记页里打开悬浮窗，问的问题天然是关于这篇笔记的，
 * 那么回答的归宿也就不该是"另存成一篇新笔记"（那会得到一堆零碎小笔记），
 * 而是**融进当前这篇**。面板不必知道编辑页长什么样，只认这个广播。
 */
const activeNote = ref(null)

/** 弹窗/按钮上显示的短标题 */
const activeNoteLabel = computed(() => {
  const t = activeNote.value?.title || ''
  return t.length > 14 ? t.slice(0, 14) + '…' : t
})

/**
 * 会话 id：存 localStorage，刷新后接着聊。
 * <p>上下文由**后端**从事件日志投影，前端不再自己拼 history ——
 * 原来那段「filter + slice(0, -1)、曾误写成 slice(0, -2)」的历史组装逻辑因此整体删除：
 * 少一处只有注释能解释清楚、而且真的写错过一次的地方。
 */
const SESSION_KEY = 'lh-agent-session'
const sessionId = ref(localStorage.getItem(SESSION_KEY) || '')

// ------------------------------------------------------------------
// 会话版块 + 会话级模型
// ------------------------------------------------------------------

/** 会话列表（库里全部会话，按最后活动倒序） */
const sessions = ref([])
const showSessions = ref(false)
/** 已配置的模型档案（可无限新增多个） */
const modelProfiles = ref([])
/** 本会话指定的档案 id；空 = 跟随任务分工表 */
const chatProfile = ref('')

const currentModelLabel = computed(() => {
  const p = modelProfiles.value.find((x) => x.id === chatProfile.value)
  if (p) {
    return `代码答疑 · ${p.name}（${p.model}）`
  }
  return '代码答疑 · 默认（当前生效档案）'
})

async function loadSessions() {
  try {
    sessions.value = await modelApi.sessions(50)
  } catch (e) {
    sessions.value = []
  }
}

async function loadModelProfiles() {
  try {
    const d = await modelApi.profiles()
    modelProfiles.value = chatProfiles(d.profiles)
    chatProfile.value = chatProfileSelection(modelProfiles.value, chatProfile.value)
  } catch (e) {
    modelProfiles.value = []
  }
}

/** 切换会话：把该会话的历史读进来，并带上它自己记的模型 */
async function switchSession(id) {
  if (id === sessionId.value) {
    showSessions.value = false
    return
  }
  sessionId.value = id
  localStorage.setItem(SESSION_KEY, id)
  messages.value = []
  pending.value = []
  showSessions.value = false
  const s = sessions.value.find((x) => x.id === id)
  chatProfile.value = chatProfileSelection(modelProfiles.value, s?.modelProfileId)
  await loadSession()
  notifyCurrentSession()
}

/** 会话换模型：**同时写库**，这样下次打开这个会话还是这个模型 */
async function onProfileChange(v) {
  if (v && !chatProfileSelection(modelProfiles.value, v)) return
  if (!sessionId.value) {
    return // 还没建会话，下一次提问时后端会按请求体里的档案建
  }
  try {
    await modelApi.setSessionModel(sessionId.value, v || '')
    ElMessage.success(v ? '本会话已固定使用该模型' : '本会话已改为默认（当前生效档案）')
    await loadSessions()
    notifyCurrentSession()
  } catch (e) {
    /* 拦截器已提示 */
  }
}

async function removeSession(s) {
  try {
    await ElMessageBox.confirm(
      `删除会话「${s.title || '新对话'}」及其全部消息？<br><br><span style="color:#6b7280">· 只删这次对话的记录，笔记/资料不受影响</span>`,
      '删除会话',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', dangerouslyUseHTMLString: true }
    )
  } catch {
    return
  }
  try {
    await modelApi.removeSession(s.id)
    ElMessage.success('已删除')
    if (s.id === sessionId.value) {
      // 删的正是当前会话：清空面板，下一次提问自动开新会话
      sessionId.value = ''
      localStorage.removeItem(SESSION_KEY)
      messages.value = []
      pending.value = []
      chatProfile.value = ''
    }
    await loadSessions()
  } catch (e) {
    /* 拦截器已提示 */
  }
}

/**
 * 刷新后恢复对话。
 * 不做这一步会出现最别扭的状态：模型记得上一轮，面板却是空的。
 */
async function loadSession() {
  if (!sessionId.value) return
  try {
    const s = await aiApi.session(sessionId.value)
    if (!s?.messages?.length) return
    messages.value = s.messages.map((m, i) => ({
      id: Date.now() + i,
      role: m.role,
      content: m.content,
      events: m.events || [],
      // 思考过程（thinking 模型才有）：刷新后由事件流还原，和当轮显示同一套渲染
      reasoning: m.reasoning || '',
      toolUsed: !!m.toolUsed,
    }))
    pending.value = s.pendingActions || []
    scrollBottom()
  } catch (e) {
    // 会话不存在（清过库 / 换了后端）：丢掉本地 id，下一次提问自动开新会话
    sessionId.value = ''
    localStorage.removeItem(SESSION_KEY)
  }
}

/** 新对话：只丢本地引用；旧会话仍留在库里（可审计、可回看） */
function newChat() {
  sessionId.value = ''
  localStorage.removeItem(SESSION_KEY)
  messages.value = []
  pending.value = []
  input.value = ''
}

/**
 * 待确认的写操作。
 * 在用户点「确认执行」之前，后端**不会**动数据库 —— 这是防"AI 误改笔记"的关键一环。
 * 刷新后由 loadSession() 重新取回（否则待确认项会变成无法点击的死状态）。
 */
const pending = ref([])
const resolvingActions = ref(new Set())

function notifyLearningActivity() {
  window.dispatchEvent(new CustomEvent('lh-learning-activity-changed'))
}

async function resolveAction(a, approve) {
  if (resolvingActions.value.has(a.id)) return
  if (approve && a.toolName === 'edit_note' && activeNote.value?.dirty
      && matchesOpenNote(activeNote.value.noteId, a.preview?.noteId)) {
    ElMessage.warning('当前笔记有未保存改动，请先保存，再重新生成定向编辑预览')
    return
  }
  resolvingActions.value.add(a.id)
  try {
    let hint
    let succeeded = true
    if (approve) {
      const r = await aiApi.approveAction(a.id)
      const result = parseActionResult(r)
      succeeded = r?.ok !== false
      if (succeeded) {
        notifyLearningActivity()
        hint = r?.hint || '已执行'
        ElMessage.success(hint)
        const edit = r?.noteEdit || result.noteEdit
        if (edit?.changed) window.dispatchEvent(new CustomEvent(AGENT_NOTE_UPDATED_EVENT, { detail: edit }))
      } else {
        hint = r?.error || result.error || r?.hint || '执行失败，未修改笔记'
        ElMessage.error(hint)
      }
    } else {
      await aiApi.rejectAction(a.id)
      hint = '已取消：' + (a.summary || '')
      ElMessage.info('已取消，未做任何改动')
    }
    // 立刻把执行痕迹挂到最近一条回答的事件角标上：后端也会追加同样的事件，
    // 刷新后由 loadSession 恢复 —— 两处表现保持一致，不必等刷新才看到结果。
    const last = [...messages.value].reverse().find((m) => m.role === 'assistant')
    if (last) {
      last.events = [...(last.events || []), hint]
      // 同时改正文：那条回答里通常写着"请在下方卡片上点确认"，而卡片点完就消失了 ——
      // 只剩这句话会让人以为还没确认。补一行结果，读起来才自洽。
      const tail = approve
        ? `\n\n（${succeeded ? '已确认执行' : '执行失败'}：${hint}）`
        : `\n\n（已取消，未做任何改动：${a.summary || ''}）`
      last.content = (last.content || '') + tail
      scrollBottom()
    }
    pending.value = pending.value.filter((x) => x.id !== a.id)
  } catch (e) {
    // 失败（例如已被处理过）时也把它从列表里摘掉，避免反复点同一个死项；
    // 错误提示已由请求拦截器统一弹出，这里不再重复打扰
    pending.value = pending.value.filter((x) => x.id !== a.id)
  } finally {
    resolvingActions.value.delete(a.id)
  }
}

const SUGGESTIONS = [
  'Java 的 == 和 equals 有什么区别？',
  'MyBatis-Plus 分页怎么写？',
  '这段代码帮我逐行讲讲',
  'MQTT 消息会丢吗？',
]

function scrollBottom() {
  nextTick(() => {
    if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight
  })
}

/**
 * 联网开关（放在输入框那一行）。
 *
 * 存的是设置里的 `ai.web_enabled`：**默认开**，关掉就只用工作台里的资料回答。
 * 点一下立即 PUT、立即生效 —— 不需要"保存"，也不需要离开对话去设置面板改。
 */
const webEnabled = ref(true)

async function loadWebSetting() {
  try {
    const s = await settingsApi.get()
    webEnabled.value = s?.webEnabled !== false
  } catch (e) {
    webEnabled.value = true // 读不到就按默认开（与服务端默认一致）
  }
}

async function toggleWeb() {
  const next = !webEnabled.value
  try {
    await settingsApi.update({ webEnabled: next ? '1' : '0' })
    webEnabled.value = next
    ElMessage.success(next ? '已开启联网：需要时会搜索并读页面' : '已关闭联网：只用工作台里的资料回答')
  } catch (e) {
    /* 拦截器已提示 */
  }
}
async function loadStatus() {
  try {
    const s = await aiApi.status()
    configured.value = s.configured
  } catch (e) {
    configured.value = false
  }
}

/**
 * 发一条消息。
 * @param text 提问内容
 * @param ctx  可选上下文（noteId / noteTitle / noteContext）—— 从知识图谱或 wiki 里
 *             「问智能体」时带上，让模型知道这句话是在问哪一条记录，不必再检索一遍
 */
async function send(text, ctx) {
  const msg = (text ?? input.value).trim()
  if (!msg || busy.value) return
  input.value = ''
  messages.value.push({ role: 'user', content: msg })
  scrollBottom()
  busy.value = true
  const holder = { role: 'assistant', loading: true, content: '' }
  messages.value.push(holder)
  scrollBottom()
  try {
    // 上下文交给后端会话维护：只带 sessionId（首次为空，响应里会把新 id 带回来）
    const res = await aiApi.chat({
      message: msg,
      sessionId: sessionId.value,
      // 会话级模型：带上它后端就用这个档案（空 = 跟随任务分工表）
      modelProfileId: chatProfile.value || undefined,
      // 笔记页里打开的面板：把「当前笔记」当背景一起发（后端只截前 1500 字注入，
      // 并明确告知模型"仅供理解背景"）。显式传来的 ctx（知识图谱/wiki 的「问智能体」）优先。
      noteId: ctx?.noteId ?? activeNote.value?.noteId ?? undefined,
      noteTitle: ctx?.noteTitle ?? activeNote.value?.title ?? undefined,
      noteContext: ctx?.noteContext ?? activeNote.value?.context ?? undefined,
      noteDirty: (!ctx?.noteId || matchesOpenNote(ctx.noteId, activeNote.value?.noteId)) && !!activeNote.value?.dirty,
    })
    if (res.sessionId && res.sessionId !== sessionId.value) {
      sessionId.value = res.sessionId
      localStorage.setItem(SESSION_KEY, res.sessionId)
      await loadSessions()
      // 面板第一次提问会自动建会话：通知会话侧栏把它列出来（否则侧栏看不到这次新会话）
      window.dispatchEvent(new CustomEvent('lh-agent-sessions-changed'))
      notifyCurrentSession()
    }
    holder.loading = false
    holder.content = res.reply || ''
    holder.events = res.events || []
    holder.retrieved = res.retrieved || []
    holder.grounding = res.grounding
    holder.groundingStatus = groundingDisplay(res.grounding, holder.retrieved)
    // 思考过程：thinking 模型才会返回；不拼进正文（正文要能原样存成笔记）
    holder.reasoning = res.reasoning || ''
    holder.toolUsed = res.toolUsed
    // 本轮若发起了写操作，它们是"待确认"状态，攒到下面的确认卡片里
    if (res.pendingActions?.length) {
      pending.value.push(...res.pendingActions)
    }
    holder.id = holder.id || Date.now()
    configured.value = true
  } catch (e) {
    holder.loading = false
    holder.content = ''
    holder.error = e.message || '请求失败'
  } finally {
    busy.value = false
    scrollBottom()
    notifyLearningActivity()
  }
}

/**
 * 笔记编辑页广播「我现在开着哪篇笔记」时记下来；广播 detail 为空 = 离开了笔记页，
 * 主操作随之回退成「保存为笔记」。
 */
function noteContextFromEvent(e) {
  const d = e?.detail
  activeNote.value = d && (d.noteId || d.title || d.isNew)
    ? { noteId: d.noteId || null, title: d.title || '', isNew: !!d.isNew, context: d.context || '', dirty: !!d.dirty }
    : null
}

/** 取这条回答对应的提问（往前找最近一条用户消息）——它要当插入小节里的标题 */
function questionBefore(index) {
  for (let i = index - 1; i >= 0; i--) {
    if (messages.value[i].role === 'user') return messages.value[i].content || ''
  }
  return ''
}

/** 正在等融入结果的那条回答（面板 + 笔记页是"发请求 → 收结果"的两段式，要记住收件人） */
const mergeTarget = ref(null)

/**
 * 把这条回答**融入**当前笔记。
 *
 * 面板只递材料（问 + 答），真正的活由笔记页干：它手里才有"含未保存改动的正文"，
 * 还要用它的进度弹窗和预览按钮走"确认后替换"。所以这里派发事件后进入"融入中"状态，
 * 等笔记页回报结果（见 mergeResultFromEvent）再决定打勾还是恢复按钮。
 */
function mergeIntoNote(holder, index) {
  const note = activeNote.value
  if (!note || !holder?.content) return
  if (mergeTarget.value) {
    ElMessage.info('上一条还在融入中，稍等一下')
    return
  }
  mergeTarget.value = holder
  holder.mergePending = true
  window.dispatchEvent(new CustomEvent(AGENT_NOTE_MERGE_EVENT, {
    detail: { noteId: note.noteId, question: questionBefore(index), answer: holder.content },
  }))
  // 兜底：万一笔记页没接住（比如刚好被卸载），别让按钮永远停在"融入中"
  setTimeout(() => {
    if (mergeTarget.value === holder) {
      holder.mergePending = false
      mergeTarget.value = null
      ElMessage.warning('没收到笔记页的回应，已取消本次融入（正文未改动）')
    }
  }, 6 * 60 * 1000)
}

/**
 * 笔记页回报融入结果。
 *
 * 失败要**恢复按钮**而不是打勾：失败时正文没有改动，用户应该能直接重试或改用「复制」。
 *
 * 失败时**面板不再自己弹一条**。原因（超长闸门 / 输出缩水 / 用户取消）只有笔记页知道，
 * 它那边已经用进度弹窗 + 一条提示报过了；面板再报一次就是两条几乎一样的红条
 * （实测一条写"正文保持原样"、另一条写"融入失败：…（正文未改动）"），
 * 而且"取消"和"内容已在笔记里"这种非失败结局会被面板说成"融入失败"。
 * 所以用户可见的文案统一由笔记页给（见 utils/agentNoteMerge.js 的职责划分），
 * 面板只留成功确认 —— 它负责把用户引到笔记页的预览弹窗。
 * `d.message` 仍然收着并进契约，只是当前不拿来弹提示。
 */
function mergeResultFromEvent(e) {
  const d = e?.detail || {}
  const holder = mergeTarget.value
  if (!holder) return
  mergeTarget.value = null
  holder.mergePending = false
  if (d.ok) {
    holder.merged = true
    ElMessage.success(d.message || '已在笔记页打开融入预览')
  }
}

/** 保存为笔记（每次回答后由用户决定） */
const saveVisible = ref(false)
const saveBusy = ref(false)
const saveForm = ref({ title: '', categoryId: undefined, content: '' })
const categories = ref([])

async function askSave(holder) {
  if (!holder || !holder.content) return
  categories.value = await loadCategories()
  saveForm.value = {
    title: guessTitle(holder.content),
    categoryId: undefined,
    content: holder.content,
    sourceId: holder.id,
  }
  saveVisible.value = true
}

function guessTitle(md) {
  const lines = (md || '').split('\n')
  for (const line of lines) {
    const m = line.match(/^\s*#\s+(.+)$/)
    if (m) return m[1].trim().slice(0, 60)
  }
  const plain = (md || '').replace(/[#*`>\[\]()]/g, '').replace(/\s+/g, ' ').trim()
  return plain.slice(0, 40) || '智能体回答'
}

async function loadCategories() {
  const tree = await categoryApi.tree()
  const flat = []
  ;(function walk(nodes, depth = 0) {
    for (const n of nodes || []) {
      flat.push({ ...n, depth })
      if (n.children?.length) walk(n.children, depth + 1)
    }
  })(tree)
  return flat
}

async function doSaveNote() {
  if (!saveForm.value.title.trim()) {
    ElMessage.warning('请填写标题')
    return
  }
  saveBusy.value = true
  try {
    const created = await noteApi.add({
      title: saveForm.value.title.trim(),
      content: saveForm.value.content,
      categoryId: saveForm.value.categoryId || null,
      tagIds: [],
    })
    notifyLearningActivity()
    saveVisible.value = false
    const holder = messages.value.find((m) => m.id === saveForm.value.sourceId)
    if (holder) holder.saved = true
    ElMessageBox.confirm(`已保存为笔记「${created.title}」，现在去看看？`, '保存成功', {
      confirmButtonText: '去看看',
      cancelButtonText: '继续提问',
      type: 'success',
    })
      .then(() => {
        open.value = false
        router.push(`/notes/${created.id}`)
      })
      .catch(() => {})
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    saveBusy.value = false
  }
}

/**
 * 整段回答一键复制：**同时写 text/html 与 text/plain**。
 *
 * 为什么要两份：只写纯文本时，粘进阅读模式（那一栏是 tiptap 富文本）就是一堆
 * `##`、``` 字面量 —— 用户看到的是"没转换格式的源码"，这正是"复制粘进去是代码"的原因。
 * 两份都写之后：富文本编辑器（阅读模式、Word、飞书…）取 text/html，拿到渲染好的结构；
 * 源码对照的 CodeMirror、终端、vim 这类只认纯文本的地方取 text/plain，拿到的仍是
 * Markdown 原文 —— 两种落点都对。
 *
 * 渲染走 `mdToHtml` 的同一个 markdown-it 实例（动态 import，不拖面板首屏），
 * 所以贴出来的结构与该笔记「导出 HTML」看到的一致。
 * 浏览器不支持 ClipboardItem（或写入被拒）时，退回原来的纯文本复制。
 */
async function copyText(text) {
  const markdown = text || ''
  try {
    const { renderMarkdownFragment } = await import('../utils/mdToHtml')
    const html = renderMarkdownFragment(markdown)
    if (!navigator.clipboard?.write || typeof ClipboardItem === 'undefined') {
      throw new Error('这个浏览器不支持写入富文本剪贴板')
    }
    await navigator.clipboard.write([
      new ClipboardItem({
        'text/html': new Blob([html], { type: 'text/html' }),
        'text/plain': new Blob([markdown], { type: 'text/plain' }),
      }),
    ])
    ElMessage.success('已复制，粘贴时保留格式')
  } catch (e) {
    try {
      await navigator.clipboard.writeText(markdown)
      ElMessage.success('已复制')
    } catch (err) {
      ElMessage.error('复制失败，请手动选择复制')
    }
  }
}

onMounted(() => {
  loadStatus()
  // 联网开关的当前状态（输入框那一行要显示"开/关"）
  loadWebSetting()
  loadSession()
  // 会话列表 + 模型档案：右边栏的"新开/切换会话"与"本会话用哪个模型"都靠它们
  Promise.all([loadSessions(), loadModelProfiles()]).then(() => {
    // 恢复上次会话时，把该会话自己记的模型也带回来
    const cur = sessions.value.find((x) => x.id === sessionId.value)
    if (cur) {
      chatProfile.value = chatProfileSelection(modelProfiles.value, cur.modelProfileId)
    }
    notifyCurrentSession()
  })
  // 嵌入模式（左侧导航「智能体」页）默认展开会话列表：那一页的左栏就是会话清单
  if (props.embedded) {
    showSessions.value = true
  }
  // 支持从笔记编辑器等页面唤起（window 事件，避免组件强耦合）
  window.addEventListener('lh-agent-open', openFromEvent)
  window.addEventListener(MODEL_CONFIGURATION_CHANGED_EVENT, loadModelProfiles)
  window.addEventListener('lh-agent-compose', composeFromEvent)
  // 知识图谱 / wiki 里的「问智能体」：不仅打开面板，还直接把问题发出去
  window.addEventListener('lh-ask-agent', askFromEvent)
  // 智能体页请求展开会话列表
  window.addEventListener('lh-agent-sessions-open', openSessionsFromEvent)
  // 智能体页的会话侧栏点了某条会话：切过去并载入它的历史
  window.addEventListener('lh-agent-switch', switchFromEvent)
  // 笔记编辑页广播「当前开着哪篇笔记」：决定回答后的主操作是「融入当前笔记」还是「保存为笔记」
  window.addEventListener(AGENT_NOTE_CONTEXT_EVENT, noteContextFromEvent)
  // 编辑页把融入结果回报过来（成功=预览已打开；失败=正文未改动，按钮要能重试）
  window.addEventListener(AGENT_NOTE_MERGE_RESULT_EVENT, mergeResultFromEvent)
  // 让侧栏知道"当前是哪条会话"（高亮、以及它用哪个模型）
  notifyCurrentSession()
})

onBeforeUnmount(() => {
  window.removeEventListener('lh-agent-open', openFromEvent)
  window.removeEventListener(MODEL_CONFIGURATION_CHANGED_EVENT, loadModelProfiles)
  window.removeEventListener('lh-agent-compose', composeFromEvent)
  window.removeEventListener('lh-ask-agent', askFromEvent)
  window.removeEventListener('lh-agent-sessions-open', openSessionsFromEvent)
  window.removeEventListener('lh-agent-switch', switchFromEvent)
  window.removeEventListener(AGENT_NOTE_CONTEXT_EVENT, noteContextFromEvent)
  window.removeEventListener(AGENT_NOTE_MERGE_RESULT_EVENT, mergeResultFromEvent)
})

/** 外部（会话侧栏）要求切换会话 */
async function switchFromEvent(e) {
  const id = e?.detail?.id
  if (!id || id === sessionId.value) {
    return
  }
  await switchSession(id)
}

/** 把"当前会话 + 它的模型"广播给会话侧栏，让侧栏能高亮并显示模型 */
function notifyCurrentSession() {
  const cur = sessions.value.find((x) => x.id === sessionId.value)
  window.dispatchEvent(new CustomEvent('lh-agent-current', {
    detail: {
      id: sessionId.value,
      modelProfileId: cur ? cur.modelProfileId : chatProfile.value,
      modelName: modelProfiles.value.find((p) => p.id === chatProfile.value)?.name || '',
    },
  }))
}

function openSessionsFromEvent() {
  showSessions.value = true
}

function openFromEvent() {
  open.value = true
}

/** Block writing requests become editable drafts; sending remains the user's action. */
function composeFromEvent(e) {
  const message = typeof e?.detail?.message === 'string' ? e.detail.message.trim() : ''
  if (!message) return
  input.value = input.value.trim() ? `${input.value}\n\n${message}` : message
  open.value = true
  nextTick(() => inputRef.value?.focus())
}

/**
 * 从图谱节点 / wiki 页跳进来提问。
 * 面板可能是首次打开（内容懒渲染），等一帧再发，避免消息推入时列表还没挂载。
 */
function askFromEvent(e) {
  const d = e?.detail || {}
  if (!d.message) return
  open.value = true
  nextTick(() => send(d.message, d))
}
</script>

<template>
  <div
    class="agent-root"
    :class="{ 'agent-embedded': embedded }"
    :style="embedded ? EMBEDDED_ROOT_STYLE : null"
  >
    <!-- 悬浮入口（仅抽屉模式；嵌入模式下页面本身就是入口） -->
    <transition v-if="!embedded" name="fab">
      <button v-if="!open" class="fab" type="button" @click="open = true">
        <span class="fab-dot"></span>
        <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
          <rect x="4" y="4" width="16" height="13" rx="3"></rect>
          <path d="M8 21h8M12 17v4"></path>
          <path d="M8.5 10.5v3M12 9v4.5M15.5 11.5v2"></path>
        </svg>
        <span class="fab-label">智能体</span>
        <span v-if="!configured" class="fab-warn" title="未配置 API Key">!</span>
      </button>
    </transition>

    <!-- 抽屉模式的遮罩（嵌入模式没有遮罩：它就是页面本身） -->
    <div v-if="!embedded && open" class="agent-backdrop" @click="open = false"></div>

    <!--
      两种承载方式共用同一套面板内容：
        · 抽屉模式（默认）：桌面任意页面右下角悬浮球唤起，固定右侧 480px
        · 嵌入模式：左侧导航「智能体」页整页显示（会话列表常驻左侧一栏）
      用 CSS 承载而不是两个组件：面板里有上千行对话逻辑，复制一份必然发散，
      而"智能体在哪都能用"恰恰要求行为完全一致。
    -->
    <!-- 嵌入模式用**内联样式**钉死形状：抽屉那套是 position:fixed + 480px，
         靠 CSS 优先级去覆盖容易受先后顺序影响（实测就没盖住），内联最确定 -->
    <div
      class="agent-dock"
      :class="{ open: open, embedded: embedded }"
      :style="embedded ? EMBEDDED_DOCK_STYLE : null"
    >
      <div class="panel">
        <!-- 会话版块：新开/切换/删除。会话存在库里，刷新与重启都能接着聊；
             每个会话还能记住自己用哪个模型档案（下面那排选择器）。
             嵌入模式（智能体页）下这一块由**页面自己的侧栏**承担（见 AgentSessions.vue），
             所以这里用 showSessionBar 关掉，避免同一件事有两个入口。 -->
        <div v-if="showSessionBar" class="session-bar">
          <button type="button" class="sess-toggle" @click="showSessions = !showSessions">
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M4 6h16M4 12h16M4 18h10" /></svg>
            会话<template v-if="sessions.length">（{{ sessions.length }}）</template>
          </button>
          <el-select
            v-model="chatProfile"
            size="small"
            class="sess-model"
            placeholder="默认（当前生效档案）"
            @change="onProfileChange"
          >
            <el-option label="默认（当前生效档案）" value="" />
            <el-option
              v-for="p in modelProfiles"
              :key="p.id"
              :label="p.name + '（' + p.model + '）'"
              :value="p.id"
            />
          </el-select>
          <button type="button" class="icon-btn" title="新对话" @click="newChat">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M12 5v14M5 12h14" /></svg>
          </button>
        </div>

        <div v-if="showSessionBar && showSessions" class="session-list">
          <div
            v-for="s in sessions"
            :key="s.id"
            class="sess-row"
            :class="{ on: s.id === sessionId }"
            @click="switchSession(s.id)"
          >
            <span class="sess-title" :title="s.title">{{ s.title || '新对话' }}</span>
            <span class="sess-meta">{{ s.events }} 条 · {{ s.updatedAt ? s.updatedAt.slice(5, 16) : '' }}</span>
            <button type="button" class="sess-del" title="删除这个会话及其全部消息" @click.stop="removeSession(s)">✕</button>
          </div>
          <p v-if="!sessions.length" class="hint sess-empty">还没有会话 —— 问一句就会自动建一个</p>
        </div>

        <header class="panel-head">
          <div class="head-left">
            <span class="head-avatar">A</span>
            <div>
              <div class="head-title">智能体助手</div>
              <div class="head-sub">
                <span class="dot" :class="configured ? 'on' : 'off'"></span>
                {{ configured ? currentModelLabel : '未配置模型' }}
              </div>
            </div>
          </div>
          <button v-if="messages.length" class="icon-btn" type="button" @click="newChat" title="新对话（旧对话仍保留在库里）">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M12 5v14M5 12h14" /></svg>
          </button>
          <button v-if="!embedded" class="icon-btn" type="button" @click="open = false" title="收起">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M6 6l12 12M18 6 6 18" /></svg>
          </button>
        </header>

        <div v-if="!configured" class="cfg-tip">
          智能体未启用：请在 <code>backend/src/main/resources/application.yml</code> 的
          <code>ai.deepseek.api-key</code> 填入 API Key（或设置环境变量
          <code>DEEPSEEK_API_KEY</code>）并重启后端。
        </div>

        <div ref="listRef" class="msg-list">
          <div v-if="!messages.length" class="welcome">
            <p class="welcome-t">你好，我是你的代码学习搭子</p>
            <p class="welcome-s">可以问我任何编程/IT 问题；讲到值得沉淀的知识点，点「保存为笔记」就能存进工作台。</p>
            <div class="chips">
              <button v-for="s in SUGGESTIONS" :key="s" type="button" class="chip" @click="send(s)">{{ s }}</button>
            </div>
          </div>

          <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
            <template v-if="m.role === 'assistant'">
              <div class="avatar av-ai">A</div>
              <div class="bubble assistant">
                <div v-if="m.loading" class="typing">
                  <span></span><span></span><span></span>
                </div>
                <div v-else-if="m.error" class="err">{{ m.error }}</div>
                <template v-else>
                  <!-- 工具事件角标 -->
                  <div v-if="m.events?.length" class="evt-list">
                    <span v-for="(ev, j) in m.events" :key="j" class="evt">{{ ev }}</span>
                  </div>
                  <!--
                    思考过程（thinking 模型的 reasoning_content）。
                    默认**折叠**：它动辄几千字，摊开会把正文挤到看不见；
                    但最新一轮默认展开 —— 用户点「发消息」后最想看的恰恰是"它到底怎么想的"。
                  -->
                  <details v-if="m.reasoning" class="think" :open="i === lastAssistantIndex">
                    <summary class="think-head">
                      <span class="think-title">思考过程</span>
                      <span class="think-meta">{{ m.reasoning.length }} 字 · 点此{{ i === lastAssistantIndex ? '收起' : '展开' }}</span>
                    </summary>
                    <div class="think-body">{{ m.reasoning }}</div>
                  </details>
                  <!-- 自动检索透明度：这轮回答参考了你自己的哪些记录（事件里不存它，刷新后不显示） -->
                  <div v-for="group in retrievalGroups(m.retrieved)" :key="group.key" class="ref-line" :class="'ref-' + group.key">
                    <span class="ref-label">{{ group.label }}</span>
                    <component v-for="h in group.items" :key="retrievalKey(h)" :is="retrievalSourcePath(h) ? 'button' : 'span'"
                      :type="retrievalSourcePath(h) ? 'button' : undefined" class="ref-item" :class="{ 'ref-item-link': !!retrievalSourcePath(h) }"
                      :title="retrievalTooltip(h)" @click="openRetrievedSource(h)">
                      <span class="ref-title">{{ retrievalTitle(h) }}</span>
                      <span v-if="retrievalChannels(h).length" class="ref-channels">{{ retrievalChannels(h).join(' / ') }}</span>
                    </component>
                    <span v-if="group.key === 'guide'" class="ref-guide-note">请结合原文核对</span>
                  </div>
                  <div class="md-body"><MdPreview :modelValue="fixHtmlQuotes(m.content || '')" :theme="isDark ? 'dark' : 'light'" previewTheme="github" /></div>
                  <div v-if="m.groundingStatus" class="grounding-line" :class="m.groundingStatus.status">
                    <details v-if="m.groundingStatus.note || m.groundingStatus.unsupported.length">
                      <summary>{{ m.groundingStatus.label }}</summary>
                      <p v-if="m.groundingStatus.note">{{ m.groundingStatus.note }}</p>
                      <ul v-if="m.groundingStatus.unsupported.length"><li v-for="claim in m.groundingStatus.unsupported" :key="claim">{{ claim }}</li></ul>
                    </details>
                    <span v-else>{{ m.groundingStatus.label }}</span>
                  </div>
                  <!--
                    每次回答后：询问是否沉淀。
                    在笔记页里打开的面板，主操作是「融入当前笔记」——问的问题本来就是关于这篇的，
                    另存成新笔记只会攒出一堆零碎小笔记；不在笔记页时维持「保存为笔记」。
                  -->
                  <div v-if="m.content && !m.saved && !m.merged" class="msg-actions">
                    <button
                      v-if="activeNote"
                      type="button"
                      class="act-btn primary"
                      :disabled="m.mergePending"
                      @click="mergeIntoNote(m, i)"
                    >
                      <span class="btn-ico"><svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14" /></svg></span>
                      <template v-if="m.mergePending">正在融入…</template>
                      <template v-else>融入当前笔记<template v-if="activeNoteLabel"> · {{ activeNoteLabel }}</template></template>
                    </button>
                    <button v-else type="button" class="act-btn primary" @click="askSave(m)">
                      <span class="btn-ico"><svg viewBox="0 0 24 24"><path d="M6.5 3.5h11v17l-5.5-4-5.5 4z" /></svg></span>保存为笔记
                    </button>
                    <button type="button" class="act-btn" @click="copyText(m.content)">复制</button>
                  </div>
                  <div v-else-if="m.merged" class="saved-tag">✓ 融入预览已在笔记页打开（点「替换正文」后保存生效）</div>
                  <div v-else-if="m.saved" class="saved-tag">✓ 已保存为笔记</div>
                </template>
              </div>
            </template>
            <div v-else class="bubble user">{{ m.content }}</div>
          </div>
        </div>

        <!-- 待确认的写操作：在点「确认执行」之前，后端不会动数据库 -->
        <div v-if="pending.length" class="pending-box">
          <div class="pending-head">待确认 · {{ pending.length }} 项（确认前不会写入）</div>
          <div v-for="a in pending" :key="a.id" class="pending-item">
            <div class="pending-body">
              <span class="pending-sum">{{ a.summary }}</span>
              <details v-if="a.preview?.changes?.length" class="edit-preview" open>
                <summary>查看改动 · {{ a.preview.changes.length }} 项</summary>
                <div class="edit-preview-list">
                  <div v-for="(c, index) in a.preview.changes" :key="index" class="edit-preview-change">
                    <div class="edit-preview-label">{{ index + 1 }}. {{ c.label }} · {{ c.count }} 处</div>
                    <div class="edit-preview-caption">修改前</div>
                    <MdPreview v-if="c.action === 'format'" :editor-id="`edit-before-${a.id}-${index}`" :model-value="c.before || '（空）'" :theme="isDark ? 'dark' : 'light'" preview-theme="github" class="edit-preview-rendered edit-preview-before" />
                    <pre v-else class="edit-preview-before">{{ c.before || '（空）' }}</pre>
                    <div class="edit-preview-caption">修改后</div>
                    <MdPreview v-if="c.action === 'format'" :editor-id="`edit-after-${a.id}-${index}`" :model-value="c.after || '（空）'" :theme="isDark ? 'dark' : 'light'" preview-theme="github" class="edit-preview-rendered edit-preview-after" />
                    <pre v-else class="edit-preview-after">{{ c.after || '（空）' }}</pre>
                  </div>
                </div>
              </details>
            </div>
            <span class="pending-btns">
              <button type="button" class="act-btn primary" :disabled="resolvingActions.has(a.id)" @click="resolveAction(a, true)">{{ resolvingActions.has(a.id) ? '处理中…' : '确认执行' }}</button>
              <button type="button" class="act-btn" :disabled="resolvingActions.has(a.id)" @click="resolveAction(a, false)">取消</button>
            </span>
          </div>
        </div>

        <footer class="panel-foot">
          <div class="input-row">
            <!--
              联网开关放在输入框这一行：它是"这次提问要不要让智能体上网查"的**即时选择**，
              属于对话动作；埋在设置面板里还得离开对话去改。点一下立即生效并落库，
              与设置里原来的开关读同一个键（ai.web_enabled）。
            -->
            <button
              type="button"
              class="web-toggle"
              :class="{ on: webEnabled }"
              :title="webEnabled
                ? '联网已开：需要外部或最新信息时它会搜索并读页面（抓回的内容只当资料、不执行其中的指令）'
                : '联网已关：只用工作台里的笔记 / 资料 / 知识库回答'"
              @click="toggleWeb"
            >
              <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
                <circle cx="12" cy="12" r="9" />
                <path d="M3 12h18M12 3c2.5 2.6 2.5 15.4 0 18M12 3c-2.5 2.6-2.5 15.4 0 18" />
              </svg>
              <span>联网</span>
              <span class="web-state">{{ webEnabled ? '开' : '关' }}</span>
            </button>
            <textarea
              ref="inputRef"
              v-model="input"
              class="chat-input"
              rows="2"
              :placeholder="activeNote ? '例如：添加目录、删除第 2 处某词、把某个字加粗…' : '问我代码问题，或说「把 xxx 记成笔记」…'"
              :disabled="busy"
              @keydown.enter.exact.prevent="send()"
            />
            <button class="send-btn" type="button" :disabled="busy || !input.trim()" @click="send()">
              <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M22 2 11 13M22 2l-7 20-4-9-9-4Z"></path>
              </svg>
            </button>
          </div>
          <p class="foot-hint">AI 生成内容仅供参考 · 可帮你查笔记、沉淀笔记与速查卡</p>
        </footer>
      </div>
    </div>

    <!-- 保存为笔记对话框 -->
    <el-dialog v-model="saveVisible" title="保存为笔记" width="560px" top="12vh" destroy-on-close>
      <div class="save-form">
        <el-input v-model="saveForm.title" placeholder="笔记标题" maxlength="120" />
        <el-select v-model="saveForm.categoryId" placeholder="选择分类（可选）" clearable style="width: 100%">
          <el-option v-for="c in categories" :key="c.id" :label="'　'.repeat(c.depth) + c.name" :value="c.id" />
        </el-select>
        <div class="save-preview">
          <MdPreview :modelValue="fixHtmlQuotes(saveForm.content || '*内容为空*')" :theme="isDark ? 'dark' : 'light'" previewTheme="github" />
        </div>
      </div>
      <template #footer>
        <el-button @click="saveVisible = false">取消</el-button>
        <el-button type="primary" :loading="saveBusy" @click="doSaveNote">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.agent-root {
  position: fixed;
  right: 22px;
  bottom: 22px;
  z-index: 60;
}

/* 悬浮按钮 */
.fab {
  display: flex;
  align-items: center;
  gap: 7px;
  height: 46px;
  padding: 0 16px 0 12px;
  border: none;
  border-radius: 999px;
  background: var(--app-brand);
  color: #fff;
  font-size: 14px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  box-shadow: 0 8px 24px var(--app-brand-glow);
  transition: transform 0.18s ease, box-shadow 0.18s ease;
}
.fab:hover {
  transform: translateY(-2px);
  box-shadow: 0 12px 28px var(--app-brand-glow);
}
.fab-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #fff;
  opacity: 0.9;
}
.fab-warn {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: #ffd166;
  color: #7a5a00;
  font-size: 11px;
  font-weight: 700;
  display: grid;
  place-items: center;
}

/* 承载容器：抽屉模式固定右侧一列并带遮罩；嵌入模式就是普通块级元素 */
.agent-dock {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  width: 480px;
  z-index: 2100;
  background: var(--app-card);
  border-left: 1px solid var(--app-border-weak);
  box-shadow: -8px 0 32px rgba(0, 0, 0, 0.08);
  transform: translateX(100%);
  transition: transform var(--dur) var(--ease);
  pointer-events: none;
}
.agent-dock.open {
  transform: none;
  pointer-events: auto;
}
.agent-backdrop {
  position: fixed;
  inset: 0;
  z-index: 2050;
  background: rgba(0, 0, 0, 0.18);
}
/* 嵌入模式（左侧导航「智能体」页）：占满页面、无遮罩、无位移 */
.agent-dock.embedded {
  position: static;
  width: 100%;
  height: 100%;
  transform: none;
  pointer-events: auto;
  border-left: 0;
  box-shadow: none;
}
.agent-embedded {
  height: 100%;
}
.panel {
  display: flex;
  flex-direction: column;
  height: 100%;
}
/* 会话版块：新开/切换/删除 + 本会话的模型选择 */
.session-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border-bottom: 1px solid var(--app-border-weak);
  background: var(--app-bg);
}
.sess-toggle {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: 0;
  background: transparent;
  color: var(--app-text-2);
  font-size: 12px;
  cursor: pointer;
  padding: 4px 6px;
  border-radius: 6px;
}
.sess-toggle:hover {
  background: var(--app-card);
  color: var(--app-text-1);
}
.sess-model {
  flex: 1 1 auto;
  min-width: 0;
}
.session-list {
  max-height: 220px;
  overflow-y: auto;
  padding: 6px 8px;
  border-bottom: 1px solid var(--app-border-weak);
  background: var(--app-card);
}
.sess-row {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto 20px;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 7px;
  cursor: pointer;
  font-size: 12.5px;
  color: var(--app-text-2);
}
.sess-row:hover {
  background: var(--app-bg);
  color: var(--app-text-1);
}
.sess-row.on {
  background: var(--app-brand-soft);
  color: var(--app-brand-deep);
  font-weight: 600;
}
.sess-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sess-meta {
  font-size: 11px;
  color: var(--app-text-3);
  white-space: nowrap;
}
.sess-del {
  width: 18px;
  height: 18px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--app-text-3);
  font-size: 11px;
  line-height: 1;
  cursor: pointer;
  visibility: hidden;
}
.sess-row:hover .sess-del,
.sess-row.on .sess-del {
  visibility: visible;
}
.sess-del:hover {
  background: color-mix(in srgb, #dc2626 14%, transparent);
  color: #dc2626;
}
.sess-empty {
  padding: 4px 6px;
}
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  border-bottom: 1px solid var(--app-border);
}
.head-left {
  display: flex;
  align-items: center;
  gap: 10px;
}
.head-avatar {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: linear-gradient(135deg, var(--app-brand), var(--app-brand-2, var(--app-brand)));
  color: #fff;
  font-weight: 700;
  display: grid;
  place-items: center;
}
.head-title {
  font-size: 14.5px;
  font-weight: 600;
  color: var(--app-text-1);
}
.head-sub {
  font-size: 11.5px;
  color: var(--app-text-3);
  display: flex;
  align-items: center;
  gap: 5px;
}
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  display: inline-block;
}
.dot.on {
  background: #22c55e;
}
.dot.off {
  background: #f59e0b;
}
.icon-btn {
  border: none;
  background: transparent;
  color: var(--app-text-3);
  cursor: pointer;
  font-size: 14px;
  width: 28px;
  height: 28px;
  border-radius: 7px;
}
.icon-btn:hover {
  background: var(--app-brand-soft);
  color: var(--app-brand-deep);
}

.cfg-tip {
  margin: 10px 14px 0;
  padding: 10px 12px;
  border-radius: 9px;
  background: #fff7e6;
  border: 1px solid #ffe0a3;
  color: #8a5a00;
  font-size: 12px;
  line-height: 1.7;
}
html.dark .cfg-tip {
  background: rgba(255, 209, 102, 0.12);
  border-color: rgba(255, 209, 102, 0.35);
  color: #ffd166;
}
.cfg-tip code {
  background: rgba(0, 0, 0, 0.06);
  padding: 0 4px;
  border-radius: 4px;
  font-size: 11.5px;
}

.msg-list {
  flex: 1;
  overflow-y: auto;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.welcome {
  color: var(--app-text-3);
  font-size: 13px;
}
.welcome-t {
  font-size: 15px;
  font-weight: 600;
  color: var(--app-text-1);
  margin-bottom: 6px;
}
.welcome-s {
  line-height: 1.7;
  margin-bottom: 12px;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.chip {
  border: 1px solid var(--app-border);
  background: var(--app-bg);
  color: var(--app-text-2);
  font-size: 12px;
  font-family: inherit;
  padding: 6px 11px;
  border-radius: 999px;
  cursor: pointer;
  transition: all 0.15s ease;
  text-align: left;
}
.chip:hover {
  border-color: var(--app-brand);
  color: var(--app-brand-deep);
  background: var(--app-brand-soft);
}

.msg {
  display: flex;
  gap: 8px;
}
.msg.user {
  justify-content: flex-end;
}
.avatar {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  flex-shrink: 0;
  display: grid;
  place-items: center;
  font-size: 12px;
  font-weight: 700;
  color: #fff;
  margin-top: 2px;
}
.av-ai {
  background: var(--app-brand);
}
.bubble {
  max-width: 82%;
  padding: 10px 12px;
  border-radius: 12px;
  font-size: 13.5px;
  line-height: 1.7;
  word-break: break-word;
}
.bubble.assistant {
  background: var(--app-bg);
  border: 1px solid var(--app-border);
  color: var(--app-text-1);
}
.bubble.user {
  background: var(--app-brand);
  color: #fff;
  border-bottom-right-radius: 4px;
}
/* 用户提问保留气泡（一眼能分清谁说的）；AI 回答不套框，直接铺满面板，
   像一篇文档那样读——短栏里再套一层 82% 的气泡，等于把正文挤成 330px。 */
.bubble.assistant {
  background: transparent;
  border: 0;
  border-radius: 0;
  padding: 0;
  max-width: 100%;
  flex: 1 1 auto;
  min-width: 0;
  /* 不用 break-word：它会把 AutoConfiguration.imports 这类标识符从中间劈开 */
  word-break: normal;
  overflow-wrap: anywhere;
}
.bubble.assistant .md-body {
  min-width: 0;
}

/* ---- AI 回答的排版：按"对话内阅读"收档 ----
   全局那套是给笔记页的宽栏调的（正文 15px/1.8、标题 26/21/18px —— 见 style.css 的 `--md-h*`），
   拿到 ~450px 的面板里就又大又散：标题像横幅、段间距过大、表格被挤到逐字换行。
   这里整体降一档并收紧节奏（正文 13.5px、标题 1.3/1.18/1.06/1em）。
   注意：全局标题字号带 !important，所以下面这几条字号**也必须带 !important** 才能生效
   —— 光靠"选择器更具体"胜不过 !important。 */
.bubble.assistant .md-body :deep(.md-editor-preview.md-editor-preview) {
  font-size: 13.5px;
  line-height: 1.72;
  padding: 0;
  background: transparent;
}
.bubble.assistant .md-body :deep(.md-editor-preview.md-editor-preview p) {
  margin: 0.55em 0;
}
.bubble.assistant .md-body :deep(.md-editor-preview.md-editor-preview h1),
.bubble.assistant .md-body :deep(.md-editor-preview.md-editor-preview h2),
.bubble.assistant .md-body :deep(.md-editor-preview.md-editor-preview h3),
.bubble.assistant .md-body :deep(.md-editor-preview.md-editor-preview h4),
.bubble.assistant .md-body :deep(.md-editor-preview.md-editor-preview h5),
.bubble.assistant .md-body :deep(.md-editor-preview.md-editor-preview h6) {
  /* 预览主题给标题设了 word-break:break-all，会把英文标识符拦腰截断 */
  word-break: normal;
  overflow-wrap: anywhere;
}
.bubble.assistant .md-body :deep(.md-editor-preview.md-editor-preview h1) {
  /* !important 是必须的：全站标题字号（style.css）带 !important，而悬浮面板是窄栏，
     刻意用更小的一套比例（1.3/1.18/1.06/1em）。这里靠"更具体的选择器 + !important"胜过它，
     否则窄栏里的标题会跳到 26/21/18px，一条回答的标题比面板还宽。 */
  font-size: 1.3em !important;
  margin: 1.05em 0 0.4em;
}
.bubble.assistant .md-body :deep(.md-editor-preview.md-editor-preview h2) {
  font-size: 1.18em !important;
  margin: 1.05em 0 0.4em;
  padding-bottom: 0;
  border-bottom: 0; /* 窄栏里这条 GitHub 点线太抢眼，去掉 */
}
.bubble.assistant .md-body :deep(.md-editor-preview.md-editor-preview h3) {
  font-size: 1.06em !important;
  margin: 0.95em 0 0.35em;
}
.bubble.assistant .md-body :deep(.md-editor-preview.md-editor-preview h4),
.bubble.assistant .md-body :deep(.md-editor-preview.md-editor-preview h5),
.bubble.assistant .md-body :deep(.md-editor-preview.md-editor-preview h6) {
  font-size: 1em !important;
  margin: 0.85em 0 0.3em;
}
/* 首尾不留白：回答的开头贴着事件角标，结尾贴着操作按钮 */
.bubble.assistant .md-body :deep(.md-editor-preview.md-editor-preview > :first-child) {
  margin-top: 0;
}
.bubble.assistant .md-body :deep(.md-editor-preview.md-editor-preview > :last-child) {
  margin-bottom: 0;
}
.bubble.assistant .md-body :deep(.md-editor-preview.md-editor-preview ul),
.bubble.assistant .md-body :deep(.md-editor-preview.md-editor-preview ol) {
  margin: 0.5em 0;
  padding-left: 1.35em;
}
.bubble.assistant .md-body :deep(.md-editor-preview.md-editor-preview li) {
  margin: 0.18em 0;
}
.bubble.assistant .md-body :deep(.md-editor-preview.md-editor-preview blockquote) {
  margin: 0.7em 0;
  padding: 0.1em 0.85em;
}
.bubble.assistant .md-body :deep(.md-editor-preview.md-editor-preview hr) {
  margin: 1.1em 0;
}
/* 表格：width:auto 会被压到列宽内逐字换行；改成按内容撑开 + 超出横向滚动 */
.bubble.assistant .md-body :deep(.md-editor-preview.md-editor-preview table) {
  width: max-content;
  min-width: 100%;
  margin: 0.7em 0;
}
.bubble.assistant .md-body :deep(.md-editor-preview.md-editor-preview th) {
  white-space: nowrap;
}
.bubble.assistant .md-body :deep(.md-editor-preview.md-editor-preview th),
.bubble.assistant .md-body :deep(.md-editor-preview.md-editor-preview td) {
  padding: 0.4em 0.75em;
}
.bubble.assistant .md-body :deep(.md-editor-preview.md-editor-preview td) {
  min-width: 4.5em;
}
.bubble.assistant .md-body :deep(.md-editor-preview.md-editor-preview pre) {
  margin: 0.6em 0;
}
/* 正文降到 13.5px 后，全局 0.875em 的行内代码只有 11.8px，比正文小太多 */
.bubble.assistant .md-body :deep(.md-editor-preview.md-editor-preview code) {
  font-size: 0.92em;
}


.evt-list {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-bottom: 8px;
}
.evt {
  font-size: 11px;
  color: var(--app-brand-deep);
  background: var(--app-brand-soft);
  padding: 3px 8px;
  border-radius: 999px;
}

/* 思考过程：折叠块。刻意做得比正文"轻"——虚线感的分隔 + 弱化文字，
   让它读起来像"过程记录"，而不是回答的一部分（回答才是主视觉）。 */
.think {
  margin: 0 0 10px;
  border: 1px dashed var(--app-border);
  border-radius: var(--radius);
  background: var(--app-code-bg);
}
.think-head {
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  font-size: 11.5px;
  color: var(--app-text-2);
  user-select: none;
}
.think-head:hover {
  color: var(--app-brand-deep);
}
.think-title {
  font-weight: 600;
}
.think-meta {
  color: var(--app-text-3);
}
/* 长思考要能滚动：几千字全摊开会把整条对话推得找不着回答 */
.think-body {
  max-height: 320px;
  overflow: auto;
  padding: 0 12px 10px;
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--app-text-2);
  white-space: pre-wrap;
  word-break: break-word;
}

/* 自动检索的透明度提示：刻意比工具角标更轻（工具是"做了事"，这个是"看了什么"）。
   11px 小字按项目规则用 text-2，不用更弱的 text-3。 */
.ref-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  font-size: 11px;
  color: var(--app-text-2);
}

.ref-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  max-width: 100%;
  overflow: hidden;
  white-space: nowrap;
  padding: 2px 7px;
  border: 1px solid var(--app-border);
  border-radius: 999px;
  font: inherit;
  color: inherit;
  background: transparent;
}
.ref-item-link { cursor: pointer; }
.ref-item-link:hover { border-color: var(--app-brand); color: var(--app-brand-deep); }
.ref-item-link:focus-visible { outline: 2px solid var(--app-brand); outline-offset: 2px; }
.ref-label { flex-shrink: 0; font-weight: 600; }
.ref-guide .ref-item { border-style: dashed; }
.ref-guide-note { color: var(--app-text-2); }
.ref-title {
  max-width: 200px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}
.ref-channels {
  flex-shrink: 0;
  color: var(--app-brand-deep);
}

.grounding-line { margin: 8px 0; padding: 6px 9px; border-left: 2px solid var(--app-border); color: var(--app-text-2); font-size: 12px; line-height: 1.6; }
.grounding-line.verified { border-color: var(--app-brand); }
.grounding-line.unsupported, .grounding-line.unchecked { border-color: var(--el-color-warning); }
.grounding-line summary { cursor: pointer; }
.grounding-line p { margin: 5px 0 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.grounding-line ul { margin: 5px 0 0; padding-left: 20px; }
.grounding-line li { overflow-wrap: anywhere; }

.typing {
  display: flex;
  gap: 4px;
  padding: 3px 0;
}
.typing span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--app-text-3);
  animation: blink 1.2s infinite;
}
.typing span:nth-child(2) {
  animation-delay: 0.2s;
}
.typing span:nth-child(3) {
  animation-delay: 0.4s;
}
@keyframes blink {
  0%, 80%, 100% {
    opacity: 0.25;
  }
  40% {
    opacity: 1;
  }
}
.err {
  color: #e53e3e;
  font-size: 13px;
}

.msg-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--app-border);
}
.act-btn {
  display: inline-flex;
  align-items: center;
  border: 1px solid var(--app-border);
  background: var(--app-card);
  color: var(--app-text-2);
  font-size: 12px;
  font-family: inherit;
  padding: 4px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.15s ease;
}
.act-btn:hover {
  border-color: var(--app-brand);
}
.act-btn.primary {
  background: var(--app-brand);
  border-color: var(--app-brand);
  color: #fff;
  font-weight: 500;
}
.saved-tag {
  margin-top: 8px;
  font-size: 12px;
  color: #16a34a;
}

/* 待确认的写操作卡片：做得像"一张待办"，并在标题里明说"确认前不会写入" ——
   这句话本身就是这个功能的价值所在（用户因此敢让 AI 自由发挥）。 */
.pending-box {
  margin: 0 12px 8px;
  padding: 10px 12px;
  border: 1px solid color-mix(in srgb, var(--app-brand) 35%, var(--app-border));
  border-radius: 10px;
  background: color-mix(in srgb, var(--app-brand) 6%, transparent);
  max-height: min(360px, 42vh);
  overflow-y: auto;
  flex-shrink: 0;
}

.pending-head {
  margin-bottom: 8px;
  font-size: 11.5px;
  font-weight: 600;
  color: var(--app-brand-deep);
}

.pending-item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  padding: 6px 0;
}

.pending-body { flex: 1; min-width: 0; }
.edit-preview { margin-top: 6px; font-size: 12px; color: var(--app-text-2); }
.edit-preview summary { cursor: pointer; color: var(--app-brand-deep); }
.edit-preview-list { margin-top: 6px; }
.edit-preview-change + .edit-preview-change { margin-top: 10px; }
.edit-preview-label { font-weight: 600; color: var(--app-text-1); }
.edit-preview-caption { margin: 4px 0 2px; font-size: 11px; }
.edit-preview pre { padding: 6px 8px; margin: 0; border-radius: 5px; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 11px; line-height: 1.5; }
.edit-preview-rendered { border-radius: 5px; background-color: transparent; }
.edit-preview-rendered :deep(.md-editor-preview-wrapper) { padding: 6px 8px; }
.edit-preview-rendered :deep(.md-editor-preview) { font-size: 12px; line-height: 1.5; }
.edit-preview-rendered :deep(p) { margin: 0; }
.edit-preview-before { background: color-mix(in srgb, #e34e58 10%, var(--app-bg)); }
.edit-preview-after { background: color-mix(in srgb, #239b68 10%, var(--app-bg)); }
.pending-btns button:disabled { opacity: .6; cursor: wait; }

.pending-item + .pending-item {
  border-top: 1px dashed var(--app-border);
}

.pending-sum {
  min-width: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-text-1);
  word-break: break-all;
}

.pending-btns {
  display: inline-flex;
  gap: 6px;
  flex: none;
}

.panel-foot {
  border-top: 1px solid var(--app-border);
  padding: 12px 14px 14px;
}
.input-row {
  display: flex;
  gap: 8px;
  align-items: center;
}
/* 联网开关：小、贴着输入框，一眼看出开/关 */
.web-toggle {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 38px;
  padding: 0 10px;
  border: 1px solid var(--app-border);
  border-radius: 10px;
  background: var(--app-card);
  color: var(--app-text-3);
  font-size: 12px;
  cursor: pointer;
  transition: all var(--dur-fast) ease;
  white-space: nowrap;
}
.web-toggle:hover {
  color: var(--app-text-1);
  border-color: var(--app-border-weak);
}
.web-toggle.on {
  color: var(--app-brand-deep);
  border-color: color-mix(in srgb, var(--app-brand) 45%, transparent);
  background: var(--app-brand-soft);
}
.web-state {
  font-weight: 600;
}
.chat-input {
  flex: 1;
  min-width: 0;
  height: 64px;
  min-height: 38px;
  max-height: 180px;
  resize: vertical;
  line-height: 1.55;
  border: 1px solid var(--app-border);
  border-radius: 10px;
  background: var(--app-bg);
  color: var(--app-text-1);
  padding: 9px 12px;
  font-size: 13.5px;
  font-family: inherit;
  outline: none;
  transition: border-color 0.15s ease;
}
.chat-input:focus {
  border-color: var(--app-brand);
}
/* P4：基础规则里的 outline:none 会抹掉键盘焦点环，这里为键盘导航补回 */
.chat-input:focus-visible {
  outline: 2px solid color-mix(in srgb, var(--app-brand) 55%, transparent);
  outline-offset: 2px;
}
.chat-input::placeholder {
  color: var(--app-text-3);
}
.send-btn {
  width: 38px;
  height: 38px;
  border: none;
  border-radius: 10px;
  background: var(--app-brand);
  color: #fff;
  cursor: pointer;
  display: grid;
  place-items: center;
  transition: opacity 0.15s ease;
}
.send-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.foot-hint {
  margin-top: 8px;
  font-size: 11px;
  color: var(--app-text-3);
  text-align: center;
}

.save-form {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.save-preview {
  border: 1px solid var(--app-border);
  border-radius: 8px;
  padding: 8px 12px;
  max-height: 260px;
  overflow: auto;
  background: var(--app-bg);
  font-size: 13px;
}

/* 抽屉开关动画 */
.fab-enter-active,
.fab-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}
.fab-enter-from,
.fab-leave-to {
  opacity: 0;
  transform: translateY(8px);
}
</style>
