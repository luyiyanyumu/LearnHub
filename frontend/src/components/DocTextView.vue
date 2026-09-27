<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { fileApi } from '../api'

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
})

const translateError = ref('')
/** 译文缓存：key = 页码:块序号（跨页唯一，翻回来不用重翻） */
const translations = ref({})
const translatingKey = ref('')
const busy = ref(false)

const blockCount = computed(() =>
  props.pages.reduce((n, p) => n + (p.blocks?.length || 0), 0))

/** 可翻译的块：标题/作者这类短标签没必要翻 */
function translatable(block) {
  return block.type === 'para' || block.type === 'bullet'
}

function keyOf(page, index) {
  return `${page}:${index}`
}

function tooLong(block) {
  return (block.text || '').length > props.maxChars
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
  const text = props.pages
    .map((p) => (p.blocks || []).map((b) => b.text).join('\n'))
    .join('\n\n')
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
</script>

<template>
  <div class="doc-view">
    <div class="reader-toolbar doc-tools">
      <span class="reader-hint">
        按原文档排版还原 · {{ pages.length }} / {{ pageCount || pages.length }} 页 · {{ chars || blockCount }} 字
      </span>
      <span class="reader-sep" />
      <span class="reader-hint">段落 hover 出「译」，逐段翻译</span>
      <span class="reader-spacer" />
      <button
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
        <template v-for="(b, i) in pg.blocks" :key="i">
          <h2 v-if="b.type === 'title'" class="doc-title">{{ b.text }}</h2>
          <p v-else-if="b.type === 'authors'" class="doc-authors">{{ b.text }}</p>
          <p v-else-if="b.type === 'meta'" class="doc-meta">{{ b.text }}</p>
          <h3 v-else-if="b.type === 'heading'" class="doc-heading" :class="'doc-lv' + (b.level || 1)">
            {{ b.text }}
          </h3>
          <!-- 代码 / 表格：按"一行一行"原样渲染，不参与翻译（翻代码没有意义，还会把缩进搅乱） -->
          <pre v-else-if="b.type === 'code'" class="doc-code">{{ b.text }}</pre>
          <pre v-else-if="b.type === 'table'" class="doc-table">{{ b.text }}</pre>
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
            <p :class="['doc-text', b.type === 'bullet' ? 'doc-bullet' : 'doc-para']">
              <template v-if="b.type === 'bullet'">•&nbsp;&nbsp;</template>{{ b.text }}
            </p>
            <div v-if="translations[keyOf(pg.page, i)]" class="doc-trans">
              <span class="doc-trans-tag">
                译 · {{ translations[keyOf(pg.page, i)].by || '模型' }}
              </span>
              <p class="doc-trans-text">{{ translations[keyOf(pg.page, i)].text }}</p>
            </div>
          </div>
        </template>
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

/* 机构 / 邮箱 / 脚注：小一号居中，和正文区分开 */
.doc-meta {
  margin: 0 0 2px;
  text-align: center;
  font-size: 12.5px;
  line-height: 1.8;
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

/* 首行缩进：纸质文档的段落感，比空行更省纵向空间 */
.doc-para {
  text-indent: 2em;
  text-align: justify;
}

.doc-bullet {
  padding-left: 1.6em;
  text-indent: -1.6em;
}

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
