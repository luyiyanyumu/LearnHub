<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'
import { MdEditor, MdPreview } from 'md-editor-v3'
// 编辑器全局初始化 + 样式（原来在 main.js，为了不占首屏挪到这里；
// 本页是路由懒加载的，静态 import 不会影响首屏）
import '../utils/mdEditorSetup'
import { aiApi, categoryApi, tagApi, noteApi, saveBlob } from '../api'
import { fixHtmlQuotes } from '../utils/htmlQuotes'
import { isDark } from '../composables/useTheme'
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

/** 预览区 DOM（限定在本页编辑容器内，避开 AI 弹窗/全局面板里的 MdPreview） */
function previewEl() {
  return editorWrapRef.value?.querySelector('.md-editor-preview') || null
}

function setEditable(on) {
  const el = previewEl()
  if (!el) return false
  if (on) {
    el.setAttribute('contenteditable', 'true')
    el.classList.add('lh-preview-editing')
    el.addEventListener('paste', onPreviewPaste)
  } else {
    el.removeAttribute('contenteditable')
    el.classList.remove('lh-preview-editing')
    el.removeEventListener('paste', onPreviewPaste)
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
  editorRef.value?.togglePreviewOnly?.(true)
  // 等 previewOnly 切换 + markdown 渲染完成再把预览区设为可编辑
  for (let i = 0; i < 40; i++) {
    await nextTick()
    await new Promise((r) => requestAnimationFrame(r))
    if (setEditable(true)) {
      document.addEventListener('keydown', onPreviewKeydown)
      return
    }
  }
  previewEditing.value = false
  editorRef.value?.togglePreviewOnly?.(false)
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
  setEditable(false)
  previewEditing.value = false
  editorRef.value?.togglePreviewOnly?.(false)
  maybeOpenCatalog()
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
  ElMessage.success('已用 AI 结果替换正文，确认无误后点右上角「保存」')
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
  maybeOpenCatalog()
}

/** 打开笔记后，若正文含标题，自动展开右侧「目录」（md-editor 自带目录，flat 固定侧栏式） */
function maybeOpenCatalog() {
  if (!/^#{1,6}\s/m.test(form.value.content || '')) return
  // 等编辑器内部就绪再开目录，避免事件发出时目录组件还没挂载
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      const ed = editorRef.value
      if (ed && typeof ed.toggleCatalog === 'function') ed.toggleCatalog(true)
    })
  })
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
      ElMessage.success('笔记已创建')
      router.replace(`/notes/${created.id}`)
      maybeOpenCatalog()
    } else {
      await noteApi.update(id.value, payload)
      savedSnapshot = formFingerprint()
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
  await loadMeta()
  loadNote()
})

onBeforeUnmount(() => {
  window.removeEventListener('lh-meta-changed', loadMeta)
  document.removeEventListener('keydown', onPreviewKeydown)
})
</script>

