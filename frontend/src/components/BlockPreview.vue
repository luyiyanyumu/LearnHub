<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { Editor, EditorContent } from '@tiptap/vue-3'
import { Extension, Node, mergeAttributes } from '@tiptap/core'
import { DOMParser as ProseMirrorDOMParser } from '@tiptap/pm/model'
import { NodeSelection, Selection, TextSelection } from '@tiptap/pm/state'
import { CellSelection, TableMap, cellAround } from '@tiptap/pm/tables'
import StarterKit from '@tiptap/starter-kit'
import { TaskList, TaskItem } from '@tiptap/extension-list'
import { CodeBlockCm } from '../utils/codeBlockCm'
import { Callout } from '../utils/calloutNode'
import { Details, Summary } from '../utils/detailsNode'
import { Superscript, Subscript, Highlight, FontStyle } from '../utils/inlineMarks'
import { Underline } from '@tiptap/extension-underline'
import markdownItSup from 'markdown-it-sup'
import { ElMessage } from 'element-plus'
import { hasRichHtml, sanitizePastedHtml } from '../utils/pasteHtml'
import markdownItSub from 'markdown-it-sub'
import markdownItMark from 'markdown-it-mark'
import mdDataLine from '../utils/mdDataLine'
import { DataLineAttr } from '../utils/dataLineAttr'
import { dataLineUpdates } from '../utils/dataLineRefresh'
import { TableKit } from '@tiptap/extension-table'
import MarkdownIt from 'markdown-it'
import mdCallout from '../utils/mdCallout'
import mdAnchor from '../utils/mdAnchor'
import { previewHtmlToMd } from '../utils/htmlToMd'
import { BlockMeta, isBlockId } from '../utils/blockMeta'
import { findActionBlock } from '../utils/blockActions'
import { runBlockTool, applyBlockFormat } from '../utils/blockToolbar'
import { nextTableCell } from '../utils/tableNavigation'
import { HeadingAnchorAttr, findNoteAnchor } from '../utils/headingAnchorAttr'
import BlockActionMenu from './BlockActionMenu.vue'

// Inline images keep the text on both sides of the caret in the same paragraph.
const InlineImage = Node.create({
  name: 'image', inline: true, group: 'inline', atom: true, draggable: true,
  addAttributes() { return { src: { default: null }, alt: { default: '' }, title: { default: null } } },
  parseHTML() { return [{ tag: 'img[src]' }] },
  renderHTML({ HTMLAttributes }) { return ['img', mergeAttributes(HTMLAttributes)] },
})
const ToolbarAttrs = Extension.create({
  name: 'toolbarAttrs',
  addGlobalAttributes() {
    return [
      { types: ['paragraph', 'heading'], attributes: { textAlign: {
        default: null,
        parseHTML: (el) => ['left', 'center', 'right', 'justify'].includes(el.style.textAlign) ? el.style.textAlign : null,
        renderHTML: (attrs) => attrs.textAlign ? { style: `text-align: ${attrs.textAlign}` } : {},
      } } },
      { types: ['taskList', 'taskItem'], attributes: { dataLine: {
        default: null, parseHTML: (el) => el.getAttribute('data-line'),
        renderHTML: (attrs) => attrs.dataLine != null ? { 'data-line': attrs.dataLine } : {},
      } } },
    ]
  },
})
// Turndown's task-list rule expects the checkbox to be a direct child of <li>.
const EditableTaskItem = TaskItem.extend({
  renderHTML({ node, HTMLAttributes }) {
    return ['li', mergeAttributes(HTMLAttributes, { 'data-type': 'taskItem' }),
      ['input', { type: 'checkbox', checked: node.attrs.checked ? 'checked' : null }], ['div', 0]]
  },
}).configure({ nested: true, a11y: { checkboxLabel: (node) => `待办：${node.textContent || '空白项目'}` } })

