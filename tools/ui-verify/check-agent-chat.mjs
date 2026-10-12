// Browser regression against the synthetic, isolated agent chat fixture.
// Start: node tools/ui-verify/agent-chat-fixture.mjs
// Run: LEARNHUB_PLAYWRIGHT_PATH=<Playwright package directory> node tools/ui-verify/check-agent-chat.mjs
// Optional: AGENT_CHAT_FIXTURE_URL, AGENT_CHAT_SCREENSHOT_DIR, LEARNHUB_BROWSER_CHANNEL.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const require = createRequire(import.meta.url)
const { chromium } = require(process.env.LEARNHUB_PLAYWRIGHT_PATH || 'playwright')
const fixtureUrl = new URL(process.env.AGENT_CHAT_FIXTURE_URL || 'http://127.0.0.1:5186/__agent_chat.html')
const output = resolve(process.env.AGENT_CHAT_SCREENSHOT_DIR || 'output/chat-progress-ui')
await mkdir(output, { recursive: true })
const browser = await chromium.launch({ channel: process.env.LEARNHUB_BROWSER_CHANNEL || 'msedge', headless: true })
const checks = []
let normalSession = ''
let currentPage

async function check(name, fn) {
  const startedAt = Date.now()
  try {
    const detail = await fn()
    checks.push({ name, ok: true, elapsedMs: Date.now() - startedAt, detail })
    console.log(`PASS ${name}${detail ? `: ${JSON.stringify(detail)}` : ''}`)
  } catch (error) {
    checks.push({ name, ok: false, elapsedMs: Date.now() - startedAt, error: String(error.stack || error) })
    if (currentPage && !currentPage.isClosed()) {
      await currentPage.screenshot({ path: resolve(output, `failure-${checks.length}.png`), fullPage: true }).catch(() => {})
    }
    console.error(`FAIL ${name}: ${error.message}`)
  }
}

async function newPage({ width = 1440, height = 1000, theme = 'light', session = '', mode = 'embedded' } = {}) {
  const context = await browser.newContext({ viewport: { width, height }, reducedMotion: 'reduce' })
  const page = await context.newPage()
  currentPage = page
  page.setDefaultTimeout(12_000)
  const pageErrors = []
  const streamRequests = []
  page.on('pageerror', error => pageErrors.push(error.message))
  page.on('request', request => {
    if (new URL(request.url()).pathname === '/api/ai/chat-stream') streamRequests.push(request.postDataJSON())
  })
  let sessionId = session
  if (!sessionId) {
    const response = await page.request.post(new URL('/api/model/sessions', fixtureUrl).href, { data: { title: `合成浏览器核验 ${theme} ${width}` } })
    const payload = await response.json()
    assert.equal(payload.code, 200)
    sessionId = payload.data.id
  }
  const url = new URL(fixtureUrl)
  url.searchParams.set('session', sessionId)
  url.searchParams.set('theme', theme)
  url.searchParams.set('mode', mode)
  await page.goto(url.href)
  if (mode === 'drawer') await page.getByRole('button', { name: '智能体', exact: true }).click()
  await page.locator('textarea.chat-input').waitFor({ state: 'visible' })
  return { context, page, sessionId, pageErrors, streamRequests }
}

async function send(page, text) {
  await page.locator('textarea.chat-input').fill(text)
  await page.getByRole('button', { name: '发送消息', exact: true }).click()
  await page.locator('.run-trace.is-running').last().waitFor({ state: 'visible' })
}

async function finished(page) {
  await page.waitForFunction(() => !document.querySelector('.run-trace.is-running'))
  await page.locator('.msg.assistant .md-body').last().waitFor({ state: 'visible' })
}

async function screenshot(page, name) {
  await page.screenshot({ path: resolve(output, name), fullPage: true })
}

async function layout(page) {
  return page.evaluate(() => {
    const input = document.querySelector('textarea.chat-input')
    const r = input.getBoundingClientRect()
    const visibleParents = [...document.querySelectorAll('.agent-layout,.chat-area,.panel,.panel-foot,.run-trace')]
      .map(el => ({ className: el.className, clientWidth: el.clientWidth, scrollWidth: el.scrollWidth }))
    return {
      width: innerWidth, height: innerHeight,
      scrollWidth: document.documentElement.scrollWidth,
      input: { left: r.left, right: r.right, top: r.top, bottom: r.bottom, width: r.width, height: r.height },
      overflow: visibleParents.filter(el => el.scrollWidth > el.clientWidth + 2),
      dark: document.documentElement.classList.contains('dark'),
    }
  })
}

