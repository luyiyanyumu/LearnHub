// Synthetic browser checks for the actual KnowledgeSettingsPanel; no production API or data.
// Start: node tools/ui-verify/knowledge-chunking-fixture.mjs
// Run with LEARNHUB_PLAYWRIGHT_PATH=<playwright package directory> and optional
// KNOWLEDGE_CHUNKING_SCREENSHOT_DIR, KNOWLEDGE_CHUNKING_FIXTURE_URL, LEARNHUB_BROWSER_CHANNEL.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const require = createRequire(import.meta.url)
const { chromium } = require(process.env.LEARNHUB_PLAYWRIGHT_PATH || 'playwright')
const fixtureUrl = new URL(process.env.KNOWLEDGE_CHUNKING_FIXTURE_URL || 'http://127.0.0.1:5192/__knowledge_chunking.html')
const output = resolve(process.env.KNOWLEDGE_CHUNKING_SCREENSHOT_DIR || 'output/chunking-ui')
await mkdir(output, { recursive: true })
const browser = await chromium.launch({ channel: process.env.LEARNHUB_BROWSER_CHANNEL || 'msedge', headless: true })
const checks = []

async function selectNextWithKeyboard(page, label) {
  const input = page.getByRole('combobox', { name: label, exact: true })
  await input.focus()
  await page.keyboard.press('Enter')
  await page.keyboard.press('ArrowDown')
  await page.keyboard.press('Enter')
  return input
}

async function metrics(page) {
  return page.evaluate(() => {
    const fields = [...document.querySelectorAll('.kb-field')].filter(el => el.getClientRects().length)
    const bounds = fields.map(el => {
      const r = el.getBoundingClientRect()
      const control = el.querySelector('.kb-field-control').getBoundingClientRect()
      return { label: el.querySelector('label').textContent, left: r.left, right: r.right, width: r.width, controlLeft: control.left, controlRight: control.right, controlWidth: control.width }
    })
    const hint = document.querySelector('.kb-chunking-hint'), style = getComputedStyle(hint), panel = getComputedStyle(document.querySelector('.fixture-panel'))
    const selectedLabel = [...document.querySelector('#kb-chunkStrategy').closest('.kb-field').querySelectorAll('.el-select__selected-item')]
      .filter(el => el.textContent.includes('长资料语义切割')).map(el => ({ width: el.clientWidth, textWidth: el.scrollWidth }))
    return { width: innerWidth, documentWidth: document.documentElement.scrollWidth, dark: document.documentElement.classList.contains('dark'), bounds, selectedLabel, hintColor: style.color, hintBackground: panel.backgroundColor }
  })
}

function contrast(a, b) {
  const luminance = color => {
    const channels = color.match(/[\d.]+/g).slice(0, 3).map(n => Number(n) / 255).map(n => n <= .04045 ? n / 12.92 : ((n + .055) / 1.055) ** 2.4)
    return channels[0] * .2126 + channels[1] * .7152 + channels[2] * .0722
  }
  const first = luminance(a), second = luminance(b)
  return (Math.max(first, second) + .05) / (Math.min(first, second) + .05)
}