/** Excel-like Enter navigation: keep the column and move one table row. */
function moveExcelTableCell(editor, direction) {
  const $cell = cellAround(editor.state.selection.$from)
  if (!$cell) return false
  let tableDepth = -1
  for (let depth = $cell.depth; depth > 0; depth--) {
    if ($cell.node(depth).type.name === 'table') { tableDepth = depth; break }
  }
  if (tableDepth < 0) return false
  const table = $cell.node(tableDepth)
  const tableStart = $cell.start(tableDepth)
  const map = TableMap.get(table)
  const cellOffset = $cell.pos - tableStart
  const cellIndex = map.map.indexOf(cellOffset)
  if (cellIndex < 0) return false
  const target = nextTableCell({
    row: Math.floor(cellIndex / map.width),
    col: cellIndex % map.width,
    width: map.width,
    height: map.height,
  }, direction)
  if (!target) return false
  const targetPos = tableStart + map.positionAt(target.row, target.col, table)
  const targetCell = editor.state.doc.nodeAt(targetPos)
  const firstBlock = targetCell?.firstChild
  const transaction = editor.state.tr
  if (firstBlock?.isTextblock && firstBlock.content.size > 0) {
    const from = targetPos + 2
    transaction.setSelection(TextSelection.create(editor.state.doc, from, from + firstBlock.content.size))
  } else {
    transaction.setSelection(Selection.near(editor.state.doc.resolve(targetPos + 1), 1))
  }
  transaction.scrollIntoView()
  editor.view.dispatch(transaction)
  return true
}

function isCellSelectionValue(selection) {
  return selection instanceof CellSelection || Boolean(selection?.$anchorCell && selection?.$headCell)
}

function readTableContext(ed) {
  if (!ed || ed.isDestroyed) return null
  const selection = ed.state.selection
  const isCellSelection = isCellSelectionValue(selection)
  // A plain text caret in a table is for editing and only keeps the toolbar
  // visible while the editor still has focus. CellSelection remains visible
  // after a drag/Shift-click so the range can be acted on from the toolbar.
  const toolbarHasFocus = document.activeElement?.closest?.('.block-table-toolbar')
  if (!ed.view.hasFocus() && !toolbarHasFocus) return null
  const $cell = isCellSelection ? selection.$anchorCell : cellAround(selection.$from)
  if (!$cell) return null
  let tableDepth = -1
  for (let depth = $cell.depth; depth > 0; depth--) {
    if ($cell.node(depth).type.name === 'table') { tableDepth = depth; break }
  }
  if (tableDepth < 0) return null
  const table = $cell.node(tableDepth)
  const tableStart = $cell.start(tableDepth)
  const map = TableMap.get(table)
  const index = map.map.indexOf($cell.pos - tableStart)
  if (index < 0) return null
  const anchorIndex = Math.floor(index / map.width)
  const anchorColumn = index % map.width
  const range = isCellSelection
    ? map.rectBetween(
      selection.$anchorCell.pos - tableStart,
      selection.$headCell.pos - tableStart,
    )
    : { top: anchorIndex, left: anchorColumn, bottom: anchorIndex + 1, right: anchorColumn + 1 }
  const selectedCells = isCellSelection
    ? [...new Set(selection.ranges.map((item) => item.$from.pos))].length
    : 1
  const multiple = isCellSelection && selectedCells > 1
  const canMerge = isCellSelection && selectedCells > 1 && ed.can().mergeCells()
  const canSplit = ed.can().splitCell()
  return {
    row: anchorIndex,
    col: anchorColumn,
    rows: map.height,
    cols: map.width,
    header: $cell.nodeAfter?.type.name === 'tableHeader',
    selectedCells,
    selectedRows: range.bottom - range.top,
    selectedCols: range.right - range.left,
    cellSelection: isCellSelection,
    multiple,
    canMerge,
    canSplit,
  }
}

function refreshTableContext() {
  tableContext.value = readTableContext(editor.value)
}

function selectCurrentCell() {
  const ed = editor.value
  if (!ed || ed.isDestroyed || isCellSelectionValue(ed.state.selection)) return false
  const $cell = cellAround(ed.state.selection.$from)
  if (!$cell) return false
  const applied = ed.commands.setCellSelection({ anchorCell: $cell.pos })
  if (!applied) return false
  refreshTableContext()
  return true
}

