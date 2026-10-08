<script setup>
import { computed, ref, useId } from 'vue'
import { Close, Delete, Search } from '@element-plus/icons-vue'
import { WIKI_TOPIC_STATES, WIKI_TOPIC_TYPES, wikiTopicCountLabel, wikiTopicState, wikiTopicView } from '../utils/wikiTopicView.js'

const props = defineProps({
  topics: { type: Array, default: () => [] },
  activeTopic: { type: String, default: '' },
  loading: { type: Boolean, default: false },
  deletingKey: { type: String, default: '' },
})
const emit = defineEmits(['select', 'delete'])
const id = `wiki-nav-${useId()}`
const query = ref('')
const type = ref('all')
const state = ref('all')
const view = computed(() => wikiTopicView(props.topics, { query: query.value, type: type.value, state: state.value }))
const stateLabels = Object.fromEntries(WIKI_TOPIC_STATES.map(item => [item.value, item.label]))

function resetFilters() {
  query.value = ''
  type.value = 'all'
  state.value = 'all'
}
</script>

<template>
  <nav class="wiki-navigator" aria-label="Wiki 知识页" :aria-busy="loading">
    <div class="navigator-controls">
      <div class="navigator-title">
        <h3>知识页</h3>
        <span class="total-count">{{ view.totalCount }}</span>
      </div>
      <p class="navigator-summary">{{ view.generatedCount }} 页已生成<span v-if="view.counts.stale"> · {{ view.counts.stale }} 页待更新</span></p>
      <div class="navigator-search">
        <Search class="control-icon" aria-hidden="true" />
        <label class="visually-hidden" :for="`${id}-query`">搜索知识页标题</label>
        <input :id="`${id}-query`" v-model="query" type="search" placeholder="搜索知识页标题" autocomplete="off" />
        <button v-if="query" type="button" class="clear-query" aria-label="清空知识页搜索" @click="query = ''"><Close aria-hidden="true" /></button>
      </div>
      <div class="navigator-filters">
        <div class="filter-field">
          <label :for="`${id}-type`">页面类型</label>
          <select :id="`${id}-type`" v-model="type">
            <option value="all">全部类型</option>
            <option v-for="option in WIKI_TOPIC_TYPES" :key="option.value" :value="option.value">{{ option.label }}</option>
          </select>
        </div>
        <div class="filter-field">
          <label :for="`${id}-state`">生成状态</label>
          <select :id="`${id}-state`" v-model="state">
            <option value="all">全部状态</option>
            <option v-for="option in WIKI_TOPIC_STATES" :key="option.value" :value="option.value">{{ option.label }} · {{ view.counts[option.value] }}</option>
          </select>
        </div>
      </div>
      <div v-if="view.hasFilter" class="filter-summary" aria-live="polite">
        <span>匹配 {{ view.visibleCount }} / {{ view.totalCount }} 个主题</span>
        <button type="button" @click="resetFilters">重置筛选</button>
      </div>
    </div>

    <div class="navigator-list">
      <p v-if="loading && !view.totalCount" class="navigator-message" role="status">正在加载知识页…</p>
      <section v-for="group in view.groups" :key="group.type" class="topic-group" :aria-labelledby="`${id}-${group.type}`">
        <h4 :id="`${id}-${group.type}`" class="group-title"><span>{{ group.label }}</span><span class="group-count">{{ group.items.length }}</span></h4>
        <ul>
          <li v-for="topic in group.items" :key="topic.topicKey" class="topic-row" :class="{ selected: topic.topicKey === activeTopic, deleting: topic.topicKey === deletingKey }">
            <button type="button" class="topic-open" :aria-current="topic.topicKey === activeTopic ? 'page' : undefined" @click="emit('select', topic.topicKey)">
              <span class="topic-title">{{ topic.title || topic.topicKey }}</span>
              <span class="topic-meta">
                <span class="topic-state" :class="wikiTopicState(topic)"><span class="state-dot" aria-hidden="true" />{{ stateLabels[wikiTopicState(topic)] }}</span>
                <span v-if="wikiTopicCountLabel(topic)" class="topic-material">{{ wikiTopicCountLabel(topic) }}</span>
              </span>
            </button>
            <button type="button" class="topic-delete" :disabled="!!deletingKey || !topic.generated" :aria-label="`删除「${topic.title || topic.topicKey}」知识页`" :title="topic.generated ? '删除这一页' : '尚未生成知识页'" @click="emit('delete', topic)">
              <span v-if="topic.topicKey === deletingKey" class="deleting-spinner" aria-hidden="true" />
              <Delete v-else aria-hidden="true" />
            </button>
          </li>
        </ul>
      </section>
      <div v-if="!loading && !view.visibleCount" class="navigator-empty">
        <h4>{{ view.totalCount ? '没有匹配的知识页' : '还没有知识页' }}</h4>
        <p>{{ view.totalCount ? '换一个关键词，或减少筛选条件。' : '分类、标签和编译后的实体页会出现在这里。' }}</p>
        <button v-if="view.hasFilter" type="button" class="empty-reset" @click="resetFilters">显示全部主题</button>
      </div>
    </div>
  </nav>
