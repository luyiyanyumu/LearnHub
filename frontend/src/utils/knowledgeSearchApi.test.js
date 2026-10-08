import test from 'node:test'
import assert from 'node:assert/strict'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import request, { kbApi, knowledgeApi } from '../api/index.js'

test('search-owned errors still reject but bypass global toasts, while other callers retain them', async () => {
  const adapter = request.defaults.adapter
  const notify = ElMessage.error
  const messages = []
  ElMessage.error = message => messages.push(message)
  try {
    for (const transportError of [false, true]) {
      request.defaults.adapter = async config => {
        const response = { data: { code: 500, msg: '服务不可用' }, status: transportError ? 500 : 200, statusText: '', headers: {}, config }
        if (transportError) throw new axios.AxiosError('HTTP 500', axios.AxiosError.ERR_BAD_RESPONSE, config, null, response)
        return response
      }
      const count = messages.length
      await assert.rejects(kbApi.search('ReAct', 10, { silentError: true }), /服务不可用/)
      await assert.rejects(knowledgeApi.search('ReAct', { silentError: true }), /服务不可用/)
      assert.equal(messages.length, count)
      await assert.rejects(kbApi.search('ReAct'), /服务不可用/)
      await assert.rejects(knowledgeApi.search('ReAct'), /服务不可用/)
      assert.equal(messages.length, count + 2)
    }
  } finally {
    request.defaults.adapter = adapter
    ElMessage.error = notify
  }
})

test('optional silent search configuration preserves request parameters and successful payloads', async () => {
  const adapter = request.defaults.adapter
  const calls = []
  request.defaults.adapter = async config => {
    calls.push(config)
    return { data: { code: 200, data: config.url === '/kb/search' ? [] : { keyword: '原查询', items: [] } }, status: 200, statusText: '', headers: {}, config }
  }
  try {
    assert.deepEqual(await kbApi.search('问题', 7, { silentError: true }), [])
    assert.deepEqual(await knowledgeApi.search(' 原查询 ', { silentError: true }), { keyword: '原查询', items: [] })
    assert.deepEqual(calls[0].params, { q: '问题', topK: 7 })
    assert.equal(calls[0].timeout, 60000)
    assert.deepEqual(calls[1].params, { kw: ' 原查询 ' })
    assert.ok(calls.every(config => config.silentError === true))
  } finally { request.defaults.adapter = adapter }
})
