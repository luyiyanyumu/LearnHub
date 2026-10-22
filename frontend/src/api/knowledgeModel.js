import axios from 'axios'

// Keep model-service outages local to this page. A busy CUDA worker does not
// mean LearnHub's database or the rest of the backend is unavailable.
const request = axios.create({ baseURL: '/api', timeout: 15000 })

request.interceptors.response.use(response => {
  const body = response.data
  if (body?.code === 200) return body.data
  const error = new Error(body?.msg || '本地知识模型请求失败')
  error.status = body?.code || response.status
  return Promise.reject(error)
}, error => {
  if (axios.isCancel(error)) return Promise.reject(error)
  const timedOut = ['ECONNABORTED', 'ETIMEDOUT'].includes(error.code)
  error.status = error.response?.status
  error.message = error.response?.data?.msg || error.response?.data?.detail || (timedOut
    ? '等待本地模型超时。首次加载或训练可能较慢，可稍后重试。'
    : '暂时无法连接本地知识模型，请检查后端与训练服务。')
  return Promise.reject(error)
})

export const knowledgeModelApi = {
  status: signal => request.get('/knowledge-model/status', { signal }),
  jobs: signal => request.get('/knowledge-model/jobs', { signal }),
  settings: enabled => request.put('/knowledge-model/settings', { enabled }),
  retry: id => request.post(`/knowledge-model/jobs/${encodeURIComponent(id)}/retry`),
  enqueue: id => request.post(`/knowledge-model/files/${encodeURIComponent(id)}/enqueue`),
  query: (data, signal) => request.post('/knowledge-model/query', data, { timeout: 300000, signal }),
  files: (kw, signal) => request.get('/files/page', { params: { kw, page: 1, size: 30 }, signal }),
}
