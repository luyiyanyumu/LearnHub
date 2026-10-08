<script setup>
import { computed, ref, watch } from 'vue'
import { ArrowRight, Close, Connection, Document, Download, Reading, Refresh, Search } from '@element-plus/icons-vue'
import { isGeneratedGuide, retrievalChannels, retrievalPosition, retrievalRelations, retrievalSnippet, retrievalSourcePath, retrievalTitle } from '../utils/retrievalDisplay.js'
import { highlightKnowledgeText, KNOWLEDGE_SOURCE_LABELS, knowledgeResultRows, knowledgeSourceFilters, knowledgeSourceType } from '../utils/knowledgeResultView.js'

const props = defineProps({
  query: { type: String, default: '' },
  mode: { type: String, default: 'fusion' },
  loading: { type: Boolean, default: false },
  searched: { type: Boolean, default: false },
  items: { type: Array, default: () => [] },
  keyword: { type: String, default: '' },
  lastMode: { type: String, default: 'fusion' },
  error: { type: String, default: '' },
})
const emit = defineEmits(['update:query', 'mode', 'search', 'clear', 'open', 'download'])
const sourceFilter = ref('all')
const filters = computed(() => knowledgeSourceFilters(props.items))
const rows = computed(() => knowledgeResultRows(props.items, sourceFilter.value))
const recent = computed(() => props.searched && props.lastMode === 'keyword' && !props.keyword.trim())
const inputValue = computed({ get: () => props.query, set: value => emit('update:query', value) })
const modeLabel = mode => mode === 'fusion' ? '融合检索' : '词面检索'
const sourceLabel = item => KNOWLEDGE_SOURCE_LABELS[knowledgeSourceType(item)]
const highlight = text => highlightKnowledgeText(text, props.keyword)
const scoreLabel = item => Number.isFinite(item?.score) ? Number(item.score).toFixed(4) : ''
const sourceFileName = item => item?.ext ? String(item.ext).replace(/^\./, '').toUpperCase() : ''
watch(() => props.items, () => { sourceFilter.value = 'all' })

function useExample(query) {
  emit('update:query', query)
  emit('search')
}
function clearQuery() {
  sourceFilter.value = 'all'
  emit('clear')
}
</script>

