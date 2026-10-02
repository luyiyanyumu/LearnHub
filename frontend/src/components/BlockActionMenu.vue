<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  addActionBlockBelow, canIndentActionBlock, convertActionBlock, deleteActionBlock,
  findActionBlock, getBlockActionSupport, indentActionBlock, serializeActionBlock,
} from '../utils/blockActions'
import { createBlockId, isBlockId } from '../utils/blockMeta'
import { previewHtmlToMd } from '../utils/htmlToMd'
import { createBlockTarget, mapBlockTarget } from '../utils/blockTarget'

const props = defineProps({ editor: { type: Object, required: true }, host: Object, linkBase: { type: String, default: '' } })
const emit = defineEmits(['outline'])
const active = ref(null)
const open = ref(false)
const submenu = ref('')
const grip = ref(null)
const menu = ref(null)
const clipboardBusy = ref(false)
const box = reactive({ top: 0, left: 0, width: 0, height: 0 })
const position = reactive({ left: 0, top: 0, flip: false, maxHeight: 600 })
let leaveTimer = 0
let clipboardTarget = null

const types = [
  { key: 'paragraph', label: '正文', icon: 'T' },
  ...Array.from({ length: 6 }, (_, i) => ({ key: `h${i + 1}`, label: `${['一', '二', '三', '四', '五', '六'][i]}级标题`, icon: `H${i + 1}` })),
  { key: 'bulletList', label: '无序列表', icon: '•' },
  { key: 'orderedList', label: '有序列表', icon: '1.' },
  { key: 'blockquote', label: '引用', icon: '“' },
  { key: 'codeBlock', label: '代码块', icon: '</>' },
  { key: 'callout', label: '提示块', icon: '!' },
  { key: 'details', label: '折叠块', icon: '▸' },
]
const additions = [...types, { key: 'horizontalRule', label: '分隔线', icon: '—' }, { key: 'table', label: '表格', icon: '▦' }]
const kind = computed(() => {
  const node = active.value && props.editor.state.doc.nodeAt(active.value.pos)
  return node?.type.name === 'heading' ? `H${node.attrs.level}`
    : ({ paragraph: '正文', listItem: '列表', codeBlock: '代码', blockquote: '引用', table: '表格', callout: '提示', details: '折叠' })[node?.type.name] || '块'
})
const canLink = computed(() => {
  const node = active.value && props.editor.state.doc.nodeAt(active.value.pos)
  return !!node && Object.prototype.hasOwnProperty.call(node.attrs, 'blockId')
})

