import test from 'node:test'
import assert from 'node:assert/strict'
import {
  IMPORT_ACCEPT,
  IMPORT_MAX_BYTES,
  extOf,
  isHtmlFile,
  isMarkdownFile,
  titleFromFileName,
  parseMarkdownImport,
} from './importNote.js'

test('extension detection is case-insensitive and path-aware', () => {
  assert.equal(extOf('a/b/笔记.MD'), 'md')
  assert.equal(extOf('C:\\x\\说明.HTML'), 'html')
  assert.equal(extOf('没有扩展名'), '')
  assert.equal(extOf('.gitignore'), '')            // 只有点开头不算扩展名
  assert.equal(isHtmlFile('导出.html'), true)
  assert.equal(isHtmlFile('导出.HTM'), true)
  assert.equal(isHtmlFile('导出.md'), false)
  assert.equal(isMarkdownFile('笔记.md'), true)
  assert.equal(isMarkdownFile('笔记.markdown'), true)
  assert.equal(isMarkdownFile('随手记.txt'), true)
  assert.equal(isMarkdownFile('页面.html'), false)
})

test('accept list covers markdown and html', () => {
  for (const ext of ['.md', '.markdown', '.txt', '.html', '.htm']) {
    assert.ok(IMPORT_ACCEPT.includes(ext), IMPORT_ACCEPT + ' 应当包含 ' + ext)
  }
  assert.ok(IMPORT_MAX_BYTES >= 1024 * 1024)
})

test('title falls back to the file name without its extension', () => {
  assert.equal(titleFromFileName('Spring Boot 启动流程.md'), 'Spring Boot 启动流程')
  assert.equal(titleFromFileName('a/b/说明.html'), '说明')
  assert.equal(titleFromFileName('没有扩展名'), '没有扩展名')
  assert.equal(titleFromFileName('   '), '未命名笔记')
})

test('leading " # 标题 " becomes the note title and leaves the body', () => {
  const { title, content } = parseMarkdownImport('文件名.md', '# Spring Boot 启动流程\n\n一句话版……\n')
  assert.equal(title, 'Spring Boot 启动流程')
  assert.equal(content, '一句话版……\n')
})

test('exports round-trip without duplicating the title', () => {
  // 导出的写法就是 `# 标题\n\n正文\n`（见 NoteEdit.exportNote）
  const exported = '# MQTT 三句话入门\n\nMQTT 是一种发布/订阅协议。\n\n- 轻量\n- 低带宽\n'
  const { title, content } = parseMarkdownImport('MQTT 三句话入门.md', exported)
  assert.equal(title, 'MQTT 三句话入门')
  assert.ok(!content.startsWith('# '), '正文里不应再出现那条标题行')
  assert.equal(content, 'MQTT 是一种发布/订阅协议。\n\n- 轻量\n- 低带宽\n')
})

test('leading blank lines and a BOM do not hide the title', () => {
  const { title, content } = parseMarkdownImport('x.md', '\uFEFF\n\n\n# 标题在这\n\n正文\n')
  assert.equal(title, '标题在这')
  assert.equal(content, '正文\n')
})

test('CRLF is normalized to LF', () => {
  const { title, content } = parseMarkdownImport('x.md', '# T\r\n\r\n甲\r\n乙\r\n')
  assert.equal(title, 'T')
  assert.equal(content, '甲\n乙\n')
})

test('a file that starts with ## keeps the file name as title', () => {
  const { title, content } = parseMarkdownImport('目录速记.md', '## 二级标题\n\n正文\n')
  assert.equal(title, '目录速记')
  assert.equal(content, '## 二级标题\n\n正文\n')
})

test('a file with no heading at all uses the file name', () => {
  const { title, content } = parseMarkdownImport('随手记.txt', '就是一段散文。\n\n第二段。\n')
  assert.equal(title, '随手记')
  assert.equal(content, '就是一段散文。\n\n第二段。\n')
})

test('trailing blank lines are trimmed, inner blank lines kept', () => {
  const { content } = parseMarkdownImport('x.md', '# T\n\n甲\n\n\n乙\n\n\n')
  assert.equal(content, '甲\n\n\n乙\n')
})

test('an empty file yields the file name and empty content', () => {
  const { title, content } = parseMarkdownImport('空的.md', '')
  assert.equal(title, '空的')
  assert.equal(content, '')
})

test('a heading with trailing decoration # is handled', () => {
  const { title } = parseMarkdownImport('x.md', '# 标题 ##\n\n正文\n')
  assert.equal(title, '标题')
})
