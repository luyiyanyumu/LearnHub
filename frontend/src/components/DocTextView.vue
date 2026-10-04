<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { fileApi } from '../api'
import ExtractedFormula from './ExtractedFormula.vue'
import { exportExtractedText, renderExtractedText } from '../utils/extractedMath'

/**
 * 抽取正文的**文档视图**（不是"一段一段往下堆"）。
 *
 * <h3>为什么要有它</h3>
 * 用 PDFTextStripper 的默认输出读论文是这样的：
 *
 * <pre>
 *   Abstract et al. 2023; Liu et al. 2024; Wang et al. 2026). During exe-
 *   Many real-world tasks require LLM agents to interact with with
 * </pre>
 *
 * 左栏右栏逐行交错，一句完整的话被拆到两条不相邻的行里 —— 能检索，但读不了。
 * 后端 {@code /files/{id}/text-layout} 已经按坐标把版面重建过（定中缝 → 分栏 → 成段 → 分型），
 * 这里只负责"照原文档的样子排版"：标题居中、章节加粗、正文首行缩进、按页分隔，
 * 让人一眼看出这份正文和「原文」是同一份东西。
 *
 * <h3>保留逐段翻译</h3>
 * 整篇翻译会撞输出上限（实测两万字就被截断），所以仍然是**读到哪里翻到哪里**：
 * 每段 hover 出「译」，结果贴在段落下方的品牌色块里。
 */
const props = defineProps({
  /** 资料 id：翻译接口用它校验资料存在 */
  fileId: { type: [Number, String], required: true },
  /** 按页返回的结构化正文：[{ page, columns, blocks: [{ type, text, level }] }] */
  pages: { type: Array, default: () => [] },
  /** 还原出来的字数（顶部提示用） */
  chars: { type: Number, default: 0 },
  /** 原文总页数（含没还原出内容的页） */
  pageCount: { type: Number, default: 0 },
  /** 翻译目标语言（来自阅读器工具条） */
  targetLang: { type: String, default: '简体中文' },
  /** 单段翻译上限（与后端一致，超了按钮直接禁用，免得点了没反应） */
  maxChars: { type: Number, default: 4000 },
  /** 宿主提供右侧译文栏时，段落按钮将原文交给宿主。 */
  externalTranslation: { type: Boolean, default: false },
})
const emit = defineEmits(['open-original', 'translate'])
const formulaCorrections = ref({})
const formulaCapabilities = ref(null)
let requestedFormulaCapabilities = false
let mounted = true

// One capability lookup per reader, never one request per formula or per page.
watch(() => props.pages.some(pg => (pg.blocks || []).some(block => block.type === 'formula' && block.rect)), async hasFormulaImage => {
  if (!hasFormulaImage || requestedFormulaCapabilities) return
  requestedFormulaCapabilities = true
  try {
    const result = await fileApi.formulaRecognitionCaps()
    if (mounted) formulaCapabilities.value = result
  } catch {
    // Recognition remains usable: clicking returns the endpoint's precise reason.
    if (mounted) formulaCapabilities.value = { reason: '暂时无法读取识别配置，点击识别后可查看具体原因。' }
  }
}, { immediate: true })
onBeforeUnmount(() => { mounted = false })

const translateError = ref('')
/** 译文缓存：key = 页码:块序号（跨页唯一，翻回来不用重翻） */
const translations = ref({})
const translatingKey = ref('')
const busy = ref(false)

const blockCount = computed(() =>
  props.pages.reduce((n, p) => n + (p.blocks?.length || 0), 0))

/** 插图数（工具条上提示"图是按原位贴出来的"，顺带告诉读者这页有几张图） */
const figureCount = computed(() =>
  props.pages.reduce((n, p) => n + (p.blocks || []).filter((b) => b.type === 'figure').length, 0))

const renderedBlocks = computed(() => {
  const result = new Map()
  for (const page of props.pages) {
    for (const [index, block] of (page.blocks || []).entries()) {
      if (['formula', 'code', 'figure'].includes(block.type)) continue
      const inline = ['title', 'authors', 'meta', 'heading', 'note', 'table'].includes(block.type)
      result.set(keyOf(page.page, index), renderExtractedText(block.text, { inline }))
    }
  }
  return result
})

function renderedText(page, index) {
  return renderedBlocks.value.get(keyOf(page, index)) || { html: '', errors: [] }
}

function correctFormula(page, index, latex) {
  formulaCorrections.value = { ...formulaCorrections.value, [keyOf(page, index)]: latex }
}

/** 可翻译的块：标题/作者这类短标签没必要翻 */
function translatable(block) {
  return block.type === 'para' || block.type === 'bullet'
}

function keyOf(page, index) {
  return `${page}:${index}`
}

