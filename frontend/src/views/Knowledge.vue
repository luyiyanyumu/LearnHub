<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { knowledgeApi } from '../api'

/**
 * 知识库：跨「笔记 + 速查卡」的统一检索入口。
 * kw 为空时后端返回最近知识 —— 页面空态也有内容可看。
 */
const router = useRouter()

const kw = ref('')
const loading = ref(false)
const searched = ref(false) // 是否已经搜过（区分「首屏最近知识」和「搜了没结果」）
const items = ref([])
const keyword = ref('')

async function doSearch() {
  loading.value = true
  try {
    const res = await knowledgeApi.search(kw.value)
    items.value = res.items || []
    keyword.value = res.keyword || ''
    searched.value = true
  } finally {
    loading.value = false
  }
}

const noteItems = () => items.value.filter((i) => i.type === 'note')
const refItems = () => items.value.filter((i) => i.type === 'quick_ref')

function open(item) {
  if (item.type === 'note') {
    router.push(`/notes/${item.id}`)
  } else {
    router.push('/refs')
  }
}

/** 把关键词高亮成 <mark>：先整体转义再替换，避免用户输入被当 HTML 执行 */
function hl(text) {
  const safe = String(text ?? '')
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  const k = keyword.value.trim()
  if (!k) return safe
  const pattern = k.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return safe.replace(new RegExp(pattern, 'gi'), (m) => `<mark>${m}</mark>`)
}

function clearSearch() {
  kw.value = ''
  doSearch()
}

onMounted(doSearch)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="head">
      <div>
        <h2 class="page-h2">知识库</h2>
        <p class="head-sub">笔记与速查卡，一处检索。</p>
      </div>
    </div>

    <div class="search-card">
      <el-input
        v-model="kw"
        class="search-input"
        placeholder="搜索笔记、速查卡…（留空看最近知识）"
        clearable
        @keyup.enter="doSearch"
        @clear="clearSearch"
      >
        <template #suffix>
          <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
            <circle cx="11" cy="11" r="7" />
            <path d="m20 20-3.8-3.8" />
          </svg>
        </template>
      </el-input>
      <el-button type="primary" @click="doSearch">检索</el-button>
    </div>

    <template v-if="noteItems().length || refItems().length">
      <section v-if="noteItems().length" class="group">
        <h3 class="group-title">笔记<span class="count">{{ noteItems().length }}</span></h3>
        <div
          v-for="it in noteItems()"
          :key="'n' + it.id"
          class="kitem"
          @click="open(it)"
        >
          <div class="kitem-main">
            <div class="kitem-title" v-html="hl(it.title)"></div>
            <div class="kitem-snippet" v-html="hl(it.snippet)"></div>
          </div>
          <div class="kitem-meta">
            <span v-if="it.categoryName" class="ktag">{{ it.categoryName }}</span>
            <span class="ktime">{{ it.updatedAt }}</span>
          </div>
        </div>
      </section>

      <section v-if="refItems().length" class="group">
        <h3 class="group-title">速查卡<span class="count">{{ refItems().length }}</span></h3>
        <div
          v-for="it in refItems()"
          :key="'r' + it.id"
          class="kitem"
          @click="open(it)"
        >
          <div class="kitem-main">
            <div class="kitem-title" v-html="hl(it.title)"></div>
            <div class="kitem-snippet" v-html="hl(it.snippet)"></div>
          </div>
          <div class="kitem-meta">
            <span v-if="it.categoryName" class="ktag">{{ it.categoryName }}</span>
            <span class="ktime">{{ it.updatedAt }}</span>
          </div>
        </div>
      </section>
    </template>

    <el-empty
      v-else-if="searched && !loading"
      :description="keyword ? `没有与「${keyword}」相关的知识` : '工作台还是空的，先写一篇笔记吧'"
    />
  </div>
</template>

<style scoped>
.search-card {
  display: flex;
  gap: 10px;
  margin-bottom: 26px;
}
.search-input {
  flex: 1;
}
.search-input :deep(.el-input__wrapper) {
  border-radius: var(--radius);
  box-shadow: 0 0 0 1px var(--app-border) inset;
}
.search-input :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1.5px var(--el-color-primary) inset;
}

.group {
  margin-bottom: 26px;
}
.group-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--app-text-3);
  margin: 0 0 10px 2px;
}
.group-title .count {
  font-weight: 500;
  font-size: 11px;
  color: var(--app-text-3);
  background: var(--app-brand-soft);
  border-radius: 99px;
  padding: 1px 8px;
}

.kitem {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 14px 16px;
  background: var(--app-card);
  border: 1px solid var(--app-border-weak);
  border-radius: var(--radius);
  cursor: pointer;
  transition:
    border-color var(--dur-fast) var(--ease),
    transform var(--dur-fast) var(--ease),
    box-shadow var(--dur-fast) var(--ease);
}
.kitem + .kitem {
  margin-top: 8px;
}
.kitem:hover {
  border-color: color-mix(in srgb, var(--app-brand) 22%, var(--app-border));
  transform: translateY(-1px);
  box-shadow: var(--shadow-sm);
}
.kitem:active {
  transform: scale(0.995);
}

.kitem-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--app-text-1);
  letter-spacing: -0.01em;
}
.kitem-snippet {
  margin-top: 4px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--app-text-2);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.kitem :deep(mark) {
  background: color-mix(in srgb, var(--app-brand) 16%, transparent);
  color: var(--app-brand-deep);
  border-radius: 3px;
  padding: 0 1px;
}

.kitem-meta {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 10px;
}
.ktag {
  font-size: 12px;
  color: var(--app-text-2);
  background: var(--app-bg);
  border: 1px solid var(--app-border-weak);
  border-radius: 6px;
  padding: 2px 8px;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ktime {
  font-size: 12px;
  color: var(--app-text-3);
  font-variant-numeric: tabular-nums;
}

html.dark .kitem :deep(mark) {
  color: var(--app-brand);
}
</style>