try {
  for (const width of [1440, 390, 320]) for (const theme of ['light', 'dark']) {
    const name = `${width}px ${theme}: keyboard selection, validation, save and reflow`
    const context = await browser.newContext({ viewport: { width, height: width === 1440 ? 1000 : 844 }, reducedMotion: 'reduce' })
    const page = await context.newPage()
    page.setDefaultTimeout(15000)
    const pageErrors = [], blockedRequests = [], mutations = []
    page.on('pageerror', error => pageErrors.push(error.message))
    page.on('request', request => { if (new URL(request.url()).pathname.startsWith('/api/') && request.method() !== 'GET') mutations.push({ path: new URL(request.url()).pathname, method: request.method(), body: request.postDataJSON() }) })
    await page.route('**/*', route => {
      const requestUrl = new URL(route.request().url())
      if (requestUrl.protocol.startsWith('http') && requestUrl.origin !== fixtureUrl.origin) { blockedRequests.push(route.request().url()); return route.abort() }
      return route.continue()
    })
    try {
      await page.request.post(new URL('/__fixture/reset', fixtureUrl).href)
      const url = new URL(fixtureUrl); url.searchParams.set('theme', theme)
      await page.goto(url.href, { waitUntil: 'domcontentloaded', timeout: 60000 })
      const strategy = page.getByRole('combobox', { name: '切割方式', exact: true })
      await strategy.waitFor({ timeout: 45000 })
      await selectNextWithKeyboard(page, '知识库参数分类')
      await page.getByRole('spinbutton', { name: '语义切割起始长度', exact: true }).waitFor()
      await selectNextWithKeyboard(page, '切割方式')
      assert.match(await page.locator('.kb-field').filter({ has: strategy }).innerText(), /长资料语义切割/)
      assert.match(await page.locator('.kb-chunking-hint').innerText(), /笔记、速查卡和短资料保留结构切割/)
      assert.match(await page.locator('.kb-chunking-hint').innerText(), /增加重建耗时/)
      await strategy.focus(); await page.keyboard.press('Enter')
      await page.screenshot({ path: resolve(output, `${width}-${theme}-keyboard-select.png`), fullPage: true })
      await page.keyboard.press('Escape')
      assert.equal(await strategy.getAttribute('aria-expanded'), 'false')
      await page.keyboard.press('Tab'); await page.keyboard.press('Shift+Tab')
      assert.equal(await strategy.evaluate(el => el === document.activeElement), true)

      const minimum = page.getByRole('spinbutton', { name: '语义切割起始长度', exact: true })
      const percentile = page.getByRole('spinbutton', { name: '语义边界百分位', exact: true })
      await minimum.fill('6000'); await minimum.press('Tab')
      await percentile.fill('90'); await percentile.press('Tab')
      const overlap = page.getByRole('spinbutton', { name: '片段重叠长度', exact: true })
      await overlap.fill('800'); await overlap.press('Tab')
      await page.locator('.kb-validation').waitFor()
      await page.getByRole('button', { name: '保存知识库设置', exact: true }).click()
      await page.locator('.kb-alert[role="alert"]').waitFor()
      assert.equal(mutations.length, 0)
      assert.match(await page.locator('.kb-alert[role="alert"]').innerText(), /重叠必须小于目标长度/)
      await page.screenshot({ path: resolve(output, `${width}-${theme}-validation.png`), fullPage: true })
      await overlap.fill('120'); await overlap.press('Tab')
      assert.match(await page.locator('.kb-change-note').innerText(), /切块或嵌入参数已修改/)
      await page.getByRole('button', { name: '保存知识库设置', exact: true }).click()
      await page.getByText('当前参数已保存', { exact: true }).waitFor()
      await page.waitForFunction(() => document.querySelector('.kb-status-link')?.textContent.includes('索引待更新'))
      assert.equal(mutations.length, 1)
      assert.deepEqual(mutations[0], { path: '/api/kb/settings', method: 'PUT', body: { values: { chunkStrategy: 'semantic_long', semanticMinChars: 6000, semanticBreakpointPercentile: 90 } } })
      const audit = (await (await page.request.get(new URL('/__fixture/audit', fixtureUrl).href)).json()).data
      assert.equal(audit.values.chunkStrategy, 'semantic_long')
      assert.equal(audit.values.semanticMinChars, 6000)
      assert.equal(audit.values.semanticBreakpointPercentile, 90)
      const measured = await metrics(page)
      assert.equal(measured.dark, theme === 'dark')
      assert.ok(measured.documentWidth <= width + 1, JSON.stringify(measured))
      assert.ok(measured.bounds.every(item => item.left >= 0 && item.right <= width + 1 && item.controlLeft >= item.left && item.controlRight <= item.right + 1), JSON.stringify(measured))
      assert.ok(measured.selectedLabel.length > 0 && measured.selectedLabel.every(item => item.textWidth <= item.width + 1), JSON.stringify(measured.selectedLabel))
      const hintContrast = contrast(measured.hintColor, measured.hintBackground)
      assert.ok(hintContrast >= 4.5, `hint text contrast ${hintContrast}`)
      assert.deepEqual(pageErrors, [])
      assert.deepEqual(blockedRequests, [])
      await page.locator('.el-message').waitFor({ state: 'hidden' })
      await page.evaluate(() => window.scrollTo(0, 0))
      await page.screenshot({ path: resolve(output, `${width}-${theme}-saved.png`), fullPage: true })
      checks.push({ name, ok: true, measured, hintContrast, mutations: mutations.length, screenshots: [`${width}-${theme}-keyboard-select.png`, `${width}-${theme}-validation.png`, `${width}-${theme}-saved.png`] })
      console.log(`PASS ${name}`)
    } catch (error) {
      checks.push({ name, ok: false, error: String(error.stack || error), pageErrors, blockedRequests })
      await page.screenshot({ path: resolve(output, `${width}-${theme}-failure.png`), fullPage: true }).catch(() => {})
      console.error(`FAIL ${name}: ${error.message}`)
    } finally { await context.close() }
  }
} finally {
  await browser.close()
  await writeFile(resolve(output, 'checks.json'), JSON.stringify({ synthetic: true, fixture: fixtureUrl.href, checks }, null, 2))
}
if (checks.some(item => !item.ok)) process.exitCode = 1