function contentBlocks(page) {
  return (page.blocks || []).map((block, index) => ({ block, index }))
    .filter(({ block }) => block.type !== 'note')
}

function pageNotes(page) {
  return (page.blocks || []).map((block, index) => ({ block, index }))
    .filter(({ block }) => block.type === 'note')
}

function tooLong(block) {
  return (block.text || '').length > props.maxChars
}

/**
 * 插图地址：抽取层只给得出"第几页的第几张图"（它按文件路径工作，拿不到资料 id），
 * 这里补上 fileId 拼成真实地址。图在服务端按需裁剪并缓存，重复滚动不会重复渲染。
 */
function figureUrl(block) {
  const [page, idx] = String(block.src || '').split('-')
  return `/api/files/${props.fileId}/page-image?page=${page}&idx=${idx}`
}

/** 前 n 个可翻译段（"翻译前 5 段"用） */
function firstParagraphs(n) {
  const out = []
  for (const p of props.pages) {
    const blocks = p.blocks || []
    for (let i = 0; i < blocks.length; i++) {
      if (translatable(blocks[i])) {
        out.push({ page: p.page, index: i, block: blocks[i] })
        if (out.length >= n) return out
      }
    }
  }
  return out
}

async function translateOne(page, index, block, force) {
  if (props.externalTranslation) {
    emit('translate', block.text)
    return
  }
  const key = keyOf(page, index)
  if (translatingKey.value || tooLong(block)) return
  if (translations.value[key] && !force) return
  translatingKey.value = key
  translateError.value = ''
  try {
    const r = await fileApi.translate(props.fileId, block.text, props.targetLang)
    translations.value = {
      ...translations.value,
      [key]: { text: r.translation, by: r.profile || r.model, ms: r.ms },
    }
  } catch (e) {
    translateError.value = e?.message || String(e)
  } finally {
    translatingKey.value = ''
  }
}

async function translateFirst(n) {
  if (busy.value) return
  busy.value = true
  try {
    for (const it of firstParagraphs(n)) {
      // 已经翻过的跳过，避免重复花模型调用
      if (!translations.value[keyOf(it.page, it.index)]) {
        await translateOne(it.page, it.index, it.block, false)
      }
    }
  } finally {
    busy.value = false
  }
}

async function copyAll() {
  const text = exportExtractedText(props.pages, formulaCorrections.value)
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制正文')
  } catch (e) {
    ElMessage.error('复制失败，请手动选择')
  }
}

/** 换语言就清缓存：同一种语言复用，换了语言必须重翻 */
watch(() => props.targetLang, () => {
  translations.value = {}
})
watch([() => props.fileId, () => props.pages], () => {
  formulaCorrections.value = {}
  translations.value = {}
})
</script>

