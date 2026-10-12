<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { NodeViewWrapper, nodeViewProps } from '@tiptap/vue-3'
import { EditorState, Compartment, Transaction } from '@codemirror/state'
import { EditorView, keymap, lineNumbers } from '@codemirror/view'
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands'
import { bracketMatching, syntaxHighlighting, defaultHighlightStyle } from '@codemirror/language'
import MermaidPreview from './MermaidPreview.vue'
import { codeBlockChange, codeBlockDocument } from '../utils/codeBlockViewState.js'
import { createCodeBlockViewport } from '../utils/codeBlockViewport.js'

const props = defineProps(nodeViewProps)
const host = ref(null)
const view = shallowRef(null)
const languageSelect = ref(null)
const controlsMounted = ref(false)
const languageOptionsMounted = ref(false)
const previewActive = ref(true)
const sourceLines = computed(() => codeBlockDocument(props.node.attrs.code).split('\n'))
const collapsed = ref(false)
const copied = ref(false)
const mermaidMode = ref('preview')
const isMermaid = computed(() => String(props.node.attrs.language || '').toLowerCase() === 'mermaid')
const showSource = computed(() => !isMermaid.value || mermaidMode.value === 'source')
const langComp = new Compartment()
let disposed = false
let languageVersion = 0
let syncingCode = false
let copyTimer
let viewport

const LANGS = ['mermaid', 'python', 'java', 'javascript', 'typescript', 'vue', 'html', 'css', 'json', 'yaml', 'xml', 'sql', 'go', 'rust', 'c', 'cpp', 'csharp', 'php', 'ruby', 'kotlin', 'markdown', 'plaintext']
const languageOptions = computed(() => {
  const current = props.node.attrs.language
  return current && !LANGS.includes(current) ? [current, ...LANGS] : LANGS
})

/**
 * 只读渲染（速查卡这类只读预览）：语言控件从下拉换成静态标签，CodeMirror 关掉编辑。
 * 取编辑器的 options.editable 而不是 isEditable —— node view 是在 EditorView 构造期间创建的，
 * 那一刻 editor.view 还没赋值，isEditable 会返回 undefined。
 * 行号、复制、折叠全部保留：只读场景照样要能看清、能选中复制命令。
 */
const readonly = props.editor?.options?.editable === false

const LANG_LOADERS = {
  python: () => import('@codemirror/lang-python').then((m) => m.python()),
  java: () => import('@codemirror/lang-java').then((m) => m.java()),
  javascript: () => import('@codemirror/lang-javascript').then((m) => m.javascript()),
  typescript: () => import('@codemirror/lang-javascript').then((m) => m.javascript({ typescript: true })),
  vue: () => import('@codemirror/lang-vue').then((m) => m.vue()),
  html: () => import('@codemirror/lang-html').then((m) => m.html()),
  css: () => import('@codemirror/lang-css').then((m) => m.css()),
  json: () => import('@codemirror/lang-json').then((m) => m.json()),
  yaml: () => import('@codemirror/lang-yaml').then((m) => m.yaml()),
  xml: () => import('@codemirror/lang-xml').then((m) => m.xml()),
  sql: () => import('@codemirror/lang-sql').then((m) => m.sql()),
  go: () => import('@codemirror/lang-go').then((m) => m.go()),
  rust: () => import('@codemirror/lang-rust').then((m) => m.rust()),
  cpp: () => import('@codemirror/lang-cpp').then((m) => m.cpp()),
  php: () => import('@codemirror/lang-php').then((m) => m.php()),
  markdown: () => import('@codemirror/lang-markdown').then((m) => m.markdown()),
}
async function langExt(lang) {
  const loader = LANG_LOADERS[String(lang || '').toLowerCase()]
  if (!loader) return []
  try { return await loader() } catch { return [] }
}

function changeLang(language) {
  props.updateAttributes({ language })
}

async function toggleMermaidMode() {
  mermaidMode.value = mermaidMode.value === 'preview' ? 'source' : 'preview'
  if (showSource.value && !collapsed.value) {
    await nextTick()
    viewport?.request()
    view.value?.requestMeasure()
    if (!readonly) view.value?.focus()
  }
}

async function copyCode() {
  try {
    await navigator.clipboard.writeText(props.node.attrs.code || '')
    copied.value = true
    clearTimeout(copyTimer)
    copyTimer = setTimeout(() => (copied.value = false), 1500)
  } catch { /* 剪贴板不可用则忽略 */ }
}

async function configureLanguage() {
  if (!view.value) return
  const version = ++languageVersion
  const language = await langExt(props.node.attrs.language)
  if (disposed || props.editor.isDestroyed || version !== languageVersion) return
  view.value?.dispatch({ effects: langComp.reconfigure(language) })
}