function runTableCommand(command) {
  const ed = editor.value
  if (!ed || !tableContext.value || typeof ed.commands[command] !== 'function') return false
  const applied = ed.chain().focus()[command]().run()
  refreshTableContext()
  return applied
}

const ExcelTableNavigation = Extension.create({
  name: 'excelTableNavigation',
  addKeyboardShortcuts() {
    return {
      Enter: () => moveExcelTableCell(this.editor, 1),
      'Shift-Enter': () => moveExcelTableCell(this.editor, -1),
    }
  },
})

const props = defineProps({ content: { type: String, default: '' }, linkBase: { type: String, default: '' } })
const emit = defineEmits(['update', 'outline'])
const editor = shallowRef(null)
const previewRoot = ref(null)
const actions = ref(null)
const tableContext = ref(null)
const md = new MarkdownIt({ html: true, linkify: true, breaks: true }).use(mdCallout).use(mdAnchor).use(markdownItSup).use(markdownItSub).use(markdownItMark).use(mdDataLine)

/** 上次 emit 出去的 Markdown：用于识别"自己的回显"，避免 setContent 把光标重置 */
let lastEmitted = ''
let revealedHash = ''
const toolbarTargets = new Set()

/** Convert saved Markdown tasks back into editable checkbox nodes. */
function renderContent(markdown) {
  const html = md.render(markdown || '')
  if (!/\[[ xX]\]/.test(markdown || '')) return html
  const box = document.createElement('div')
  box.innerHTML = html
  for (const list of [...box.querySelectorAll('ul')].reverse()) {
    if (list.hasAttribute('data-type')) continue
    const items = [...list.children].filter((el) => el.tagName === 'LI')
    const starts = items.map((item) => {
      const walker = document.createTreeWalker(item, NodeFilter.SHOW_TEXT)
      let text = walker.nextNode()
      while (text && !text.textContent.trim()) text = walker.nextNode()
      const match = text?.textContent.match(/^\[([ xX])\](?:\s+|$)/)
      return match ? { text, match } : null
    })
    if (!items.length || starts.some((start) => !start)) continue
    list.setAttribute('data-type', 'taskList')
    items.forEach((item, index) => {
      const { text, match } = starts[index]
      text.textContent = text.textContent.slice(match[0].length)
      item.setAttribute('data-type', 'taskItem')
      item.setAttribute('data-checked', String(match[1].toLowerCase() === 'x'))
      const content = document.createElement('div')
      content.append(...item.childNodes)
      item.append(content)
    })
  }
  return box.innerHTML
}

function invalidateToolbarTargets() {
  for (const target of toolbarTargets) target.invalid = true
  toolbarTargets.clear()
}

function mapToolbarTargets({ transaction }) {
  if (!transaction.docChanged) return
  for (const target of toolbarTargets) {
    const from = transaction.mapping.mapResult(target.from, 1)
    const to = transaction.mapping.mapResult(target.to, target.from === target.to ? 1 : -1)
    if (from.deletedAcross || to.deletedAcross || (target.from !== target.to && from.pos >= to.pos)) {
      target.invalid = true
      toolbarTargets.delete(target)
      continue
    }
    target.bookmark = target.bookmark.map(transaction.mapping)
    target.from = from.pos
    target.to = to.pos
  }
}

function handleTransaction(payload) {
  mapToolbarTargets(payload)
  refreshTableContext()
}

function focusToolbarSelection(ed) {
  ed.view.focus()
  if (ed.state.selection instanceof NodeSelection && ed.state.selection.node.type.name === 'codeBlock') {
    nextTick(() => {
      if (ed.isDestroyed) return
      const dom = ed.view.nodeDOM(ed.state.selection.from)
      dom?.querySelector?.('.cm-content')?.focus()
    })
  }
}

function runTool(name, arg) {
  const ed = editor.value
  if (!ed || ed.isDestroyed) return false
  captureCodeFocus({ target: document.activeElement })
  let applied = false
  if (name === 'markdown') {
    // Reuse this editor's schema/parser, so templates use the current caret too.
    if (ed.state.selection instanceof NodeSelection && ed.state.selection.node.type.name === 'codeBlock') return false
    applied = ed.commands.insertContent(renderContent(String(arg || '')))
  } else applied = runBlockTool(ed, name, arg)
  if (applied) focusToolbarSelection(ed)
  return applied
}