try {
  await check('real SSE shows in-flight actions, elapsed time and in-place completion before a six-second result', async () => {
    const h = await newPage()
    try {
      normalSession = h.sessionId
      const begin = Date.now()
      await send(h.page, '计划并正常检索合成资料')
      await h.page.locator('.trace-entry.is-running .entry-title').filter({ hasText: '准备对话上下文' }).waitFor()
      assert.equal(await h.page.locator('.trace-toggle').last().getAttribute('aria-expanded'), 'true')
      await h.page.locator('.trace-entry.is-running .entry-title').filter({ hasText: '检索知识库' }).waitFor()
      assert.equal(await h.page.getByRole('list', { name: '工作计划', exact: true }).count(), 1)
      assert.equal(await h.page.locator('.plan-list > li.is-pending').count(), 1)
      assert.equal(await h.page.locator('.plan-list > li.is-cancelled').count(), 1)
      assert.match(await h.page.locator('.trace-toggle').last().innerText(), /2 项计划/)
      assert.equal(await h.page.locator('.trace-entry .entry-title').filter({ hasText: '准备对话上下文' }).count(), 1)
      await h.page.waitForFunction(() => /[1-9]\d* 秒/.test(document.querySelector('.run-trace.is-running .trace-time')?.textContent || ''))
      assert.equal(await h.page.locator('.msg.assistant .md-body').count(), 0)
      await screenshot(h.page, 'desktop-light-running.png')
      await h.page.locator('.trace-entry.is-nested .entry-title').filter({ hasText: '读取笔记' }).waitFor()
      await finished(h.page)
      assert.equal(await h.page.locator('.plan-list > li.is-completed').count(), 2)
      assert.equal(await h.page.locator('.trace-mark.is-completed').count(), 1)
      const elapsedMs = Date.now() - begin
      assert.ok(elapsedMs >= 5_500, `fixture result arrived suspiciously soon: ${elapsedMs} ms`)
      assert.equal(await h.page.locator('.trace-toggle').last().getAttribute('aria-expanded'), 'false')
      assert.equal(await h.page.locator('.trace-entry .entry-title').filter({ hasText: '读取笔记' }).count(), 1)
      await h.page.locator('.trace-toggle').last().focus()
      await h.page.keyboard.press('Enter')
      assert.equal(await h.page.locator('.trace-toggle').last().getAttribute('aria-expanded'), 'true')
      await h.page.keyboard.press('Space')
      assert.equal(await h.page.locator('.trace-toggle').last().getAttribute('aria-expanded'), 'false')
      assert.equal(h.streamRequests.length, 1)
      assert.deepEqual(h.pageErrors, [])
      await screenshot(h.page, 'desktop-light-completed.png')
      return { elapsedMs, streamRequests: h.streamRequests.length }
    } finally { await h.context.close() }
  })

  for (const width of [390, 1440]) for (const theme of ['light', 'dark']) {
    await check(`${width}px ${theme} layout keeps the composer visible without horizontal overflow`, async () => {
      const h = await newPage({ width, height: width === 390 ? 844 : 1000, theme, session: normalSession })
      try {
        await h.page.locator('.trace-toggle').last().waitFor()
        await h.page.locator('.trace-toggle').last().click()
        const measured = await layout(h.page)
        assert.equal(measured.dark, theme === 'dark')
        assert.ok(measured.scrollWidth <= measured.width + 2, JSON.stringify(measured))
        assert.deepEqual(measured.overflow, [], JSON.stringify(measured))
        assert.ok(measured.input.left >= 0 && measured.input.right <= measured.width + 1, JSON.stringify(measured.input))
        assert.ok(measured.input.top >= 0 && measured.input.bottom <= measured.height + 1, JSON.stringify(measured.input))
        assert.ok(measured.input.width >= 100 && measured.input.height >= 30)
        assert.deepEqual(h.pageErrors, [])
        await screenshot(h.page, `${width}-${theme}-expanded.png`)
        return measured
      } finally { await h.context.close() }
    })
  }

  await check('switching history during a slow request prevents the old result from replacing the selected session', async () => {
    const h = await newPage()
    try {
      const begin = Date.now()
      await send(h.page, '慢速检索合成资料')
      await h.page.locator('.trace-entry .entry-title').filter({ hasText: '准备对话上下文' }).waitFor()
      await h.page.locator('.row-main').filter({ hasText: '合成示例 · 历史会话' }).click()
      await h.page.getByText('先获取候选，再根据来源核对。这是用于界面检查的合成回答。', { exact: true }).waitFor()
      await h.page.waitForTimeout(Math.max(0, 19_000 - (Date.now() - begin)))
      assert.match(await h.page.locator('.row-main[aria-current="true"]').innerText(), /合成示例 · 历史会话/)
      assert.match(await h.page.locator('.msg.assistant .md-body').innerText(), /先获取候选，再根据来源核对/)
      assert.doesNotMatch(await h.page.locator('.msg-list').innerText(), /慢速检索合成资料|已阅读合成来源/)
      assert.equal(await h.page.locator('.run-trace.is-running').count(), 0)
      assert.equal(h.streamRequests.length, 1)
      assert.deepEqual(h.pageErrors, [])
      await screenshot(h.page, 'history-after-slow-response.png')
      return { waitedMs: Date.now() - begin, selectedHistory: true }
    } finally { await h.context.close() }
  })

  await check('a disconnected response is not resent and reopening history restores the completed server result', async () => {
    const h = await newPage()
    try {
      const begin = Date.now()
      await send(h.page, '断流恢复合成资料')
      await h.page.locator('.msg.assistant .err').filter({ hasText: '连接已中断' }).waitFor()
      assert.equal(h.streamRequests.length, 1)
      assert.match(await h.page.locator('.trace-toggle').last().innerText(), /中断|未完成/)
      await screenshot(h.page, 'disconnected-visible.png')
      await h.page.waitForTimeout(Math.max(0, 6_500 - (Date.now() - begin)))
      await h.page.getByRole('button', { name: '重新读取会话', exact: true }).click()
      await h.page.locator('.msg.assistant .md-body').filter({ hasText: '已阅读合成来源' }).waitFor()
      assert.equal(await h.page.locator('.msg.assistant .err').count(), 0)
      assert.equal(await h.page.locator('.run-trace.is-running').count(), 0)
      assert.equal(h.streamRequests.length, 1)
      assert.deepEqual(h.pageErrors, [])
      await screenshot(h.page, 'disconnected-history-restored.png')
      return { streamRequests: h.streamRequests.length }
    } finally { await h.context.close() }
  })

  await check('tool failures remain visible in collapsed process summary and the message alert', async () => {
    const h = await newPage()
    try {
      await send(h.page, '失败合成检索')
      await h.page.locator('.msg.assistant .err').filter({ hasText: '合成错误' }).waitFor()
      assert.equal(await h.page.locator('.trace-toggle').last().getAttribute('aria-expanded'), 'false')
      assert.match(await h.page.locator('.trace-toggle').last().innerText(), /1 项未成功/)
      await h.page.locator('.trace-toggle').last().click()
      assert.match(await h.page.locator('.trace-entry.is-error').innerText(), /检索服务暂时不可用/)
      assert.equal(h.streamRequests.length, 1)
      assert.deepEqual(h.pageErrors, [])
      await screenshot(h.page, 'failure-visible.png')
    } finally { await h.context.close() }
  })

  await check('cancelling a prepared note edit clears its waiting status and confirmation card', async () => {
    const h = await newPage()
    try {
      await send(h.page, '为合成笔记添加目录')
      await h.page.locator('.pending-box').waitFor()
      await finished(h.page)
      assert.match(await h.page.locator('.trace-toggle').last().innerText(), /1 项待确认/)
      await screenshot(h.page, 'edit-waiting.png')
      await h.page.locator('.pending-box').getByRole('button', { name: '取消', exact: true }).click()
      await h.page.locator('.pending-box').waitFor({ state: 'detached' })
      await h.page.waitForFunction(() => !document.querySelector('.trace-entry.is-waiting'))
      assert.doesNotMatch(await h.page.locator('.trace-toggle').last().innerText(), /项待确认/)
      assert.match(await h.page.locator('.msg.assistant .md-body').last().innerText(), /已取消/)
      assert.deepEqual(h.pageErrors, [])
      await screenshot(h.page, 'edit-cancelled.png')
    } finally { await h.context.close() }
  })

  await check('IME confirmation and Shift+Enter do not submit a new message', async () => {
    const h = await newPage({ width: 390, height: 844 })
    try {
      const input = h.page.locator('textarea.chat-input')
      await input.fill('合成输入法候选')
      await input.dispatchEvent('compositionstart', { data: '候选' })
      await input.dispatchEvent('keydown', { key: 'Enter', code: 'Enter', isComposing: true, keyCode: 229, bubbles: true })
      await input.dispatchEvent('compositionend', { data: '候选' })
      await input.press('Shift+Enter')
      await h.page.waitForTimeout(250)
      assert.equal(h.streamRequests.length, 0)
      assert.match(await input.inputValue(), /合成输入法候选/)
      assert.ok((await input.inputValue()).includes('\n'))
      assert.equal(await h.page.locator('.msg.user').count(), 0)
      assert.deepEqual(h.pageErrors, [])
      await screenshot(h.page, 'mobile-ime-composer.png')
    } finally { await h.context.close() }
  })
} finally {
  await browser.close()
  const result = { fixture: fixtureUrl.pathname, passed: checks.filter(check => check.ok).length, total: checks.length, checks }
  await writeFile(resolve(output, 'report.json'), JSON.stringify(result, null, 2), 'utf8')
  console.log(`Agent chat browser checks: ${result.passed}/${result.total}`)
  if (result.passed !== result.total) process.exitCode = 1
}
