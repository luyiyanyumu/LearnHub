<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import * as pdfjsLib from 'pdfjs-dist'
// 官方 viewer 样式：文字层（textLayer）的定位与命中规则都在里面 ——
// 手写那几条能让 span 位置算对，但鼠标点不到它（实测 elementFromPoint 落在容器上、拖拽选区为空）。
import 'pdfjs-dist/web/pdf_viewer.css'
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'
import { ElMessage } from 'element-plus'
import { fileApi } from '../api'

/**
 * 自研 PDF 阅读器（不用浏览器内置查看器）。
 *
 * <h3>四件事</h3>
 * <ol>
 *   <li><b>文字层</b>：canvas 之上叠一层透明文字（pdf.js TextLayer）→ 页面里的文字可以**选中**，
 *       选中即出译文。没有它，PDF 就是个"图片"，读英文论文只能来回切标签。</li>
 *   <li><b>目录</b>：读论文的 `getOutline()`，侧栏跳转（上百页没有目录没法读）。</li>
 *   <li><b>记住位置</b>：页码/缩放/模式存到资料上，下次打开接着读。</li>
 *   <li><b>三种模式</b>：单页 / 双页 / 连续滚动。连续模式下按视口懒渲染，翻到哪画到哪。</li>
 * </ol>
 *
 * <h3>踩过的坑（都写在这里，免得再犯）</h3>
 * <ul>
 *   <li>canvas 必须按 devicePixelRatio 放大再 transform，否则高分屏糊；</li>
 *   <li>渲染是异步的，连翻要 cancel 上一张，否则两张叠画；</li>
 *   <li>文字层容器要设 `--scale-factor`，pdf.js 用它定位每个 span（设错就选不中）；</li>
 *   <li>连续模式一次渲染所有页会卡死（19 页的论文已经明显）→ IntersectionObserver 懒渲染。</li>
 * </ul>
 */
const props = defineProps({
  fileId: { type: [Number, String], required: true },
  src: { type: String, required: true },
  /** 上次的阅读位置（来自资料详情），打开时恢复 */
  initial: { type: Object, default: () => ({}) },
})

pdfjsLib.GlobalWorkerOptions.workerSrc = workerUrl

const wrap = ref(null)
const loading = ref(true)
const error = ref('')
const totalPages = ref(0)
const pageNo = ref(1)
const scale = ref(1)
const fitWidth = ref(true)
const mode = ref('single')          // single | double | continuous
const outline = ref([])
const showOutline = ref(false)
const rendering = ref(false)
/** 连续模式的页容器：key = 页码，value = { canvas, textLayer } 的 ref 集合 */
const pages = ref([])               // [{ n, canvas, text }] —— 连续模式用
const textSpans = ref(0)

/** 选中翻译：{ text, translation, loading, x, y } */
const sel = ref(null)

let doc = null
let renderTask = null
let resizeObserver = null
let io = null
let saveTimer = null
let pendingJump = null

const displayScale = computed(() => scale.value)
const pageLabel = computed(() => `${pageNo.value} / ${totalPages.value || '–'}`)

// ------------------------------------------------------------------
// 打开与索引
// ------------------------------------------------------------------

async function open() {
  loading.value = true
  error.value = ''
  outline.value = []
  pages.value = []
  try {
    doc = await pdfjsLib.getDocument({ url: props.src, withCredentials: false }).promise
    totalPages.value = doc.numPages
    // 恢复上次位置（页码越界就落回第 1 页）
    const initPage = Number(pendingJump ?? props.initial?.readPage ?? 1)
    pendingJump = null
    const initScale = Number(props.initial?.readScale || 0)
    const initMode = props.initial?.readMode
    pageNo.value = Math.min(Math.max(1, initPage), totalPages.value || 1)
    if (initScale > 0.2 && initScale < 5) {
      scale.value = initScale
      fitWidth.value = false
    }
    if (['single', 'double', 'continuous'].includes(initMode)) {
      mode.value = initMode
    }
    outline.value = await loadOutline()
    await nextTick()
    await render()
    observeResize()
  } catch (e) {
    error.value = 'PDF 加载失败：' + (e?.message || e)
  } finally {
    loading.value = false
    if (pendingJump !== null && doc && !error.value) {
      const requestedPage = pendingJump
      pendingJump = null
      jump(requestedPage)
    }
  }
}