function applyFormat(format) {
  const ed = editor.value
  if (!ed || ed.isDestroyed) return false
  captureCodeFocus({ target: document.activeElement })
  const applied = applyBlockFormat(ed, format)
  if (applied) focusToolbarSelection(ed)
  return applied
}

/** Dialogs retain a mapped selection instead of reading a caret after focus moves. */
function captureToolbarTarget() {
  const ed = editor.value
  if (!ed || ed.isDestroyed) return null
  captureCodeFocus({ target: document.activeElement })
  const selection = ed.state.selection
  const target = { bookmark: selection.getBookmark(), from: selection.from, to: selection.to, storedMarks: ed.state.storedMarks, invalid: false }
  toolbarTargets.add(target)
  const valid = () => {
    if (target.invalid || ed.isDestroyed || editor.value !== ed) return false
    try {
      const restored = target.bookmark.resolve(ed.state.doc)
      return restored.from === target.from && restored.to === target.to
        && (!(selection instanceof NodeSelection) || restored instanceof NodeSelection)
    } catch { return false }
  }
  const release = () => { target.invalid = true; toolbarTargets.delete(target) }
  const apply = (operation) => {
    if (!valid()) return false
    let restored
    try { restored = target.bookmark.resolve(ed.state.doc) } catch { release(); return false }
    // A deleted atom or selected range must not silently become a new text caret.
    if (restored.from !== target.from || restored.to !== target.to || (selection instanceof NodeSelection && !(restored instanceof NodeSelection))) {
      release()
      return false
    }
    const transaction = ed.state.tr.setSelection(restored).setMeta('addToHistory', false)
    if (restored.empty && target.storedMarks) transaction.setStoredMarks(target.storedMarks)
    ed.view.dispatch(transaction)
    const result = operation()
    release()
    return result
  }
  return { get valid() { return valid() }, release, runTool: (name, arg) => apply(() => runTool(name, arg)), applyFormat: (format) => apply(() => applyFormat(format)) }
}

function captureCodeFocus(event) {
  const ed = editor.value
  if (!ed || !event.target?.closest?.('.code-block-cm')) return
  const block = findActionBlock(ed.view, event.target)
  if (!block || block.node.type.name !== 'codeBlock') return
  if (ed.state.selection instanceof NodeSelection && ed.state.selection.from === block.pos) return
  ed.view.dispatch(ed.state.tr.setSelection(NodeSelection.create(ed.state.doc, block.pos)).setMeta('addToHistory', false))
}

function revealLinkedBlock() {
  let hash = ''
  try { hash = decodeURIComponent(window.location.hash.slice(1)) } catch { return }
  if (!hash || hash === revealedHash) return
  const target = findNoteAnchor(editor.value?.view.dom, window.location.hash)
  const scroller = previewRoot.value?.closest('.pv-scroll')
  if (!target || !scroller) return
  revealedHash = hash
  scroller.scrollTop += target.getBoundingClientRect().top - scroller.getBoundingClientRect().top - 24
  target.animate?.([{ backgroundColor: 'rgba(30, 160, 140, .16)' }, { backgroundColor: 'transparent' }], { duration: 1800 })
}
function hashChanged() { revealedHash = ''; nextTick(revealLinkedBlock) }

function jumpToNoteAnchor(event) {
  const link = event.target?.closest?.('a[href]')
  const href = link?.getAttribute('href')
  if (!findNoteAnchor(editor.value?.view.dom, href)) return false
  event.preventDefault()
  event.stopPropagation()
  revealedHash = ''
  if (window.location.hash !== href) window.location.hash = href
  nextTick(revealLinkedBlock)
  return true
}
defineExpose({ runTool, applyFormat, captureToolbarTarget, undo: () => editor.value?.commands.undo(), redo: () => editor.value?.commands.redo() })