</template>

<style scoped>
.wiki-navigator { display: flex; flex-direction: column; min-width: 0; min-height: 0; height: 100%; color: var(--app-text-1); }
.navigator-controls { padding: 20px 16px 16px; border-bottom: 1px solid var(--app-border); }
.navigator-title { display: flex; align-items: center; gap: 8px; }
.navigator-title h3 { margin: 0; font-size: 16px; font-weight: 650; line-height: 1.5; }
.total-count { padding: 1px 7px; border-radius: 6px; background: var(--app-bg); color: var(--app-text-2); font-size: 12px; font-variant-numeric: tabular-nums; }
.navigator-summary { margin: 4px 0 16px; color: var(--app-text-2); font-size: 12px; line-height: 1.6; }
.navigator-search { display: flex; align-items: center; gap: 8px; min-height: 40px; padding: 0 10px; border: 1px solid var(--app-border); border-radius: var(--radius); background: var(--app-card); }
.navigator-search:focus-within { border-color: var(--app-brand); box-shadow: 0 0 0 3px var(--app-brand-glow); }
.control-icon { flex: 0 0 16px; width: 16px; height: 16px; color: var(--app-text-3); }
.navigator-search input { flex: 1; width: 100%; min-width: 0; border: 0; outline: none; padding: 8px 0; color: var(--app-text-1); background: transparent; font: inherit; font-size: 13px; }
.navigator-search input::placeholder { color: var(--app-text-3); }
.navigator-search input::-webkit-search-cancel-button { display: none; }
.clear-query { display: grid; place-items: center; flex: 0 0 24px; width: 24px; height: 28px; border: 0; border-radius: 4px; background: transparent; color: var(--app-text-2); cursor: pointer; }
.clear-query svg { width: 14px; height: 14px; }
.clear-query:hover { background: var(--app-bg); }
.navigator-filters { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 12px; }
.filter-field { min-width: 0; }
.filter-field label { display: block; margin-bottom: 4px; color: var(--app-text-2); font-size: 12px; }
.filter-field select { width: 100%; min-width: 0; height: 36px; padding: 0 6px; border: 1px solid var(--app-border); border-radius: var(--radius); background: var(--app-card); color: var(--app-text-1); font: inherit; font-size: 12px; cursor: pointer; }
.filter-summary { display: flex; justify-content: space-between; align-items: center; gap: 8px; margin-top: 12px; font-size: 12px; color: var(--app-text-2); }
.filter-summary button, .empty-reset { border: 0; padding: 4px 0; color: var(--app-brand-deep); background: transparent; font: inherit; font-size: 12px; cursor: pointer; }
.filter-summary button:hover, .empty-reset:hover { text-decoration: underline; }
.navigator-list { flex: 1; min-height: 0; overflow-y: auto; overscroll-behavior: contain; padding: 12px 8px 16px; scrollbar-gutter: stable; }
.topic-group + .topic-group { margin-top: 20px; }
.group-title { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin: 0 8px 8px; color: var(--app-text-2); font-size: 12px; font-weight: 600; }
.group-count { font-weight: 400; font-variant-numeric: tabular-nums; }
.topic-group ul { list-style: none; margin: 0; padding: 0; }
.topic-row { display: flex; align-items: center; min-width: 0; border: 1px solid transparent; border-radius: var(--radius); }
.topic-row + .topic-row { margin-top: 4px; }
.topic-row:hover, .topic-row:focus-within { background: var(--app-bg); }
.topic-row.selected { border-color: color-mix(in srgb, var(--app-brand) 24%, var(--app-border)); background: var(--app-brand-soft); }
.topic-open { flex: 1; display: flex; flex-direction: column; gap: 7px; min-width: 0; padding: 10px 8px; border: 0; border-radius: var(--radius); text-align: left; color: inherit; background: transparent; font: inherit; cursor: pointer; }
.topic-title { color: var(--app-text-1); font-size: 13px; font-weight: 550; line-height: 1.6; overflow-wrap: anywhere; }
.topic-row.selected .topic-title { color: var(--app-brand-deep); font-weight: 650; }
.topic-meta { display: flex; flex-wrap: wrap; align-items: center; column-gap: 9px; row-gap: 4px; color: var(--app-text-2); font-size: 12px; line-height: 1.5; }
.topic-state { display: inline-flex; align-items: center; gap: 4px; white-space: nowrap; }
.state-dot { width: 5px; height: 5px; flex: 0 0 5px; border-radius: 50%; background: var(--app-text-3); }
.topic-state.generated .state-dot { background: var(--app-brand); }
.topic-state.stale { color: #a56405; }
.topic-state.stale .state-dot { background: currentColor; }
:global(html.dark) .topic-state.stale { color: #efbb6c; }
.topic-material { overflow-wrap: anywhere; }
.topic-delete { flex: 0 0 30px; display: grid; place-items: center; width: 30px; height: 34px; margin: 0 4px 0 0; padding: 0; border: 0; border-radius: 6px; color: var(--app-text-3); background: transparent; cursor: pointer; opacity: 0; }
.topic-delete svg { width: 15px; height: 15px; }
.topic-row:hover .topic-delete, .topic-row:focus-within .topic-delete, .topic-row.deleting .topic-delete { opacity: 1; }
.topic-delete:hover:not(:disabled) { background: color-mix(in srgb, var(--el-color-danger) 10%, var(--app-card)); color: var(--el-color-danger); }
.topic-delete:disabled { cursor: default; opacity: 0.35; }
.deleting-spinner { width: 13px; height: 13px; border: 2px solid var(--app-border); border-top-color: var(--app-brand); border-radius: 50%; animation: wiki-nav-spin 0.7s linear infinite; }
.navigator-empty { padding: 32px 12px; text-align: center; }
.navigator-empty h4 { margin: 0 0 8px; font-size: 13px; color: var(--app-text-1); }
.navigator-empty p, .navigator-message { margin: 0; font-size: 12px; line-height: 1.8; color: var(--app-text-2); }
.navigator-message { padding: 24px 12px; text-align: center; }
.empty-reset { margin-top: 12px; }
.visually-hidden { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
button:focus-visible, select:focus-visible { outline: 2px solid var(--app-brand); outline-offset: 2px; }
@keyframes wiki-nav-spin { to { transform: rotate(360deg); } }
@media (hover: none) { .topic-delete { opacity: 1; } }
@media (prefers-reduced-motion: reduce) { .deleting-spinner { animation: none; } }
@media (max-width: 720px) { .navigator-controls { padding: 16px; } .navigator-list { max-height: 340px; } }
</style>