/** 读目录：dest → 页码（dest 可能是命名目标，要 resolve） */
async function loadOutline() {
  try {
    const raw = await doc.getOutline()
    if (!raw || !raw.length) return []
    const build = async (items) => {
      const out = []
      for (const it of items) {
        let n = null
        try {
          const dest = typeof it.dest === 'string' ? await doc.getDestination(it.dest) : it.dest
          if (dest && dest[0]) {
            const idx = await doc.getPageIndex(dest[0])
            n = idx + 1
          }
        } catch (e) {
          n = null // 有些条目点不过去（坏 dest），留着但不给跳转
        }
        out.push({
          title: it.title,
          page: n,
          items: it.items && it.items.length ? await build(it.items) : [],
        })
      }
      return out
    }
    return await build(raw)
  } catch (e) {
    return []
  }
}

// ------------------------------------------------------------------
// 渲染
// ------------------------------------------------------------------

/** 当前应显示的页码列表（双页模式是连续两页） */
function visiblePages() {
  if (mode.value === 'double') {
    const first = pageNo.value % 2 === 0 ? pageNo.value - 1 : pageNo.value
    const list = [first, first + 1].filter((n) => n >= 1 && n <= totalPages.value)
    return list.length ? list : [pageNo.value]
  }
  return [pageNo.value]
}

async function render() {
  if (!doc) return
  if (mode.value === 'continuous') {
    await buildContinuous()
  } else {
    await buildFixed()
  }
  scheduleSave()
}

/** 单页/双页：渲染到同一块画布区域（每页一个 canvas + 文字层） */
async function buildFixed() {
  const host = wrap.value
  if (!host) return
  const numbers = visiblePages()
  // 固定模式：容器里重建（页数少，重建成本可忽略）
  pages.value = numbers.map((n) => ({ n, canvas: null, text: null, spans: 0, holder: null }))
  await nextTick()
  const holders = host.querySelectorAll('.pdf-page-box')
  for (let i = 0; i < numbers.length; i++) {
    await renderOne(numbers[i], holders[i], true)
  }
}

/** 连续模式：先占位，再按视口懒渲染 */
async function buildContinuous() {
  const host = wrap.value
  if (!host) return
  pages.value = Array.from({ length: totalPages.value }, (_, i) => ({
    n: i + 1, canvas: null, text: null, spans: 0, holder: null,
  }))
  await nextTick()
  const boxes = host.querySelectorAll('.pdf-page-box')
  if (io) io.disconnect()
  io = new IntersectionObserver((entries) => {
    for (const en of entries) {
      if (en.isIntersecting) {
        const n = Number(en.target.dataset.page)
        renderOne(n, en.target, false)
      }
    }
  }, { root: host, rootMargin: '300px 0px' })
  boxes.forEach((b) => io.observe(b))
  // 先把当前页滚进来
  const cur = host.querySelector(`.pdf-page-box[data-page="${pageNo.value}"]`)
  if (cur) cur.scrollIntoView({ block: 'start' })
}

/**
 * 渲染一页：canvas + 文字层。
 * @param fixed true = 固定模式（单页/双页），false = 连续模式里的懒渲染
 */
