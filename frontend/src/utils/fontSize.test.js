import assert from 'node:assert/strict'
import test from 'node:test'
import { stripFontSizeInHtml, withoutFontSize } from './fontSize.js'

/** 用例取自笔记 #94 的真实内容（从 Word 粘进来的行内样式） */

test('withoutFontSize：只去 font-size，颜色与字体保留', () => {
  assert.equal(
    withoutFontSize('font-family: Arial; color: rgb(51, 51, 51); font-size: 10.5pt'),
    'font-family: Arial; color: rgb(51, 51, 51)',
  )
  // 大小写与空格都要认
  assert.equal(withoutFontSize('FONT-SIZE:12px'), '')
  assert.equal(withoutFontSize(' color: red ; font-size : 12px '), 'color: red')
  // 不能误伤 font-size-adjust / font-family 这类前缀相同的属性
  assert.equal(withoutFontSize('font-size-adjust: 0.5'), 'font-size-adjust: 0.5')
})

test('stripFontSizeInHtml：行内 font 只掉字号', () => {
  const line = '## <font style="font-family: Arial; color: rgb(51, 51, 51); font-size: 10.5pt;">2.重载和重写的区别</font>'
  assert.equal(
    stripFontSizeInHtml(line),
    '## <font style="font-family: Arial; color: rgb(51, 51, 51)">2.重载和重写的区别</font>',
  )
})

test('stripFontSizeInHtml：style 只剩 font-size 时连属性一起去掉', () => {
  assert.equal(stripFontSizeInHtml('<font style="font-size: 12px">注</font>'), '<font>注</font>')
  assert.equal(stripFontSizeInHtml('<span style="font-size:12px">x</span>'), '<span>x</span>')
})

test('stripFontSizeInHtml：一行里多个标签都处理', () => {
  const line = '<font style="font-size: 11pt">a</font> 与 <font style="color: red; font-size: 10.5pt">b</font>'
  assert.equal(
    stripFontSizeInHtml(line),
    '<font>a</font> 与 <font style="color: red">b</font>',
  )
})

test('stripFontSizeInHtml：没有样式的内容一字不动（幂等）', () => {
  const plain = '## 1.instanceof关键字的作用'
  assert.equal(stripFontSizeInHtml(plain), plain)
  const already = '## <font style="color: red">标题</font>'
  assert.equal(stripFontSizeInHtml(already), already)
  assert.equal(stripFontSizeInHtml(stripFontSizeInHtml(already)), already)
})

test('stripFontSizeInHtml：空值不炸', () => {
  assert.equal(stripFontSizeInHtml(null), '')
  assert.equal(stripFontSizeInHtml(undefined), '')
})
