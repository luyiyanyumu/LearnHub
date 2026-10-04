<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import { fileApi } from '../api'
import { createOriginalPreviewLoader } from '../utils/originalPreviewHtml'

const props = defineProps({
  fileId: { type: [Number, String], required: true },
  filename: { type: String, default: '' },
})
const state = ref({ loading: true, error: '', html: '', format: '', warnings: [], hasContent: false })
const scrollRoot = ref(null)
const article = ref(null)
const loader = createOriginalPreviewLoader({
  load: id => fileApi.originalPreview(id),
  onState(value) {
    state.value = value
    if (value.loading && scrollRoot.value) scrollRoot.value.scrollTop = 0
  },
})
const reload = () => loader.open(props.fileId)

function onDocumentClick(event) {
  const link = event.target.closest('a[href]')
  if (!link || !article.value?.contains(link)) return
  const href = link.getAttribute('href')
  if (!href?.startsWith('#')) return
  event.preventDefault()
  // Avoid document-wide ID collisions and CSS-selector injection from a document anchor.
  let id
  try { id = decodeURIComponent(href.slice(1)) } catch { return }
  const target = Array.from(article.value.querySelectorAll('[id]')).find(node => node.id === id)
  target?.scrollIntoView({ block: 'start', behavior: 'smooth' })
}

watch(() => props.fileId, reload, { immediate: true })
onBeforeUnmount(() => loader.dispose())
defineExpose({ reload })
</script>

<template>
  <div ref="scrollRoot" class="original-document-view" :aria-busy="state.loading">
    <div v-if="state.loading" class="original-state" role="status">正在打开原文…</div>
    <div v-else-if="state.error" class="original-state original-error" role="alert">
      <p>{{ state.error }}</p>
      <button type="button" class="reader-btn" @click="reload">重新加载</button>
    </div>
    <template v-else>
      <div v-if="state.warnings.length" class="original-warnings" role="status">
        <p>{{ state.warnings[0] }}</p>
        <details v-if="state.warnings.length > 1">
          <summary>阅读说明（{{ state.warnings.length - 1 }}）</summary>
          <p v-for="warning in state.warnings.slice(1)" :key="warning">{{ warning }}</p>
        </details>
      </div>
      <article v-if="state.hasContent" ref="article" class="original-paper" :class="'original-' + state.format"
               :aria-label="filename || '文档原文'" @click="onDocumentClick" v-html="state.html" />
      <div v-else class="original-state">原文件没有可显示的正文。可以下载原文件检查内容。</div>
    </template>
  </div>
</template>