<template>
  <div class="knowledge-search-panel">
    <section class="query-panel" aria-labelledby="knowledge-query-title">
      <div class="query-heading">
        <h2 id="knowledge-query-title">从一个问题开始</h2>
      </div>
      <form class="query-form" @submit.prevent="emit('search')">
        <div class="query-input-wrap">
          <Search class="query-input-icon" aria-hidden="true" />
          <input
            v-model="inputValue"
            name="knowledge-query"
            aria-label="检索知识"
            :placeholder="mode === 'fusion' ? '描述你的问题，或输入一个知识点…' : '输入类名、术语或原文中的关键词…'"
            autocomplete="off"
            class="query-input"
          />
          <button v-if="query" class="clear-query" type="button" aria-label="清空检索，查看最近知识" @click="clearQuery"><Close aria-hidden="true" /></button>
        </div>
        <el-button class="search-submit" type="primary" native-type="submit" :loading="loading">检索<ArrowRight v-if="!loading" aria-hidden="true" /></el-button>
      </form>
      <div class="query-footer">
        <div class="query-options">
          <div class="mode-switch" role="group" aria-label="检索方式">
            <button type="button" :class="{ active: mode === 'fusion' }" :aria-pressed="mode === 'fusion'" @click="emit('mode', 'fusion')">融合检索</button>
            <button type="button" :class="{ active: mode === 'keyword' }" :aria-pressed="mode === 'keyword'" @click="emit('mode', 'keyword')">词面检索</button>
          </div>
          <span>{{ mode === 'fusion' ? '结合关键词、语义与关系，查找相关知识' : '按关键词匹配，适合定位术语与原文' }}</span>
        </div>
        <div class="query-examples">
          <span>试着搜</span>
          <button type="button" @click="useExample('ReAct 与 Plan-and-Execute 有什么区别')">ReAct 与 Plan-and-Execute</button>
          <button type="button" @click="useExample('大量字符串拼接用哪个类性能更好')">字符串拼接性能</button>
        </div>
      </div>
    </section>

    <section class="results-panel" :aria-busy="loading" aria-labelledby="knowledge-results-title">
      <div class="results-heading">
        <div>
          <h2 id="knowledge-results-title">{{ recent && !loading ? '最近知识' : '检索结果' }}<span v-if="searched && !loading && !error">{{ items.length }}</span></h2>
          <p v-if="loading" role="status">正在查找相关知识…<template v-if="items.length"> 暂时显示上次结果。</template></p>
          <p v-else-if="error">此次检索未完成，可重试或切换检索方式。</p>
          <p v-else-if="recent">留空查看最近知识；输入问题，查找更具体的内容。</p>
          <p v-else-if="searched"><template v-if="keyword">“{{ keyword }}” · </template>{{ modeLabel(lastMode) }}<template v-if="lastMode === 'fusion'"> · 按相关性排序</template></p>
          <p v-else>输入问题后，结果会显示在这里。</p>
        </div>
        <span v-if="sourceFilter !== 'all' && !error" class="filter-status">显示 {{ rows.length }} / {{ items.length }} 条</span>
      </div>

      <div v-if="items.length && !error" class="source-filters" role="group" aria-label="筛选结果来源">
        <button v-for="filter in filters" :key="filter.key" type="button" :class="{ selected: sourceFilter === filter.key }" :aria-pressed="sourceFilter === filter.key" @click="sourceFilter = filter.key">
          {{ filter.label }}<span>{{ filter.count }}</span>
        </button>
      </div>

      <div v-if="error && !loading" class="search-feedback feedback-error" role="alert">
        <strong>{{ modeLabel(lastMode) }}暂时不可用</strong>
        <p>{{ error }}</p>
        <div class="feedback-actions">
          <el-button type="primary" plain @click="emit('search')"><Refresh aria-hidden="true" />重试</el-button>
          <el-button @click="emit('mode', mode === 'fusion' ? 'keyword' : 'fusion')">切换到{{ modeLabel(mode === 'fusion' ? 'keyword' : 'fusion') }}</el-button>
        </div>
      </div>

      <div v-else-if="loading && !items.length" class="result-skeletons" aria-hidden="true">
        <div v-for="n in 3" :key="n" class="result-skeleton"><span></span><span></span><span></span></div>
      </div>

      <ol v-else-if="rows.length" class="results-list" aria-label="知识检索结果">
        <li v-for="row in rows" :key="row.key" class="result-row" :class="{ 'result-guide': isGeneratedGuide(row.item) }" :value="row.rank">
          <span class="result-rank" :title="lastMode === 'fusion' && !recent ? `原始相关性排名 ${row.rank}` : `结果 ${row.rank}`" aria-hidden="true">{{ String(row.rank).padStart(2, '0') }}</span>
          <article class="result-content">
            <div class="result-title-row">
              <span class="source-label" :class="`source-${knowledgeSourceType(row.item)}`">{{ sourceLabel(row.item) }}</span>
              <button v-if="retrievalSourcePath(row.item)" type="button" class="result-title" @click="emit('open', row.item)" v-html="highlight(retrievalTitle(row.item) || '未命名知识')"></button>
              <span v-else class="result-title unavailable" v-html="highlight(retrievalTitle(row.item) || '未命名知识')"></span>
              <button v-if="knowledgeSourceType(row.item) === 'file'" type="button" class="download-file" :aria-label="`下载${retrievalTitle(row.item) || '资料'}`" @click="emit('download', row.item)"><Download aria-hidden="true" /><span>下载</span></button>
            </div>
            <p v-if="row.item.snippet || row.item.text" class="result-snippet" v-html="highlight(retrievalSnippet(row.item, 260))"></p>
            <p v-if="isGeneratedGuide(row.item)" class="guide-note"><Reading aria-hidden="true" />模型生成的主题导览，请结合原文核对。</p>
            <div class="result-metadata">
              <span v-if="row.item.categoryName" class="result-category">{{ row.item.categoryName }}</span>
              <span v-if="sourceFileName(row.item)">{{ sourceFileName(row.item) }}<template v-if="row.item.textChars"> · {{ row.item.textChars }} 字</template></span>
              <span v-if="retrievalPosition(row.item)" class="passage-position">{{ retrievalPosition(row.item) }}</span>
              <span v-if="retrievalChannels(row.item).length" class="retrieval-channels"><span class="metadata-label">召回</span><span v-for="channel in retrievalChannels(row.item)" :key="channel" class="channel-label">{{ channel }}</span></span>
              <span v-if="scoreLabel(row.item)" class="result-score" title="检索排序分，用于比较同一次检索的结果相关性">{{ lastMode === 'fusion' ? '融合分' : '排序分' }} {{ scoreLabel(row.item) }}</span>
              <time v-if="row.item.updatedAt" :datetime="row.item.updatedAt">{{ row.item.updatedAt }}</time>
            </div>
            <details v-if="retrievalRelations(row.item).length" class="result-relations">
              <summary><Connection aria-hidden="true" />查看图谱关系<span>{{ retrievalRelations(row.item).length }}</span></summary>
              <ul><li v-for="relation in retrievalRelations(row.item)" :key="relation">{{ relation }}</li></ul>
            </details>
          </article>
        </li>
      </ol>

      <div v-else-if="searched && !loading" class="search-feedback feedback-empty" role="status">
        <span class="empty-icon"><Document aria-hidden="true" /></span>
        <strong>{{ sourceFilter !== 'all' ? '这个来源暂时没有结果' : keyword ? '没有找到相关知识' : '还没有可阅读的知识' }}</strong>
        <p>{{ sourceFilter !== 'all' ? '清除来源筛选，查看本次检索的其他结果。' : keyword ? '试试更短的关键词，或切换检索方式。' : '添加笔记、速查卡或资料后，会在这里汇集。' }}</p>
        <el-button v-if="sourceFilter !== 'all'" @click="sourceFilter = 'all'">查看全部结果</el-button>
        <el-button v-else-if="keyword" @click="emit('mode', mode === 'fusion' ? 'keyword' : 'fusion')">试试{{ modeLabel(mode === 'fusion' ? 'keyword' : 'fusion') }}</el-button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.knowledge-search-panel { display: flex; flex-direction: column; gap: 16px; min-width: 0; }
