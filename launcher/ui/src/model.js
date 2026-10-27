export const stateLabels = { healthy: '健康', running: '运行中', starting: '启动中', stopped: '已停止', unhealthy: '异常', conflict: '端口冲突', unknown: '未确认', missing: '缺少依赖' }
export const actionLabels = { 'start-all': '启动全部服务', 'stop-all': '停止全部服务', start: '启动服务', stop: '停止服务', restart: '重启服务', build: '构建并更新前后端', diagnose: '检查环境', 'docker-start': '启动 Docker Desktop', 'update-source': '更新项目源码', 'launcher-update': '更新启动器' }
export function canUpdateSource(info) {
  return !!(info?.canUpdate && info.updateAvailable && !info.dirty && info.latestVersion && info.latestCommit)
}
export function versionSummary(info) {
  if (!info) return { kind: 'unknown', title: '检查官方稳定版', detail: '点击“检查更新”，读取当前源码并查询官方发布。' }
  if (!info.latestVersion) return { kind: 'unknown', title: '暂未获取最新稳定版', detail: info.blockedReason || '请重新检查更新，或查看官方发布页面。' }
  if (!info.currentCommit || !info.latestCommit) return { kind: 'unknown', title: '源码更新暂不可用', detail: info.blockedReason || '源码提交信息尚未完整，请重新检查更新。' }
  if (info.updateAvailable) return { kind: canUpdateSource(info) ? 'available' : 'blocked', title: `发现新版本 ${info.latestVersion}`, detail: info.blockedReason || (info.dirty ? '当前目录存在本地改动，请先处理改动后再更新。' : canUpdateSource(info) ? '可将项目源码更新到官方稳定版。' : '当前源码尚不满足自动更新条件，请查看官方发布页面或重新检查。') }
  return { kind: info.dirty ? 'blocked' : 'current', title: '已包含最新稳定版', detail: info.blockedReason || (info.dirty ? '当前目录存在本地改动，已启用更新保护。' : '当前源码已包含官方最新稳定版，无需更新。') }
}
export function healthSummary(snapshot) {
  if (!snapshot) return { state: 'unknown', title: '等待检查运行状态', detail: '正在连接启动器并读取服务状态。', healthy: 0, total: 0 }
  const services = snapshot.services ?? []
  const healthy = services.filter(s => s.state === 'healthy').length
  if (!services.length) return { state: 'unknown', title: '尚未发现服务', detail: '检查项目目录及运行模式，然后重新检测。', healthy, total: 0 }
  if (healthy === services.length) return { state: 'healthy', title: '所有服务运行正常', detail: '各服务健康检查已通过，可以打开工作台。', healthy, total: services.length }
  if (services.every(s => s.state === 'stopped')) return { state: 'stopped', title: '服务已停止', detail: '启动全部服务，即可继续你的学习工作台。', healthy, total: services.length }
  if (services.some(s => ['unhealthy', 'conflict', 'missing'].includes(s.state))) return { state: 'unhealthy', title: '部分服务需要关注', detail: '查看下方异常详情，或运行环境诊断定位问题。', healthy, total: services.length }
  return { state: 'starting', title: '部分服务尚未就绪', detail: '查看服务状态及日志，启动完成后会自动更新。', healthy, total: services.length }
}
export function readableError(error) { return error?.message || String(error || '操作未完成，请查看运行日志。') }
export function formatTime(value) {
  if (!value) return '尚未检查'
  const date = new Date(value)
  return Number.isNaN(+date) ? value : date.toLocaleTimeString('zh-CN', { hour12: false })
}