<template>
  <div class="doc-view">
    <div class="reader-toolbar doc-tools">
      <span class="reader-hint">
        按原文档排版还原 · {{ pages.length }} / {{ pageCount || pages.length }} 页 · {{ chars || blockCount }} 字<template
          v-if="figureCount"
        > · 含 {{ figureCount }} 张插图</template>
      </span>
      <span class="reader-sep" />
      <span class="reader-hint">{{ externalTranslation ? '选中文字或点击「译」，右侧对照阅读' : '段落 hover 出「译」，逐段翻译' }}</span>
      <span class="reader-spacer" />
      <button
        v-if="!externalTranslation"
        type="button"
        class="reader-btn"
        :disabled="busy || !firstParagraphs(5).length"
        @click="translateFirst(5)"
      >
        翻译前 5 段
      </button>
      <button type="button" class="reader-btn" @click="copyAll">复制正文</button>
    </div>
    <p v-if="translateError" class="doc-error">翻译失败：{{ translateError }}</p>

    <div class="doc-scroll">
      <article v-for="pg in pages" :key="pg.page" class="doc-page">
        <div class="doc-page-sep">
          <span>第 {{ pg.page }} 页<template v-if="pg.columns > 1"> · {{ pg.columns }} 栏</template></span>
        </div>
        <template v-for="{ block: b, index: i } in contentBlocks(pg)" :key="i">
          <h2 v-if="b.type === 'title'" class="doc-title" v-html="renderedText(pg.page, i).html" />
          <p v-else-if="b.type === 'authors'" class="doc-authors" v-html="renderedText(pg.page, i).html" />
          <p v-else-if="b.type === 'meta'" class="doc-meta" v-html="renderedText(pg.page, i).html" />
          <h3 v-else-if="b.type === 'heading'" class="doc-heading" :class="'doc-lv' + (b.level || 1)" v-html="renderedText(pg.page, i).html" />
          <!-- 代码 / 表格：按"一行一行"原样渲染，不参与翻译（翻代码没有意义，还会把缩进搅乱） -->
          <pre v-else-if="b.type === 'code'" class="doc-code">{{ b.text }}</pre>
          <pre v-else-if="b.type === 'table'" class="doc-table" v-html="renderedText(pg.page, i).html" />
          <!-- 不确定的 PDF 几何结果优先保留原图，由用户按需发起视觉识别。 -->
          <ExtractedFormula
            v-else-if="b.type === 'formula'"
            :block="b"
            :file-id="fileId"
            :page="pg.page"
            :recognition-capabilities="formulaCapabilities"
            @open-original="emit('open-original', $event)"
            @update-latex="correctFormula(pg.page, i, $event)"
          />
          <!-- 插图：直接把原 PDF 的那块图裁出来贴在这里。**不识别**图里的文字 ——
               图表里的刻度/流程框抽成文字只会变成一堆散落的碎片，看图反而准 -->
          <figure v-else-if="b.type === 'figure' && b.src" class="doc-figure">
            <img :src="figureUrl(b)" alt="" loading="lazy" decoding="async" />
          </figure>
          <div v-else class="doc-block">
            <button
              type="button"
              class="reader-btn doc-translate"
              :disabled="!!translatingKey || tooLong(b) || busy"
              :title="tooLong(b) ? '超过单段上限，请选中更小的一段' : '翻译这一段'"
              @click="translateOne(pg.page, i, b, !!translations[keyOf(pg.page, i)])"
            >
              {{ translatingKey === keyOf(pg.page, i) ? '翻译中' : (translations[keyOf(pg.page, i)] ? '重译' : '译') }}
            </button>
            <div :class="['doc-text', b.type === 'bullet' ? 'doc-bullet' : 'doc-para']" v-html="renderedText(pg.page, i).html" />
            <p v-if="renderedText(pg.page, i).errors.length" class="doc-math-warning" data-translation-ignore>
              部分公式暂不能预览，已保留源码，可查看原文核对。
            </p>
            <div v-if="!externalTranslation && translations[keyOf(pg.page, i)]" class="doc-trans">
              <span class="doc-trans-tag">
                译 · {{ translations[keyOf(pg.page, i)].by || '模型' }}
              </span>
              <p class="doc-trans-text">{{ translations[keyOf(pg.page, i)].text }}</p>
            </div>
          </div>
          <p
            v-if="b.type !== 'formula' && b.latexStatus && b.latexStatus !== 'restored'"
            class="doc-math-warning"
            data-translation-ignore
          >部分内联公式未完整还原，请查看原文核对。{{ b.latexMessage || '' }}</p>
        </template>
        <aside v-if="pageNotes(pg).length" class="doc-notes" aria-label="本页脚注">
          <div class="doc-notes-head">
            <span>本页脚注</span>
            <button type="button" class="reader-btn" @click="emit('open-original', pg.page)">查看原文</button>
          </div>
          <div v-for="{ block: note, index } in pageNotes(pg)" :key="index">
            <p class="doc-note" v-html="renderedText(pg.page, index).html" />
            <p v-if="note.latexStatus && note.latexStatus !== 'restored'" class="doc-math-warning" data-translation-ignore>
              部分脚注公式未完整还原，请查看原文核对。{{ note.latexMessage || '' }}
            </p>
          </div>
        </aside>
      </article>
      <p v-if="!pages.length" class="reader-hint doc-empty">（这份资料没有还原出正文）</p>
    </div>
  </div>
</template>

<style scoped>
/* 整块：工具条 + 可滚动的"文档区"（高度由父容器给，阅读器全屏时就是剩余高度） */
.doc-view {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
  gap: 8px;
}

.doc-tools {
  flex: 0 0 auto;
}

.doc-error {
  margin: 0;
  font-size: 12.5px;
  color: #b45309;
}

.doc-scroll {
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
  background: var(--app-bg);
  border: 1px solid var(--app-border-weak);
  border-radius: var(--radius-lg);
  padding: 8px 0 40px;
  scroll-behavior: smooth;
}

/* 一行文字最舒服的宽度是 70~80 字，所以正文栏宽度封顶，两侧留白 */
.doc-page {
  max-width: 860px;
  margin: 0 auto;
  padding: 0 28px;
}

/* 页分隔：不抢眼，但能让人知道"现在读到原文第几页" */
.doc-page-sep {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 26px 0 20px;
  font-size: 11.5px;
  color: var(--app-text-3);
}

.doc-page-sep::before,
.doc-page-sep::after {
  content: '';
  flex: 1 1 auto;
  height: 1px;
  background: var(--app-border-weak);
}