async function renderOne(n, holder, fixed) {
  if (!holder || holder.dataset.done === '1') return
  holder.dataset.done = '1'
  try {
    const page = await doc.getPage(n)
    let s = scale.value
    if (fitWidth.value && wrap.value) {
      const base = page.getViewport({ scale: 1 })
      const avail = (fixed ? wrap.value.clientWidth : wrap.value.clientWidth) - 24
      s = Math.max(0.2, avail / (base.width * (mode.value === 'double' ? 2 : 1)))
      scale.value = s
    }
    const viewport = page.getViewport({ scale: s })
    const dpr = window.devicePixelRatio || 1
    const canvas = holder.querySelector('canvas')
    canvas.width = Math.floor(viewport.width * dpr)
    canvas.height = Math.floor(viewport.height * dpr)
    canvas.style.width = Math.floor(viewport.width) + 'px'
    canvas.style.height = Math.floor(viewport.height) + 'px'
    const ctx = canvas.getContext('2d')
    ctx.clearRect(0, 0, canvas.width, canvas.height)
    if (fixed && renderTask) {
      try { renderTask.cancel() } catch (e) { /* 已结束 */ }
    }
    const task = page.render({
      canvasContext: ctx,
      viewport,
      transform: dpr === 1 ? null : [dpr, 0, 0, dpr, 0, 0],
    })
    if (fixed) renderTask = task
    await task.promise
    // 文字层：透明文字叠在 canvas 上，页面里的文本才能被选中
    const textHost = holder.querySelector('.pdf-textlayer')
    if (textHost) {
      textHost.innerHTML = ''
      // pdf.js 用 --scale-factor 定位每个 span，必须设（设错就完全选不中）
      textHost.style.setProperty('--scale-factor', String(viewport.scale))
      // 官方 viewer 把缩放系数设在**页容器**上，再由 CSS 算 --total-scale-factor；
      // 只设在文字层上不够（实测：位置对、命中不通）
      holder.style.setProperty('--scale-factor', String(viewport.scale))
      textHost.style.width = Math.floor(viewport.width) + 'px'
      textHost.style.height = Math.floor(viewport.height) + 'px'
      const layer = new pdfjsLib.TextLayer({
        textContentSource: await page.getTextContent(),
        container: textHost,
        viewport,
      })
      await layer.render()
      const spans = textHost.querySelectorAll('span').length
      const row = pages.value.find((p) => p.n === n)
      if (row) row.spans = spans
      textSpans.value += spans
    }
  } catch (e) {
    if (!/cancel/i.test(String(e?.name || e?.message))) {
      error.value = '渲染第 ' + n + ' 页失败：' + (e?.message || e)
    }
  }
}

function go(delta) {
  const step = mode.value === 'double' ? 2 : 1
  const next = Math.min(Math.max(1, pageNo.value + delta * step), totalPages.value || 1)
  if (next !== pageNo.value) {
    pageNo.value = next
    render()
  }
}

function jump(p) {
  const n = Number(p)
  if (!Number.isFinite(n)) return
  if (loading.value) {
    pendingJump = n
    return
  }
  pageNo.value = Math.min(Math.max(1, Math.round(n)), totalPages.value || 1)
  render()
}

function zoom(delta) {
  fitWidth.value = false
  scale.value = Math.min(4, Math.max(0.25, +(scale.value + delta).toFixed(2)))
  rerenderAll()
}

function setFitWidth() {
  fitWidth.value = true
  rerenderAll()
}

function setMode(m) {
  mode.value = m
  pages.value = []
  nextTick(() => render())
}

/** 缩放/适宽变化后要重画：固定模式整块重建，连续模式清掉已渲染标记让懒渲染重来 */
async function rerenderAll() {
  const host = wrap.value
  if (!host) return
  host.querySelectorAll('.pdf-page-box').forEach((b) => { b.dataset.done = '0' })
  if (mode.value === 'continuous') {
    pages.value.forEach((p) => { p.spans = 0 })
    textSpans.value = 0
    nextTick(() => buildContinuous())
  } else {
    await buildFixed()
  }
  scheduleSave()
}

function observeResize() {
  if (!wrap.value || resizeObserver) return
  resizeObserver = new ResizeObserver(() => {
    if (fitWidth.value) rerenderAll()
  })
  resizeObserver.observe(wrap.value)
}

