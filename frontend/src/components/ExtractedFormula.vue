<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { fileApi } from '../api'
import {
  extractedFormulaLatex, extractedFormulaState, MAX_EXTRACTED_LATEX_CHARS,
  renderExtractedMath, validateFormulaRecognition,
} from '../utils/extractedMath'

const props = defineProps({
  block: { type: Object, required: true },
  fileId: { type: [Number, String], required: true },
  page: { type: Number, default: 1 },
  recognitionCapabilities: { type: Object, default: null },
})
const emit = defineEmits(['open-original', 'update-latex'])
const source = ref('')
const baseline = ref('')
const sourceOpen = ref(false)
const imageFailed = ref(false)
const recognizing = ref(false)
const recognition = ref(null)
const recognitionError = ref('')
let forceNextRecognition = false
let requestSequence = 0
const restoredSource = computed(() => extractedFormulaLatex(props.block))
const pageNumber = computed(() => Number(props.block.page || props.page))
const region = computed(() => {
  const rect = props.block.rect
  if (!Array.isArray(rect) || rect.length !== 4 || !rect.every(value => Number.isFinite(Number(value)))
    || Number(rect[2]) <= Number(rect[0]) || Number(rect[3]) <= Number(rect[1])
    || !Number.isInteger(pageNumber.value) || pageNumber.value < 1) return null
  const [x0, y0, x1, y1] = rect.map(Number)
  return { page: pageNumber.value, x0, y0, x1, y1 }
})
const imageUrl = computed(() => {
  if (!region.value) return ''
  return `/api/files/${encodeURIComponent(props.fileId)}/page-image?${new URLSearchParams(region.value)}`
})
const state = computed(() => extractedFormulaState(props.block, source.value, {
  baseline: baseline.value, recognition: recognition.value, hasImage: !!imageUrl.value,
}))
const rendered = computed(() => state.value.rendered)
const provider = computed(() => {
  if (!recognition.value) return ''
  const by = [recognition.value.profile, recognition.value.model].filter(Boolean).join(' / ')
  return [by, recognition.value.cached ? '已缓存' : '', recognition.value.ms != null ? `${recognition.value.ms} ms` : ''].filter(Boolean).join(' · ')
})
const capabilityReason = computed(() => props.recognitionCapabilities?.reason || '')
const status = computed(() => {
  if (recognizing.value) return '正在识别公式原图…'
  if (state.value.edited && rendered.value.ok) return '当前视图已修正'
  if (!source.value.trim()) return imageUrl.value ? '暂无可靠 LaTeX，保留原图' : '暂无可靠 LaTeX，请查看原文核对'
  if (!rendered.value.ok) return imageUrl.value ? 'LaTeX 暂不能渲染，保留源码与原图' : 'LaTeX 暂不能渲染，保留源码'
  if (state.value.visual) return state.value.status === 'partial' ? '视觉识别结果仍有不确定内容，请核对原图' : '视觉识别结果，请核对'
  if (state.value.status === 'partial') return imageUrl.value ? '几何还原不确定，优先显示原图' : '已部分还原，请与原文核对'
  if (state.value.needsReview) return 'LaTeX 预览，请与原文核对'
  return 'LaTeX 已还原'
})

watch([() => props.block, () => props.fileId, () => props.page], () => {
  requestSequence++
  source.value = restoredSource.value
  baseline.value = restoredSource.value
  recognition.value = null
  recognitionError.value = ''
  forceNextRecognition = false
  recognizing.value = false
  imageFailed.value = false
  sourceOpen.value = !!source.value && !renderExtractedMath(source.value).ok
}, { immediate: true, deep: true })
onBeforeUnmount(() => { requestSequence++ })

