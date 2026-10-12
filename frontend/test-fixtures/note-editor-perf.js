import { createApp, computed, h, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElButton, ElCheckbox, ElConfigProvider, ElInput, ElOption, ElSelect, ElTag } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import 'katex/dist/katex.min.css'
import '../src/style.css'
import BlockPreview from '../src/components/BlockPreview.vue'
import { isDark } from '../src/composables/useTheme.js'

// This entry never imports the Note API, router, localStorage, or model clients.
// It records only the synthetic document mounted below and does not run a benchmark on load.
const layout = document.createElement('style')
layout.textContent = `
  body { margin: 0; }
  .perf-fixture { max-width: 1240px; margin: 0 auto; padding: 20px; color: var(--app-text-1); }
  .perf-fixture h1 { font-size: 22px; margin: 0 0 10px; }
  .perf-fixture .description { font-size: 13px; color: var(--app-text-2); line-height: 1.7; }
  .perf-controls { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin: 12px 0; }
  .perf-controls .el-button + .el-button { margin-left: 0; }
  .perf-controls .el-select { width: 130px; }
  .perf-preview-shell, .perf-source-shell, .perf-metrics { border: 1px solid var(--app-border); border-radius: var(--radius); background: var(--app-card); margin: 16px 0; padding: 14px; }
  .perf-fixture .pv-scroll { height: 530px; max-height: 65vh; overflow: auto; position: relative; overscroll-behavior: contain; }
  .perf-fixture .pv-inner { padding: 12px 22px; }
  .perf-metrics pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 390px; overflow: auto; font-size: 12px; line-height: 1.6; }
  .perf-source-shell textarea { font-family: var(--font-mono, monospace); font-size: 12px; line-height: 1.6; }
  .perf-fixture .notice { min-height: 24px; font-size: 13px; }
  @media(max-width: 600px) { .perf-fixture { padding: 10px; } .perf-fixture .pv-inner { padding: 8px; } .perf-controls .el-select { width: 110px; } }
`
document.head.append(layout)

export function syntheticMarkdown(headings = 120) {
  const count = Math.max(100, Math.min(200, Number(headings) || 120))
  const codeAt = new Set(Array.from({ length: 20 }, (_, index) => Math.floor(index * count / 20)))
  const mathAt = new Set(Array.from({ length: 10 }, (_, index) => Math.floor(index * count / 10)))
  const parts = ['# 合成性能笔记', '', '以下内容全部由夹具生成，只用于输入、滚动、光标与草稿回归。', '']
  for (let index = 0; index < count; index++) {
    const number = String(index + 1).padStart(3, '0')
    parts.push(`## 合成章节 ${number}`, '',
      `这是第${number}段合成说明，记录字段Alpha_${number}与Beta_${number}的组合规则。编辑者可在此插入合成文字，然后检查光标停留位置、撤销重做与父组件同步。该段落没有外部资料，也不触发任何请求。不同章节保留独立编号，便于确认长文末尾的内容没有在回显或切换显示时丢失。`, '')
    if (codeAt.has(index)) {
      parts.push('```javascript', `export function syntheticSequence${number}(seed) {`,
        '  const start = Number(seed) || 0', '  return [start, start + 1, start + 2]', '}', '```', '')
    }
    if (mathAt.has(index)) parts.push('$$', `E_{${index + 1}} = x^2 + 2x + 1`, '$$', '')
  }
  return parts.join('\n')
}

function distribution(values) {
  if (!values.length) return { count: 0, medianMs: null, p95Ms: null, maxMs: null }
  const ordered = [...values].sort((a, b) => a - b)
  const median = ordered.length % 2 ? ordered[(ordered.length - 1) / 2]
    : (ordered[ordered.length / 2 - 1] + ordered[ordered.length / 2]) / 2
  const rounded = value => Number(value.toFixed(3))
  return { count: values.length, medianMs: rounded(median),
    p95Ms: rounded(ordered[Math.ceil(ordered.length * 0.95) - 1]), maxMs: rounded(ordered.at(-1)) }
}
function pushBounded(values, value) {
  values.push(value)
  if (values.length > 500) values.shift()
}
function readonly(value) {
  if (value && typeof value === 'object') {
    for (const child of Object.values(value)) readonly(child)
    Object.freeze(value)
  }
  return value
}

