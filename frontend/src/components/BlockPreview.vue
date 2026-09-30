<script setup>
/**
 * Stage 0/1：块编辑器（Tiptap）。
 *
 * 关键（踩坑记录）：必须用 `@tiptap/vue-3` 的 `Editor` 并用 `<EditorContent>` 渲染，
 * 不能用 `@tiptap/core` 的 Editor + 裸 div。原因：`VueNodeViewRenderer` 里有一行
 * `if (!props.editor.contentComponent) return {}` —— contentComponent 只有 EditorContent
 * 挂载后才会被注册；用 core 的 Editor 时它恒为 null，node view（代码块内嵌 CodeMirror）
 * 永远挂不上、静默回退成 renderHTML。
 */
import { onBeforeUnmount, onMounted, shallowRef, watch } from 'vue'
import { Editor, EditorContent } from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
import { CodeBlockCm } from '../utils/codeBlockCm'
import MarkdownIt from 'markdown-it'
import mdCallout from '../utils/mdCallout'
import mdAnchor from '../utils/mdAnchor'

const props = defineProps({ content: { type: String, default: '' } })
const editor = shallowRef(null)
const md = new MarkdownIt({ html: true, linkify: true, breaks: true }).use(mdCallout).use(mdAnchor)

onMounted(() => {
  editor.value = new Editor({
    editable: true,
    extensions: [StarterKit.configure({ codeBlock: false }), CodeBlockCm],
    content: md.render(props.content || ''),
  })
})
onBeforeUnmount(() => {
  editor.value?.destroy()
  editor.value = null
})
watch(
  () => props.content,
  (v) => {
    if (editor.value) editor.value.commands.setContent(md.render(v || ''), false)
  },
)
</script>

<template>
  <EditorContent :editor="editor" class="block-preview" />
</template>

<style scoped>
.block-preview :deep(.tiptap) {
  outline: none;
  font-size: 15px;
  line-height: 1.8;
  color: var(--app-text-1);
}
.block-preview :deep(.tiptap h1),
.block-preview :deep(.tiptap h2),
.block-preview :deep(.tiptap h3) {
  font-weight: 650;
  margin: 1.2em 0 0.6em;
}
.block-preview :deep(.tiptap blockquote) {
  margin: 1em 0;
  padding-left: 14px;
  border-left: 3px solid var(--app-border);
  color: var(--app-text-2);
}
</style>