.query-panel { padding: 16px; border: 1px solid var(--app-border); border-radius: var(--radius-lg); background: var(--app-card); }
.query-heading { margin-bottom: 10px; }
.query-heading h2, .results-heading h2 { margin: 0; font-size: 16px; line-height: 1.4; font-weight: 650; letter-spacing: -.3px; }
.query-form { display: flex; align-items: stretch; gap: 8px; }
.query-input-wrap { display: flex; align-items: center; flex: 1; min-width: 0; min-height: 44px; gap: 10px; border: 1px solid var(--app-border); border-radius: var(--radius); padding: 0 12px; background: var(--app-bg); transition: border-color var(--dur-fast), box-shadow var(--dur-fast); }
.query-input-wrap:focus-within { border-color: var(--app-brand); box-shadow: 0 0 0 3px var(--app-brand-glow); }
.query-input-icon { width: 19px; height: 19px; color: var(--app-text-3); flex-shrink: 0; }
.query-input { width: 100%; min-width: 0; border: 0; outline: 0; box-shadow: none; background: transparent; color: var(--app-text-1); font: inherit; font-size: 14px; padding: 10px 0; }
.query-input::placeholder { color: var(--app-text-3); }
.query-input:focus-visible { outline: 0; }
.clear-query { display: grid; place-items: center; width: 28px; height: 28px; flex-shrink: 0; border: 0; border-radius: var(--radius); background: transparent; color: var(--app-text-3); cursor: pointer; }
.clear-query:hover { background: var(--app-border); color: var(--app-text-1); }
.clear-query svg { width: 16px; height: 16px; }
.search-submit { height: auto; min-height: 44px; min-width: 88px; padding: 0 16px; font-weight: 600; }
.search-submit svg { width: 16px; height: 16px; margin-left: 8px; }
.query-footer { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px 16px; margin-top: 10px; }
.query-options { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.query-options > span { color: var(--app-text-3); font-size: 12px; }
.mode-switch { display: flex; gap: 2px; background: var(--app-bg); border-radius: var(--radius); padding: 3px; }
.mode-switch button { border: 0; border-radius: 6px; padding: 5px 10px; background: transparent; color: var(--app-text-2); font: inherit; font-size: 12px; cursor: pointer; white-space: nowrap; }
.mode-switch button.active { color: var(--app-brand-deep); background: var(--app-card); box-shadow: var(--shadow-sm); font-weight: 600; }
.query-examples { display: flex; align-items: baseline; flex-wrap: wrap; gap: 4px 10px; font-size: 12px; color: var(--app-text-3); }
.query-examples button { color: var(--app-text-2); background: transparent; border: 0; font: inherit; padding: 2px 0; cursor: pointer; text-align: left; }
.query-examples button:hover { color: var(--app-brand-deep); text-decoration: underline; text-underline-offset: 3px; }
.results-panel { min-width: 0; }
.results-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.results-heading h2 { display: flex; gap: 10px; align-items: center; font-size: 16px; letter-spacing: 0; }
.results-heading h2 > span { font-size: 12px; font-weight: 500; color: var(--app-text-2); background: var(--app-border-weak); padding: 1px 7px; border-radius: 6px; }
.results-heading p { margin: 4px 0 0; color: var(--app-text-3); font-size: 12px; overflow-wrap: anywhere; }
.filter-status { color: var(--app-text-3); font-size: 12px; white-space: nowrap; }
.source-filters { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 16px; }
.source-filters button { display: flex; align-items: center; gap: 7px; border: 1px solid var(--app-border); background: var(--app-card); border-radius: var(--radius); color: var(--app-text-2); padding: 6px 12px; font: inherit; font-size: 12px; cursor: pointer; }
.source-filters button:hover { border-color: var(--app-brand); }
.source-filters button.selected { border-color: var(--app-brand-soft); background: var(--app-brand-soft); color: var(--app-brand-deep); font-weight: 600; }
.source-filters button span { font-size: 11px; opacity: .85; }
.results-list { list-style: none; padding: 0; margin: 0; border: 1px solid var(--app-border); border-radius: var(--radius-lg); overflow: hidden; background: var(--app-card); }
.result-row { display: flex; align-items: flex-start; gap: 16px; padding: 20px; min-width: 0; }
.result-row + .result-row { border-top: 1px solid var(--app-border-weak); }
.result-rank { display: grid; place-items: center; flex-shrink: 0; width: 28px; height: 28px; border-radius: var(--radius); background: var(--app-bg); color: var(--app-text-3); font-size: 11px; font-variant-numeric: tabular-nums; margin-top: 1px; }
.result-content { min-width: 0; flex: 1; }
.result-title-row { display: flex; align-items: baseline; flex-wrap: wrap; gap: 8px; }
.source-label { padding: 1px 6px; border-radius: 4px; font-size: 11px; font-weight: 600; flex-shrink: 0; color: var(--app-text-2); background: var(--app-bg); }
.source-note { color: var(--app-brand-deep); background: var(--app-brand-soft); }
.source-wiki { border: 1px solid var(--app-border); background: transparent; }
.result-title { border: 0; background: transparent; padding: 0; color: var(--app-text-1); font: inherit; font-size: 15px; line-height: 1.7; font-weight: 650; text-align: left; cursor: pointer; overflow-wrap: anywhere; }
.result-title:hover { color: var(--app-brand-deep); text-decoration: underline; text-underline-offset: 4px; }
.result-title.unavailable { cursor: text; }
.result-title.unavailable:hover { color: var(--app-text-1); text-decoration: none; }
.download-file { display: inline-flex; align-items: center; gap: 4px; margin-left: auto; border: 0; border-radius: 6px; background: transparent; padding: 4px 6px; font: inherit; font-size: 12px; color: var(--app-text-3); cursor: pointer; white-space: nowrap; }
.download-file svg { width: 14px; height: 14px; }
.download-file:hover { color: var(--app-brand-deep); background: var(--app-brand-soft); }
.result-snippet { color: var(--app-text-2); font-size: 14px; line-height: 1.9; margin: 8px 0 0; overflow-wrap: anywhere; }
:deep(mark) { color: var(--app-brand-deep); background: var(--app-brand-soft); border-radius: 2px; padding: 0 1px; }
.guide-note { display: flex; align-items: center; gap: 6px; color: var(--app-text-3); font-size: 12px; margin: 8px 0 0; }
.guide-note svg { width: 14px; height: 14px; flex-shrink: 0; }
.result-metadata { display: flex; align-items: center; flex-wrap: wrap; gap: 6px 12px; color: var(--app-text-3); font-size: 12px; margin-top: 12px; }
.result-category { color: var(--app-text-2); }
.passage-position { color: var(--app-text-2); }
.retrieval-channels { display: inline-flex; align-items: center; gap: 5px; flex-wrap: wrap; }
.metadata-label { color: var(--app-text-3); margin-right: 1px; }
.channel-label { font-size: 11px; border: 1px solid var(--app-border); border-radius: 4px; padding: 0 5px; color: var(--app-text-2); }
.result-score { font-variant-numeric: tabular-nums; }
.result-relations { margin-top: 10px; color: var(--app-text-2); font-size: 12px; }
.result-relations summary { cursor: pointer; width: fit-content; }
.result-relations summary svg { display: inline-block; width: 13px; height: 13px; vertical-align: -2px; margin: 0 4px 0 2px; }
.result-relations summary > span { margin-left: 7px; color: var(--app-text-3); }
.result-relations ul { display: flex; flex-direction: column; gap: 4px; list-style: none; padding: 8px 12px; margin: 8px 0 0; background: var(--app-bg); border-left: 2px solid var(--app-brand-soft); border-radius: 0 var(--radius) var(--radius) 0; overflow-wrap: anywhere; }
.search-feedback { display: flex; flex-direction: column; align-items: flex-start; padding: 32px; border: 1px solid var(--app-border); border-radius: var(--radius-lg); background: var(--app-card); }
.search-feedback strong { font-size: 15px; font-weight: 600; }
.search-feedback p { color: var(--app-text-2); margin: 8px 0 16px; overflow-wrap: anywhere; }
.feedback-error { border-color: var(--el-color-danger-light-7); }
.feedback-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.feedback-actions .el-button { margin-left: 0; }
.feedback-actions svg { width: 14px; height: 14px; margin-right: 5px; }
.feedback-empty { align-items: center; text-align: center; padding: 40px 24px; }
.empty-icon { color: var(--app-text-3); width: 36px; height: 36px; margin-bottom: 12px; }
.result-skeletons { border: 1px solid var(--app-border); border-radius: var(--radius-lg); overflow: hidden; background: var(--app-card); }
.result-skeleton { padding: 24px; display: flex; flex-direction: column; gap: 12px; }
.result-skeleton + .result-skeleton { border-top: 1px solid var(--app-border-weak); }
.result-skeleton span { height: 12px; border-radius: 4px; background: var(--app-border-weak); }
.result-skeleton span:first-child { width: 42%; height: 18px; }
.result-skeleton span:last-child { width: 70%; }
@media (max-width: 600px) {
  .query-panel { padding: 14px 12px; }
  .query-input-wrap { padding: 0 10px; min-height: 44px; gap: 6px; }
  .query-input { font-size: 14px; }
  .search-submit { min-width: 64px; min-height: 44px; padding: 0 12px; }
  .search-submit svg { display: none; }
  .query-options { gap: 8px; }
  .query-options > span { width: 100%; }
  .results-heading { align-items: flex-start; gap: 8px; }
  .result-row { padding: 16px 12px; gap: 10px; }
  .result-rank { width: 24px; height: 24px; font-size: 10px; }
  .result-title { font-size: 14px; }
  .result-snippet { font-size: 13px; }
  .download-file { padding: 3px; }
  .download-file > span { display: none; }
  .result-metadata { gap: 6px 8px; }
  .feedback-actions { justify-content: flex-start; }
  .search-feedback { padding: 24px 16px; }
}
</style>
