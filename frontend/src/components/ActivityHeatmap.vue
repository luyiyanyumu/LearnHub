<script setup>
import { computed } from 'vue'
import { buildActivityCalendar } from '../utils/activityCalendar'

const props = defineProps({
  activity: {
    type: Object,
    default: () => ({ year: new Date().getFullYear(), total: 0, activeDays: 0, max: 0, days: [] }),
  },
  year: { type: Number, required: true },
  years: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  error: { type: Boolean, default: false },
})
const emit = defineEmits(['year-change', 'retry'])

const weekdayLabels = ['一', '', '三', '', '五', '', '']
const calendar = computed(() => buildActivityCalendar(
  props.year, props.activity?.days || [], Number(props.activity?.max) || 0,
))

function cellTitle(cell) {
  if (cell.outside) return `${cell.date}：相邻年份，不计入 ${props.year} 年记录`
  return `${cell.date}：${cell.count ? `${cell.count} 条记录` : '无记录'}`
}

function selectYear(year) {
  if (year === props.year) return
  emit('year-change', year)
}
</script>

<template>
  <section class="activity-card" aria-labelledby="activity-title">
    <div class="activity-header">
      <div>
        <h3 id="activity-title" class="activity-title">
          <template v-if="loading">正在加载学习记录</template>
          <template v-else-if="error">学习记录</template>
          <template v-else>{{ activity?.total || 0 }} 条学习记录</template>
          <span>· {{ year }} 年</span>
        </h3>
        <p class="activity-subtitle">记录笔记、速查卡、资料和智能体学习活动</p>
      </div>
      <div v-if="!loading && !error" class="activity-summary">
        <strong>{{ activity?.activeDays || 0 }}</strong> 天有记录
      </div>
    </div>

    <div v-if="error" class="activity-error" role="alert">
      学习记录暂时加载失败
      <button type="button" @click="emit('retry')">重新加载</button>
    </div>
    <div class="activity-layout" :class="{ 'is-loading': loading }" :aria-busy="loading">
      <div class="activity-board" :style="{ '--activity-weeks': calendar.weekCount }" aria-label="年度学习记录热力图">
        <div class="activity-months" aria-hidden="true">
          <span v-for="label in calendar.labels" :key="label.month" :style="{ gridColumn: label.week + 1 }">{{ label.month }}</span>
        </div>
        <div class="activity-chart">
          <div class="activity-weekdays" aria-hidden="true">
            <span v-for="(label, index) in weekdayLabels" :key="index">{{ label }}</span>
          </div>
          <div class="activity-grid" role="img" :aria-label="`${year} 年每天的学习记录，${activity?.activeDays || 0} 个活跃日`">
            <span
              v-for="cell in calendar.cells"
              :key="cell.key"
              class="activity-cell"
              :class="[`level-${cell.level}`, { outside: cell.outside }]"
              :title="cellTitle(cell)"
            />
          </div>
        </div>
        <div class="activity-footer">
          <span class="activity-rule" title="同一笔记或速查卡每天计 1 次，资料上传和智能体提问按条计数">同一笔记 / 速查卡每天计 1 次</span>
          <div class="activity-legend" aria-hidden="true">
            <span>少</span>
            <i class="activity-cell level-0" />
            <i class="activity-cell level-1" />
            <i class="activity-cell level-2" />
            <i class="activity-cell level-3" />
            <i class="activity-cell level-4" />
            <span>多</span>
          </div>
        </div>
      </div>

      <div class="activity-years" role="group" aria-label="学习记录年份选择">
        <button
          v-for="item in years"
          :key="item"
          type="button"
          :aria-pressed="item === year"
          :class="{ active: item === year }"
          @click="selectYear(item)"
        >{{ item }}</button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.activity-card {
  margin-bottom: 16px;
  padding: 20px 22px 18px;
  border: 1px solid var(--app-border);
  border-radius: 12px;
  background: var(--app-card);
  box-shadow: var(--shadow-sm);
}

.activity-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.activity-title {
  margin: 0;
  color: var(--app-text-1);
  font-size: 18px;
  font-weight: 650;
  letter-spacing: -0.02em;
}

.activity-title span,
.activity-subtitle,
.activity-summary {
  color: var(--app-text-3);
  font-size: 12.5px;
  font-weight: 400;
}

.activity-subtitle {
  margin: 5px 0 0;
}

.activity-title span {
  margin-left: 4px;
}

.activity-summary {
  flex-shrink: 0;
  padding-top: 3px;
}

