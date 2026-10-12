<script setup>
import { nextTick, ref, watch } from 'vue'
import { Close, Document, Menu, Plus } from '@element-plus/icons-vue'

const props = defineProps({
  tabs: { type: Array, default: () => [] },
  activeKey: { type: String, default: '' },
  closingKey: { type: String, default: '' },
})
const emit = defineEmits(['select', 'close', 'new', 'list'])
const tabButtons = new Map()
const tabStrip = ref(null)

function setButton(key, element) {
  if (element) tabButtons.set(key, element)
  else tabButtons.delete(key)
}

async function revealActiveTab(key) {
  await nextTick()
  if (!key || props.activeKey !== key) return
  tabButtons.get(key)?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' })
}

watch(() => props.activeKey, revealActiveTab, { flush: 'post', immediate: true })

function selectTab(key) {
  const alreadyActive = props.activeKey === key
  emit('select', key)
  // A changed key is revealed by the watcher, including external navigation.
  if (alreadyActive) return revealActiveTab(key)
}

function navigateTab(event, index) {
  const length = props.tabs.length
  if (!length || !['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? length - 1 : (index + (event.key === 'ArrowRight' ? 1 : -1) + length) % length
  const key = props.tabs[next].key
  selectTab(key)
  tabButtons.get(key)?.focus()
}
</script>

<template>
  <div class="note-tabs-bar" aria-label="打开的笔记">
    <button class="tabs-action list-action" :class="{ active: !activeKey }" :aria-current="!activeKey ? 'page' : undefined" type="button" title="笔记列表" aria-label="返回笔记列表" @click="emit('list')">
      <Menu class="tabs-icon" aria-hidden="true" />
      <span>笔记</span>
    </button>
    <div ref="tabStrip" class="note-tabs-strip" role="tablist" aria-label="笔记标签页">
      <div v-for="(tab, index) in tabs" :key="tab.key" class="note-tab" :class="{ active: activeKey === tab.key }">
        <button
          :ref="element => setButton(tab.key, element)"
          class="note-tab-select"
          type="button"
          role="tab"
          :id="`note-tab-${encodeURIComponent(tab.key)}`"
          :aria-controls="`note-panel-${encodeURIComponent(tab.key)}`"
          :aria-selected="activeKey === tab.key"
          :tabindex="activeKey === tab.key || (!activeKey && index === 0) ? 0 : -1"
          :title="`${tab.title || '未命名笔记'}${tab.dirty ? ' · 未保存' : ''}`"
          @click="selectTab(tab.key)"
          @keydown="navigateTab($event, index)"
        >
          <Document class="tab-document" aria-hidden="true" />
          <span v-if="tab.dirty" class="tab-dirty" aria-label="未保存"></span>
          <span class="tab-title">{{ tab.title || '未命名笔记' }}</span>
        </button>
        <button class="tab-close" type="button" :disabled="closingKey === tab.key" :aria-label="`关闭 ${tab.title || '未命名笔记'}`" :title="closingKey === tab.key ? '正在关闭' : '关闭笔记'" @click="emit('close', tab.key)">
          <Close class="close-icon" aria-hidden="true" />
        </button>
      </div>
    </div>
    <button class="tabs-action new-action" type="button" title="打开新笔记" aria-label="打开新笔记" @click="emit('new')">
      <Plus class="tabs-icon" aria-hidden="true" />
    </button>
  </div>
</template>

<style scoped>
.note-tabs-bar {
  display: flex;
  align-items: center;
  height: 52px;
  flex: 0 0 52px;
  min-width: 0;
  padding: 0 16px;
  gap: 12px;
  background: var(--app-side);
  border-bottom: 1px solid var(--app-border);
}
.note-tabs-strip {
  display: flex;
  align-items: center;
  flex: 1;
  min-width: 0;
  height: 100%;
  gap: 6px;
  overflow-x: auto;
  scrollbar-width: thin;
}
.note-tab {
  display: flex;
  align-items: center;
  position: relative;
  flex: 0 0 auto;
  min-width: 120px;
  max-width: 240px;
  height: 34px;
  padding: 0 5px 0 8px;
  border: 1px solid transparent;
  border-radius: var(--radius);
  color: var(--app-text-2);
  transition: color var(--dur-fast) ease, background-color var(--dur-fast) ease, border-color var(--dur-fast) ease;
}
.note-tab:hover { background: var(--app-bg); }
.note-tab.active {
  color: var(--app-brand-deep);
  background: color-mix(in srgb, var(--app-brand-soft) 65%, var(--app-card));
  border-color: color-mix(in srgb, var(--app-brand) 18%, transparent);
}
.note-tab.active .tab-title { font-weight: 600; }
.note-tab-select {
  display: flex;
  align-items: center;
  gap: 7px;
  flex: 1;
  min-width: 0;
  height: 100%;
  padding: 0 4px;
  text-align: left;
  font-size: 12px;
}
.tab-title {
  display: block;
  max-width: 175px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.tab-document { flex: 0 0 14px; width: 14px; height: 14px; opacity: .7; }
.tab-dirty { flex: 0 0 5px; width: 5px; height: 5px; background: var(--app-brand); border-radius: 50%; }
.note-tabs-bar :where(button) { color: inherit; font-family: inherit; border: 0; background: transparent; cursor: pointer; }
.note-tabs-bar button:focus-visible { outline: 2px solid var(--app-brand); outline-offset: -2px; border-radius: var(--radius); }
.tabs-action {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  flex: 0 0 auto;
  height: 32px;
  padding: 0 8px;
  border-radius: var(--radius);
  color: var(--app-text-2) !important;
  font-size: 12px;
  transition: color var(--dur-fast) ease, background-color var(--dur-fast) ease;
}
.list-action { position: relative; margin-right: 6px; }
.list-action.active { color: var(--app-brand-deep) !important; background: var(--app-brand-soft); font-weight: 600; }
.list-action::after { content: ''; position: absolute; right: -9px; width: 1px; height: 18px; background: var(--app-border); }
.tabs-action:hover { color: var(--app-brand-deep) !important; background: var(--app-brand-soft); }
.tabs-icon { width: 15px; height: 15px; }
.new-action { width: 32px; padding: 0; }
.tab-close {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 22px;
  width: 22px;
  height: 22px;
  opacity: .6;
  padding: 0;
  border-radius: var(--radius-sm);
}
.close-icon { width: 12px; height: 12px; }
.tab-close:hover { opacity: 1; background: color-mix(in srgb, var(--app-text-2) 10%, transparent); }
.tab-close:disabled { cursor: wait; opacity: .3; }
@media (max-width: 680px) {
  .note-tabs-bar { height: 48px; flex-basis: 48px; padding: 0 8px; gap: 6px; }
  .list-action { margin-right: 2px; padding: 0 5px; }
  .list-action::after { right: -4px; }
  .list-action span { display: none; }
  .note-tab { min-width: 112px; max-width: 185px; }
  .tab-title { max-width: 120px; }
}
</style>
