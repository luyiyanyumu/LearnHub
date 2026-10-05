<script setup>
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { NodeViewWrapper, nodeViewProps } from '@tiptap/vue-3'
import { EditorState, Compartment } from '@codemirror/state'
import { EditorView, keymap, lineNumbers } from '@codemirror/view'
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands'
import { bracketMatching, syntaxHighlighting, defaultHighlightStyle } from '@codemirror/language'

const props = defineProps(nodeViewProps)
const host = ref(null)
const view = shallowRef(null)
const collapsed = ref(false)
const copied = ref(false)
const langComp = new Compartment()
let disposed = false
let languageVersion = 0

const LANGS = ['python', 'java', 'javascript', 'typescript', 'vue', 'html', 'css', 'json', 'yaml', 'xml', 'sql', 'go', 'rust', 'c', 'cpp', 'csharp', 'php', 'ruby', 'kotlin', 'markdown', 'plaintext']

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

function changeLang(e) {
  props.updateAttributes({ language: e.target.value })
}

async function copyCode() {
  try {
    await navigator.clipboard.writeText(props.node.attrs.code || '')
    copied.value = true
    setTimeout(() => (copied.value = false), 1500)
  } catch { /* 剪贴板不可用则忽略 */ }
}

onMounted(async () => {
  const language = await langExt(props.node.attrs.language)
  if (disposed || !host.value || props.editor.isDestroyed) return
  // 去掉结尾换行：markdown 围栏内容的 <code> 带一个尾 \n，会让 CodeMirror 多渲染一个空行
  const doc = (props.node.attrs.code || '').replace(/\n$/, '')
  const state = EditorState.create({
    doc,
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
      langComp.of(language),
      EditorView.updateListener.of((u) => { if (u.docChanged) props.updateAttributes({ code: u.state.doc.toString() }) }),
    ],
  })
  view.value = new EditorView({ state, parent: host.value })
  // The language import can finish after the outer editor's insertion callback.
  // Move focus only while this exact code block is still the active selection.
  let pos = null
  try { pos = props.getPos() } catch { return }
  if (props.selected && props.editor.view.hasFocus() && props.editor.state.selection.from === pos) {
    view.value.focus()
  }
})

watch(
  () => props.node.attrs.language,
  async (lang) => {
    const version = ++languageVersion
    const language = await langExt(lang)
    if (disposed || props.editor.isDestroyed || version !== languageVersion) return
    view.value?.dispatch({ effects: langComp.reconfigure(language) })
  },
)

onBeforeUnmount(() => { disposed = true; view.value?.destroy(); view.value = null })
</script>

<template>
  <NodeViewWrapper class="code-block-cm" :class="{ 'is-selected': selected }" :data-line="node.attrs.dataLine || null">
    <div class="cm-head" contenteditable="false">
      <select v-if="!readonly" class="cm-lang" :value="node.attrs.language || ''" @change="changeLang" @mousedown.stop @click.stop>
        <option v-for="l in LANGS" :key="l" :value="l">{{ l }}</option>
      </select>
      <!-- 只读时语言是静态标签：没有语言就不显示（跟下拉没选中时的空白一致） -->
      <span v-else-if="node.attrs.language" class="cm-lang-text">{{ node.attrs.language }}</span>
      <span class="cm-head-spacer" />
      <button class="cm-btn" type="button" @mousedown.stop @click.stop="copyCode">{{ copied ? '已复制' : '复制代码' }}</button>
      <button class="cm-btn" type="button" @mousedown.stop @click.stop="collapsed = !collapsed">{{ collapsed ? '展开' : '折叠' }}</button>
    </div>
    <div v-show="!collapsed" ref="host" class="code-block-cm-host" />
  </NodeViewWrapper>
</template>

<style scoped>
.code-block-cm { border: 1px solid var(--app-border-weak); border-radius: 10px; background: var(--app-card); margin: 1em 0; overflow: hidden; }
.code-block-cm.is-selected { border-color: var(--app-brand); }
.cm-head { display: flex; align-items: center; gap: 8px; padding: 4px 10px; border-bottom: 1px solid var(--app-border-weak); }
.cm-lang { font: 12px/1 ui-monospace, SFMono-Regular, Consolas, monospace; color: var(--app-text-2); background: transparent; border: 1px solid var(--app-border-weak); border-radius: 5px; padding: 2px 4px; cursor: pointer; }
/* 只读态的语言标签：去掉下拉的边框与光标感，其余与下拉的字体/颜色一致 */
.cm-lang-text { font: 12px/1 ui-monospace, SFMono-Regular, Consolas, monospace; color: var(--app-text-2); padding: 2px 2px; user-select: none; }
.cm-head-spacer { flex: 1; }
.cm-btn { font-size: 12px; color: var(--app-text-2); background: transparent; border: 0; border-radius: 5px; padding: 2px 8px; cursor: pointer; }
.cm-btn:hover { background: color-mix(in srgb, var(--app-text-1) 8%, transparent); color: var(--app-text-1); }
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
</style>
