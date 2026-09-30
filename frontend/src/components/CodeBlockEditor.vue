<script setup>
/**
 * 代码块内嵌编辑器（CodeMirror 6）。
 *
 * 用在笔记预览里：**覆盖**在 md-editor 渲染出的 <pre> 之上，而不是替换它 ——
 * 这样 md-editor 的 DOM 结构不变，"复制代码"和"预览反推成 Markdown"这两条路径
 * （都读 <pre><code> 的 textContent）继续有效；我们只在编辑后把文本写回那个 <code>。
 *
 * 行号用 CodeMirror 自己的 lineNumbers()：**一行一个真实行号**，
 * 打字换行会立即多出一个号（这正是"渲染后按行拆 DOM"做不到的事）。
 */
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { EditorState, Compartment } from '@codemirror/state'
import { EditorView, keymap, lineNumbers, highlightActiveLine, drawSelection } from '@codemirror/view'
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands'
import { bracketMatching, indentOnInput, syntaxHighlighting, defaultHighlightStyle } from '@codemirror/language'

const props = defineProps({
  code: { type: String, default: '' },
  lang: { type: String, default: '' },
  dark: { type: Boolean, default: false },
})
const emit = defineEmits(['change'])

const host = ref(null)
const view = shallowRef(null)
/** 语言与主题各自独立成 compartment：换语言/换主题时只重配那一块，不重建编辑器 */
const langComp = new Compartment()
const themeComp = new Compartment()

/** 语言名 → CodeMirror 语言包（与代码库/工具栏的语言清单同名）。没有对应包就退回纯文本。 */
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
  shell: () => import('@codemirror/legacy-modes/mode/shell').then((m) => shellExt(m.shell)),
  plaintext: () => Promise.resolve([]),
}

/** legacy-modes 的写法与 lang-* 不同，需要包一层 StreamLanguage */
async function shellExt(parser) {
  const { StreamLanguage } = await import('@codemirror/language')
  return StreamLanguage.define(parser)
}

function buildTheme(dark) {
  return EditorView.theme(
    {
      '&': { fontSize: '13.5px', backgroundColor: 'transparent' },
      '.cm-scroller': {
        fontFamily: "ui-monospace, SFMono-Regular, Consolas, 'Cascadia Code', monospace",
        lineHeight: '1.7',
      },
      '.cm-gutters': {
        backgroundColor: 'transparent',
        border: 'none',
        color: 'var(--app-text-3)',
        userSelect: 'none',
      },
      '.cm-content': { padding: '0', caretColor: 'var(--app-text-1)' },
      '&.cm-focused': { outline: 'none' },
      '.cm-activeLine': { backgroundColor: 'transparent' },
      '.cm-activeLineGutter': { backgroundColor: 'transparent', color: 'var(--app-text-2)' },
    },
    { dark },
  )
}

async function langExt(lang) {
  const loader = LANG_LOADERS[String(lang || '').toLowerCase()]
  if (!loader) return []
  try {
    return await loader()
  } catch {
    return [] // 语言包缺失就当纯文本，不影响编辑
  }
}

async function mount() {
  if (!host.value) return
  const state = EditorState.create({
    doc: props.code,
    extensions: [
      lineNumbers(),
      history(),
      drawSelection(),
      indentOnInput(),
      bracketMatching(),
      syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
      highlightActiveLine(),
      keymap.of([...defaultKeymap, ...historyKeymap, indentWithTab]),
      EditorView.lineWrapping,
      langComp.of(await langExt(props.lang)),
      themeComp.of(buildTheme(props.dark)),
      EditorView.updateListener.of((u) => {
        if (u.docChanged) emit('change', u.state.doc.toString())
      }),
    ],
  })
  view.value = new EditorView({ state, parent: host.value })
}

onMounted(mount)
onBeforeUnmount(() => {
  view.value?.destroy()
  view.value = null
})

// 外部内容变化（例如反推后再渲染）：只在确实不同的时候写回，避免打断输入
watch(
  () => props.code,
  (v) => {
    const cur = view.value
    if (!cur) return
    if (v !== cur.state.doc.toString()) {
      cur.dispatch({ changes: { from: 0, to: cur.state.doc.length, insert: v } })
    }
  },
)

watch(
  () => props.lang,
  async (v) => view.value?.dispatch({ effects: langComp.reconfigure(await langExt(v)) }),
)

watch(
  () => props.dark,
  (v) => view.value?.dispatch({ effects: themeComp.reconfigure(buildTheme(v)) }),
)
</script>

<template>
  <div ref="host" class="code-block-editor" />
</template>

<style scoped>
/* 代码块**竖向完全展开**：不设固定高度、不出现内部滚动条（用户要求"不需要滑动窗口"）。
   长行交给 lineWrapping 折行，所以也不需要横向滚动。 */
.code-block-editor {
  height: auto;
  overflow: visible;
}

.code-block-editor :deep(.cm-editor) {
  height: auto;
}

.code-block-editor :deep(.cm-scroller) {
  overflow: visible;
}
</style>
