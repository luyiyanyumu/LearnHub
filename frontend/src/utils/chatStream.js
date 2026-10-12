/** Read a chat event stream without retrying operations after an ambiguous disconnect. */
export async function postChatStream(path, data, onEvent, signal, fetcher = globalThis.fetch) {
  const response = await fetcher(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify(data),
    signal,
  })
  if (!response.ok) {
    let message = `请求失败（HTTP ${response.status}）`
    try {
      const body = await response.json()
      message = body?.msg || body?.message || message
    } catch { /* Keep the status when a proxy returned non-JSON. */ }
    const error = new Error(message)
    error.status = response.status
    throw error
  }
  if (!response.headers.get('content-type')?.includes('text/event-stream')) {
    throw new Error('服务未返回对话进度，请检查后端版本')
  }
  if (!response.body) throw new Error('浏览器无法读取流式响应')
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let eventName = 'message'
  let dataLines = []
  let completed = false
  let result
  const dispatch = () => {
    if (!dataLines.length) { eventName = 'message'; return }
    const raw = dataLines.join('\n')
    const event = eventName
    dataLines = []
    eventName = 'message'
    let payload
    try { payload = JSON.parse(raw) }
    catch { throw new Error('对话进度格式错误，请重新打开会话查看结果') }
    if (event === 'error') throw new Error(payload?.message || payload?.error || '本轮处理失败')
    onEvent?.(event, payload)
    if (event === 'result') { completed = true; result = payload }
  }
  const line = (value) => {
    if (!value) return dispatch()
    if (value.startsWith(':')) return
    const separator = value.indexOf(':')
    const field = separator < 0 ? value : value.slice(0, separator)
    const valueText = separator < 0 ? '' : value.slice(separator + 1).replace(/^ /, '')
    if (field === 'event') eventName = valueText
    else if (field === 'data') dataLines.push(valueText)
  }
  const consume = (final = false) => {
    for (;;) {
      const end = buffer.search(/[\r\n]/)
      if (end < 0) break
      // A CR may be the first half of CRLF in the next network chunk.
      if (buffer[end] === '\r' && end === buffer.length - 1 && !final) break
      const width = buffer[end] === '\r' && buffer[end + 1] === '\n' ? 2 : 1
      const value = buffer.slice(0, end)
      buffer = buffer.slice(end + width)
      line(value)
      if (completed) break
    }
    if (final && !completed) {
      if (buffer) line(buffer)
      buffer = ''
      dispatch()
    }
  }
  try {
    for (;;) {
      if (signal?.aborted) throw new DOMException('已停止接收进度', 'AbortError')
      const chunk = await reader.read()
      buffer += chunk.done ? decoder.decode() : decoder.decode(chunk.value, { stream: true })
      consume(chunk.done)
      if (completed) return result
      if (chunk.done) throw new Error('连接已中断，后台可能仍在处理；可重新打开此会话查看结果')
    }
  } finally {
    try { await reader.cancel() } catch { /* A disconnected body is already closed. */ }
    reader.releaseLock()
  }
}
