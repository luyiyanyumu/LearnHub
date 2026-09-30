<script setup>
import { onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { NodeViewWrapper, nodeViewProps } from '@tiptap/vue-3'
import { EditorState, Compartment } from '@codemirror/state'
import { EditorView, keymap, lineNumbers } from '@codemirror/view'
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands'
import { bracketMatching, syntaxHighlighting, defaultHighlightStyle } from '@codemirror/language'

const props = defineProps(nodeViewProps)
const host = ref(null)
const view = shallowRef(null)
const langComp = new Compartment()

async function langExt(lang) {
  if (lang === 'python') return (await import('@codemirror/lang-python')).python()
  if (lang === 'java') return (await import('@codemirror/lang-java')).java()
  if (lang === 'javascript') return (await import('@codemirror/lang-javascript')).javascript()
  if (lang === 'json') return (await import('@codemirror/lang-json')).json()
  return []
}

onMounted(async () => {
  const state = EditorState.create({
    doc: props.node.attrs.code || '',
    extensions: [
      lineNumbers(), history(), syntaxHighlighting(defaultHighlightStyle, { fallback: true }), bracketMatching(),
      keymap.of([...defaultKeymap, ...historyKeymap]), EditorView.lineWrapping,
      langComp.of(await langExt(props.node.attrs.language)),
      EditorView.updateListener.of((u) => { if (u.docChanged) props.updateAttributes({ code: u.state.doc.toString() }) }),
    ],
  })
  view.value = new EditorView({ state, parent: host.value })
})

onBeforeUnmount(() => { view.value?.destroy(); view.value = null })
</script>

<template>
  <NodeViewWrapper class="code-block-cm" :class="{ 'is-selected': selected }">
    <div ref="host" class="code-block-cm-host" />
  </NodeViewWrapper>
</template>

<style scoped>
.code-block-cm { border: 1px solid var(--app-border-weak); border-radius: 10px; background: var(--app-card); margin: 1em 0; }
/* 行号对齐：**不要**自定义 line-height —— CodeMirror 的 lineNumbers() 在只给字号/字体、
   不干预行高时是自对齐的；手动加 line-height 反而让 gutter 与 .cm-line 各行高不一致
   （实测出现 4px 顶部偏移 + 一个多余 gutter 元素）。只设字号与等宽字体。 */
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