/** Refresh source anchors without replacing content or moving the editing selection. */
function refreshDataLines(markdown) {
  const ed = editor.value
  if (!ed) return
  let renderedDoc = null
  try {
    const container = document.createElement('div')
    container.innerHTML = renderContent(markdown)
    renderedDoc = ProseMirrorDOMParser.fromSchema(ed.schema).parse(container)
  } catch {
    // Failed mappings must not leave old source-line numbers on edited blocks.
  }
  const updates = dataLineUpdates(ed.state.doc, renderedDoc)
  if (!updates.length) return
  const transaction = ed.state.tr
  for (const { pos, node, line } of updates) {
    transaction.setNodeMarkup(pos, undefined, { ...node.attrs, dataLine: line })
  }
  ed.view.dispatch(transaction.setMeta('preventUpdate', true).setMeta('addToHistory', false))
}

function syncDown() {
  if (!editor.value) return
  const markdown = previewHtmlToMd(editor.value.getHTML())
  if (markdown === lastEmitted) return
  lastEmitted = markdown
  refreshDataLines(markdown)
  emit('update', markdown)
}

onMounted(() => {
  editor.value = new Editor({
    editable: true,
    extensions: [StarterKit.configure({ codeBlock: false, underline: false, link: { openOnClick: false } }), CodeBlockCm, Callout, Details, Summary, Underline, Superscript, Subscript, Highlight, FontStyle, TableKit, ExcelTableNavigation, DataLineAttr, BlockMeta, HeadingAnchorAttr, ToolbarAttrs, InlineImage, TaskList, EditableTaskItem],
    content: renderContent(props.content),
    editorProps: {
      // 关掉浏览器拼写检查：笔记里全是 StringBuffer / spring_factories / AutoConfiguration 这类
      // 标识符，contenteditable 默认开启拼写检查，它们会被英文词典逐条标红波浪线（中文不查，
      // 只有这些"疑似英文单词"被误报）。autocorrect/autocapitalize 同理关掉，避免移动端首字母大写。
      // 组件自带该属性，所以在笔记页之外使用也不会重新冒出来（页面根节点另有兜底，见 NoteEdit.vue）。
      attributes: { spellcheck: 'false', autocorrect: 'off', autocapitalize: 'off' },
      handleClick: (_view, _position, event) => jumpToNoteAnchor(event),
    },
    onUpdate: syncDown,
    onTransaction: handleTransaction,
    onSelectionUpdate: refreshTableContext,
  })
  lastEmitted = props.content || ''
  refreshTableContext()
  window.addEventListener('hashchange', hashChanged)
  // 带格式粘贴的前置清洗。**必须用捕获阶段**：ProseMirror 的粘贴处理挂在 .tiptap（事件目标）上，
  // 冒泡阶段再改 clipboardData 就晚了（它已经读完剪贴板）。捕获阶段先跑，改完的 HTML 才轮到它读。
  previewRoot.value?.addEventListener('paste', onBlockPasteCapture, true)
  nextTick(revealLinkedBlock)
})
onBeforeUnmount(() => {
  window.removeEventListener('hashchange', hashChanged)
  previewRoot.value?.removeEventListener('paste', onBlockPasteCapture, true)
  invalidateToolbarTargets()
  editor.value?.destroy()
  editor.value = null
})

/**
 * 带格式粘贴（Ctrl/Cmd+V）的净化：块编辑器是笔记页的**默认**渲染器，
 * 而它自己没有 paste 处理 —— Word 里贴一张截图就会变成几百 KB 的 base64 直接写进笔记。
 * 这条与 md-editor 那条（NoteEdit 的 onPreviewPaste）用同一个清洗函数，规则一致。
 *
 * <p>只净化、不阻断：Ctrl/Cmd+Shift+V（纯文本）直接 return，交给浏览器与 ProseMirror
 * 原生处理（Chrome 的"粘贴为纯文本"本来只给 text/plain）。
 */
