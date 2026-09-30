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
.code-block-cm-host :deep(.cm-editor) { font-size: 13.5px; }
.code-block-cm-host :deep(.cm-scroller) { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; line-height: 1.7; }
.code-block-cm-host :deep(.cm-gutters) { background: transparent; border: none; color: var(--app-text-3); }
</style>