function locate(item) {
  if (!item?.dom || !props.host) return
  const rect = item.dom.getBoundingClientRect()
  const host = props.host.getBoundingClientRect()
  Object.assign(box, { top: rect.top - host.top, left: rect.left - host.left, width: rect.width, height: rect.height })
}
function choose(item) {
  if (!item) return
  const target = createBlockTarget(props.editor.state.doc, item.pos)
  if (!target) return
  active.value = { ...target, dom: item.dom }
  locate(item)
}
function hover(event) {
  clearTimeout(leaveTimer)
  if (open.value || event.target.closest?.('.block-action-grip')) return
  choose(findActionBlock(props.editor.view, event.target))
}
function leave() {
  clearTimeout(leaveTimer)
  if (open.value) return
  leaveTimer = setTimeout(() => { if (!open.value && !props.editor.isFocused) active.value = null }, 180)
}
function close(focusGrip = false) {
  open.value = false
  submenu.value = ''
  if (focusGrip) nextTick(() => grip.value?.focus())
}
async function toggle(event) {
  if (open.value) { close(); return }
  const rect = grip.value?.getBoundingClientRect()
  if (!rect) return
  position.left = Math.max(8, Math.min(rect.left, window.innerWidth - 248))
  position.top = Math.max(8, Math.min(rect.bottom + 7, window.innerHeight - 380))
  position.flip = position.left + 490 > window.innerWidth
  open.value = true
  submenu.value = ''
  await nextTick()
  const menuRect = menu.value?.getBoundingClientRect()
  if (menuRect && menuRect.bottom > window.innerHeight - 8) position.top = Math.max(8, window.innerHeight - menuRect.height - 8)
  position.maxHeight = Math.max(120, window.innerHeight - position.top - 16)
  if (event?.detail === 0 || event?.key === 'ArrowDown') menu.value?.querySelector('button')?.focus()
}
function supported(type) {
  return active.value && getBlockActionSupport(props.editor, active.value.pos, type)
}
function canIndent(direction) {
  return active.value && canIndentActionBlock(props.editor, active.value.pos, direction)
}
function mutate(action, value) {
  if (!active.value) return
  const pos = active.value.pos
  close()
  const ok = action === 'convert' ? convertActionBlock(props.editor, pos, value)
    : action === 'add' ? addActionBlockBelow(props.editor, pos, value)
      : action === 'indent' ? indentActionBlock(props.editor, pos, value)
        : deleteActionBlock(props.editor, pos)
  if (!ok) { ElMessage.info('这个块暂不支持此操作'); return }
  props.editor.view.focus()
  selectionChanged()
}
async function copy(cut = false) {
  if (!active.value || clipboardBusy.value) return
  const pos = active.value.pos
  const original = props.editor.state.doc.nodeAt(pos)
  const payload = serializeActionBlock(props.editor, pos)
  if (!payload) return
  const target = createBlockTarget(props.editor.state.doc, pos)
  clipboardTarget = target
  clipboardBusy.value = true
  // 复制产生新块，不能继承原段落的永久链接；剪切则保留链接。
  const html = cut ? payload.html : payload.html.replace(/\s+id="block-[^"]*"/g, '')
  const text = previewHtmlToMd(html) || payload.text
  try {
    await writeClipboard(text, html)
    if (cut) {
      const now = !target.deleted && !props.editor.isDestroyed && props.editor.state.doc.nodeAt(target.pos)
      if (!sameBlock(now, original)) { ElMessage.warning('段落已改变，未剪切，请重试'); return }
      deleteActionBlock(props.editor, target.pos)
      props.editor.view.focus()
    }
    close()
    ElMessage.success(cut ? '已剪切段落' : '已复制段落')
  } catch { ElMessage.warning('剪贴板不可用，段落已保留') }
  finally { clipboardBusy.value = false; if (clipboardTarget === target) clipboardTarget = null }
}
function sameBlock(a, b) {
  if (!a || !b || a.type !== b.type || !a.content.eq(b.content)) return false
  const attrs = node => Object.fromEntries(Object.entries(node.attrs).filter(([key]) => key !== 'dataLine'))
  return JSON.stringify(attrs(a)) === JSON.stringify(attrs(b))
}
async function writeClipboard(text, html) {
  if (navigator.clipboard?.write && typeof ClipboardItem !== 'undefined') {
    try {
      const data = { 'text/plain': new Blob([text], { type: 'text/plain' }) }
      if (html) data['text/html'] = new Blob([html], { type: 'text/html' })
      await navigator.clipboard.write([new ClipboardItem(data)])
      return
    } catch { /* Browsers without rich clipboard support can still copy plain text. */ }
  }
  await navigator.clipboard.writeText(text)
}
async function copyLink() {
  if (!active.value || clipboardBusy.value) return
  if (!props.linkBase) { ElMessage.info('保存笔记后即可复制段落链接'); return }
  const pos = active.value.pos
  const node = props.editor.state.doc.nodeAt(pos)
  if (!node || !canLink.value) return
  const blockId = isBlockId(node.attrs.blockId) ? node.attrs.blockId : createBlockId()
  const target = createBlockTarget(props.editor.state.doc, pos)
  clipboardTarget = target
  clipboardBusy.value = true
  try {
    await writeClipboard(`${props.linkBase.split('#')[0]}#${blockId}`)
    const current = !target.deleted && !props.editor.isDestroyed && props.editor.state.doc.nodeAt(target.pos)
    if (!sameBlock(current, node)) { ElMessage.info('段落已改变，请重新复制链接'); return }
    if (!node.attrs.blockId) {
      props.editor.view.dispatch(props.editor.state.tr.setNodeMarkup(target.pos, undefined, { ...current.attrs, blockId }))
    }
    close()
    ElMessage.success(node.attrs.blockId ? '已复制段落链接' : '已复制段落链接，保存笔记后生效')
  } catch { ElMessage.warning('剪贴板不可用') }
  finally { clipboardBusy.value = false; if (clipboardTarget === target) clipboardTarget = null }
}
function outline() {
  const node = active.value && props.editor.state.doc.nodeAt(active.value.pos)
  if (!node) return
  const text = node.type.name === 'codeBlock' ? node.attrs.code : node.textContent
  close()
  if (!text?.trim()) { ElMessage.info('先写点内容，再使用大纲写作法'); return }
  emit('outline', text)
}
function selectionChanged() {
  if (open.value) return
  const view = props.editor.view
  const { from } = props.editor.state.selection
  choose(findActionBlock(view, view.domAtPos(from).node))
}
function transactionChanged({ transaction }) {
  if (clipboardTarget) mapBlockTarget(clipboardTarget, transaction)
  if (!active.value || !transaction.docChanged) return
  mapBlockTarget(active.value, transaction)
  const node = props.editor.state.doc.nodeAt(active.value.pos)
  if (!node || active.value.deleted) { active.value = null; close(); return }
  const dom = props.editor.view.nodeDOM(active.value.pos)
  if (dom?.nodeType === 1) choose({ pos: active.value.pos, dom })
  nextTick(() => { if (active.value) locate(active.value) })
}
function outside(event) {
  if (menu.value?.contains(event.target) || grip.value?.contains(event.target)) return
  close()
}
function menuKeydown(event) {
  if (event.key === 'Escape') { event.preventDefault(); close(true); return }
  if (event.key === 'ArrowLeft' && submenu.value) { event.preventDefault(); submenu.value = ''; return }
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const scope = event.target.closest('.block-action-submenu') || menu.value
  const buttons = [...scope.querySelectorAll('button:not(:disabled)')].filter(el => el.closest('.block-action-submenu') === (scope.classList.contains('block-action-submenu') ? scope : null))
  const index = buttons.indexOf(document.activeElement)
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1
    : (index + (event.key === 'ArrowUp' ? -1 : 1) + buttons.length) % buttons.length
  buttons[next]?.focus()
}
function scrollChanged() { close(); if (active.value) locate(active.value) }
let scroller = null
onMounted(() => {
  props.editor.on('selectionUpdate', selectionChanged)
  props.editor.on('transaction', transactionChanged)
  document.addEventListener('pointerdown', outside, true)
  window.addEventListener('resize', scrollChanged)
  scroller = props.host?.closest('.pv-scroll')
  scroller?.addEventListener('scroll', scrollChanged, { passive: true })
})
onBeforeUnmount(() => {
  if (clipboardTarget) clipboardTarget.deleted = true
  props.editor.off('selectionUpdate', selectionChanged)
  props.editor.off('transaction', transactionChanged)
  document.removeEventListener('pointerdown', outside, true)
  window.removeEventListener('resize', scrollChanged)
  scroller?.removeEventListener('scroll', scrollChanged)
  clearTimeout(leaveTimer)
})
defineExpose({ hover, leave, close })
</script>

