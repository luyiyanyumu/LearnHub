import test from 'node:test'
import assert from 'node:assert/strict'
import {
  splitNoteSections,
  buildNoteOutline,
  applySectionPatch,
  appendToSection,
  appendNoteSection,
  demoteSectionHeadings,
  pickSectionLevel,
  scanHeadings,
  SECTION_REWRITE_MAX,
} from './noteSections.js'

const NOTE = [
  '# Java八股文（基础篇）',
  '',
  '> 一句总览。',
  '',
  '## 1.instanceof关键字的作用',
  '',
  'instanceof 是双目运算符。',
  '',
  '```java',
  '// ## 这不是标题',
  'boolean r = obj instanceof Class',
  '```',
  '',
  '## 2.重载和重写的区别',
  '',
  '### 重写',
  '',
  '发生在父类与子类之间。',
  '',
  '### 重载',
  '',
  '发生在同一个类里。',
  '',
].join('\n')

test('splits a note on its level-2 headings and keeps the H1 line as preamble', () => {
  const { level, preamble, sections } = splitNoteSections(NOTE)
  assert.equal(level, 2)
  assert.match(preamble, /^# Java八股文（基础篇）/)
  assert.deepEqual(sections.map((s) => s.heading), [
    '1.instanceof关键字的作用',
    '2.重载和重写的区别',
  ])
})

test('a "## " inside a fenced code block is not a heading', () => {
  const { sections } = splitNoteSections(NOTE)
  assert.deepEqual(sections.map((s) => s.heading), [
    '1.instanceof关键字的作用',
    '2.重载和重写的区别',
  ])
  // the fenced line survives inside the first section's body
  assert.ok(sections[0].body.includes('// ## 这不是标题'))
})

test('tilde fences close only on the same marker', () => {
  const md = ['## A', '', '~~~', '```', '## still code', '~~~', '', '## B', ''].join('\n')
  assert.deepEqual(splitNoteSections(md).sections.map((s) => s.heading), ['A', 'B'])
  const md2 = ['## A', '', '```', '~~~', '## still code', '```', '', '## B', ''].join('\n')
  assert.deepEqual(splitNoteSections(md2).sections.map((s) => s.heading), ['A', 'B'])
})

test('without any ## it falls back to the shallowest repeated level', () => {
  const md = ['# 标题', '', '### 一', '甲', '', '### 二', '乙', ''].join('\n')
  const { level, sections } = splitNoteSections(md)
  assert.equal(level, 3)
  assert.deepEqual(sections.map((s) => s.heading), ['一', '二'])
})

test('a lone H1 is not treated as a section of its own', () => {
  // 只有一个 # 标题时，没法按它分节（那就成了"整篇一节"，等于没有分节）
  assert.equal(pickSectionLevel(scanHeadings('# 只有标题\n\n正文\n')), 1)
  const md = ['# 只有标题', '', '正文', ''].join('\n')
  const { sections } = splitNoteSections(md)
  assert.equal(sections.length, 1)
  assert.equal(sections[0].heading, '只有标题')
})

test('a note without headings reports no sections and keeps the whole text as preamble', () => {
  const md = '就是一段散文，没有标题。\n\n第二段。\n'
  const { level, sections, preamble } = splitNoteSections(md)
  assert.equal(level, null)
  assert.equal(sections.length, 0)
  assert.equal(preamble.trimEnd(), md.trimEnd())
})

test('offsets are exact: the slice matches text and body drops the heading line', () => {
  const { sections } = splitNoteSections(NOTE)
  for (const s of sections) {
    assert.equal(NOTE.slice(s.start, s.end), s.text)
    assert.equal(NOTE.slice(s.bodyStart, s.end), s.body)
    assert.ok(!s.body.startsWith('## '), 'body must not contain its own heading line')
    assert.equal(s.size, s.end - s.start)
  }
})

test('replacing one section leaves every other byte untouched', () => {
  const { sections } = splitNoteSections(NOTE)
  const target = sections[0]
  const out = applySectionPatch(NOTE, target, 'instanceof 是双目运算符。\n\n补充：`null instanceof X` 恒为 false。')
  // 其余两段（前言 + 第二节）逐字不变
  assert.ok(out.startsWith(NOTE.slice(0, NOTE.indexOf('## 1.'))))
  assert.ok(out.trimEnd().endsWith('## 2.重载和重写的区别\n\n### 重写\n\n发生在父类与子类之间。\n\n### 重载\n\n发生在同一个类里。'))
  assert.ok(out.includes('补充：`null instanceof X` 恒为 false。'))
  // 再切一次：节数不变、第二节内容一致
  const after = splitNoteSections(out)
  assert.equal(after.sections.length, 2)
  assert.equal(after.sections[1].text, sections[1].text)
})

test('applySectionPatch refuses when the note changed after the split', () => {
  const { sections } = splitNoteSections(NOTE)
  const edited = NOTE.replace('instanceof 是双目运算符。', 'instanceof 是双目运算符（用户刚改的）。')
  assert.throws(() => applySectionPatch(edited, sections[0], '新正文'), /发生了变化/)
})

test('applySectionPatch refuses an empty body', () => {
  const { sections } = splitNoteSections(NOTE)
  assert.throws(() => applySectionPatch(NOTE, sections[0], '   \n  '), /没有给出可用的小节正文/)
})

test('appendToSection adds the block at the end of that section only', () => {
  const { sections } = splitNoteSections(NOTE)
  const out = appendToSection(NOTE, sections[0], '### 补充\n\n`null instanceof X` 恒为 false。')
  const after = splitNoteSections(out)
  assert.equal(after.sections.length, 2)
  assert.match(after.sections[0].body, /### 补充/)
  assert.equal(after.sections[1].text, sections[1].text)
  assert.ok(after.sections[0].body.trimEnd().endsWith('恒为 false。'))
})

test('appendNoteSection adds a brand new section at the end', () => {
  const out = appendNoteSection(NOTE, '3.补充', '新内容。')
  const after = splitNoteSections(out)
  assert.deepEqual(after.sections.map((s) => s.heading), [
    '1.instanceof关键字的作用',
    '2.重载和重写的区别',
    '3.补充',
  ])
  assert.ok(after.sections[2].body.includes('新内容。'))
})

test('buildNoteOutline numbers sections, reports sizes and child headings', () => {
  const { text, sections } = buildNoteOutline(NOTE)
  assert.equal(sections.length, 2)
  assert.match(text, /1\. 【\d+ 字】1\.instanceof关键字的作用/)
  assert.match(text, /2\. 【\d+ 字】2\.重载和重写的区别/)
  assert.match(text, /子节：重写 \/ 重载/)
  assert.match(text, /开头前言/)
})

test('a note without headings still yields a usable outline', () => {
  const { text, sections } = buildNoteOutline('一段没有标题的长文。')
  assert.equal(sections.length, 0)
  assert.match(text, /没有可用的分节标题/)
})

test('CRLF notes split with offsets that still match the source', () => {
  const md = '# 标题\r\n\r\n## 甲\r\n\r\n内容一\r\n\r\n## 乙\r\n\r\n内容二\r\n'
  const { sections } = splitNoteSections(md)
  assert.deepEqual(sections.map((s) => s.heading), ['甲', '乙'])
  for (const s of sections) assert.equal(md.slice(s.start, s.end), s.text)
  const out = applySectionPatch(md, sections[0], '内容一改')
  assert.ok(out.includes('## 甲\r\n\r\n内容一改'))
  assert.ok(out.includes('## 乙\r\n\r\n内容二'))
})

test('the rewrite threshold is a sane positive number', () => {
  assert.ok(SECTION_REWRITE_MAX > 1000 && SECTION_REWRITE_MAX < 20000)
})

// ---- 层级兜底：模型在节内写同级标题，会把这一节截成好几节 ----
// 端到端实测：让模型给"新增的一节"写正文，它写了 5 个 `##`，那一节裂成了 6 节。

// ---- 空小节（只有标题、没有正文）----
// 回归测试：实测踩到过 —— `bodyStart` 夹取到"标题文字的末尾"，
// 新正文被贴到标题**同一行**：`## 10.泛型常用特点泛型（Generics）是把…`。
// 而空小节正是"先写好标题、再让智能体补内容"的常见形态（用户笔记里第 9、10 节都是）。

const EMPTY_CASES = {
  mid: '## 1.甲\n\n甲正文。\n\n## 2.乙\n\n## 3.丙\n\n丙正文。\n',
  lastWithNewline: '## 1.甲\n\n甲正文。\n\n## 2.乙\n',
  lastWithoutNewline: '## 1.甲\n\n甲正文。\n\n## 2.乙',
  extraBlankLines: '## 1.甲\n\n甲正文。\n\n## 2.乙\n\n\n## 3.丙\n',
}

for (const [name, md] of Object.entries(EMPTY_CASES)) {
  test(`patching an empty section (${name}) keeps the heading on its own line`, () => {
    const before = splitNoteSections(md)
    const target = before.sections.find((s) => s.heading === '2.乙')
    assert.equal(target.body, '', '空小节的 body 应当是空串')
    assert.ok(target.bodyStart > target.start + '## 2.乙'.length,
      'bodyStart 必须落在标题行的换行之后（这正是当初的 bug）')
    const out = applySectionPatch(md, target, '新写入的第一句话。')
    assert.ok(!/## 2\.乙[^\n]/.test(out), '标题后面不能跟着正文：' + JSON.stringify(out))
    assert.match(out, /## 2\.乙\n\n新写入的第一句话。/)
    // 其余小节逐字不变
    const after = splitNoteSections(out)
    assert.equal(after.sections.length, before.sections.length, '节数不变')
    for (const s of before.sections) {
      if (s.heading === '2.乙') continue
      assert.equal(after.sections.find((x) => x.heading === s.heading).text, s.text,
        `小节「${s.heading}」必须逐字不变`)
    }
  })
}

test('appendToSection on an empty section also keeps the heading on its own line', () => {
  const md = '## 1.甲\n\n甲正文。\n\n## 2.乙\n'
  const { sections } = splitNoteSections(md)
  const out = appendToSection(md, sections[1], '### 子小节\n\n内容。')
  assert.ok(!/## 2\.乙[^\n]/.test(out), JSON.stringify(out))
  assert.match(out, /## 2\.乙\n\n### 子小节\n\n内容。/)
})

test('a real note shape: an empty last section gets the body on the next line', () => {
  // 用户笔记的形态：一堆有正文的小节 + 末尾一个只有标题的空小节（正等着智能体补内容）
  const md = [
    '# Java八股文（基础篇）',
    '',
    '## 9.Java的四种引用，强弱软虚',
    '',
    '## 10.泛型常用特点',
    '',
  ].join('\n')
  const { sections } = splitNoteSections(md)
  const ten = sections.find((s) => s.heading.startsWith('10.'))
  const out = applySectionPatch(md, ten, '泛型（Generics）是把"类型"作为参数传给类、接口或方法的机制。')
  assert.ok(!/## 10\.[^\n]*泛型（Generics）/.test(out), JSON.stringify(out))
  assert.match(out, /## 10\.泛型常用特点\n\n泛型（Generics）是把/)
})

test('demoteSectionHeadings pushes same-or-shallower headings one level down', () => {  const md = ['# 一级', '## 二级', '### 三级', '#### 四级'].join('\n')
  assert.equal(demoteSectionHeadings(md, 2), ['## 一级', '### 二级', '### 三级', '#### 四级'].join('\n'))
  assert.equal(demoteSectionHeadings(md, 3), ['## 一级', '### 二级', '#### 三级', '#### 四级'].join('\n'))
  assert.equal(demoteSectionHeadings(md, 1), ['## 一级', '## 二级', '### 三级', '#### 四级'].join('\n'))
  assert.equal(demoteSectionHeadings(md, 6), md) // 6 级已到底，无处可降
})

test('demoteSectionHeadings leaves fenced code and non-headings alone', () => {
  const md = ['## 这是节内标题', '', '```', '## 代码里的井号', '```', '', '正文 ## 行内不算'].join('\n')
  const out = demoteSectionHeadings(md, 2)
  assert.match(out, /^### 这是节内标题/m)
  assert.ok(out.includes('## 代码里的井号'), '代码块里的 ## 不能动')
  assert.ok(out.includes('正文 ## 行内不算'), '行内的不算标题')
})

test('replacing a section demotes a stray ## so the section cannot split in two', () => {
  const src = ['## 甲', '', '甲正文', '', '## 乙', '', '乙正文', ''].join('\n')
  const { sections } = splitNoteSections(src)
  const out = applySectionPatch(src, sections[0], '甲正文改\n\n## 模型自己写的同级标题\n\n新内容')
  const after = splitNoteSections(out)
  assert.equal(after.sections.length, 2, '节数必须不变（模型的 ## 要降成 ###）')
  assert.ok(after.sections[0].body.includes('### 模型自己写的同级标题'))
  assert.equal(after.sections[1].text, sections[1].text)
})

test('appendNoteSection demotes ## inside the new section body', () => {
  const out = appendNoteSection(NOTE, '3.新节', '## 一、子标题\n\n内容\n\n## 二、另一个子标题\n\n更多')
  const after = splitNoteSections(out)
  assert.equal(after.sections.length, 3, '新增一节就是新增一节，不能变成三节')
  assert.ok(after.sections[2].body.includes('### 一、子标题'))
  assert.ok(after.sections[2].body.includes('### 二、另一个子标题'))
})

test('appendToSection demotes a stray ## inside the appended block', () => {
  const { sections } = splitNoteSections(NOTE)
  const out = appendToSection(NOTE, sections[0], '## 想当同级标题\n\n内容')
  const after = splitNoteSections(out)
  assert.equal(after.sections.length, 2)
  assert.ok(after.sections[0].body.includes('### 想当同级标题'))
})
