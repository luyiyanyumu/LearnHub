import test from 'node:test'
import assert from 'node:assert/strict'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import request, { aiApi } from '../api/index.js'
import { backendDown, backendDownReason, markBackendUp } from './connectionState.js'

const ACTION_ID = 900001
const timeoutMessage = '等待执行结果超时，操作可能仍在后台执行，请查看执行状态'
const response = (config, data) => ({ data: { code: 200, data }, status: 200, statusText: '', headers: {}, config })

// The adapter never performs HTTP; all action IDs, states and errors are synthetic.
async function withAdapter(adapter, run) {
  const previousAdapter = request.defaults.adapter
  const previousNotify = ElMessage.error
  const messages = []
  backendDown.value = false
  backendDownReason.value = ''
  request.defaults.adapter = adapter
  ElMessage.error = message => messages.push(message)
  try { await run(messages) } finally {
    request.defaults.adapter = previousAdapter
    ElMessage.error = previousNotify
    backendDown.value = false
    backendDownReason.value = ''
  }
}

test('approval uses the bounded long-task timeout and silent uncertain-outcome contract', async () => {
  const calls = []
  await withAdapter(async config => {
    calls.push(config)
    return response(config, { ok: true, hint: '合成操作已执行' })
  }, async messages => {
    assert.deepEqual(await aiApi.approveAction(ACTION_ID), { ok: true, hint: '合成操作已执行' })
    assert.equal(calls.length, 1)
    assert.equal(calls[0].url, `/ai/actions/${ACTION_ID}/approve`)
    assert.equal(calls[0].method, 'post')
    assert.deepEqual(JSON.parse(calls[0].data), {})
    assert.equal(calls[0].timeout, 300000)
    assert.equal(calls[0].silentError, true)
    assert.equal(calls[0].serverMayContinueOnTimeout, true)
    assert.equal(calls[0].timeoutErrorMessage, timeoutMessage)
    assert.equal(request.defaults.timeout, 15000)
    assert.deepEqual(messages, [])
  })
})

test('large research chat has its own wait while approvals and polishing keep the five-minute limit', async () => {
  const calls = []
  await withAdapter(async config => { calls.push(config); return response(config, { reply: '合成回答' }) }, async () => {
    await aiApi.chat({ message: '合成大资料问题' })
    await aiApi.approveAction(ACTION_ID)
    await aiApi.polish({ text: '合成编辑内容', mode: 'polish' })
    assert.deepEqual(calls.map(config => [config.url, config.timeout]), [
      ['/ai/chat', 960000], [`/ai/actions/${ACTION_ID}/approve`, 300000], ['/ai/polish', 300000],
    ])
    assert.equal(request.defaults.timeout, 15000)
  })
})

test('action state is one silent read-only GET with the ordinary 15-second timeout', async () => {
  const calls = []
  const state = { id: ACTION_ID, status: 'executing', toolName: 'add_file_from_url', summary: '合成文件执行中' }
  await withAdapter(async config => {
    calls.push(config)
    return response(config, state)
  }, async messages => {
    assert.deepEqual(await aiApi.actionState(ACTION_ID), state)
    assert.equal(calls.length, 1)
    assert.equal(calls[0].method, 'get')
    assert.equal(calls[0].url, `/ai/actions/${ACTION_ID}`)
    assert.equal(calls[0].timeout, 15000)
    assert.equal(calls[0].silentError, true)
    assert.equal(calls[0].data, undefined)
    assert.notEqual(calls[0].serverMayContinueOnTimeout, true)
    assert.deepEqual(messages, [])
  })
})

test('approval timeouts reject in Chinese without going offline or automatically replaying the write', async () => {
  for (const code of ['ECONNABORTED', 'ETIMEDOUT']) {
    const calls = []
    await withAdapter(async config => {
      calls.push(config)
      if (config.method === 'post') throw new axios.AxiosError(config.timeoutErrorMessage, code, config)
      return response(config, { id: ACTION_ID, status: 'executing' })
    }, async messages => {
      await assert.rejects(aiApi.approveAction(ACTION_ID), error => {
        assert.equal(error.code, code)
        assert.equal(error.message, timeoutMessage)
        return true
      })
      await new Promise(resolve => setImmediate(resolve))
      assert.equal(calls.length, 1, 'the API must not retry approval or silently issue recovery requests')
      assert.equal(backendDown.value, false)
      assert.equal(backendDownReason.value, '')
      assert.deepEqual(messages, [])

      // Recovery is an explicit status read; it cannot become another approval.
      assert.deepEqual(await aiApi.actionState(ACTION_ID), { id: ACTION_ID, status: 'executing' })
      assert.deepEqual(calls.map(config => [config.method, config.url]), [
        ['post', `/ai/actions/${ACTION_ID}/approve`], ['get', `/ai/actions/${ACTION_ID}`],
      ])
      assert.deepEqual(messages, [])
    })
  }
})

test('ordinary status-query timeouts still mark the backend offline without retrying', async () => {
  for (const code of ['ECONNABORTED', 'ETIMEDOUT']) {
    const calls = []
    await withAdapter(async config => {
      calls.push(config)
      throw new axios.AxiosError('合成普通查询超时', code, config)
    }, async messages => {
      await assert.rejects(aiApi.actionState(ACTION_ID), /合成普通查询超时/)
      assert.equal(calls.length, 1)
      assert.equal(backendDown.value, true)
      assert.equal(backendDownReason.value, '合成普通查询超时')
      assert.deepEqual(messages, [])
    })
  }
})

test('real network errors remain outage signals even for a marked long approval request', async () => {
  let calls = 0
  await withAdapter(async config => {
    calls++
    throw new axios.AxiosError('合成 Network Error', axios.AxiosError.ERR_NETWORK, config)
  }, async messages => {
    await assert.rejects(aiApi.approveAction(ACTION_ID), /合成 Network Error/)
    assert.equal(calls, 1)
    assert.equal(backendDown.value, true)
    assert.equal(backendDownReason.value, '合成 Network Error')
    assert.deepEqual(messages, [])
    markBackendUp()
    assert.equal(backendDown.value, false)
  })
})