function onBlockPasteCapture(e) {
  if (e.shiftKey) return
  const dt = e.clipboardData
  const html = dt?.getData('text/html')
  if (!html || !hasRichHtml(html)) return
  const { html: cleaned, droppedImages } = sanitizePastedHtml(html)
  if (cleaned !== html) dt.setData('text/html', cleaned)
  if (droppedImages) {
    ElMessage.info(`已忽略粘贴内容里的 ${droppedImages} 张内嵌大图（base64 太大，会把笔记撑到几百 KB）`)
  }
}
watch(
  () => props.content,
  (v) => {
    if (!editor.value) return
    if (v === lastEmitted) return // 自己发出的回显，忽略
    lastEmitted = v
    invalidateToolbarTargets()
    editor.value.commands.setContent(renderContent(v), { emitUpdate: false })
    refreshTableContext()
    actions.value?.close()
    nextTick(revealLinkedBlock)
  },
)
</script>

<template>
  <div ref="previewRoot" class="block-preview" @focusin="captureCodeFocus" @pointermove="actions?.hover($event)" @pointerleave="actions?.leave()">
    <div v-if="tableContext" class="block-table-toolbar" role="toolbar" aria-label="表格编辑工具" @mousedown.prevent>
      <span class="block-table-label">
        {{ tableContext.cellSelection ? `已选 ${tableContext.selectedRows}×${tableContext.selectedCols}` : `单元格 ${tableContext.row + 1}/${tableContext.rows} · ${tableContext.col + 1}/${tableContext.cols}` }}
      </span>
      <button v-if="!tableContext.cellSelection" type="button" title="选择当前单元格" @click="selectCurrentCell">选中单元格</button>
      <button type="button" title="上方插入行" @click="runTableCommand('addRowBefore')">上方加行</button>
      <button type="button" title="下方插入行" @click="runTableCommand('addRowAfter')">下方加行</button>
      <button type="button" title="左侧插入列" @click="runTableCommand('addColumnBefore')">左侧加列</button>
      <button type="button" title="右侧插入列" @click="runTableCommand('addColumnAfter')">右侧加列</button>
      <button type="button" title="删除当前行" :disabled="tableContext.rows <= 1" @click="runTableCommand('deleteRow')">删行</button>
      <button type="button" title="删除当前列" :disabled="tableContext.cols <= 1" @click="runTableCommand('deleteColumn')">删列</button>
      <button type="button" title="切换首行为表头" @click="runTableCommand('toggleHeaderRow')">{{ tableContext.header ? '取消表头' : '设为表头' }}</button>
      <button type="button" title="合并选中的单元格" :disabled="!tableContext.canMerge" @click="runTableCommand('mergeCells')">合并单元格</button>
      <button type="button" title="拆分当前合并单元格" :disabled="!tableContext.canSplit" @click="runTableCommand('splitCell')">拆分单元格</button>
    </div>
    <EditorContent :editor="editor" />
    <BlockActionMenu v-if="editor" ref="actions" :editor="editor" :host="previewRoot" :link-base="linkBase" @outline="emit('outline', $event)" />
  </div>
</template>

<style scoped>
/* A4 宽度排版：内容按 A4 纸宽(210mm)居中，所见即所得，方便打印 */
.block-preview { position: relative; }
.block-preview :deep(.tiptap) { position: relative; z-index: 1; }
.block-preview :deep(.tiptap) {
  outline: none;
  font-size: 15px;
  line-height: 1.8;
  color: var(--app-text-1);
  max-width: 210mm;
  margin: 0 auto;
  padding: 24px 28px;
}
/* 标题字号：**与 md-editor 预览共用同一组变量**（style.css 里的 --md-h1..--md-h6）。
   块编辑器是笔记页的**默认**渲染器（只有 ?editor=md 才走 md-editor），所以这套字号必须两处都有 ——
   否则就是"预览里有字号、日常界面里没有"（实测踩过：改完 md-editor 那套，块编辑器里标题
   仍然走浏览器默认值，被粘贴带进来的行内字号压成正文大小，看起来像"转成 H2 没反应"）。
   数字只存在一处（那些变量），这里只引用。 */
