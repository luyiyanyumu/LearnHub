<script setup>
import { computed, ref, watch } from 'vue'
import { hexToRgb, parseColor, rgbToCss, rgbToHex } from '../utils/richFormat'

const props = defineProps({
  /** 预设色板 */
  palette: { type: Array, default: () => [] },
  /** 打开时的初始色（hex 或 rgb 字符串） */
  initial: { type: String, default: '#f5222d' },
  /** 标题（如「文字颜色」「背景颜色」） */
  title: { type: String, default: '选择颜色' },
})
const emit = defineEmits(['pick'])

const rgb = ref(hexToRgb(props.initial) || { r: 245, g: 34, b: 45 })

watch(
  () => props.initial,
  (v) => {
    const c = parseColor(v)
    if (c) rgb.value = c
  },
)

const hex = computed({
  get: () => rgbToHex(rgb.value.r, rgb.value.g, rgb.value.b),
  set: (v) => {
    const c = parseColor(v)
    if (c) rgb.value = c
  },
})

const css = computed(() => rgbToCss(rgb.value.r, rgb.value.g, rgb.value.b))

/**
 * 当前色板里哪一格是「正在使用的颜色」。
 * 之前只有 hover 反馈，选中后无从判断用的是哪一个 —— 逐个比对 RGB 值补上这个状态。
 */
function isActive(color) {
  const c = parseColor(color)
  if (!c) return false
  return c.r === rgb.value.r && c.g === rgb.value.g && c.b === rgb.value.b
}

/** 预设色：直接应用 */
function pickPreset(color) {
  const c = parseColor(color)
  if (c) rgb.value = c
  emit('pick', color)
}

/** 自定义色：拖动 RGB 滑块 / 输入 hex 后即时应用（无需「应用」按钮） */
function applyCustom() {
  emit('pick', css.value)
}

function onNative(e) {
  const c = parseColor(e.target.value)
  if (c) rgb.value = c
  emit('pick', rgbToCss(rgb.value.r, rgb.value.g, rgb.value.b))
}
</script>

<template>
  <div class="color-picker">
    <!-- 标题行：左侧标题 + 右侧当前色（色点 + hex），取代原来那块 40px 大方块 -->
    <div class="cp-head">
      <span class="cp-name">{{ title }}</span>
      <span class="cp-now">
        <i class="cp-dot" :style="{ background: css }" />
        <code class="cp-hex">{{ hex }}</code>
      </span>
    </div>

    <div class="swatches">
      <button
        v-for="c in palette"
        :key="c"
        class="sw"
        :class="{ on: isActive(c) }"
        :style="{ background: c }"
        :title="c"
        type="button"
        @click="pickPreset(c)"
      />
    </div>

    <!-- 自定义：三条刻度并排，数值右对齐等宽（原实现里数值离滑块太远） -->
    <div class="cp-fields">
      <label class="cp-row">
        <span class="cp-key">R</span>
        <el-slider v-model="rgb.r" :min="0" :max="255" size="small" :show-tooltip="false" @change="applyCustom" />
        <em>{{ rgb.r }}</em>
      </label>
      <label class="cp-row">
        <span class="cp-key">G</span>
        <el-slider v-model="rgb.g" :min="0" :max="255" size="small" :show-tooltip="false" @change="applyCustom" />
        <em>{{ rgb.g }}</em>
      </label>
      <label class="cp-row">
        <span class="cp-key">B</span>
        <el-slider v-model="rgb.b" :min="0" :max="255" size="small" :show-tooltip="false" @change="applyCustom" />
        <em>{{ rgb.b }}</em>
      </label>
    </div>

    <!-- 底部：hex 输入 + 系统取色器（原生 color 输入藏在图标按钮下，不再裸露一个红方块） -->
    <div class="cp-foot">
      <el-input v-model="hex" size="small" class="cp-input" placeholder="#ff0000" @change="applyCustom" />
      <label class="cp-native" :title="`系统取色器 · ${css}`">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M15.5 3.5a2.1 2.1 0 0 1 3 3L17 8l-1-1-1.5 1.5 3 3L15 14l-3-3-5.5 5.5a2 2 0 0 1-.6.4L3 18l1.1-2.9c.1-.2.2-.4.4-.6L10 9 7 6l2.5-2.5 3 3L14 5l-1-1 2.5-.5Z" />
        </svg>
        <input type="color" :value="hex" title="系统取色器" @input="onNative" />
      </label>
    </div>

    <p class="cp-hint">点色块、拖刻度或填 hex 都会即时应用</p>
  </div>