const App = {
  setup() {
    const headings = ref(120), generatedHeadings = ref(120), sourceLines = ref(false), visible = ref(true)
    const original = syntheticMarkdown(headings.value)
    const draft = ref(original), saved = ref(original), pending = ref(false)
    const child = ref(null), host = ref(null), notice = ref('等待在合成正文中输入。')
    const displaySummary = ref({})
    const dirty = computed(() => pending.value || draft.value !== saved.value)
    const knownEditors = new WeakSet()
    let instanceCount = 0, currentEditor = null, undoInstrumentation = null, observer = null
    let publishTimer = 0, pendingTimer = 0, scrollFrame = 0, instrumentationFrame = 0, disposed = false
    let lastInputStart = null, scrollFrameIntervals = [], lastFlush = null
    const metrics = { changes: 0, markdownSyncs: 0, inputFrames: [], keyFrames: [],
      scrollFrames: [], dispatches: [], htmlCalls: [], longTasks: [], scrollRuns: [] }

    function editor() { return host.value?.querySelector('.tiptap')?.editor || null }
    function selection() {
      const state = editor()?.state
      return state ? { from: state.selection.from, to: state.selection.to,
        type: state.selection.constructor.name, docSize: state.doc.content.size } : null
    }
    function summary() {
      return {
        fixture: 'synthetic BlockPreview only; no Note API or persistence',
        generated: { headings: generatedHeadings.value, paragraphs: generatedHeadings.value, codeBlocks: 20, formulas: 10 },
        theme: isDark.value ? 'dark' : 'light', previewVisible: visible.value,
        sourceLinesActive: sourceLines.value, sourceCharacters: draft.value.length,
        dirty: dirty.value, pendingContent: Boolean(child.value?.hasPendingContent?.()),
        editorInstanceCount: instanceCount, selection: selection(), lastFlush,
        changes: metrics.changes, markdownSyncs: metrics.markdownSyncs,
        documentDispatch: distribution(metrics.dispatches), getHTML: distribution(metrics.htmlCalls),
        inputToNextFrame: distribution(metrics.inputFrames), keydownToNextFrame: distribution(metrics.keyFrames),
        scrollEventToNextFrame: distribution(metrics.scrollFrames),
        longTasks: { supported: Boolean(observer), count: metrics.longTasks.length,
          totalMs: Number(metrics.longTasks.reduce((sum, entry) => sum + entry.duration, 0).toFixed(3)),
          maxMs: Math.max(0, ...metrics.longTasks.map(entry => entry.duration)),
          recent: metrics.longTasks.slice(-8) },
        programmaticScrollRuns: metrics.scrollRuns.slice(-3),
        limitations: 'Frame callbacks precede paint. Programmatic scroll is separate from native input. This fixture does not mount NoteEdit or validate its two-pane geometry cache. Metrics retain up to 500 samples.'
      }
    }
    function publish() {
      clearTimeout(publishTimer)
      publishTimer = 0
      if (!disposed) displaySummary.value = summary()
    }
    function publishSoon() {
      if (!publishTimer && !disposed) publishTimer = setTimeout(publish, 150)
    }
    function attachInstrumentation() {
      const ed = editor()
      if (!ed) return false
      if (ed === currentEditor) return true
      undoInstrumentation?.()
      currentEditor = ed
      if (!knownEditors.has(ed)) { knownEditors.add(ed); instanceCount++ }
      const view = ed.view, originalDispatch = view.dispatch, originalHTML = ed.getHTML
      view.dispatch = function (transaction) {
        const start = performance.now()
        try { return originalDispatch.call(this, transaction) }
        finally {
          if (transaction.docChanged && !transaction.getMeta('preventUpdate')) {
            pushBounded(metrics.dispatches, performance.now() - start)
            publishSoon()
          }
        }
      }
      ed.getHTML = function (...args) {
        const start = performance.now()
        try { return originalHTML.apply(this, args) }
        finally { pushBounded(metrics.htmlCalls, performance.now() - start); publishSoon() }
      }
      undoInstrumentation = () => { view.dispatch = originalDispatch; ed.getHTML = originalHTML }
      publish()
      return true
    }
    function attachWhenReady(attempt = 0) {
      instrumentationFrame = 0
      if (disposed || attachInstrumentation()) return
      // Tiptap attaches dom.editor after the child's editor view is mounted.
      // Wait at most eight frames, then stop; this is not a background benchmark.
      if (attempt < 8) instrumentationFrame = requestAnimationFrame(() => attachWhenReady(attempt + 1))
    }
    function onChange() {
      metrics.changes++
      pending.value = true
      clearTimeout(pendingTimer)
      pendingTimer = setTimeout(() => { pending.value = Boolean(child.value?.hasPendingContent?.()); publish() }, 400)
      publishSoon()
    }
    function onUpdate(markdown) {
      metrics.markdownSyncs++
      draft.value = markdown
      pending.value = false
      publishSoon()
    }
    async function flushSnapshot(save = false) {
      const before = selection()
      const markdown = child.value?.flushContent?.(draft.value) ?? draft.value
      draft.value = markdown
      pending.value = false
      await nextTick()
      const after = selection()
      lastFlush = { before, after, sameCaret: before?.from === after?.from && before?.to === after?.to,
        characters: markdown.length, save }
      if (save) saved.value = markdown
      notice.value = `${save ? '模拟保存' : '读取快照'}：${markdown.length}字；光标${lastFlush.sameCaret ? '保持' : '发生变化'}。`
      publish()
    }
    function sourceUpdate(value) {
      draft.value = value
      pending.value = false
      notice.value = '外部源码已修改；保持同一个BlockPreview实例。'
      nextTick(() => { attachInstrumentation(); publish() })
    }
    async function sourceAppend() {
      await flushSnapshot()
      sourceUpdate(draft.value + '\n\n合成源文追加标记。')
    }
    function history(redo) {
      const changed = redo ? child.value?.redo?.() : child.value?.undo?.()
      notice.value = changed ? (redo ? '已重做合成编辑。' : '已撤销合成编辑。') : '暂无可操作的历史。'
      publishSoon()
    }
    function clearMetrics() {
      for (const key of Object.keys(metrics)) metrics[key] = Array.isArray(metrics[key]) ? [] : 0
      lastFlush = null
      notice.value = '统计已清空，正文、光标和撤销历史保留。'
      publish()
    }
    async function regenerate() {
      generatedHeadings.value = headings.value
      sourceUpdate(syntheticMarkdown(headings.value))
      saved.value = draft.value
      pending.value = false
      clearMetrics()
      await nextTick()
      attachWhenReady()
      notice.value = '已重新生成合成笔记；无网络或持久化写入。'
      publish()
    }
    function toggleTheme() {
      isDark.value = !isDark.value
      document.documentElement.classList.toggle('dark', isDark.value)
      publishSoon()
    }
    function toggleVisible() {
      visible.value = !visible.value
      nextTick(() => { attachInstrumentation(); publish() })
    }
    function keyCapture(event) {
      if (!event.target?.closest('.tiptap') || event.ctrlKey || event.metaKey || event.altKey) return
      lastInputStart = performance.now()
    }
    function inputCapture(event) {
      if (!event.target?.closest('.tiptap')) return
      const start = performance.now(), keyStart = lastInputStart
      lastInputStart = null
      requestAnimationFrame(() => {
        if (disposed) return
        const now = performance.now()
        pushBounded(metrics.inputFrames, now - start)
        if (keyStart !== null) pushBounded(metrics.keyFrames, now - keyStart)
        publishSoon()
      })
    }
    function scrollCapture() {
      const start = performance.now()
      requestAnimationFrame(() => {
        if (disposed) return
        pushBounded(metrics.scrollFrames, performance.now() - start)
        publishSoon()
      })
    }
    function scrollSample() {
      if (scrollFrame || !visible.value) return
      const scroll = host.value?.querySelector('.pv-scroll')
      if (!scroll) return
      scrollFrameIntervals = []
      const originalTop = scroll.scrollTop
      const start = performance.now()
      let previous = start, frame = 0
      notice.value = '正在采样90帧程序化滚动；此项与真实键入分开统计。'
      function step(now) {
        if (disposed) return
        if (frame) pushBounded(scrollFrameIntervals, now - previous)
        previous = now
        scroll.scrollTop = (frame / 89) * Math.max(0, scroll.scrollHeight - scroll.clientHeight)
        frame++
        if (frame < 90) scrollFrame = requestAnimationFrame(step)
        else {
          scrollFrame = 0
          scroll.scrollTop = originalTop
          pushBounded(metrics.scrollRuns, { frames: frame, elapsedMs: Number((now - start).toFixed(3)),
            frameIntervals: distribution(scrollFrameIntervals) })
          notice.value = '程序化滚动采样完成；正文与光标没有修改。'
          publish()
        }
      }
      scrollFrame = requestAnimationFrame(step)
    }
    onMounted(async () => {
      // Set theme without using initTheme/setThemeMode, which persist user preferences.
      isDark.value = new URL(location.href).searchParams.get('theme') === 'dark'
      document.documentElement.classList.toggle('dark', isDark.value)
      await nextTick()
      attachWhenReady()
      if (globalThis.PerformanceObserver?.supportedEntryTypes?.includes('longtask')) {
        observer = new PerformanceObserver(list => {
          for (const entry of list.getEntries()) pushBounded(metrics.longTasks,
            { startTime: Number(entry.startTime.toFixed(3)), duration: Number(entry.duration.toFixed(3)) })
          publishSoon()
        })
        observer.observe({ type: 'longtask' })
      }
      Object.defineProperty(window, 'perfFixture', { configurable: true, get: () => readonly(summary()) })
      publish()
    })
    onBeforeUnmount(() => {
      disposed = true
      clearTimeout(publishTimer)
      clearTimeout(pendingTimer)
      if (scrollFrame) cancelAnimationFrame(scrollFrame)
      if (instrumentationFrame) cancelAnimationFrame(instrumentationFrame)
      observer?.disconnect()
      undoInstrumentation?.()
      delete window.perfFixture
      layout.remove()
    })

    return () => h(ElConfigProvider, { locale: zhCn }, () => h('main', { class: 'perf-fixture' }, [
      h('h1', '合成笔记编辑性能夹具'),
      h('p', { class: 'description' }, '真实BlockPreview + 简单父组件。默认120标题、120段落、20代码块和10公式；不接入笔记接口，不保存到浏览器或数据库。只在此页输入合成文字。'),
      h('div', { class: 'perf-controls' }, [
        h(ElSelect, { modelValue: headings.value, 'onUpdate:modelValue': value => { headings.value = value }, 'aria-label': '合成章节数' }, () =>
          [100, 120, 160, 200].map(value => h(ElOption, { key: value, label: `${value}章`, value }))),
        h(ElButton, { onClick: regenerate }, () => '重新生成合成笔记'),
        h(ElButton, { onClick: toggleTheme }, () => isDark.value ? '切换浅色' : '切换深色'),
        h(ElButton, { onClick: toggleVisible, 'data-testid': 'toggle-preview' }, () => visible.value ? '隐藏预览' : '显示预览'),
        h(ElCheckbox, { modelValue: sourceLines.value, 'onUpdate:modelValue': value => { sourceLines.value = value; publishSoon() } }, () => '启用源码行映射'),
      ]),
      h('div', { class: 'perf-controls', onMousedown: event => event.preventDefault() }, [
        h(ElButton, { onClick: () => history(false), 'data-testid': 'undo' }, () => '撤销'),
        h(ElButton, { onClick: () => history(true), 'data-testid': 'redo' }, () => '重做'),
        h(ElButton, { onClick: () => flushSnapshot(), 'data-testid': 'flush' }, () => '读取Markdown快照'),
        h(ElButton, { onClick: () => flushSnapshot(true), 'data-testid': 'save' }, () => '模拟保存'),
        h(ElButton, { onClick: clearMetrics }, () => '清空统计'),
        h(ElButton, { onClick: scrollSample }, () => '短滚动采样'),
        h(ElTag, { type: dirty.value ? 'warning' : 'success', 'data-testid': 'dirty-state' }, () => dirty.value ? '合成草稿已修改' : '合成草稿未修改'),
      ]),
      h('p', { class: 'notice', role: 'status', 'aria-live': 'polite' }, notice.value),
      h('section', { ref: host, class: 'perf-preview-shell edit-page',
        style: visible.value ? {} : { display: 'none' }, onKeydownCapture: keyCapture, onInputCapture: inputCapture }, [
        h('div', { class: 'pv-scroll', onScroll: scrollCapture }, [h('div', { class: 'pv-inner' }, [
          h(BlockPreview, { ref: child, content: draft.value, active: true, visible: visible.value,
            sourceLinesActive: sourceLines.value, onChange, onUpdate }),
        ])]),
      ]),
      h('section', { class: 'perf-source-shell' }, [
        h('h2', '同源合成Markdown'),
        h('p', { class: 'description' }, '获得源码焦点时先flush块编辑器，避免外部修改覆盖尚未同步的尾字。下面改动用于检验同一实例的外部content同步。'),
        h(ElInput, { type: 'textarea', rows: 8, modelValue: draft.value,
          'onUpdate:modelValue': sourceUpdate, onFocus: () => flushSnapshot(),
          'aria-label': '合成Markdown源码', 'data-testid': 'source' }),
        h(ElButton, { onClick: sourceAppend }, () => '源文末追加合成字'),
      ]),
      h('section', { class: 'perf-metrics' }, [
        h('h2', '合成输入与滚动统计'),
        h('p', { class: 'description' }, '事务耗时和getHTML次数在本夹具实例上采样；下一帧回调早于实际绘制，不能直接当作端到端呈现延迟。longtask阈值为浏览器定义的50ms。全局只读window.perfFixture也可读取此概要。'),
        h('pre', { 'data-testid': 'perf-summary', 'aria-live': 'off' }, JSON.stringify(displaySummary.value, null, 2)),
      ]),
    ]))
  },
}

createApp(App).mount('#app')
