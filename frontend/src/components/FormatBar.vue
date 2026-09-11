<script setup>
import { ref } from 'vue'
import ColorPicker from './ColorPicker.vue'
import { BG_COLORS, FONT_SIZES, TEXT_COLORS } from '../utils/richFormat'

const emit = defineEmits(['apply'])

/** bare：嵌入外部工具行时去掉自带的边框/底色/内边距（笔记编辑页顶栏用） */
defineProps({ bare: { type: Boolean, default: false } })

const textPop = ref(false)
const bgPop = ref(false)
const lastTextColor = ref('#f5222d')
const lastBgColor = ref('#dbeafe')

function apply(kind, value) {
  emit('apply', { kind, value })
}

function pickText(c) {
  lastTextColor.value = c
  apply('color', c)
  textPop.value = false
}

function pickBg(c) {
  lastBgColor.value = c
  apply('bg', c)
  bgPop.value = false
}
</script>

<template>
  <div class="format-bar" :class="{ bare }">
    <span v-if="!bare" class="fb-label">格式</span>

    <!-- 文字颜色 -->
    <el-popover v-model:visible="textPop" placement="bottom-start" :width="290" trigger="click">
      <template #reference>
        <button class="fb-btn" type="button" title="文字颜色（支持 RGB 自定义）">
          <span class="fb-a" :style="{ color: lastTextColor }">A</span>
          <i class="fb-caret" :style="{ background: lastTextColor }" />
        </button>
      </template>
      <ColorPicker
        :palette="TEXT_COLORS"
        :initial="lastTextColor"
        title="文字颜色 · 点色块即应用，或调 RGB 后点「应用」"
        @pick="pickText"
      />
    </el-popover>

    <!-- 背景颜色 -->
    <el-popover v-model:visible="bgPop" placement="bottom-start" :width="290" trigger="click">
      <template #reference>
        <button class="fb-btn" type="button" title="背景颜色（行内高亮）">
          <span class="fb-a fb-bg" :style="{ background: lastBgColor }">A</span>
        </button>
      </template>
      <ColorPicker
        :palette="BG_COLORS"
        :initial="lastBgColor"
        title="背景颜色 · 点色块即应用，或调 RGB 后点「应用」"
        @pick="pickBg"
      />
    </el-popover>

    <!-- 字号 -->
    <el-dropdown trigger="click" @command="(v) => apply('size', v)">
      <button class="fb-btn" type="button" title="字号">
        <span class="fb-a fb-size">A<sup>+</sup></span>
      </button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item v-for="s in FONT_SIZES" :key="s" :command="s">{{ s }} px</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>

    <i class="fb-sep" />

    <button class="fb-btn" type="button" title="下划线" @click="apply('underline')"><u>U</u></button>
    <button class="fb-btn" type="button" title="删除线" @click="apply('strike')"><s>S</s></button>
    <button class="fb-btn" type="button" title="上标" @click="apply('sup')">x<sup>2</sup></button>
    <button class="fb-btn" type="button" title="下标" @click="apply('sub')">x<sub>2</sub></button>
    <button class="fb-btn" type="button" title="荧光高亮" @click="apply('mark')"><mark class="fb-mark">H</mark></button>

    <i class="fb-sep" />

    <button class="fb-btn" type="button" title="整段居中" @click="apply('center')">≡</button>
    <button class="fb-btn" type="button" title="插入折叠块" @click="apply('details')">📁</button>
    <button class="fb-btn" type="button" title="插入提示块 :::tip" @click="apply('callout')">💬</button>

    <i class="fb-sep" />

    <button class="fb-btn fb-clear" type="button" title="清除选中文字的内联格式" @click="apply('clear')">
      ⌫ 清格式
    </button>
  </div>
</template>

<style scoped>
.format-bar {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
  padding: 5px 10px;
  border-bottom: 1px solid var(--app-border);
  background: var(--app-bg);
}

/* 嵌入外部工具行：只保留按钮本身 */
.format-bar.bare {
  padding: 0;
  border-bottom: none;
  background: transparent;
  flex-wrap: nowrap;
}

.fb-label {
  font-size: 12px;
  color: var(--app-text-3);
  margin-right: 4px;
}

.fb-btn {
  position: relative;
  min-width: 28px;
  height: 26px;
  padding: 0 6px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  font-size: 13px;
  line-height: 1;
  color: var(--app-text-2);
  background: transparent;
  border: 1px solid transparent;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.12s ease;
}

.fb-btn:hover {
  background: var(--app-card);
  border-color: var(--app-border);
  color: var(--app-brand-deep);
}

.fb-a {
  font-weight: 700;
  font-size: 14px;
}

.fb-bg {
  border-radius: 3px;
  padding: 0 3px;
}

.fb-size sup {
  font-size: 9px;
  margin-left: -1px;
}

.fb-mark {
  background: #fff3a3;
  color: #262626;
  padding: 0 3px;
  border-radius: 2px;
}

.fb-caret {
  width: 100%;
  height: 3px;
  border-radius: 2px;
  position: absolute;
  bottom: 1px;
  left: 0;
}

.fb-sep {
  width: 1px;
  height: 16px;
  background: var(--app-border);
  margin: 0 4px;
}

.fb-clear {
  font-size: 12px;
}
</style>