// ------------------------------------------------------------------
// 选中翻译
// ------------------------------------------------------------------

/** 鼠标在文字层里放开时：有选中就翻译（这是"读英文论文"最常用的动作） */
function onMouseUp() {
  const text = String(window.getSelection?.() || '').trim()
  if (!text) {
    sel.value = null
    return
  }
  translateSelection(text)
}

async function translateSelection(text) {
  // 纯数字/符号没必要翻
  if (!/[\p{L}]/u.test(text)) {
    return
  }
  sel.value = { text, translation: '', loading: true }
  try {
    const r = await fileApi.translate(props.fileId, text.slice(0, 4000), '简体中文')
    sel.value = { text, translation: r.translation, loading: false, model: r.profile || r.model }
  } catch (e) {
    sel.value = { text, translation: '', loading: false, err: e?.message || String(e) }
  }
}

async function translateFullPage() {
  const row = pages.value.find((p) => p.n === pageNo.value)
  const holder = wrap.value?.querySelector(`.pdf-page-box[data-page="${pageNo.value}"]`)
  const text = (holder?.querySelector('.pdf-textlayer')?.innerText || '').replace(/\s+\n/g, '\n').trim()
  if (!text) {
    ElMessage.warning('本页没有可提取的文字（可能是扫描件）')
    return
  }
  if (text.length > 4000) {
    ElMessage.warning(`本页 ${text.length} 字，超过单段上限 4000 —— 请选中要翻的段落`)
    return
  }
  translateSelection(text)
  if (row) row.spans = row.spans || 0
}

// ------------------------------------------------------------------
// 阅读位置（防抖保存）
// ------------------------------------------------------------------

function scheduleSave() {
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(() => {
    fileApi.saveReading(props.fileId, {
      page: pageNo.value,
      scale: Number(scale.value.toFixed(2)),
      mode: mode.value,
    }).catch(() => { /* 保存失败不打扰阅读 */ })
  }, 1200)
}

/** 连续模式下滚动会改变"当前页"：用视口中心最近的页更新页码（页码也用于保存位置） */
function onScroll() {
  if (mode.value !== 'continuous' || !wrap.value) return
  const boxes = wrap.value.querySelectorAll('.pdf-page-box')
  const mid = wrap.value.scrollTop + wrap.value.clientHeight / 3
  let cur = pageNo.value
  boxes.forEach((b) => {
    if (b.offsetTop <= mid) cur = Number(b.dataset.page)
  })
  if (cur !== pageNo.value) {
    pageNo.value = cur
    scheduleSave()
  }
}

function onKey(e) {
  const tag = (e.target?.tagName || '').toLowerCase()
  if (tag === 'input' || tag === 'textarea') return
  if (e.key === 'PageDown' || e.key === 'ArrowRight') go(1)
  else if (e.key === 'PageUp' || e.key === 'ArrowLeft') go(-1)
}

watch(() => props.src, open)
defineExpose({ jump })
onBeforeUnmount(() => {
  if (renderTask) { try { renderTask.cancel() } catch (e) { /* ignore */ } }
  if (resizeObserver) resizeObserver.disconnect()
  if (io) io.disconnect()
  if (saveTimer) clearTimeout(saveTimer)
  if (doc) { try { doc.destroy() } catch (e) { /* ignore */ } }
  window.removeEventListener('keydown', onKey)
})

open()
window.addEventListener('keydown', onKey)
</script>

