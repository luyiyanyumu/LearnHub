import test from 'node:test'
import assert from 'node:assert/strict'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import request, { codeApi } from '../api/index.js'

test('agentic API posts a scoped body with a multi-round timeout and abort signal', async () => {
  const adapter = request.defaults.adapter
  const controller = new AbortController()
  const calls = []
  request.defaults.adapter = async config => {
    calls.push(config)
    return { data: { code: 200, data: { status: 'fallback', results: [] } }, status: 200, statusText: '', headers: {}, config }
  }
  try {
    const body = { question: '合成检索问题', repoId: 5, lang: 'java', limit: 8 }
    assert.deepEqual(await codeApi.agenticSearch(body, controller.signal), { status: 'fallback', results: [] })
    assert.equal(calls[0].url, '/code/agentic-search')
    assert.equal(calls[0].method, 'post')
    assert.deepEqual(JSON.parse(calls[0].data), body)
    assert.equal(calls[0].signal, controller.signal)
    assert.ok(calls[0].timeout >= 300000)
    assert.equal(calls[0].silentError, true)
  } finally { request.defaults.adapter = adapter }
})

test('agentic failures are owned by the active page and cancellation produces no global toast', async () => {
  const adapter = request.defaults.adapter
  const notify = ElMessage.error
  const messages = []
  ElMessage.error = message => messages.push(message)
  try {
    for (const cancel of [false, true]) {
      request.defaults.adapter = async config => {
        if (cancel) throw new axios.CanceledError('已取消', config)
        throw new axios.AxiosError('服务暂时不可用', axios.AxiosError.ERR_BAD_RESPONSE, config)
      }
      await assert.rejects(codeApi.agenticSearch({ question: '合成问题', limit: 8 }))
    }
    assert.deepEqual(messages, [])
  } finally {
    request.defaults.adapter = adapter
    ElMessage.error = notify
  }
})