.block-preview :deep(.tiptap h1) { font-size: var(--md-h1) !important; line-height: 1.35; font-weight: 700; margin: 1.6em 0 0.6em; }
.block-preview :deep(.tiptap h2) { font-size: var(--md-h2) !important; line-height: 1.4; font-weight: 650; margin: 1.5em 0 0.55em; }
.block-preview :deep(.tiptap h3) { font-size: var(--md-h3) !important; line-height: 1.45; font-weight: 650; margin: 1.4em 0 0.5em; }
.block-preview :deep(.tiptap h4) { font-size: var(--md-h4) !important; line-height: 1.5; font-weight: 650; margin: 1.3em 0 0.45em; }
/* h5/h6 与正文同号：标题不比正文小，用字重与颜色区分 */
.block-preview :deep(.tiptap h5) { font-size: var(--md-h5) !important; line-height: 1.55; font-weight: 650; letter-spacing: .01em; margin: 1.2em 0 0.4em; }
.block-preview :deep(.tiptap h6) { font-size: var(--md-h6) !important; line-height: 1.55; font-weight: 600; letter-spacing: .01em; color: var(--app-text-2); margin: 1.2em 0 0.4em; }
/* 标题字号口径："改级别清字号代码，谁最近按谁"。
   级别默认值在这里给（复用 style.css 的 --md-h*），元素自身带 !important 防止标题元素上的
   行内 style 盖掉它；但标题**内部**的显式字号不压制 —— 用户改级别时清了一次，
   之后手动改的字号属于最近的一步操作，必须生效（见 utils/fontSize.js）。
   曾经在这里加过 `:is(h1..h6) :not(sup):not(sub):not(code){font-size:inherit!important}`，
   已删除：它能修显示，但会让"后面再改"永远无效。 */
.block-preview :deep(.tiptap blockquote) { margin: 1em 0; padding-left: 14px; border-left: 3px solid var(--app-border); color: var(--app-text-2); }
/* 表格边框 */
.block-preview :deep(.tiptap table) { border-collapse: collapse; width: 100%; margin: 1em 0; }
.block-preview :deep(.tiptap th), .block-preview :deep(.tiptap td) { border: 1px solid var(--app-border); padding: 6px 12px; text-align: left; }
.block-preview :deep(.tiptap th) { background: color-mix(in srgb, var(--app-text-1) 6%, transparent); font-weight: 600; }
.block-preview :deep(.tiptap img) { max-width: 100%; height: auto; vertical-align: middle; }
.block-table-toolbar { position: sticky; top: 8px; z-index: 8; display: flex; flex-wrap: wrap; align-items: center; gap: 5px; width: fit-content; max-width: calc(100% - 24px); margin: 8px auto -36px; padding: 5px 7px; border: 1px solid var(--app-border); border-radius: 8px; background: color-mix(in srgb, var(--app-card) 94%, transparent); box-shadow: 0 4px 18px #0002; font-size: 12px; }
.block-table-toolbar button { border: 1px solid var(--app-border-weak); border-radius: 5px; padding: 3px 7px; background: transparent; color: var(--app-text-1); cursor: pointer; font: inherit; }
.block-table-toolbar button:hover { background: var(--app-brand-soft); }
.block-table-toolbar button:disabled { opacity: .45; cursor: default; }
.block-table-label { color: var(--app-text-2); margin-right: 2px; white-space: nowrap; }
.block-preview :deep(.selectedCell) { background: color-mix(in srgb, var(--app-brand) 16%, transparent); box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--app-brand) 60%, transparent); }
.block-preview :deep(.tiptap ul[data-type="taskList"]) { list-style: none; padding-left: 0; }
.block-preview :deep(.tiptap li[data-type="taskItem"]) { display: flex; align-items: flex-start; gap: 8px; }
.block-preview :deep(.tiptap li[data-type="taskItem"] > label) { flex: 0 0 auto; padding-top: 3px; user-select: none; }
.block-preview :deep(.tiptap li[data-type="taskItem"] > div) { flex: 1; min-width: 0; }
.block-preview :deep(.tiptap li[data-type="taskItem"] > div > p) { margin: 0; }
.block-preview :deep(.tiptap li[data-type="taskItem"] input[type="checkbox"]) { accent-color: var(--app-brand); cursor: pointer; }
</style>
