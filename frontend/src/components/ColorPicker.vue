<script setup>
import { computed, ref, watch } from 'vue'
import { hexToRgb, parseColor, rgbToCss, rgbToHex } from '../utils/richFormat'

const props = defineProps({
  /** 预设色板 */
  palette: { type: Array, default: () => [] },
  /** 打开时的初始色（hex 或 rgb 字符串） */
  initial: { type: String, default: '#f5222d' },
  /** 无障碍标题 */
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

/** 预设色：直接应用 */
function pickPreset(color) {
  const c = parseColor(color)
  if (c) rgb.value = c
  emit('pick', color)
}

/** 自定义色：按当前 RGB 应用 */
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
    <div class="cp-title">{{ title }}</div>

    <div class="swatches">
      <button
        v-for="c in palette"
        :key="c"
        class="sw"
        :style="{ background: c }"
        :title="c"
        type="button"
        @click="pickPreset(c)"
      />
    </div>

    <div class="cp-custom">
      <div class="cp-preview" :style="{ background: css }" />
      <div class="cp-fields">
        <label class="cp-row">
          <span>R</span>
          <el-slider v-model="rgb.r" :min="0" :max="255" size="small" />
          <em>{{ rgb.r }}</em>
        </label>
        <label class="cp-row">
          <span>G</span>
          <el-slider v-model="rgb.g" :min="0" :max="255" size="small" />
          <em>{{ rgb.g }}</em>
        </label>
        <label class="cp-row">
          <span>B</span>
          <el-slider v-model="rgb.b" :min="0" :max="255" size="small" />
          <em>{{ rgb.b }}</em>
        </label>
      </div>
    </div>

    <div class="cp-foot">
      <el-input v-model="hex" size="small" class="hex-input" />
      <input type="color" :value="hex" class="native" title="系统取色器" @input="onNative" />
      <el-button type="primary" size="small" @click="applyCustom">应用</el-button>
    </div>
    <div class="cp-css">{{ css }}</div>
  </div>
</template>

<style scoped>
.color-picker {
  width: 268px;
}

.cp-title {
  font-size: 12px;
  color: var(--app-text-3);
  margin-bottom: 8px;
}

.swatches {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 6px;
  margin-bottom: 12px;
}

.sw {
  width: 100%;
  aspect-ratio: 1;
  border: 1px solid var(--app-border);
  border-radius: 5px;
  cursor: pointer;
  padding: 0;
  transition: transform 0.12s ease;
}

.sw:hover {
  transform: scale(1.12);
  border-color: var(--app-brand);
}

.cp-custom {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.cp-preview {
  width: 40px;
  height: 40px;
  flex: none;
  border-radius: 8px;
  border: 1px solid var(--app-border);
}

.cp-fields {
  flex: 1;
  min-width: 0;
}

.cp-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  color: var(--app-text-3);
}

.cp-row span {
  width: 10px;
  flex: none;
}

.cp-row :deep(.el-slider) {
  flex: 1;
  margin-right: 6px;
}

.cp-row em {
  font-style: normal;
  width: 26px;
  text-align: right;
  flex: none;
  font-variant-numeric: tabular-nums;
}

.cp-foot {
  display: flex;
  align-items: center;
  gap: 8px;
}

.hex-input {
  width: 92px;
}

.native {
  width: 30px;
  height: 30px;
  padding: 0;
  border: 1px solid var(--app-border);
  border-radius: 6px;
  background: transparent;
  cursor: pointer;
}

.cp-css {
  margin-top: 8px;
  font-size: 11px;
  font-family: ui-monospace, Consolas, monospace;
  color: var(--app-text-3);
  text-align: center;
}
</style>