<style scoped>
.original-document-view { flex: 1 1 auto; height: 100%; min-height: 0; overflow: auto; padding: 24px; background: var(--app-bg); border-radius: 8px; }
.original-state { margin: 0 auto; max-width: 860px; padding: 44px 20px; text-align: center; color: var(--app-text-2); font-size: 14px; line-height: 1.8; }
.original-error { color: var(--el-color-danger); }
.original-warnings { max-width: 860px; margin: 0 auto 14px; padding: 10px 14px; border: 1px solid var(--app-border-weak); border-radius: 7px; color: var(--app-text-2); background: var(--app-card); font-size: 12px; line-height: 1.6; }
.original-warnings p { margin: 2px 0; }
.original-warnings details { margin-top: 5px; }
.original-warnings summary { width: fit-content; cursor: pointer; color: var(--app-brand-deep); }
.original-warnings details p { margin-top: 7px; }
.original-paper { max-width: 860px; margin: 0 auto; padding: 48px 56px 64px; border: 1px solid var(--app-border-weak); border-radius: 3px; box-shadow: 0 3px 16px #0000000c; background: var(--app-card); color: var(--app-text-1); font-size: 15px; line-height: 1.85; overflow-wrap: anywhere; user-select: text; }
.original-paper :deep(> :first-child) { margin-top: 0; }
.original-paper :deep(h1), .original-paper :deep(h2), .original-paper :deep(h3), .original-paper :deep(h4), .original-paper :deep(h5), .original-paper :deep(h6) { line-height: 1.4; margin: 1.5em 0 .65em; font-weight: 650; scroll-margin-top: 20px; }
.original-paper :deep(h1) { font-size: 2em; }
.original-paper :deep(h2) { font-size: 1.5em; }
.original-paper :deep(h3) { font-size: 1.25em; }
.original-paper :deep(h4) { font-size: 1.1em; }
.original-paper :deep(p) { margin: .85em 0; }
.original-paper :deep(a) { color: var(--app-brand-deep); text-decoration: underline; text-underline-offset: 3px; }
.original-paper :deep(img) { max-width: 100%; height: auto; }
.original-paper :deep(table) { border-collapse: collapse; max-width: 100%; margin: 1.1em 0; }
.original-paper :deep(th), .original-paper :deep(td) { border: 1px solid var(--app-border); padding: 7px 12px; vertical-align: top; }
.original-paper :deep(th) { background: color-mix(in srgb, var(--app-text-1) 5%, transparent); font-weight: 600; }
.original-paper :deep(ul), .original-paper :deep(ol) { padding-left: 1.8em; }
.original-paper :deep(li) { margin: .3em 0; }
.original-paper :deep(blockquote) { margin: 1.2em 0; padding: .2em 1em; border-left: 3px solid var(--app-border); color: var(--app-text-2); background: var(--app-bg); }
.original-paper :deep(hr) { border: 0; border-top: 1px solid var(--app-border); margin: 2em 0; }
.original-paper :deep(code) { font: .88em/1.6 ui-monospace, Consolas, monospace; padding: .1em .3em; background: var(--app-bg); border-radius: 3px; }
.original-paper :deep(pre) { padding: 14px 16px; overflow-x: auto; white-space: pre; border: 1px solid var(--app-border-weak); border-radius: 7px; background: var(--app-bg); }
.original-paper :deep(pre code) { padding: 0; background: transparent; }
.original-paper :deep(.hljs-keyword), .original-paper :deep(.hljs-selector-tag) { color: #b65da3; }
.original-paper :deep(.hljs-string), .original-paper :deep(.hljs-attr) { color: var(--app-brand-deep); }
.original-paper :deep(.hljs-comment) { color: var(--app-text-3); }
.original-paper :deep(.hljs-number), .original-paper :deep(.hljs-literal) { color: #b87837; }
.original-paper :deep(.original-math-block) { display: block; text-align: center; overflow-x: auto; margin: 1em 0; }
.original-paper :deep(math) { font-size: 1.1em; }
.original-paper :deep(.original-math-error) { color: var(--el-color-warning); white-space: pre-wrap; }
.original-paper :deep(.original-image-blocked) { display: inline-block; padding: 6px 10px; border: 1px dashed var(--app-border); border-radius: 4px; color: var(--app-text-3); font-size: 12px; }
.original-paper :deep(.md-callout) { margin: 1em 0; padding: 10px 14px; border-radius: 8px; background: var(--app-brand-soft); }
.original-paper :deep(.word-footnote), .original-paper :deep(.word-comment), .original-paper :deep(.word-endnote), .original-paper :deep(.word-notes), .original-paper :deep(.word-comments) { padding: 8px 12px; margin: 12px 0; border-left: 2px solid var(--app-border); color: var(--app-text-2); font-size: 12px; }
.original-paper :deep(.word-formula), .original-paper :deep(.word-equation) { font-family: Cambria, 'Times New Roman', serif; white-space: pre-wrap; }
.original-markdown :deep(table) { display: block; overflow-x: auto; }
:global(html.dark .original-paper [style*="color: #000000;"]), :global(html.dark .original-paper [style$="color: #000000"]), :global(html.dark .original-paper [style*="color: #000;"]), :global(html.dark .original-paper [style$="color: #000"]), :global(html.dark .original-paper [style*="color: black"]), :global(html.dark .original-paper [style*="color: rgb(0, 0, 0)"]) { color: var(--app-text-1) !important; }
@media (max-width: 800px) { .original-document-view { padding: 12px; } .original-paper { padding: 28px 24px 40px; } }
@media (max-width: 480px) { .original-document-view { padding: 6px; } .original-paper { padding: 22px 16px 32px; font-size: 14px; } }
</style>
