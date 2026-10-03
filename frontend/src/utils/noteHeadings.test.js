import assert from 'node:assert/strict'
import test from 'node:test'
import { collapseAdjacentHeadingDuplicates, parseHeadingLine } from './noteHeadings.js'

/**
 * 这一组用例的形状**全部来自库里真实笔记**（抽查那条 4.9 万字的 R 笔记与 6.1 万字的 Milvus 笔记）：
 * 邻居重复的标题是最常见的形态，而"跨小节的同名标题"必须保住。
 */

test('parseHeadingLine：认 markdown 标题与编辑器存下来的 HTML 标题', () => {
  assert.deepEqual(parseHeadingLine('## R 与 RStudio 起步'), { level: 2, text: 'R 与 RStudio 起步', kind: 'md' })
  assert.deepEqual(parseHeadingLine('# 一级'), { level: 1, text: '一级', kind: 'md' })
  assert.deepEqual(parseHeadingLine('<h2 id="block-abc">R 是什么</h2>'), { level: 2, text: 'R 是什么', kind: 'html' })
  assert.deepEqual(parseHeadingLine('<h3 data-block-indent="1" style="margin-left: 2em">缩进小节</h3>'),
    { level: 3, text: '缩进小节', kind: 'html' })
  // 强调符号参与比较前要先去掉，否则 `## **小结**` 与 `## 小结` 会被当成两个标题
  assert.equal(parseHeadingLine('## **小结**').text, '小结')
  // 非标题
  assert.equal(parseHeadingLine('正文一行'), null)
  assert.equal(parseHeadingLine('#### 四级不进大纲'), null)
  assert.equal(parseHeadingLine('#不带空格'), null)
})

test('相邻重复的标题只留第一份（真实形态：标题 + 空行 + 同标题）', () => {
  const before = [
    '# R 语言常用指令笔记',
    '',
    '## R 与 RStudio 起步',
    '',
    '## R 与 RStudio 起步',
    '',
    '### R 是什么',
    '正文。',
  ].join('\n')
  const { content, removed } = collapseAdjacentHeadingDuplicates(before)
  assert.deepEqual(removed, [5])
  assert.equal(content, [
    '# R 语言常用指令笔记',
    '',
    '## R 与 RStudio 起步',
    '',
    '### R 是什么',
    '正文。',
  ].join('\n'))
})

test('三连重复也合并，且层级不同不算重复', () => {
  const three = ['### 安装与运行方式', '', '### 安装与运行方式', '', '### 安装与运行方式', '正文'].join('\n')
  assert.deepEqual(collapseAdjacentHeadingDuplicates(three).removed, [3, 5])
  // 同名但层级不同 → 保留（真实笔记里 `## 小结` 与 `### 小结` 是两处不同结构）
  const mixed = ['## 小结', '', '### 小结', '正文'].join('\n')
  assert.deepEqual(collapseAdjacentHeadingDuplicates(mixed).removed, [])
})

test('跨小节的同名标题必须保住（只有相邻的才算重复）', () => {
  const content = [
    '# 总览',
    '',
    '## 常见问题',
    '这里是问题一。',
    '',
    '## 另一个主题',
    '正文段落。',
    '',
    '## 常见问题',
    '这里是问题二（不同小节里的同名标题，合法）。',
  ].join('\n')
  const { content: after, removed } = collapseAdjacentHeadingDuplicates(content)
  assert.deepEqual(removed, [])
  assert.equal(after, content)
})

test('md 标题与 HTML 标题互为重复时也能合并（编辑器两种形态混存）', () => {
  const content = ['## R 是什么', '<h2 id="block-xyz">R 是什么</h2>', '', '正文'].join('\n')
  const { content: after, removed } = collapseAdjacentHeadingDuplicates(content)
  assert.deepEqual(removed, [2])
  assert.equal(after, ['## R 是什么', '', '正文'].join('\n'))
})

test('没有重复时正文一字不动（含 CRLF 与空正文）', () => {
  const content = '# 标题\r\n\r\n## 小节\r\n正文。'
  const { content: after, removed } = collapseAdjacentHeadingDuplicates(content)
  assert.deepEqual(removed, [])
  assert.equal(after, content)
  assert.deepEqual(collapseAdjacentHeadingDuplicates('').removed, [])
  assert.deepEqual(collapseAdjacentHeadingDuplicates(null).removed, [])
})