<template>
  <template v-if="active">
    <div class="block-action-highlight" :style="{ top: `${box.top}px`, left: `${box.left}px`, width: `${box.width}px`, height: `${box.height}px` }" />
    <button ref="grip" class="block-action-grip" type="button" aria-label="段落操作" title="段落操作" aria-haspopup="menu" :aria-expanded="open"
      :style="{ top: `${box.top}px`, left: `${Math.max(0, box.left - 36)}px` }" @mousedown.prevent @click.stop="toggle" @keydown.esc="close" @keydown.down.prevent="toggle">
      <svg viewBox="0 0 24 24" aria-hidden="true"><circle v-for="(point, i) in [[9,5],[15,5],[9,12],[15,12],[9,19],[15,19]]" :key="i" :cx="point[0]" :cy="point[1]" r="1.6" /></svg>
    </button>
  </template>
  <Teleport to="body">
    <div v-if="open && active" ref="menu" class="block-action-menu" role="menu" aria-label="段落操作菜单"
      :style="{ top: `${position.top}px`, left: `${position.left}px`, '--submenu-height': `${position.maxHeight}px` }" @mousedown.prevent @click.stop @keydown="menuKeydown">
      <button type="button" role="menuitem" :aria-expanded="submenu === 'convert'" aria-haspopup="menu" @click="submenu = submenu === 'convert' ? '' : 'convert'">
        <svg viewBox="0 0 24 24"><path d="M20 8a8 8 0 0 0-14-3L3 8m0-5v5h5M4 16a8 8 0 0 0 14 3l3-3m0 5v-5h-5" /></svg><span>转化为</span><small>{{ kind }}</small><b>›</b>
      </button>
      <div class="block-action-divider" />
      <button type="button" role="menuitem" @click="outline"><span class="block-action-ai">AI</span><span>大纲写作法</span></button>
      <div class="block-action-divider" />
      <button type="button" role="menuitem" @click="mutate('delete')"><svg viewBox="0 0 24 24"><path d="M4 7h16M9 7V4h6v3M7 7l1 13h8l1-13M10 11v5M14 11v5" /></svg><span>删除</span></button>
      <button type="button" role="menuitem" :disabled="clipboardBusy" @click="copy(false)"><svg viewBox="0 0 24 24"><rect x="9" y="3" width="12" height="14" rx="2" /><path d="M15 17v4H3V7h6" /></svg><span>复制</span></button>
      <button type="button" role="menuitem" :disabled="clipboardBusy" @click="copy(true)"><svg viewBox="0 0 24 24"><circle cx="6" cy="6" r="3" /><circle cx="6" cy="18" r="3" /><path d="m8.5 7.5 12 12M8.5 16.5 21 4" /></svg><span>剪切</span></button>
      <div class="block-action-divider" />
      <button type="button" role="menuitem" :aria-expanded="submenu === 'indent'" aria-haspopup="menu" @click="submenu = submenu === 'indent' ? '' : 'indent'">
        <svg viewBox="0 0 24 24"><path d="M3 4h18M10 9h11M10 15h11M3 20h18m0-12 3 4-3 4" /></svg><span>缩进</span><b>›</b>
      </button>
      <button type="button" role="menuitem" :disabled="!canLink || clipboardBusy" :title="canLink ? '' : '正文和标题支持段落链接'" @click="copyLink">
        <svg viewBox="0 0 24 24"><path d="M8 4H4v16h16v-4M11 13l6-6M14 4h6v6" /></svg><span>复制链接</span>
      </button>
      <div class="block-action-divider" />
      <button type="button" role="menuitem" :aria-expanded="submenu === 'add'" aria-haspopup="menu" @click="submenu = submenu === 'add' ? '' : 'add'">
        <svg viewBox="0 0 24 24"><path d="M12 3v18M3 12h18" /></svg><span>在下方添加</span><b>›</b>
      </button>
      <div v-if="submenu" class="block-action-submenu" :class="{ flip: position.flip }" role="menu" :aria-label="submenu === 'convert' ? '转化为' : submenu === 'add' ? '在下方添加' : '缩进'">
        <template v-if="submenu === 'indent'">
          <button type="button" role="menuitem" :disabled="!canIndent(1)" @click="mutate('indent', 1)"><span class="block-type-icon">⇥</span>增加缩进</button>
          <button type="button" role="menuitem" :disabled="!canIndent(-1)" @click="mutate('indent', -1)"><span class="block-type-icon">⇤</span>减少缩进</button>
        </template>
        <template v-else>
          <button v-for="type in submenu === 'add' ? additions : types" :key="type.key" type="button" role="menuitem"
            :disabled="submenu === 'convert' && !supported(type.key)" @click="mutate(submenu === 'add' ? 'add' : 'convert', type.key)">
            <span class="block-type-icon">{{ type.icon }}</span>{{ type.label }}
          </button>
        </template>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.block-action-highlight { position: absolute; pointer-events: none; background: color-mix(in srgb, var(--app-brand) 7%, transparent); border-radius: 4px; z-index: 0; }
