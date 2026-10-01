<script setup>
import { onBeforeUnmount, onMounted, shallowRef, watch } from 'vue'
import { Editor, EditorContent } from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
import { CodeBlockCm } from '../utils/codeBlockCm'
import { Callout } from '../utils/calloutNode'
import { Details, Summary } from '../utils/detailsNode'
import { Superscript, Subscript, Highlight, FontStyle } from '../utils/inlineMarks'
import { Underline } from '@tiptap/extension-underline'
import markdownItSup from 'markdown-it-sup'
import markdownItSub from 'markdown-it-sub'
import markdownItMark from 'markdown-it-mark'
import { TableKit } from '@tiptap/extension-table'
import MarkdownIt from 'markdown-it'
import mdCallout from '../utils/mdCallout'
import mdAnchor from '../utils/mdAnchor'
import { previewHtmlToMd } from '../utils/htmlToMd'

const props = defineProps({ content: { type: String, default: '' } })
const emit = defineEmits(['update'])
const editor = shallowRef(null)
const md = new MarkdownIt({ html: true, linkify: true, breaks: true }).use(mdCallout).use(mdAnchor).use(markdownItSup).use(markdownItSub).use(markdownItMark)

/** 上次 emit 出去的 Markdown：用于识别"自己的回显"，避免 setContent 把光标重置 */
let lastEmitted = ''

function syncDown() {
  if (!editor.value) return
  const markdown = previewHtmlToMd(editor.value.getHTML())
  if (markdown === lastEmitted) return
  lastEmitted = markdown
  emit('update', markdown)
}

onMounted(() => {
  editor.value = new Editor({
    editable: true,
    extensions: [StarterKit.configure({ codeBlock: false }), CodeBlockCm, Callout, Details, Summary, Underline, Superscript, Subscript, Highlight, FontStyle, TableKit],
    content: md.render(props.content || ''),
    onUpdate: syncDown,
  })
  lastEmitted = props.content || ''
})
onBeforeUnmount(() => {
  editor.value?.destroy()
  editor.value = null
})
watch(
  () => props.content,
  (v) => {
    if (!editor.value) return
    if (v === lastEmitted) return // 自己发出的回显，忽略
    lastEmitted = v
    editor.value.commands.setContent(md.render(v || ''), false)
  },
)
</script>

<template>
  <EditorContent :editor="editor" class="block-preview" />
</template>

<style scoped>
.block-preview :deep(.tiptap) { outline: none; font-size: 15px; line-height: 1.8; color: var(--app-text-1); }
.block-preview :deep(.tiptap h1), .block-preview :deep(.tiptap h2), .block-preview :deep(.tiptap h3) { font-weight: 650; margin: 1.2em 0 0.6em; }
.block-preview :deep(.tiptap blockquote) { margin: 1em 0; padding-left: 14px; border-left: 3px solid var(--app-border); color: var(--app-text-2); }
</style>
