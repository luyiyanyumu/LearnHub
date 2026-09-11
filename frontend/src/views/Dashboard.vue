<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { statsApi } from '../api'

const router = useRouter()
const loading = ref(false)
const stats = ref({
  noteTotal: 0,
  refTotal: 0,
  categoryTotal: 0,
  tagTotal: 0,
  categoryStats: [],
  recentNotes: [],
})

const cards = [
  {
    key: 'noteTotal',
    label: '笔记',
    icon: 'M7 3.5h7a3 3 0 0 1 3 3V20.5H7a3 3 0 0 1-3-3v-11a3 3 0 0 1 3-3Z M10 8h4M10 11.5h4M10 15h2',
  },
  {
    key: 'refTotal',
    label: '速查卡',
    icon: 'M13 2.5 5 10.5V21.5h14V2.5h-6Zm0 0v7h6M8.5 14h7M8.5 17h5',
  },
  {
    key: 'categoryTotal',
    label: '分类',
    icon: 'M3.5 7.5h7v13h-7zM13.5 3.5h7v17h-7zM3.5 3.5h4M20.5 3.5h0M6 11v1.5M16.5 11v1.5M16.5 7v1',
  },
  {
    key: 'tagTotal',
    label: '标签',
    icon: 'M11 3.5 20.5 13 13 20.5 3.5 11V3.5H11Zm-3.5 3.2a1 1 0 1 0 0-2 1 1 0 0 0 0 2Z',
  },
]

async function load() {
  loading.value = true
  try {
    stats.value = await statsApi.dashboard()
  } finally {
    loading.value = false
  }
}

/** 问候语带日期：比一句固定的口号更像「为你而做」的工具 */
const greeting = computed(() => {
  const now = new Date()
  const h = now.getHours()
  const greet = h < 6 ? '夜深了' : h < 12 ? '早上好' : h < 18 ? '下午好' : '晚上好'
  const week = '日一二三四五六'.charAt(now.getDay())
  return `${greet}。${now.getMonth() + 1} 月 ${now.getDate()} 日 · 周${week}，今天也积累一点点。`
})

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="head">
      <div>
        <h2 class="page-h2">总览</h2>
        <p class="head-sub">{{ greeting }}</p>
      </div>
    </div>

    <div class="stat-grid">
      <el-card v-for="c in cards" :key="c.key" shadow="never" class="stat-card" @click="router.push(c.key === 'noteTotal' ? '/notes' : c.key === 'refTotal' ? '/refs' : c.key === 'categoryTotal' ? '/notes' : '/notes')">
        <div class="stat-icon">
          <svg viewBox="0 0 24 24" width="21" height="21" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
            <path :d="c.icon" />
          </svg>
        </div>
        <div class="stat-body">
          <div class="stat-num">{{ stats[c.key] }}</div>
          <div class="stat-label">{{ c.label }}</div>
        </div>
        <div class="stat-glow"></div>
      </el-card>
    </div>

    <div class="lower">
      <el-card shadow="never" class="lower-card">
        <template #header>
          <div class="card-head">
            <span class="card-title">最近更新的笔记</span>
            <el-link type="primary" @click="router.push('/notes')">查看全部</el-link>
          </div>
        </template>
        <el-table :data="stats.recentNotes" size="small" @row-click="(row) => router.push(`/notes/${row.id}`)" class="recent-table">
          <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
          <el-table-column prop="categoryName" label="分类" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.categoryName" size="small" effect="plain" class="cat-tag">{{ row.categoryName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="updatedAt" label="更新时间" width="170">
            <template #default="{ row }">
              <span class="time-cell">{{ (row.updatedAt || '').replace('T', ' ').slice(0, 16) }}</span>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="还没有笔记" :image-size="70" />
          </template>
        </el-table>
      </el-card>

      <el-card shadow="never" class="lower-card dist">
        <template #header>
          <span class="card-title">分类分布</span>
        </template>
        <div v-for="s in stats.categoryStats" :key="s.id" class="cat-row">
          <span class="cat-name">{{ s.name }}</span>
          <div class="cat-bar-wrap">
            <div class="cat-bar" :style="{ width: Math.min(100, (s.noteCount + s.refCount) * 18) + '%' }"></div>
          </div>
          <span class="cat-count">{{ s.noteCount + s.refCount }}</span>
        </div>
        <el-empty v-if="!stats.categoryStats.length" description="暂无分类" :image-size="60" />
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 20px;
}

.head-sub {
  margin: 5px 0 0;
  font-size: 13px;
  color: var(--app-text-3);
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 16px;
}

.stat-card {
  cursor: pointer;
  position: relative;
  overflow: hidden;
}

.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md) !important;
  border-color: color-mix(in srgb, var(--app-brand) 22%, var(--app-border));
}

.stat-card :deep(.el-card__body) {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 20px;
  position: relative;
  z-index: 1;
}

.stat-icon {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: var(--app-brand-soft);
  color: var(--app-brand-deep);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: transform var(--dur) var(--ease);
}

.stat-card:hover .stat-icon {
  transform: scale(1.06);
}

.stat-num {
  font-size: 30px;
  font-weight: 700;
  letter-spacing: -0.03em;
  line-height: 1;
  color: var(--app-text-1);
  font-variant-numeric: tabular-nums;
}

.stat-label {
  font-size: 12.5px;
  color: var(--app-text-2);
  margin-top: 6px;
}

.stat-glow {
  position: absolute;
  right: -34px;
  top: -34px;
  width: 96px;
  height: 96px;
  border-radius: 50%;
  /* 刻意比 --app-brand-glow 更淡：四张卡各挂一个圆，重了就像模板贴花 */
  background: color-mix(in srgb, var(--app-brand) 7%, transparent);
  transition: transform var(--dur-slow) var(--ease);
}

.stat-card:hover .stat-glow {
  transform: scale(1.35);
}

.lower {
  display: grid;
  grid-template-columns: 3fr 2fr;
  gap: 16px;
}

.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--app-text-1);
}

.recent-table {
  cursor: pointer;
}

.cat-tag {
  --el-tag-text-color: var(--app-text-2);
  --el-tag-bg-color: var(--app-bg);
  --el-tag-border-color: var(--app-border);
}

.time-cell {
  font-size: 12.5px;
  color: var(--app-text-3);
  font-variant-numeric: tabular-nums;
}

.cat-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 13px;
  font-size: 13px;
}

.cat-name {
  width: 78px;
  flex-shrink: 0;
  color: var(--app-text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cat-bar-wrap {
  flex: 1;
  height: 6px;
  background: var(--app-code-bg);
  border-radius: 99px;
  overflow: hidden;
}

.cat-bar {
  height: 100%;
  background: linear-gradient(90deg, var(--app-brand), var(--app-brand-deep));
  border-radius: 99px;
  transition: width 0.5s var(--ease);
  min-width: 4px;
}

.cat-count {
  width: 20px;
  flex-shrink: 0;
  text-align: right;
  font-size: 13px;
  font-weight: 600;
  color: var(--app-text-1);
  font-variant-numeric: tabular-nums;
}

@media (max-width: 1100px) {
  .stat-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .lower {
    grid-template-columns: 1fr;
  }
}
</style>