.block-action-grip { position: absolute; display: grid; place-items: center; width: 28px; height: 28px; border: 0; border-radius: 7px; background: color-mix(in srgb, var(--app-text-1) 7%, var(--app-card)); color: var(--app-text-2); cursor: pointer; z-index: 3; padding: 5px; }
.block-action-grip svg { width: 18px; height: 18px; fill: currentColor; }
.block-action-grip:hover { color: var(--app-text-1); background: color-mix(in srgb, var(--app-text-1) 11%, var(--app-card)); }
.block-action-grip:focus-visible, .block-action-menu button:focus-visible { outline: 2px solid var(--app-brand); outline-offset: -2px; }
.block-action-menu { position: fixed; z-index: 2300; width: 240px; border: 1px solid var(--app-border); border-radius: 12px; background: var(--app-card); color: var(--app-text-1); padding: 7px 0; box-shadow: 0 12px 40px #0002, 0 2px 6px #0001; font: 14px/1.4 var(--font-sans, system-ui, sans-serif); }
.block-action-menu button { display: flex; align-items: center; gap: 13px; width: 100%; min-height: 40px; padding: 9px 19px; background: transparent; border: 0; color: inherit; font: inherit; text-align: left; cursor: pointer; }
.block-action-menu button:hover { background: var(--app-brand-soft); }
.block-action-menu button:disabled { opacity: .4; cursor: default; background: transparent; }
.block-action-menu svg { width: 19px; height: 19px; flex: none; stroke: currentColor; stroke-width: 1.65; stroke-linecap: round; stroke-linejoin: round; fill: none; }
.block-action-menu small { margin-left: auto; color: var(--app-text-3); font-size: 12px; }
.block-action-menu b { margin-left: auto; font-size: 21px; line-height: 18px; font-weight: 400; }
.block-action-menu small + b { margin-left: -6px; }
.block-action-divider { height: 1px; background: var(--app-border); margin: 5px 0; opacity: .7; }
.block-action-ai { display: grid; place-items: center; width: 19px; height: 19px; flex: none; border: 1.5px solid currentColor; border-radius: 6px; font-size: 10px; font-weight: 650; }
.block-action-submenu { position: absolute; left: calc(100% + 8px); top: 0; width: 218px; max-height: min(610px, var(--submenu-height)); overflow-y: auto; border: 1px solid var(--app-border); border-radius: 12px; background: var(--app-card); padding: 6px 0; box-shadow: 0 12px 40px #0002; }
.block-action-submenu.flip { left: auto; right: calc(100% + 8px); }
.block-type-icon { display: inline-grid; place-items: center; width: 26px; flex: none; font: 600 13px/1.2 ui-monospace, monospace; color: var(--app-text-2); }
@media (max-width: 560px) { .block-action-submenu, .block-action-submenu.flip { left: 12px; right: 12px; top: 44px; width: auto; max-height: calc(100vh - 100px); } }
</style>
