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
          <!-- 图标保持中性色，当前色只由下方色条表达：
               原来 A 本身也染成当前色，同一个颜色表达两遍 = 双份饱和色；
               而且选白色时 A 会在浅色工具栏上直接消失 -->
          <span class="fb-color">
            <span class="fb-a">A</span>
            <i class="fb-caret" :style="{ background: lastTextColor }" />
          </span>
        </button>
      </template>
      <ColorPicker
        :palette="TEXT_COLORS"
        :initial="lastTextColor"
        title="文字颜色"
        @pick="pickText"
      />
    </el-popover>

    <!-- 背景颜色（高亮笔：笔尖吃当前色 + 一条贴着笔尖的涂抹色条） -->
    <el-popover v-model:visible="bgPop" placement="bottom-start" :width="290" trigger="click">
      <template #reference>
        <button class="fb-btn" type="button" title="背景颜色（行内高亮）">
          <span class="fb-color">
            <!--
              高亮笔图标（自绘，24 网格，stroke 1.6 与工具行其它图标同规格）。
              画法：**先画竖直的马克笔，再整体旋转 45°** —— 直接手写斜线坐标很难画准，
              实测第一版画出来像个"带水滴的方盒子"。竖直构造下三件事各自可控：
              笔身是圆角矩形、笔帽缝是一条横线、笔尖是**宽斜切**（这是高亮笔最好认的特征）。
              笔尖吃当前背景色，下面那条窄色条是它"划出来的线"，两者同色。
            -->
            <svg class="fb-hl" viewBox="0 0 24 24">
              <g transform="rotate(45 12 12)">
                <rect x="8.6" y="3.6" width="6.8" height="8.8" rx="1.8" />
                <path d="M8.6 6.6h6.8" />
                <path class="fb-nib" d="M8.6 12.4h6.8l-1.1 5.1a1.6 1.6 0 0 1-1.6 1.3h-1.4a1.6 1.6 0 0 1-1.6-1.3Z"
                  :style="{ fill: lastBgColor }" />
              </g>
            </svg>
            <i class="fb-caret" :style="{ background: lastBgColor }" />
          </span>
        </button>
      </template>
      <ColorPicker
        :palette="BG_COLORS"
        :initial="lastBgColor"
        title="背景颜色"
        @pick="pickBg"
      />
    </el-popover>

    <!-- 字号（语雀「字号 ∨」文字下拉） -->
    <el-dropdown trigger="click" @command="(v) => apply('size', v)">
      <button class="fb-btn fb-size-dd" type="button" title="字号">
        <span class="fb-size-txt">字号</span>
        <svg class="fb-dd-caret" viewBox="0 0 24 24"><path d="m7 10 5 5 5-5" /></svg>
      </button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item v-for="s in FONT_SIZES" :key="s" :command="s">{{ s }} px</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>

    <i class="fb-sep" />

    <button class="fb-btn" type="button" title="下划线" @click="apply('underline')"><u>U</u></button>
    <button class="fb-btn" type="button" title="上标" @click="apply('sup')">x<sup>2</sup></button>
    <button class="fb-btn" type="button" title="下标" @click="apply('sub')">x<sub>2</sub></button>
    <!--
      这里原来还有一个「荧光高亮」按钮（<mark>，不能选色的固定黄）—— 2026-09-30 合并掉了。
      它和「背景颜色」是同一个功能（给文字加背景色），而且它的样式完全靠浏览器默认
      （项目 CSS 与 md-editor-v3 主题里都没有 mark 规则）：亮黄底 + 强制黑字，
      暗色模式下刺眼且不受主题控制；调色板里也没有那个黄，于是同一功能有两种视觉强度。
      现在：荧光黄作为调色板的**第一个色**（#fff3a3），入口只剩一个、颜色可选、样式受控；
      历史笔记里的 <mark> 仍然由预览 CSS 渲染（见 style.css 的 mark 规则）。
    -->

    <i class="fb-sep" />

    <button class="fb-btn" type="button" title="插入折叠块" @click="apply('details')">
      <svg viewBox="0 0 24 24"><rect x="4" y="5" width="16" height="14" rx="2" /><path d="m9.5 10 2 2-2 2M13.5 14h3" /></svg>
    </button>
    <button class="fb-btn" type="button" title="插入提示块 :::tip" @click="apply('callout')">
      <svg viewBox="0 0 24 24"><path d="M5 5.5h14a1 1 0 0 1 1 1v8.5a1 1 0 0 1-1 1h-7l-3.5 3v-3H5a1 1 0 0 1-1-1V6.5a1 1 0 0 1 1-1Z" /></svg>
    </button>

    <i class="fb-sep" />

    <button class="fb-btn fb-clear" type="button" title="清除选中文字的内联格式" @click="apply('clear')">
      <svg viewBox="0 0 24 24"><path d="m14.5 5.5 4 4L10 18H6.5l-2-2 10-10.5ZM8 18h11" /></svg>
      <span class="fb-clear-txt">清格式</span>
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