.activity-summary strong {
  color: var(--app-brand-deep);
  font-size: 15px;
  font-weight: 700;
}

.activity-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 68px;
  grid-template-rows: 20px auto auto;
  column-gap: 22px;
  min-height: 126px;
  transition: opacity 0.2s ease;
}

.activity-layout.is-loading {
  opacity: 0.55;
}

.activity-board {
  display: grid;
  grid-template-rows: subgrid;
  grid-column: 1;
  grid-row: 1 / -1;
  min-width: 0;
  overflow-x: auto;
  padding-bottom: 3px;
}

.activity-months {
  display: grid;
  grid-template-columns: repeat(var(--activity-weeks), minmax(0, 1fr));
  column-gap: 3px;
  min-width: 688px;
  height: 20px;
  margin-left: 32px;
  color: var(--app-text-3);
  font-size: 11px;
}

.activity-months span {
  grid-row: 1;
  white-space: nowrap;
}

.activity-chart {
  display: flex;
  min-width: 720px;
  gap: 8px;
}

.activity-weekdays {
  display: grid;
  grid-template-rows: repeat(7, 1fr);
  align-items: center;
  gap: 3px;
  width: 24px;
  flex-shrink: 0;
  color: var(--app-text-3);
  font-size: 10px;
  line-height: 13px;
  text-align: right;
}

.activity-grid {
  display: grid;
  flex: 1;
  grid-template-columns: repeat(var(--activity-weeks), minmax(0, 1fr));
  grid-template-rows: repeat(7, auto);
  grid-auto-flow: column;
  gap: 3px;
}

.activity-cell {
  display: block;
  width: 100%;
  aspect-ratio: 1;
  border: 1px solid color-mix(in srgb, var(--app-border) 78%, transparent);
  border-radius: 3px;
  box-sizing: border-box;
}

.activity-cell.level-0 {
  background: var(--app-code-bg);
}

.activity-cell.level-1 {
  border-color: color-mix(in srgb, var(--app-brand) 18%, var(--app-border));
  background: color-mix(in srgb, var(--app-brand) 22%, var(--app-card));
}

.activity-cell.level-2 {
  border-color: color-mix(in srgb, var(--app-brand) 35%, var(--app-border));
  background: color-mix(in srgb, var(--app-brand) 42%, var(--app-card));
}

.activity-cell.level-3 {
  border-color: color-mix(in srgb, var(--app-brand) 50%, var(--app-border));
  background: color-mix(in srgb, var(--app-brand) 65%, var(--app-card));
}

.activity-cell.level-4 {
  border-color: var(--app-brand-deep);
  background: var(--app-brand-deep);
}

.activity-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
  padding-left: 32px;
  min-width: 720px;
  box-sizing: border-box;
  color: var(--app-text-3);
  font-size: 11px;
}

.activity-rule {
  white-space: nowrap;
}

.activity-legend {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 4px;
  color: var(--app-text-3);
  font-size: 11px;
}

.activity-legend .activity-cell {
  width: 12px;
  height: 12px;
}

.activity-error {
  margin-bottom: 12px;
  color: var(--app-text-3);
  font-size: 12px;
}

.activity-error button {
  margin-left: 8px;
  border: 0;
  background: transparent;
  color: var(--app-brand-deep);
  cursor: pointer;
  font: inherit;
}

.activity-years {
  grid-column: 2;
  grid-row: 2;
  display: flex;
  flex-direction: column;
  gap: 3px;
  width: 68px;
  flex-shrink: 0;
}

.activity-years button {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 1;
  min-height: 0;
  border: 0;
  border-radius: 7px;
  padding: 0 9px;
  background: transparent;
  color: var(--app-text-2);
  cursor: pointer;
  font: inherit;
  font-size: 12px;
  line-height: 1.25;
  text-align: center;
  transition: background 0.15s ease, color 0.15s ease;
}

.activity-years button:hover,
.activity-years button:focus-visible {
  background: var(--app-brand-soft);
  color: var(--app-brand-deep);
  outline: none;
}

.activity-years button.active {
  background: var(--app-brand);
  color: #fff;
  font-weight: 650;
}

@media (max-width: 760px) {
  .activity-card {
    padding: 16px;
  }

  .activity-layout {
    display: block;
  }

  .activity-board {
    display: block;
  }

  .activity-years {
    flex-direction: row;
    width: auto;
    margin-top: 12px;
    overflow-x: auto;
  }

  .activity-years button {
    min-height: 32px;
    padding: 7px 9px;
  }
}
</style>
