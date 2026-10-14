// 覆盖 utils/connectionState.js 的链路状态机。
//
// 为什么值得单独测：这个模块是 P0「全局后端不可达横幅」的唯一状态源，
// 横幅出现/消失、以及「4xx 业务失败不该拉横幅」的判定全在这里，
// 但它本身没有任何测试引用（独立验证节点 grep 73 个测试文件，0 命中）。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { backendDown, backendDownReason, isLinkError, markBackendDown, markBackendUp } from './connectionState.js'

/** 模块级 ref 是单例，测试之间必须显式复位，避免互相污染 */
function reset() {
  backendDown.value = false
  backendDownReason.value = ''
}

test('初始状态是「链路正常」且没有降级原因', () => {
  reset()
  assert.equal(backendDown.value, false)
  assert.equal(backendDownReason.value, '')
})

test('markBackendDown 置为 down 并记录原始原因，供横幅展示"为什么"', () => {
  reset()
  const err = new Error('connect ECONNREFUSED 127.0.0.1:8080')
  err.code = 'ECONNREFUSED'
  markBackendDown(err)
  assert.equal(backendDown.value, true)
  assert.equal(backendDownReason.value, 'connect ECONNREFUSED 127.0.0.1:8080')
})

test('markBackendDown 拿到没有 message 的错误时回落到「网络错误」而不是空字符串', () => {
  reset()
  markBackendDown({})
  assert.equal(backendDown.value, true)
  assert.equal(backendDownReason.value, '网络错误')
  reset()
  markBackendDown(undefined)
  assert.equal(backendDownReason.value, '网络错误')
})

test('markBackendUp 把状态恢复成正常并清空原因（横幅随即消失）', () => {
  reset()
  markBackendDown(new Error('boom'))
  assert.equal(backendDown.value, true)
  markBackendUp()
  assert.equal(backendDown.value, false)
  assert.equal(backendDownReason.value, '')
})

test('链路已经正常时再调 markBackendUp 不产生任何变化（拦截器每次成功都会调它，必须廉价）', () => {
  reset()
  backendDownReason.value = '上一次的旧原因'
  markBackendUp()
  assert.equal(backendDown.value, false)
  // 源码在 backendDown 为 false 时提前 return，旧原因不会被顺手改写。
  assert.equal(backendDownReason.value, '上一次的旧原因')
  reset()
})

test('isLinkError：没有 response 的错误算链路层（连接被拒/超时/代理挂了）', () => {
  assert.equal(isLinkError(new Error('Network Error')), true)
  assert.equal(isLinkError({ message: 'timeout of 60000ms exceeded', code: 'ECONNABORTED' }), true)
  assert.equal(isLinkError({ request: {} }), true)
})

test('isLinkError：明确标记的长写入等待超时不证明后端离线', () => {
  for (const code of ['ECONNABORTED', 'ETIMEDOUT']) {
    const error = { code, message: '合成操作可能仍在后台执行', config: { serverMayContinueOnTimeout: true } }
    assert.equal(isLinkError(error), false, code)
    assert.equal(isLinkError({ ...error, config: {} }), true, `${code} without the explicit marker`)
    assert.equal(isLinkError({ ...error, config: { serverMayContinueOnTimeout: false } }), true, `${code} with marker disabled`)
  }
})

test('isLinkError：长任务标记不能隐藏真实网络故障或服务器错误', () => {
  const config = { serverMayContinueOnTimeout: true }
  for (const code of ['ERR_NETWORK', 'ECONNREFUSED', 'ENOTFOUND']) {
    assert.equal(isLinkError({ code, message: '合成连接失败', config }), true, code)
  }
  for (const status of [500, 502, 503, 504]) {
    assert.equal(isLinkError({ code: 'ERR_BAD_RESPONSE', response: { status }, config }), true, `status=${status}`)
  }
})

test('isLinkError：4xx 是业务失败，不拉全局横幅', () => {
  for (const status of [400, 401, 403, 404, 422]) {
    assert.equal(isLinkError({ response: { status } }), false, `status=${status} 不应判为链路错误`)
  }
})

test('isLinkError：5xx 算链路层（请求到了后端，但后端自己起不来，如数据库连不上）', () => {
  // 注意：任务摘要里写的是「有 response 就判 false」，源码比这更细——5xx 判 true。
  // 这条按源码实际实现断言，不做简化。
  for (const status of [500, 502, 503, 504]) {
    assert.equal(isLinkError({ response: { status } }), true, `status=${status} 应判为链路错误`)
  }
})

test('isLinkError：空值不判为链路错误', () => {
  assert.equal(isLinkError(null), false)
  assert.equal(isLinkError(undefined), false)
})

test('接线：成功响应走 markBackendUp、只有链路层失败才 markBackendDown（静态核对 api 拦截器）', () => {
  // 这条是**静态检查**（读 api/index.js 文本），不是运行时验证：
  // 「任一请求成功即自动消失」的语义由拦截器调用 markBackendUp 实现，
  // 本测试只保证这处接线没被删改，不证明拦截器在真实请求中确实按此执行。
  const api = readFileSync(new URL('../api/index.js', import.meta.url), 'utf8')
  const lines = api.split(/\r?\n/)
  const start = lines.findIndex(line => /request\.interceptors\.response\.use\(/.test(line))
  assert.ok(start >= 0, 'api/index.js 里应存在 response 拦截器')
  const errHandler = lines.findIndex((line, i) => i > start && /^\s*\(err\)\s*=>\s*\{/.test(line))
  assert.ok(errHandler > start, '拦截器应有失败分支')
  const okBranch = lines.slice(start, errHandler).join('\n')
  const errBranch = lines.slice(errHandler, errHandler + 15).join('\n')
  assert.match(okBranch, /markBackendUp\(\)/, '成功分支应调用 markBackendUp()')
  assert.match(errBranch, /if\s*\(isLinkError\(err\)\)\s*markBackendDown\(err\)/, '失败分支应只在 isLinkError 时登记')
})