</template>

<style scoped>
.color-picker {
  width: 100%;
}

/* ---- 标题行 ---- */
.cp-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
}

.cp-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--app-text-1);
}

.cp-now {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

/* 当前色：小色点（带描边，浅色也能看出边界）+ 等宽 hex */
.cp-dot {
  width: 14px;
  height: 14px;
  flex: none;
  border-radius: 4px;
  border: 1px solid color-mix(in srgb, var(--app-text-1) 18%, transparent);
}

.cp-hex {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 12px;
  color: var(--app-text-2);
  letter-spacing: 0.02em;
}

/* ---- 色板 ---- */
.swatches {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 6px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--app-border-weak);
}

.sw {
  width: 100%;
  aspect-ratio: 1;
  padding: 0;
  border: 1px solid color-mix(in srgb, var(--app-text-1) 14%, transparent);
  border-radius: 6px;
  cursor: pointer;
  transition: box-shadow var(--dur-fast) ease, transform var(--dur-fast) ease;
}

.sw:hover {
  transform: scale(1.1);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--app-brand) 45%, transparent);
}

/* 当前色：内外双环，压在任意底色上都看得出来（原来是完全没有选中态） */
.sw.on {
  transform: scale(1.06);
  box-shadow: 0 0 0 2px var(--app-card), 0 0 0 4px var(--app-brand);
}

/* ---- RGB 刻度 ---- */
.cp-fields {
  padding: 10px 0 12px;
  border-bottom: 1px solid var(--app-border-weak);
}

.cp-row {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 24px;
}

.cp-key {
  width: 12px;
  flex: none;
  font-size: 11px;
  font-weight: 600;
  color: var(--app-text-2);
}

.cp-row :deep(.el-slider) {
  flex: 1;
  min-width: 0;
}

/* 细轨道 + 小滑块：默认的粗轨道在这个紧凑面板里显得笨重 */
.cp-row :deep(.el-slider__runway) {
  height: 4px;
  margin: 0;
  background: color-mix(in srgb, var(--app-text-1) 10%, transparent);
}

.cp-row :deep(.el-slider__bar) {
  height: 4px;
}

.cp-row :deep(.el-slider__button) {
  width: 12px;
  height: 12px;
  border-width: 2px;
}

.cp-row em {
  width: 28px;
  flex: none;
  font-style: normal;
  font-size: 12px;
  text-align: right;
  color: var(--app-text-2);
  font-variant-numeric: tabular-nums; /* 数值跳动时不左右晃 */
}

/* ---- 底部：hex + 系统取色器 ---- */
.cp-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-top: 12px;
}

.cp-input {
  flex: 1;
  min-width: 0;
}

.cp-input :deep(.el-input__inner) {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  text-transform: uppercase; /* 只改显示，存储仍是小写，两种写法都能被解析 */
}

/* 原生取色器：视觉上是一个图标按钮，真实 input 透明铺满其上 */
.cp-native {
  position: relative;
  width: 28px;
  height: 28px;
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--app-border);
  border-radius: 6px;
  cursor: pointer;
  color: var(--app-text-2);
  transition: border-color var(--dur-fast) ease, color var(--dur-fast) ease;
}

.cp-native:hover {
  border-color: var(--app-brand);
  color: var(--app-brand-deep);
}

.cp-native svg {
  width: 15px;
  height: 15px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.cp-native input {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: 0;
  border: 0;
  padding: 0;
  cursor: pointer;
}

/* ---- 提示 ---- */
.cp-hint {
  margin: 8px 0 0;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--app-text-2);
}
</style>
