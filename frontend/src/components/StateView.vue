<script setup>
/**
 * 区块级状态视图：加载 / 空数据 / 失败 三态的**唯一出口**。
 *
 * 为什么要它：目前有些页面把"加载失败"和"没有数据"渲染成同一段空态文案
 * （典型如笔记列表失败时显示"这里还没有内容…"），用户分不清是坏了还是真的没内容。
 * 这里把三态固定成三种长得不一样、说得不一样的表达，且**失败必须给出行动**。
 *
 * 用法（区块级，替代表格/网格/列表自己的空态）：
 *   <StateView state="error" title="笔记列表加载失败" :detail="loadError"
 *              action-label="重试" @action="load" />
 * 页内小提示用 size="inline"，整块占位用默认 size="block"。
 * 只使用 style.css 里已有的 token，不新增颜色。
 */
import { computed } from 'vue'

const props = defineProps({
  /** 'loading' | 'empty' | 'error' */
  state: { type: String, required: true },
  /** 主文案：一句话说清发生了什么 */
  title: { type: String, default: '' },
  /** 次文案：影响范围或下一步 */
  detail: { type: String, default: '' },
  /** 主行动文案（如「重试」）。失败态建议必填 */
  actionLabel: { type: String, default: '' },
  /** 'block' 整块占位 / 'inline' 页内小提示（窄屏用） */
  size: { type: String, default: 'block' },
})
const emit = defineEmits(['action'])

const isLoading = computed(() => props.state === 'loading')
</script>

<template>
  <div
    class="state-view"
    :class="[`is-${state}`, `is-${size}`]"
    :role="state === 'error' ? 'alert' : 'status'"
    :aria-live="isLoading ? 'polite' : undefined"
    :aria-busy="isLoading || undefined"
  >
    <!-- 加载：骨架行，不写"加载中"大字，避免与失败态抢注意力 -->
    <div v-if="isLoading" class="sv-skeleton" aria-hidden="true">
      <span v-for="n in 3" :key="n" class="sv-line" />
    </div>
    <template v-else>
      <p class="sv-title">{{ title }}</p>
      <p v-if="detail" class="sv-detail">{{ detail }}</p>
      <div v-if="actionLabel || $slots.action" class="sv-actions">
        <el-button
          v-if="actionLabel"
          :type="state === 'error' ? 'default' : 'primary'"
          @click="emit('action')"
        >{{ actionLabel }}</el-button>
        <slot name="action" />
      </div>
    </template>
  </div>
</template>

<style scoped>
.state-view {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-xl) var(--space-md);
  color: var(--app-text-2);
  font-size: var(--font-body);
  line-height: var(--lh-body);
  text-align: center;
}
.state-view.is-inline { padding: var(--space-md); font-size: var(--font-caption); line-height: var(--lh-caption); }

.sv-title { margin: 0; color: var(--app-text-1); font-weight: 600; }
.sv-detail { margin: 0; color: var(--app-text-2); }
.state-view.is-error .sv-title { color: var(--el-color-danger); }

.sv-actions { display: flex; align-items: center; gap: var(--space-sm); margin-top: var(--space-xs); }
.state-view.is-inline .sv-actions :deep(.el-button) { height: var(--control-h-inline); }

.sv-skeleton { display: flex; flex-direction: column; gap: var(--space-sm); width: 100%; max-width: 360px; }
.sv-line {
  display: block;
  height: 14px;
  border-radius: var(--radius);
  background: var(--app-border-weak);
  animation: sv-pulse var(--dur-slow) var(--ease) infinite alternate;
}
.sv-line:nth-child(2) { width: 82%; }
.sv-line:nth-child(3) { width: 64%; }

@keyframes sv-pulse {
  from { opacity: .55; }
  to { opacity: 1; }
}

/* 尊重系统「减少动态效果」：停掉呼吸动画，静态骨架仍能表达"在加载" */
@media (prefers-reduced-motion: reduce) {
  .sv-line { animation: none; }
}
</style>
