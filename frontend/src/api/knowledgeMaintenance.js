import request from './index.js'

// Status polling is read-only. A run request only queues work in the backend.
export const knowledgeMaintenanceApi = {
  status: signal => request.get('/knowledge/maintenance', { signal, silentError: true }),
  settings: enabled => request.put('/knowledge/maintenance', { enabled }, { silentError: true }),
  run: () => request.post('/knowledge/maintenance/run', {}, { silentError: true }),
}