function edit(event) {
  source.value = event.target.value
  emit('update-latex', source.value)
}
function reset() {
  source.value = baseline.value
  emit('update-latex', source.value)
}
async function recognize() {
  if (recognizing.value || !region.value) return
  recognitionError.value = ''
  if (props.recognitionCapabilities?.enabled === false) {
    recognitionError.value = capabilityReason.value || '尚未配置支持图片识别的模型，请在设置中配置公式识别模型。'
    return
  }
  const sequence = ++requestSequence
  const fileId = props.fileId
  // Only send the original crop, never the fragmented text or geometric candidate.
  const payload = { ...region.value, force: state.value.visual || forceNextRecognition }
  recognizing.value = true
  try {
    const result = await fileApi.recognizeFormula(fileId, payload)
    if (sequence !== requestSequence) return
    const checked = validateFormulaRecognition(result)
    if (!checked.ok) {
      forceNextRecognition = true
      recognitionError.value = checked.error
      return
    }
    forceNextRecognition = false
    source.value = checked.latex
    baseline.value = checked.latex
    recognition.value = { ...result, latex: checked.latex }
    emit('update-latex', checked.latex)
    // A new candidate must parse in the actual renderer before it can replace the persisted result.
    try {
      if (!result.cacheKey) throw new Error('识别响应缺少缓存凭据，请刷新后重试。')
      await fileApi.acceptFormulaRecognition(fileId, { ...payload, cacheKey: result.cacheKey })
    } catch (error) {
      if (sequence === requestSequence) {
        const reason = error?.response?.data?.msg || error?.message || '请稍后重试。'
        recognitionError.value = `公式已识别，但保存失败；当前结果仅用于本次阅读。${reason}`
      }
    }
  } catch (error) {
    if (sequence === requestSequence) recognitionError.value = error?.response?.data?.msg || error?.message || '识别失败，请稍后重试。'
  } finally {
    if (sequence === requestSequence) recognizing.value = false
  }
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
  <figure class="extracted-formula" :class="{ 'formula-needs-review': state.needsReview }" :aria-busy="recognizing">
    <figcaption class="formula-status" data-translation-ignore>
      <span role="status" aria-live="polite">{{ status }}</span>
      <span v-if="state.edited" class="formula-local">仅本次阅读</span>
    </figcaption>
    <div v-if="state.mainPreview" class="formula-preview" v-html="rendered.html" />
    <p v-else-if="source.trim() && !rendered.ok" class="formula-message" data-translation-ignore>{{ rendered.error }}</p>
    <details v-if="imageUrl" class="formula-original" :open="state.originalOpen || !!recognitionError">
      <summary data-translation-ignore>原图对照</summary>
      <img
        v-if="!imageFailed"
        :src="imageUrl"
        alt="公式原图，用于核对 LaTeX 还原结果"
        loading="lazy"
        decoding="async"
        @error="imageFailed = true"
      />
      <p v-else class="formula-message" data-translation-ignore>原图预览不可用，可点击「查看原文」核对。</p>
    </details>
    <p v-else-if="state.needsReview" class="formula-message" data-translation-ignore>可点击「查看原文」核对公式。</p>
    <p v-if="(recognition?.message || block.latexMessage) && !state.edited" class="formula-message" data-translation-ignore>{{ recognition?.message || block.latexMessage }}</p>
    <p v-if="provider" class="formula-provider" data-translation-ignore>{{ provider }}</p>
    <div class="formula-actions" data-translation-ignore>
      <button v-if="region" type="button" class="reader-btn formula-recognize" :disabled="recognizing" @click="recognize">
        {{ recognizing ? '识别中…' : (state.visual ? '重新识别' : '识别公式') }}
      </button>
      <button type="button" class="reader-btn" :disabled="!rendered.latex" @click="copyLatex">{{ state.candidatePreview ? '复制候选 LaTeX' : '复制 LaTeX' }}</button>
      <button type="button" class="reader-btn" :aria-expanded="sourceOpen" @click="sourceOpen = !sourceOpen">
        {{ sourceOpen ? '收起源码' : '查看 / 修正源码' }}
      </button>
      <button type="button" class="reader-btn" @click="emit('open-original', pageNumber)">查看原文</button>
    </div>
    <p v-if="region && capabilityReason && !recognitionError && !state.visual" class="formula-message" data-translation-ignore>{{ capabilityReason }}</p>
    <p v-if="recognitionError" class="formula-error" role="alert" data-translation-ignore>{{ recognitionError }}</p>
    <div v-if="sourceOpen" class="formula-editor" data-translation-ignore>
      <label>
        <span>LaTeX 源码</span>
        <textarea
          :value="source"
          :maxlength="MAX_EXTRACTED_LATEX_CHARS"
          :disabled="recognizing"
          rows="4"
          spellcheck="false"
          aria-label="公式 LaTeX 源码，可修正并即时预览"
          @input="edit"
        />
      </label>
      <div class="formula-edit-help">
        <span>手动修改用于当前预览与复制正文，切换文件后恢复。</span>
        <button type="button" class="reader-btn" :disabled="!state.edited || recognizing" @click="reset">{{ state.visual ? '恢复识别结果' : '恢复还原结果' }}</button>
      </div>
    </div>
    <details v-if="state.candidatePreview" class="formula-candidate">
      <summary data-translation-ignore>{{ state.visual ? '查看视觉识别候选' : '查看几何还原候选' }}</summary>
      <p class="formula-message" data-translation-ignore>这是待核对候选，请对照上方原图检查下标、上下结构和符号。</p>
      <div class="formula-preview" v-html="rendered.html" />
    </details>
    <details v-if="block.text" class="formula-extraction" :open="!rendered.ok && !imageUrl">
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
.formula-recognize { color: var(--app-brand-deep); border-color: var(--app-brand); }
.formula-message, .formula-provider, .formula-error { margin: 8px 0; color: var(--app-text-3); font-size: 12px; line-height: 1.7; white-space: pre-wrap; }
.formula-provider { font-size: 11px; }
.formula-error { color: var(--app-danger, #b42318); }
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
.formula-original, .formula-extraction, .formula-candidate { margin-top: 10px; }
summary { cursor: pointer; font-size: 12px; color: var(--app-text-3); }
.formula-original img { display: block; max-width: 100%; margin: 8px auto 0; background: #fff; border: 1px solid var(--app-border-weak); border-radius: 6px; }
.formula-extraction pre { white-space: pre-wrap; word-break: break-word; margin: 6px 0 0; color: var(--app-text-3); font: 12px/1.8 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
</style>
