<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { extractedFormulaLatex, MAX_EXTRACTED_LATEX_CHARS, renderExtractedMath } from '../utils/extractedMath'

const props = defineProps({
  block: { type: Object, required: true },
  fileId: { type: [Number, String], required: true },
  page: { type: Number, default: 1 },
})
const emit = defineEmits(['open-original', 'update-latex'])
const source = ref('')
const sourceOpen = ref(false)
const imageFailed = ref(false)
const restoredSource = computed(() => extractedFormulaLatex(props.block))
const rendered = computed(() => renderExtractedMath(source.value))
const edited = computed(() => rendered.value.latex !== restoredSource.value)
const partial = computed(() => props.block.latexStatus === 'partial')
const needsReview = computed(() => partial.value || (!edited.value && props.block.latexStatus !== 'restored'))
const pageNumber = computed(() => Number(props.block.page || props.page))
const imageUrl = computed(() => {
  const rect = props.block.rect
  if (!Array.isArray(rect) || rect.length !== 4 || !rect.every(value => Number.isFinite(Number(value)))
    || Number(rect[2]) <= Number(rect[0]) || Number(rect[3]) <= Number(rect[1])
    || !Number.isInteger(pageNumber.value) || pageNumber.value < 1) return ''
  const [x0, y0, x1, y1] = rect
  const query = new URLSearchParams({ page: String(pageNumber.value), x0, y0, x1, y1 })
  return `/api/files/${encodeURIComponent(props.fileId)}/page-image?${query}`
})
const hasImagePreview = computed(() => !!imageUrl.value && !imageFailed.value)
const status = computed(() => {
  if (edited.value && rendered.value.ok) return '当前视图已修正'
  if (!source.value.trim()) return hasImagePreview.value ? '暂无可靠 LaTeX，保留原图' : '暂无可靠 LaTeX，请查看原文核对'
  if (!rendered.value.ok) return hasImagePreview.value ? 'LaTeX 暂不能渲染，保留源码与原图' : 'LaTeX 暂不能渲染，保留源码'
  if (partial.value) return hasImagePreview.value ? '已部分还原，请与原图核对' : '已部分还原，请与原文核对'
  if (needsReview.value) return 'LaTeX 预览，请与原文核对'
  return 'LaTeX 已还原'
})

watch([() => props.block, () => props.fileId], () => {
  source.value = restoredSource.value
  imageFailed.value = false
  sourceOpen.value = !!source.value && !renderExtractedMath(source.value).ok
}, { immediate: true })

function edit(event) {
  source.value = event.target.value
  emit('update-latex', source.value)
}
function reset() {
  source.value = restoredSource.value
  emit('update-latex', source.value)
}
async function copyLatex() {
  try {
    await navigator.clipboard.writeText(rendered.value.latex)
    ElMessage.success('已复制 LaTeX')
  } catch {
    ElMessage.error('复制失败，请在源码中手动选择')
    sourceOpen.value = true
  }
}
</script>

<template>
  <figure class="extracted-formula" :class="{ 'formula-needs-review': needsReview || !rendered.ok }">
    <figcaption class="formula-status" data-translation-ignore>
      <span>{{ status }}</span>
      <span v-if="edited" class="formula-local">仅本次阅读</span>
    </figcaption>
    <div v-if="rendered.ok" class="formula-preview" v-html="rendered.html" />
    <p v-else-if="source.trim()" class="formula-message" data-translation-ignore>{{ rendered.error }}</p>
    <p v-if="block.latexMessage && !edited" class="formula-message" data-translation-ignore>{{ block.latexMessage }}</p>
    <div class="formula-actions" data-translation-ignore>
      <button type="button" class="reader-btn" :disabled="!rendered.latex" @click="copyLatex">复制 LaTeX</button>
      <button type="button" class="reader-btn" :aria-expanded="sourceOpen" @click="sourceOpen = !sourceOpen">
        {{ sourceOpen ? '收起源码' : '查看 / 修正源码' }}
      </button>
      <button type="button" class="reader-btn" @click="emit('open-original', pageNumber)">查看原文</button>
    </div>
    <div v-if="sourceOpen" class="formula-editor" data-translation-ignore>
      <label>
        <span>LaTeX 源码</span>
        <textarea
          :value="source"
          :maxlength="MAX_EXTRACTED_LATEX_CHARS"
          rows="4"
          spellcheck="false"
          aria-label="公式 LaTeX 源码，可修正并即时预览"
          @input="edit"
        />
      </label>
      <div class="formula-edit-help">
        <span>修改用于当前预览与复制正文，切换文件后恢复。</span>
        <button type="button" class="reader-btn" :disabled="!edited" @click="reset">恢复还原结果</button>
      </div>
    </div>
    <details v-if="imageUrl" class="formula-original" :open="needsReview || !rendered.ok">
      <summary data-translation-ignore>原图对照</summary>
      <img
        v-if="imageUrl && !imageFailed"
        :src="imageUrl"
        alt="公式原图，用于核对 LaTeX 还原结果"
        loading="lazy"
        decoding="async"
        @error="imageFailed = true"
      />
      <p v-else class="formula-message" data-translation-ignore>原图预览不可用，可点击「查看原文」核对。</p>
    </details>
    <p v-else-if="needsReview || !rendered.ok" class="formula-message" data-translation-ignore>
      可点击「查看原文」核对公式。
    </p>
    <details v-if="block.text" class="formula-extraction" :open="!rendered.ok && !hasImagePreview">
      <summary data-translation-ignore>抽取文本</summary>
      <pre>{{ block.text }}</pre>
    </details>
  </figure>
</template>

<style scoped>
.extracted-formula {
  margin: 16px 0;
  padding: 12px 14px;
  border: 1px solid var(--app-border-weak);
  border-radius: 8px;
  background: var(--app-surface, #fff);
  text-indent: 0;
}
.formula-status, .formula-actions, .formula-edit-help {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.formula-status { justify-content: space-between; color: var(--app-text-3); font-size: 12px; }
.formula-local { color: var(--app-brand-deep); }
.formula-preview { overflow-x: auto; padding: 12px 0; text-align: center; font-size: 18px; color: var(--app-text-1); }
.formula-preview :deep(math) { max-width: none; }
.formula-actions { margin-top: 8px; }
.formula-message { margin: 8px 0; color: var(--app-text-3); font-size: 12px; line-height: 1.7; white-space: pre-wrap; }
.formula-needs-review .formula-status { color: #9a6700; }
.formula-editor { margin-top: 10px; }
.formula-editor label > span { display: block; font-size: 12px; margin-bottom: 5px; color: var(--app-text-2); }
.formula-editor textarea {
  display: block;
  width: 100%;
  box-sizing: border-box;
  resize: vertical;
  padding: 8px 10px;
  border: 1px solid var(--app-border);
  border-radius: 6px;
  color: var(--app-text-1);
  background: var(--app-bg);
  font: 12px/1.7 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.formula-editor textarea:focus { outline: 2px solid var(--app-brand-soft); border-color: var(--app-brand); }
.formula-edit-help { justify-content: space-between; margin-top: 6px; font-size: 11.5px; color: var(--app-text-3); }
.formula-original, .formula-extraction { margin-top: 10px; }
summary { cursor: pointer; font-size: 12px; color: var(--app-text-3); }
.formula-original img { display: block; max-width: 100%; margin: 8px auto 0; background: #fff; border: 1px solid var(--app-border-weak); border-radius: 6px; }
.formula-extraction pre { white-space: pre-wrap; word-break: break-word; margin: 6px 0 0; color: var(--app-text-3); font: 12px/1.8 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
</style>
