<script setup>
/**
 * Stage 0：块编辑器（Tiptap）**只读**渲染器。
 *
 * 目的不是替代现有预览，而是**验证"模型驱动"这条路能对上多少**：
 * 把 Markdown 转成 Tiptap 能吃的 HTML，用最小 schema（段落/标题/列表/引用/代码块/分割线）
 * 渲染出来，再与 md-editor 的结果逐块比对 —— 差集就是 Stage 1/2 要补的能力清单。
 *
 * 只读（editable:false）：Stage 0 不碰编辑，绝不影响现有编辑器与你的笔记。
 * 由 NoteEdit 里的开关控制（默认关闭，只有显式开启才会用到本组件）。
 */
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { EditorContent } from '@tiptap/vue-3'
import MarkdownIt from 'markdown-it'
import mdCallout from '../utils/mdCallout'
import mdAnchor from '../utils/mdAnchor'

const props = defineProps({
  /** Markdown 正文 */
  content: { type: String, default: '' },
})

const host = ref(null)
const editor = shallowRef(null)

/** 与 md-editor 尽量一致的 markdown-it 配置（html 打开、表格、换行、提示块与锚点插件） */
const md = new MarkdownIt({ html: true, linkify: true, breaks: true }).use(mdCallout).use(mdAnchor)

function build(html) {
  editor.value?.destroy()
  editor.value = new Editor({
    element: host.value,
    editable: false,
    extensions: [StarterKit],
    content: html,
  })
}

onMounted(() => build(md.render(props.content || '')))
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
  <div ref="host" class="block-preview" />
</template>

<style scoped>
/* 只为 Stage 0 的比对服务：先给一个朴素但可读的排版，样式对齐是 Stage 2 的事 */
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
.block-preview :deep(.tiptap pre) {
  background: var(--app-card);
  border: 1px solid var(--app-border-weak);
  border-radius: 10px;
  padding: 12px 14px;
  overflow-x: auto;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 13.5px;
}
.block-preview :deep(.tiptap blockquote) {
  margin: 1em 0;
  padding-left: 14px;
  border-left: 3px solid var(--app-border);
  color: var(--app-text-2);
}
</style>
