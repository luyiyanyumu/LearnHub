import { createDemo } from './demo.js'

export function createBridge(webview = globalThis.window?.chrome?.webview, options = {}) {
  const pending = new Map()
  const listeners = new Map()
  const timeoutMs = options.timeoutMs ?? 30000
  let serial = 0
  let closed = false
  const emit = (type, value) => {
    for (const listener of listeners.get(type) ?? []) listener(value)
  }
  const receive = ({ data }) => {
    if (!data || typeof data !== 'object') return
    if (data.type) {
      emit(data.type, data.type === 'log' ? data.entry : data.type === 'job' ? data.job : data)
      return
    }
    const item = pending.get(data.id)
    if (!item) return
    clearTimeout(item.timer)
    pending.delete(data.id)
    if (data.error) item.reject(new Error(typeof data.error === 'string' ? data.error : data.error.message ?? '启动器操作失败'))
    else item.resolve(data.result)
  }
  webview?.addEventListener('message', receive)
  const demo = webview ? null : createDemo(emit)
  return {
    native: !!webview,
    request(command, payload = {}, requestOptions = {}) {
      if (closed) return Promise.reject(new Error('启动器连接已关闭'))
      if (demo) return demo.request(command, payload)
      const id = `lh-${Date.now()}-${++serial}`
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          pending.delete(id)
          const error = new Error(['action', 'update-source', 'install-launcher-update'].includes(command) ? '任务等待超时，结果尚未确认。请刷新状态或取消任务后检查日志。' : '启动器响应超时，请重试或检查运行日志。')
          error.code = 'timeout'
          reject(error)
        }, requestOptions.timeoutMs ?? (['action', 'update-source', 'install-launcher-update'].includes(command) ? 15 * 60 * 1000 : timeoutMs))
        pending.set(id, { resolve, reject, timer })
        try { webview.postMessage({ id, command, payload }) }
        catch (error) {
          clearTimeout(timer)
          pending.delete(id)
          reject(error)
        }
      })
    },
    on(type, listener) {
      if (!listeners.has(type)) listeners.set(type, new Set())
      listeners.get(type).add(listener)
      return () => listeners.get(type)?.delete(listener)
    },
    dispose() {
      closed = true
      webview?.removeEventListener('message', receive)
      for (const item of pending.values()) {
        clearTimeout(item.timer)
        item.reject(new Error('启动器连接已关闭'))
      }
      pending.clear()
      listeners.clear()
    },
  }
}
