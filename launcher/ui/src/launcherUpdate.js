function parseVersion(value) {
  const match = /^v?(\d+)\.(\d+)\.(\d+)(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$/.exec(String(value ?? ''))
  return match ? { numbers: match.slice(1, 5).map(part => Number(part ?? 0)), pre: match[5]?.split('.') ?? [] } : null
}

// SemVer precedence, with a fourth numeric component for Windows assembly versions.
export function compareVersions(left, right) {
  const a = parseVersion(left), b = parseVersion(right)
  if (!a || !b) return null
  for (let index = 0; index < 4; index++) if (a.numbers[index] !== b.numbers[index]) return a.numbers[index] > b.numbers[index] ? 1 : -1
  if (!a.pre.length && b.pre.length) return 1
  if (a.pre.length && !b.pre.length) return -1
  for (let index = 0; index < Math.max(a.pre.length, b.pre.length); index++) {
    const x = a.pre[index], y = b.pre[index]
    if (x === y) continue
    if (x === undefined) return -1
    if (y === undefined) return 1
    const xn = /^\d+$/.test(x), yn = /^\d+$/.test(y)
    if (xn && yn) return Number(x) > Number(y) ? 1 : -1
    if (xn !== yn) return xn ? -1 : 1
    return x > y ? 1 : -1
  }
  return 0
}

export function canInstallLauncherUpdate(info) {
  if (!info || info.state !== 'available' || compareVersions(info.latestVersion, info.currentVersion) !== 1 || !/^[a-fA-F0-9]{64}$/.test(info.sha256 ?? '')) return false
  try { return new URL(info.downloadUrl).protocol === 'https:' } catch { return false }
}

export function launcherUpdateSummary(info) {
  if (!info) return { kind: 'unknown', title: '检查启动器新版', detail: '手动检查官方是否提供适用于当前系统的 EXE 更新包。' }
  if (info.state === 'unavailable') return { kind: 'unknown', title: '暂无可用的启动器更新包', detail: info.detail || '官方尚未提供适用于当前系统的启动器更新包。' }
  const comparison = compareVersions(info.latestVersion, info.currentVersion)
  if (comparison !== null && comparison <= 0) return { kind: 'current', title: comparison < 0 ? '当前版本高于最新发布版' : '启动器已是最新版本', detail: info.detail || '无需更新启动器。' }
  if (canInstallLauncherUpdate(info)) return { kind: 'available', title: `启动器 ${info.latestVersion} 已就绪`, detail: info.detail || '可一键下载、校验并重启到新版启动器。' }
  return { kind: 'blocked', title: '启动器更新信息暂不可用', detail: info.detail || '更新版本或校验信息不完整，请重新检查。' }
}

export function normalizeLauncherProgress(event) {
  if (!event || !['downloading', 'verifying', 'restarting'].includes(event.phase)) return null
  const bytes = value => typeof value === 'number' && Number.isFinite(value) ? Math.max(0, value) : 0
  const downloadedBytes = bytes(event.downloadedBytes), totalBytes = bytes(event.totalBytes)
  const raw = typeof event.percent === 'number' && Number.isFinite(event.percent) ? event.percent : totalBytes > 0 ? downloadedBytes / totalBytes * 100 : 0
  return { phase: event.phase, downloadedBytes, totalBytes, percent: Math.max(0, Math.min(100, raw)), version: event.version ?? null }
}
export function canCancelLauncherUpdate(progress) { return progress?.phase === 'downloading' }
export function launcherPhaseLabel(progress) { return { downloading: '正在下载更新包', verifying: '正在校验更新包', restarting: '正在替换并重启启动器' }[progress?.phase] ?? '正在准备启动器更新' }
export function formatBytes(value) {
  if (!(value > 0)) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB'], index = Math.min(3, Math.floor(Math.log(value) / Math.log(1024)))
  return `${(value / 1024 ** index).toFixed(index ? 1 : 0)} ${units[index]}`
}