<template>
  <div class="edit-page" v-loading="loading">
    <div class="meta-bar">
      <el-button link @click="onBack">← 返回列表</el-button>
      <el-input v-model="form.title" placeholder="输入笔记标题…" class="title-input" maxlength="200" />
      <el-select v-model="form.categoryId" placeholder="选择分类" clearable style="width: 140px">
        <el-option v-for="c in categories" :key="c.id" :label="'　'.repeat(c.depth) + c.name" :value="c.id" />
      </el-select>
      <el-select v-model="form.tagIds" multiple placeholder="标签" clearable style="width: 190px">
        <el-option v-for="t in tags" :key="t.id" :label="t.name" :value="t.id" />
      </el-select>
      <div class="ai-group">
        <el-button class="ai-btn" :loading="aiBusy" :disabled="aiBusy" @click="aiProcess('polish')">
          <span class="ai-ico">✨</span> AI 润色
        </el-button>
        <el-button class="ai-btn" :loading="aiBusy" :disabled="aiBusy" @click="aiProcess('format')">
          <span class="ai-ico">🧹</span> 整理格式
        </el-button>
        <el-button class="ai-btn" @click="aiOpenChat">🤖 AI 对话</el-button>
        <el-button
          class="ai-btn"
          :type="previewEditing ? 'primary' : 'default'"
          @click="togglePreviewEdit"
        >
          <span class="ai-ico">✍</span> {{ previewEditing ? '退出预览编辑' : '预览编辑' }}
        </el-button>
      </div>
      <el-dropdown v-if="!isNew" trigger="click" @command="exportNote">
        <el-button>
          导出<el-icon style="margin-left: 2px"><ArrowDown /></el-icon>
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="md">Markdown (.md)</el-dropdown-item>
            <el-dropdown-item command="html">网页 HTML (.html)</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <el-button type="primary" :loading="saving" @click="save">{{ isNew ? '创建' : '保存' }}</el-button>
    </div>

    <div
      ref="editorWrapRef"
      class="editor-wrap"
      :class="{ 'is-preview-editing': previewEditing }"
      @paste.capture="onPasteCapture"
    >
      <!-- 预览编辑模式的操作条：所见即所得改完，反推回 Markdown 源码 -->
      <div v-if="previewEditing" class="preview-edit-bar">
        <span class="pe-tip">
          ✍ 预览编辑中：直接在下方的排版内容里改文字与格式（Ctrl/⌘+S 同步并退出；Esc / 再点按钮也都是「先同步再退出」，只有「放弃改动」才丢弃）
        </span>
        <el-button size="small" @click="abandonPreviewEdit">↺ 放弃改动</el-button>
        <el-button size="small" type="primary" @click="syncPreviewToSource()">✓ 同步到源码</el-button>
      </div>

      <!-- 富文本格式条（md-editor 不支持自定义工具项，故独立成条） -->
      <FormatBar v-if="!previewEditing" @apply="applyFormat" />

      <MdEditor
        ref="editorRef"
        v-model="form.content"
        :placeholder="'支持 Markdown：代码块、表格、链接…\n# 一级标题\n```java\n// 代码示例\n```'"
        catalog-layout="flat"
        :catalog-max-depth="3"
        :toolbars="[
          'bold', 'italic', 'strikeThrough', '|',
          'title', 'quote', 'unorderedList', 'orderedList', '|',
          'code', 'inlineCode', 'link', 'image', 'table', '|',
          'preview', 'catalog',
        ]"
        language="zh-CN"
      />
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
.edit-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 12px 14px;
}

.meta-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.title-input {
  flex: 1;
  min-width: 200px;
  font-size: 15px;
  font-weight: 500;
}

.ai-group {
  display: flex;
  gap: 6px;
}

.ai-btn {
  margin-left: 0 !important;
  border-color: var(--app-border);
  color: var(--app-text-2);
}

.ai-btn:hover {
  border-color: var(--app-brand);
  color: var(--app-brand-deep);
  background: var(--app-brand-soft);
}

.ai-ico {
  font-size: 13px;
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

.editor-wrap {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--app-card);
  border: 1px solid var(--app-border);
  border-radius: var(--radius);
  overflow: hidden;
}

.editor-wrap :deep(.md-editor) {
  flex: 1;
  min-height: 0;
  height: auto;
}

/* 预览编辑操作条 */
.preview-edit-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 6px 10px;
  background: var(--app-brand-soft);
  border-bottom: 1px solid var(--app-border);
}

.pe-tip {
  flex: 1 1 260px;
  font-size: 12.5px;
  color: var(--app-brand-deep);
}

/* 预览编辑模式下必须隐藏 md-editor 自带工具栏：
   它仍然可点，会把 ** 之类写进隐藏的源码并触发预览重渲染，冲掉用户正在做的所见即所得改动 */
.editor-wrap.is-preview-editing :deep(.md-editor-toolbar-wrapper) {
  display: none;
}

/* 预览区可编辑时的视觉提示 */
.editor-wrap :deep(.lh-preview-editing) {
  outline: 2px dashed var(--app-brand);
  outline-offset: 6px;
  border-radius: 6px;
  cursor: text;
}

.editor-wrap :deep(.lh-preview-editing:focus) {
  outline-style: solid;
}
</style>
