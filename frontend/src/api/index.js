import axios from 'axios'
import { ElMessage } from 'element-plus'

// 统一请求实例：/api 在开发环境由 Vite 代理到后端 8080
const request = axios.create({ baseURL: '/api', timeout: 15000 })

request.interceptors.response.use(
  (resp) => {
    // 二进制响应（文件下载）直接透传
    if (resp.data instanceof Blob) {
      return resp
    }
    const body = resp.data
    if (body && body.code === 200) {
      return body.data
    }
    ElMessage.error((body && body.msg) || '请求失败')
    return Promise.reject(new Error((body && body.msg) || '请求失败'))
  },
  (err) => {
    ElMessage.error(err.response?.data?.msg || err.message || '网络错误')
    return Promise.reject(err)
  },
)

export const categoryApi = {
  tree: () => request.get('/categories'),
  add: (data) => request.post('/categories', data),
  update: (id, data) => request.put(`/categories/${id}`, data),
  remove: (id) => request.delete(`/categories/${id}`),
}

export const tagApi = {
  list: () => request.get('/tags'),
  add: (name) => request.post('/tags', { name }),
  rename: (id, name) => request.put(`/tags/${id}`, { name }),
  remove: (id) => request.delete(`/tags/${id}`),
}

export const noteApi = {
  page: (params) => request.get('/notes', { params }),
  detail: (id) => request.get(`/notes/${id}`),
  add: (data) => request.post('/notes', data),
  update: (id, data) => request.put(`/notes/${id}`, data),
  remove: (id) => request.delete(`/notes/${id}`),
}

export const quickRefApi = {
  list: (params) => request.get('/quick-refs', { params }),
  detail: (id) => request.get(`/quick-refs/${id}`),
  add: (data) => request.post('/quick-refs', data),
  update: (id, data) => request.put(`/quick-refs/${id}`, data),
  remove: (id) => request.delete(`/quick-refs/${id}`),
}

export const statsApi = {
  dashboard: () => request.get('/stats'),
}

export const knowledgeApi = {
  /** 统一检索：{ keyword, total, items:[{type:'note'|'quick_ref', id, title, snippet, categoryName, updatedAt}] }；kw 空则返回最近知识 */
  search: (kw) => request.get('/knowledge/search', { params: { kw } }),
}

// AI 相关请求的统一超时：思考模式（DeepSeek V4 / Kimi K3 / GLM-5.3 等）会让长文处理
// 比 90 秒慢得多（实测 4863 字润色 81.6s，更长文档直接破 90s），而对齐后端 HttpClient 的 300s。
const AI_TIMEOUT = 300000

export const aiApi = {
  /** 配置状态：{ configured, model } */
  status: () => request.get('/ai/status'),
  /** 语言润色 / 整理格式：{ text, mode: 'polish'|'format' } → 处理后的 Markdown */
  polish: (data) => request.post('/ai/polish', data, { timeout: AI_TIMEOUT }),
  /** 智能体对话：{ message, history, noteId?, noteTitle?, noteContext? } → AiChatVO（最多 8 轮工具调用，耗时叠加） */
  chat: (data) => request.post('/ai/chat', data, { timeout: AI_TIMEOUT }),
  /** 连通性测试：用传入的（含未保存的）配置发一次最小请求 → 成功文案 */
  test: (data) => request.post('/ai/test', data || {}, { timeout: AI_TIMEOUT }),
}

export const settingsApi = {
  /** 当前生效值 + 内置默认值 */
  get: () => request.get('/settings'),
  /** 批量更新；value 空白 = 恢复默认；即时生效 */
  update: (data) => request.put('/settings', data),
}

export const fileApi = {
  list: (params) => request.get('/files', { params }),
  upload: (file, categoryId) => {
    const fd = new FormData()
    fd.append('file', file)
    if (categoryId) fd.append('categoryId', categoryId)
    return request.post('/files/upload', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
  },
  /** 下载二进制：返回 axios response（blob） */
  download: (id) => request.get(`/files/${id}/download`, { responseType: 'blob' }),
  remove: (id) => request.delete(`/files/${id}`),
}

/** 触发浏览器保存文件 */
export function saveBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

export default request