<template>
  <div class="pdfreader">
    <!-- 控件样式走全局 .reader-btn / .reader-toolbar：与「抽取正文」那一行共用同一套观感 -->
    <div class="reader-toolbar pdf-toolbar">
      <button type="button" class="reader-btn" title="目录" @click="showOutline = !showOutline">
        ☰ 目录<template v-if="outline.length">（{{ outline.length }}）</template>
      </button>
      <button type="button" class="reader-btn" :disabled="pageNo <= 1" @click="go(-1)">‹</button>
      <span class="pdf-page">
        <input :value="pageNo" class="pdf-input" @change="(e) => jump(e.target.value)" />
        {{ pageLabel.replace(String(pageNo), '').trim() }}
      </span>
      <button type="button" class="reader-btn" :disabled="pageNo >= totalPages" @click="go(1)">›</button>
      <span class="reader-sep" />
      <button type="button" class="reader-btn" @click="zoom(-0.15)">−</button>
      <span class="pdf-zoom">{{ Math.round(scale * 100) }}%</span>
      <button type="button" class="reader-btn" @click="zoom(0.15)">＋</button>
      <button type="button" class="reader-btn" :class="{ on: fitWidth }" @click="setFitWidth">适宽</button>
      <span class="reader-sep" />
      <button type="button" class="reader-btn" :class="{ on: mode === 'single' }" @click="setMode('single')">单页</button>
      <button type="button" class="reader-btn" :class="{ on: mode === 'double' }" @click="setMode('double')">双页</button>
      <button type="button" class="reader-btn" :class="{ on: mode === 'continuous' }" @click="setMode('continuous')">连续</button>
      <span class="reader-spacer" />
      <button type="button" class="reader-btn" @click="translateFullPage">翻译本页</button>
      <span class="reader-hint">选中文字即翻译 · PageUp/Down 翻页</span>
    </div>

    <div class="pdf-main">
      <!-- 目录侧栏 -->
      <aside v-if="showOutline" class="pdf-outline">
        <div class="outline-head">目录</div>
        <p v-if="!outline.length" class="pdf-hint outline-empty">这份 PDF 没有书签目录</p>
        <template v-for="it in outline" :key="it.title">
          <div class="outline-item" :class="{ link: it.page }" @click="it.page && jump(it.page)">
            <span class="outline-title">{{ it.title }}</span>
            <span v-if="it.page" class="outline-page">p{{ it.page }}</span>
          </div>
          <div v-for="sub in it.items" :key="sub.title" class="outline-item sub" :class="{ link: sub.page }"
               @click="sub.page && jump(sub.page)">
            <span class="outline-title">{{ sub.title }}</span>
            <span v-if="sub.page" class="outline-page">p{{ sub.page }}</span>
          </div>
        </template>
      </aside>

      <div ref="wrap" class="pdf-canvas-wrap" @mouseup="onMouseUp" @scroll="onScroll">
        <div v-if="loading" class="pdf-state">正在解析 PDF…</div>
        <div v-else-if="error" class="pdf-state pdf-error">{{ error }}</div>
        <div class="pdf-pages" :class="'mode-' + mode">
          <div
            v-for="p in pages"
            :key="p.n"
            class="pdf-page-box"
            :data-page="p.n"
            :data-done="0"
          >
            <canvas class="pdf-canvas"></canvas>
            <!-- 透明文字层：让页面里的文字可选中（pdf.js 的 textLayer 样式在全局 CSS 里） -->
            <div class="pdf-textlayer textLayer"></div>
            <span class="pdf-page-no">
              {{ p.n }} / {{ totalPages }}
              <template v-if="p.spans"> · {{ p.spans }} 段文字</template>
            </span>
          </div>
        </div>
        <div v-if="rendering" class="pdf-rendering">渲染中…</div>
      </div>
    </div>

    <!-- 选中翻译结果 -->
    <div v-if="sel" class="sel-panel">
      <div class="sel-head">
        <b>选中翻译</b>
        <span class="pdf-hint">{{ sel.model ? '用 ' + sel.model : '' }}</span>
        <span class="reader-spacer" />
        <button type="button" class="reader-btn" @click="sel = null">关闭</button>
      </div>
      <p class="sel-src">{{ sel.text }}</p>
      <p v-if="sel.loading" class="pdf-hint">翻译中…</p>
      <p v-else-if="sel.err" class="pdf-error">翻译失败：{{ sel.err }}</p>
      <p v-else class="sel-trans">{{ sel.translation }}</p>
    </div>
  </div>