.doc-title {
  margin: 18px 0 10px;
  font-size: var(--font-heading);
  line-height: var(--lh-heading);
  font-weight: 650;
  text-align: center;
  letter-spacing: -0.01em;
  color: var(--app-text-1);
}

.doc-authors {
  margin: 0 0 4px;
  text-align: center;
  font-size: 14px;
  line-height: 1.8;
  color: var(--app-text-2);
}

/* 机构 / 邮箱：小一号居中，和正文区分开 */
.doc-meta {
  margin: 0 0 2px;
  text-align: center;
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--app-text-3);
}

/* 脚注：小字 + 左对齐 + 上边一条细线，像原文档那样落在正文之下 */
.doc-note {
  margin: 4px 0 2px;
  padding-top: 6px;
  font-size: 12px;
  line-height: 1.75;
  color: var(--app-text-3);
}

.doc-notes {
  margin-top: 24px;
  padding-top: 10px;
  border-top: 1px solid var(--app-border);
}

.doc-notes-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
  color: var(--app-text-3);
}

.doc-heading {
  margin: 26px 0 8px;
  font-weight: 650;
  color: var(--app-text-1);
}

.doc-lv1 {
  font-size: 18px;
  line-height: 1.45;
}

.doc-lv2 {
  font-size: 15.5px;
  line-height: 1.5;
}

.doc-lv3 {
  font-size: 14px;
  line-height: 1.6;
  color: var(--app-text-2);
}

.doc-block {
  position: relative;
}

/* 段：hover 才露出「译」按钮 —— 常驻会让整页都是按钮，反而不好读。
   放在正文左侧的**首行缩进位**里（首行本来就空 2em），所以不会压到文字 */
.doc-translate {
  position: absolute;
  top: 2px;
  left: -34px;
  opacity: 0;
  transition: opacity var(--dur-fast) var(--ease);
}

.doc-block:hover .doc-translate,
.doc-translate:focus-visible {
  opacity: 1;
}

.doc-text {
  margin: 0 0 14px;
  font-size: 15px;
  line-height: 1.95;
  color: var(--app-text-1);
  word-break: break-word;
}

.doc-text :deep(p) { margin: 0 0 8px; }
.doc-text :deep(p:last-child) { margin-bottom: 0; }
.doc-text :deep(pre) { white-space: pre-wrap; text-indent: 0; font-size: 12px; }
.doc-text :deep(code) { text-indent: 0; }
.doc-view :deep(.extracted-math-block),
.doc-view :deep(.extracted-math-display) { display: block; overflow-x: auto; margin: 10px 0; text-indent: 0; text-align: center; }
.doc-view :deep(.extracted-math-inline) { text-indent: 0; white-space: nowrap; }
.doc-view :deep(.extracted-math-error) { white-space: pre-wrap; color: var(--app-text-3); }
.doc-math-warning { margin: -6px 0 14px; color: #9a6700; font-size: 12px; text-indent: 0; }

/* 首行缩进：纸质文档的段落感，比空行更省纵向空间 */
.doc-para {
  text-indent: 2em;
  text-align: justify;
}

.doc-bullet {
  padding-left: 1.6em;
  text-indent: -1.6em;
}

.doc-bullet :deep(p:first-child)::before { content: '•\00a0\00a0'; }

.doc-trans {
  margin: -6px 0 16px;
  padding: 10px 14px;
  border-left: 3px solid color-mix(in srgb, var(--app-brand) 55%, transparent);
  background: var(--app-brand-soft);
  border-radius: 0 8px 8px 0;
}

/* 代码块 / 表格块：保持原样（换行、缩进都要留住），横向放不下就自己滚 */
.doc-code,
.doc-table {
  margin: 10px 0;
  padding: 10px 14px;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  background: color-mix(in srgb, var(--app-text-1) 4%, transparent);
  font-size: 12.5px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
  overflow-x: auto;
}

.doc-code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, 'Courier New', monospace;
}

/* 表格块里的"一行"是原文档的一行，字体不换等宽，读数字更舒服 */
.doc-table {
  font-size: 13px;
  letter-spacing: 0.02em;
}

/* 插图：居中、限宽，点开原尺寸（读者想看清细节时不会因为缩略而看不清） */
.doc-figure {
  margin: 14px 0;
  text-align: center;
}

.doc-figure img {
  max-width: 100%;
  height: auto;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  background: #fff;
}

.doc-trans-tag {
  font-size: 11px;
  color: var(--app-brand-deep);
}

.doc-trans-text {
  margin: 4px 0 0;
  font-size: 15px;
  line-height: 1.9;
  color: var(--app-text-1);
  white-space: pre-wrap;
  word-break: break-word;
}

.doc-empty {
  padding: 24px;
  text-align: center;
}
</style>