function initializeSource() {
  if (view.value || disposed || !previewActive.value || !host.value || props.editor.isDestroyed || collapsed.value || !showSource.value) return
  const state = EditorState.create({
    doc: codeBlockDocument(props.node.attrs.code),
    extensions: [
      lineNumbers(),
      history(),
      syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
      bracketMatching(),
      keymap.of([...defaultKeymap, ...historyKeymap, indentWithTab]),
      EditorView.lineWrapping,
      // 只读时连 CodeMirror 一起锁住（否则卡片里的代码块还能改，改完却无处可存）
      EditorState.readOnly.of(readonly),
      EditorView.editable.of(!readonly),
      langComp.of([]),
      EditorView.updateListener.of((u) => {
        if (u.docChanged && !syncingCode && !disposed) props.updateAttributes({ code: u.state.doc.toString() })
      }),
    ],
  })
  view.value = new EditorView({ state, parent: host.value })
  configureLanguage()
  nextTick(() => { if (!disposed && previewActive.value) view.value?.requestMeasure() })
}

async function activateSource(event) {
  // Readonly placeholders allow ordinary browser text selection before mounting.
  if (readonly) return
  event?.preventDefault?.()
  viewport?.request()
  await nextTick()
  if (disposed || !previewActive.value || !view.value) return
  const position = event?.clientX != null ? view.value.posAtCoords({ x: event.clientX, y: event.clientY }) : null
  if (position != null) view.value.dispatch({ selection: { anchor: position } })
  view.value.focus()
}

async function activateLanguage(open = false) {
  viewport?.request()
  await nextTick()
  if (disposed || !previewActive.value) return
  languageSelect.value?.focus()
  if (open) languageSelect.value?.toggleMenu?.()
}

onMounted(() => {
  const element = host.value?.closest('.code-block-cm')
  if (disposed || !element || props.editor.isDestroyed) return
  viewport = createCodeBlockViewport({
    editor: props.editor,
    element,
    activate() { controlsMounted.value = true; initializeSource() },
    onActivity(active) { previewActive.value = active },
  })
  // Only the selected, visible source editor may take focus after insertion.
  let pos = null
  try { pos = props.getPos() } catch { return }
  if (!readonly && showSource.value && props.selected && props.editor.view.hasFocus() && props.editor.state.selection.from === pos) {
    viewport.request()
    nextTick(() => { if (!disposed && previewActive.value) view.value?.focus() })
  }
})

watch(
  () => props.node.attrs.language,
  () => {
    mermaidMode.value = 'preview'
    configureLanguage()
  },
)

watch(() => props.node.attrs.code, code => {
  if (disposed || !view.value) return
  const changes = codeBlockChange(view.value.state.doc.toString(), codeBlockDocument(code))
  if (!changes) return
  syncingCode = true
  try { view.value.dispatch({ changes, annotations: Transaction.addToHistory.of(false) }) }
  finally { syncingCode = false }
}, { flush: 'sync' })

watch([collapsed, showSource], async () => {
  if (collapsed.value || !showSource.value) return
  await nextTick()
  if (!disposed) { viewport?.refresh(); view.value?.requestMeasure() }
})

onBeforeUnmount(() => { disposed = true; viewport?.dispose(); languageVersion++; clearTimeout(copyTimer); view.value?.destroy(); view.value = null })
</script>

<template>
  <NodeViewWrapper class="code-block-cm" :class="{ 'is-selected': selected }" :data-line="node.attrs.dataLine || null">
    <div class="cm-head" contenteditable="false" @mousedown.stop @click.stop @keydown.stop>
      <el-select v-if="!readonly && controlsMounted" ref="languageSelect" class="cm-lang" size="small" :model-value="node.attrs.language || 'plaintext'"
        aria-label="代码语言" filterable @change="changeLang" @visible-change="open => { if (open) languageOptionsMounted = true }">
        <template v-if="languageOptionsMounted"><el-option v-for="language in languageOptions" :key="language" :label="language" :value="language" /></template>
      </el-select>
      <button v-else-if="!readonly" type="button" class="cm-lang cm-lang-trigger" aria-label="代码语言" aria-haspopup="listbox"
        @mousedown.prevent="activateLanguage(true)" @focus="activateLanguage()" @keydown.enter.prevent="activateLanguage(true)" @keydown.space.prevent="activateLanguage(true)">
        <span>{{ node.attrs.language || 'plaintext' }}</span><span aria-hidden="true">⌄</span>
      </button>
      <!-- 只读时语言是静态标签：没有语言就不显示（跟下拉没选中时的空白一致） -->
      <span v-else-if="node.attrs.language" class="cm-lang-text">{{ node.attrs.language }}</span>
      <span class="cm-head-spacer" />
      <el-button v-if="isMermaid" class="cm-btn" text size="small" :aria-pressed="mermaidMode === 'source'" @click="toggleMermaidMode">
        {{ mermaidMode === 'preview' ? (readonly ? '查看源码' : '编辑源码') : '预览图' }}
      </el-button>
      <el-button class="cm-btn" text size="small" @click="copyCode">{{ copied ? '已复制' : '复制代码' }}</el-button>
      <el-button class="cm-btn" text size="small" :aria-expanded="!collapsed" @click="collapsed = !collapsed">{{ collapsed ? '展开' : '折叠' }}</el-button>
    </div>
    <MermaidPreview v-if="isMermaid" v-show="!collapsed && !showSource" :source="node.attrs.code || ''" :active="previewActive && !collapsed && !showSource" />
    <div v-show="!collapsed && showSource" ref="host" class="code-block-cm-host">
      <pre v-if="!view" class="cm-source-placeholder" contenteditable="false" :tabindex="readonly ? undefined : 0" :aria-label="readonly ? '代码' : '编辑代码'"
        @mousedown.stop="activateSource" @focus="activateSource"><template v-for="(line, index) in sourceLines" :key="index"><span class="cm-placeholder-number" aria-hidden="true" :data-line-number="index + 1" /><code class="cm-placeholder-line">{{ line + (index < sourceLines.length - 1 ? '\n' : '') }}</code></template></pre>
    </div>
  </NodeViewWrapper>