</template>

<style scoped>
.pdfreader {
  display: flex;
  flex-direction: column;
  gap: 8px;
  height: 100%;
  min-height: 0;
}
.pdf-toolbar {
  flex: 0 0 auto;
}
.pdf-page {
  color: var(--app-text-2);
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.pdf-input {
  width: 44px;
  text-align: center;
  border: 1px solid var(--app-border);
  border-radius: 6px;
  background: var(--app-bg);
  color: var(--app-text-1);
  padding: 2px 4px;
  font-size: 12.5px;
}
.pdf-zoom {
  min-width: 42px;
  text-align: center;
  color: var(--app-text-2);
}
.pdf-hint {
  font-size: 11.5px;
  color: var(--app-text-3);
}
/* 主体：目录 + 画布 */
.pdf-main {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  gap: 8px;
}
.pdf-outline {
  width: 240px;
  flex-shrink: 0;
  overflow: auto;
  border: 1px solid var(--app-border-weak);
  border-radius: 8px;
  background: var(--app-card);
  padding: 8px;
  font-size: 12.5px;
}
.outline-head {
  font-weight: 600;
  color: var(--app-text-1);
  margin-bottom: 6px;
}
.outline-empty {
  margin: 0;
}
.outline-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 3px 4px;
  border-radius: 5px;
  color: var(--app-text-2);
}
.outline-item.sub {
  padding-left: 16px;
  font-size: 12px;
}
.outline-item.link {
  cursor: pointer;
}
.outline-item.link:hover {
  background: var(--app-brand-soft);
  color: var(--app-brand-deep);
}
.outline-title {
  flex: 1 1 auto;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.outline-page {
  font-size: 11px;
  color: var(--app-text-3);
}
/* 画布区 */
.pdf-canvas-wrap {
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
  background: #6b7280;
  border-radius: 8px;
  padding: 12px;
  position: relative;
}
.pdf-pages {
  display: flex;
  gap: 12px;
  justify-content: center;
  align-items: flex-start;
}
.pdf-pages.mode-continuous {
  flex-direction: column;
  align-items: center;
}
.pdf-page-box {
  position: relative;
  background: #fff;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.25);
  flex-shrink: 0;
}
.pdf-canvas {
  display: block;
  position: relative;
  z-index: 1;
  pointer-events: none;  /* 命中测试交给上层文字层：canvas 不拦鼠标 */
}
.pdf-page-no {
  position: absolute;
  left: 6px;
  bottom: 6px;
  font-size: 11px;
  color: #6b7280;
  background: rgba(255, 255, 255, 0.85);
  padding: 1px 6px;
  border-radius: 5px;
}
.pdf-state {
  color: #f3f4f6;
  font-size: 13px;
  padding: 24px;
}
.pdf-error {
  color: #fecaca;
}
.pdf-rendering {
  position: absolute;
  right: 20px;
  bottom: 20px;
  font-size: 11.5px;
  color: #f3f4f6;
  background: rgba(0, 0, 0, 0.4);
  padding: 2px 8px;
  border-radius: 6px;
}
/* 选中翻译结果面板：贴在阅读器底部，不遮挡正文 */
.sel-panel {
  border: 1px solid color-mix(in srgb, var(--app-brand) 40%, transparent);
  border-radius: 8px;
  background: var(--app-card);
  padding: 8px 10px;
  max-height: 30vh;
  overflow: auto;
}
.sel-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
}
.sel-src {
  margin: 6px 0 0;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--app-text-3);
  white-space: pre-wrap;
}
.sel-trans {
  margin: 6px 0 0;
  font-size: 14px;
  line-height: 1.9;
  color: var(--app-text-1);
  white-space: pre-wrap;
}
</style>