/* 线性图标：与笔记编辑页 .tb svg 同规格，保证两条工具行视觉一致 */
.fb-btn svg {
  width: 16px;
  height: 16px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.fb-btn svg .fill {
  fill: currentColor;
  stroke: none;
}

/* 颜色按钮：图标 + 正下方一小段色条（当前色），整体作为一个单元居中。
   原实现是「色条 absolute 铺满按钮宽度、贴在底部」—— 3px 高 × 整宽的饱和色块，
   与 13.5px 的字形之间还空着几像素，看起来像两根脱节的横杠、也盖过了图标本身。
   现在色条贴着字形下沿，宽度略窄于字形，颜色信号清晰但不抢戏。 */
.fb-color {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 1px;
}

.fb-a {
  font-size: 13.5px; /* 与工具行里的字母按钮（B/I/S/U = 13.5px）同规格 */
  font-weight: 600;
  line-height: 1;
}

.fb-bg {
  border-radius: 3px;
  padding: 0 3px;
}

.fb-size sup {
  font-size: 9px;
  margin-left: -1px;
}

/* （这里原本有一条 .fb-mark —— 旧「荧光高亮」按钮的示例色块样式。
    该按钮 2026-09-30 合并进「背景颜色」，这条规则已无引用，一并删除。） */

/* 背景色按钮的高亮笔图标（16px：笔身 + 笔尖两段，笔尖吃当前色） */
.fb-btn svg.fb-hl {
  width: 16px;
  height: 16px;
}
/* 笔尖用当前背景色填充；浅色（如 #ffffff）时靠外面那圈描边仍然看得见形状 */
.fb-btn svg.fb-hl .fb-nib {
  stroke: currentColor;
  stroke-width: 1.6;
}

/* 字号「∨」文字下拉（语雀式） */
.fb-size-txt {
  font-size: 13px;
  color: var(--app-text-2);
}
.fb-btn svg.fb-dd-caret {
  width: 12px;
  height: 12px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
  margin-left: 1px;
  opacity: 0.7;
}

/* 色条：贴着笔尖的一条**窄涂抹线**（不是小胶囊 —— 圆角 1px、宽 14px、高 2.5px） */
.fb-caret {
  width: 14px;
  height: 2.5px;
  border-radius: 1px;
  /* 极浅色（#ffffff / #f2f2f2）在浅色工具栏上几乎没有边界，加一圈淡内描边兜底 */
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--app-text-1) 16%, transparent);
}

/* 高亮笔那格：色条紧贴笔尖（gap 0），读起来是"这支笔划出来的颜色" */
.fb-color {
  gap: 0;
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

.fb-clear-txt {
  font-size: 12px;
  line-height: 1;
}

/* ===== bare：嵌入笔记编辑页工具行时，与 .tb 按钮完全同规格 ===== */
.format-bar.bare .fb-btn {
  height: 28px;
  min-width: 28px;
  border: none;
  border-radius: 6px;
}

.format-bar.bare .fb-btn:hover {
  background: color-mix(in srgb, var(--app-text-1) 7%, transparent);
  color: var(--app-text-1);
}

.format-bar.bare .fb-btn:active {
  background: color-mix(in srgb, var(--app-text-1) 11%, transparent);
}

.format-bar.bare .fb-sep {
  margin: 0 5px;
}
</style>