</template>

<style scoped>
.code-block-cm { border: 1px solid var(--app-border-weak); border-radius: 10px; background: var(--app-card); margin: 1em 0; overflow: hidden; }
.code-block-cm.is-selected { border-color: var(--app-brand); }
.cm-head { display: flex; align-items: center; flex-wrap: wrap; gap: 4px 8px; padding: 4px 10px; border-bottom: 1px solid var(--app-border-weak); }
.cm-lang { width: 126px; min-width: 0; font: 12px/1 ui-monospace, SFMono-Regular, Consolas, monospace; }
.cm-lang :deep(.el-select__wrapper) { min-height: 28px; background: transparent; }
.cm-lang-trigger { height: 28px; display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 0 12px; border: 1px solid var(--app-border); border-radius: var(--el-border-radius-base, 4px); background: transparent; color: var(--app-text-2); cursor: pointer; }
.cm-lang-trigger span:first-child { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cm-lang-trigger:focus-visible { outline: 2px solid var(--app-brand); outline-offset: -2px; }
/* 只读态的语言标签：去掉下拉的边框与光标感，其余与下拉的字体/颜色一致 */
.cm-lang-text { font: 12px/1 ui-monospace, SFMono-Regular, Consolas, monospace; color: var(--app-text-2); padding: 2px 2px; user-select: none; }
.cm-head-spacer { flex: 1; }
.cm-btn.el-button { height: 28px; margin-left: 0; font-size: 12px; color: var(--app-text-2); padding: 2px 8px; }
.cm-btn:hover { background: color-mix(in srgb, var(--app-text-1) 8%, transparent); color: var(--app-text-1); }
.cm-btn:focus-visible { outline: 2px solid var(--app-brand); outline-offset: -2px; }
@media (max-width: 480px) { .cm-head { padding: 6px; gap: 4px; } .cm-lang { width: 106px; } .cm-btn.el-button { padding: 2px 6px; } }
/* 行号对齐：不要自定义 line-height，交给 CodeMirror 自带对齐 */
.code-block-cm-host :deep(.cm-editor),
.code-block-cm-host :deep(.cm-editor .cm-scroller),
.code-block-cm-host :deep(.cm-editor .cm-content),
.code-block-cm-host :deep(.cm-editor .cm-gutters) {
  font-family: ui-monospace, SFMono-Regular, Consolas, 'Cascadia Code', monospace;
  font-size: 13.5px;
}
.code-block-cm-host :deep(.cm-gutters) { background: transparent; border: none; color: var(--app-text-3); }
.code-block-cm-host :deep(.cm-editor) { outline: none; }
.cm-source-placeholder { display: grid; grid-template-columns: max-content minmax(0, 1fr); margin: 0; padding: 4px 0; font: 13.5px/1.4 ui-monospace, SFMono-Regular, Consolas, 'Cascadia Code', monospace; tab-size: 4; background: transparent; color: var(--app-text-1); outline: none; }
.cm-placeholder-number { min-width: 20px; padding: 0 3px 0 5px; box-sizing: border-box; text-align: right; color: var(--app-text-3); user-select: none; }
.cm-placeholder-number::before { content: attr(data-line-number); }
.cm-placeholder-line { min-height: 1.4em; padding: 0 2px 0 6px; font: inherit; white-space: break-spaces; word-break: break-word; overflow-wrap: anywhere; }
.cm-source-placeholder:focus-visible { box-shadow: inset 0 0 0 1px var(--app-brand); }
</style>
