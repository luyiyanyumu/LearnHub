const names = { frontend: '前端服务', backend: '后端服务', mysql: 'MySQL 数据库' }
const actions = { 'start-all': '启动全部服务', 'stop-all': '停止全部服务', start: '启动服务', stop: '停止服务', restart: '重启服务', build: '构建并更新前后端', diagnose: '环境诊断', 'docker-start': '启动 Docker Desktop' }

// Browser previews never contact localhost or invoke native service commands.
export function createDemo(emit = () => {}) {
  const preferences = { projectRoot: 'C:\\Demo\\LearnHub', mode: 'docker', theme: 'system', autoRefresh: true }
  let states = { frontend: 'healthy', backend: 'healthy', mysql: 'healthy' }
  let sourceVersion = 'v0.1.4'
  let launcherVersion = '1.1.0'
  let selfUpdateControl = null
  const launcherHash = 'ab'.repeat(32)
  function launcherStatus() {
    const current = launcherVersion === '1.2.0'
    return { currentVersion: launcherVersion, latestVersion: '1.2.0', state: current ? 'current' : 'available',
      detail: current ? '演示自更新已完成，浏览器和真实服务保持运行。' : '合成演示：提供适用于 Windows x64 的 EXE 更新包。',
      releaseUrl: 'https://github.com/example/learnhub-demo/releases/tag/launcher-v1.2.0',
      downloadUrl: 'https://github.com/example/learnhub-demo/releases/download/launcher-v1.2.0/LearnHub-Launcher-win-x64.exe',
      sha256: launcherHash, size: 24 * 1024 * 1024, runtime: 'win-x64', checkedAt: new Date().toISOString() }
  }
  function updateStatus() {
    const updated = sourceVersion === 'v0.1.5'
    return {
      currentVersion: sourceVersion, sourceDescription: sourceVersion, currentCommit: updated ? 'b5d9e7a42b8a135c79ef02468acdef0012345678' : 'a41c8f2300bb42d189ef02468acdef0012345678',
      branch: 'main', dirty: false, changeCount: 0, latestVersion: 'v0.1.5', latestCommit: 'b5d9e7a42b8a135c79ef02468acdef0012345678',
      releaseName: 'LearnHub v0.1.5 · 稳定版', releaseNotes: '以下内容为合成发布说明，仅用于界面演示。\n\n新增\n• 优化笔记工作空间的日常操作体验。\n• 增加更清晰的服务健康与运行状态提示。\n\n改进\n• 修复页面切换和长日志查看时的交互问题。\n• 完善深色主题与高分辨率窗口的布局。\n\n应用更新\n源码更新后，手动构建并应用前后端；当前运行的服务不会自动重启。',
      releaseUrl: 'https://github.com/example/learnhub-demo/releases/tag/v0.1.5', publishedAt: '2026-10-20T09:00:00+08:00',
      canUpdate: !updated, updateAvailable: !updated, blockedReason: updated ? '当前源码已包含官方最新稳定版，无需更新。' : null,
      checkedAt: new Date().toISOString(), repository: 'example/learnhub-demo（合成仓库）', launcherAssetUrl: null,
    }
  }
  function snapshot(mode = preferences.mode) {
    const local = mode === 'local'
    return {
      projectRoot: preferences.projectRoot, mode, checkedAt: new Date().toISOString(),
      docker: { installed: true, ready: true, version: '28.0.0（示例）', context: 'desktop-linux', error: null },
      services: Object.keys(names).map((id, index) => ({
        id, name: names[id], kind: local ? 'process' : 'container', state: states[id],
        detail: states[id] === 'healthy' ? (id === 'mysql' ? '数据库连接正常（示例）' : '健康检查通过（示例）') : '服务已停止（示例）',
        port: [8888, 8000, 3306][index], address: id === 'mysql' ? null : `http://localhost:${[8888, 8000][index]}`,
        containerId: local ? null : `demo000${index}`, containerName: local ? null : `learnhub-${id}-1`,
        image: local ? null : (id === 'mysql' ? 'mysql:8.0' : `learnhub-${id}:latest`),
        uptime: states[id] === 'healthy' ? '2 小时（示例）' : null,
        responseMs: states[id] === 'healthy' ? [12, 24, 4][index] : null,
        processId: local && states[id] === 'healthy' ? 12000 + index : null,
        canStart: true, canStop: true, canRestart: true,
      })),
      diagnostics: [
        { level: 'success', title: '项目结构完整', detail: '已找到前端、后端和 Compose 配置（合成数据）。' },
        { level: 'success', title: 'Docker 引擎就绪', detail: 'desktop-linux · Docker 28.0.0（合成数据）。' },
        { level: 'success', title: '服务端口可用', detail: '8888、8000、3306 均由当前示例项目使用（合成数据）。' },
        { level: 'info', title: '数据持久化', detail: '停止服务会保留容器与数据卷。此预览不会接触真实数据。' },
      ],
    }
  }
  return { async request(command, payload = {}) {
    if (selfUpdateControl && ['action', 'update-source', 'install-launcher-update'].includes(command)) throw new Error('已有启动器更新任务正在运行。')
    if (command === 'bootstrap') return { preferences: { ...preferences }, display: { scale: 1, width: 1920, height: 1080 }, version: launcherVersion, demo: true }
    if (command === 'snapshot') return snapshot(payload.mode)
    if (command === 'preferences') return Object.assign(preferences, payload)
    if (command === 'select-project') return { ...preferences }
    if (command === 'logs') return `[演示日志 · 合成数据]\n10:24:01 [INFO] ${names[payload.service] ?? '服务'} 正在检查运行状态\n10:24:01 [INFO] 已载入项目服务配置\n10:24:02 [INFO] 服务健康检查通过\n10:24:02 [INFO] 以上内容仅用于界面预览，未连接真实服务`
    if (command === 'check-updates') return updateStatus()
    if (command === 'check-launcher-update') return launcherStatus()
    if (command === 'install-launcher-update') {
      const status = launcherStatus()
      if (status.state !== 'available' || payload.version !== status.latestVersion || payload.sha256 !== launcherHash) throw new Error('启动器更新目标已变化，请重新检查。')
      const job = { running: true, action: 'launcher-update', startedAt: new Date().toISOString() }
      const control = { cancelled: false, phase: 'downloading' }
      selfUpdateControl = control
      emit('job', job)
      const progress = (phase, percent) => {
        control.phase = phase
        emit('launcher-update-progress', { phase, percent, downloadedBytes: Math.round(status.size * percent / 100), totalBytes: status.size, version: status.latestVersion })
      }
      try {
        for (const percent of [15, 35, 60, 85, 100]) {
          if (control.cancelled) throw new Error('演示启动器下载已取消。')
          progress('downloading', percent)
          await new Promise(resolve => setTimeout(resolve, 200))
        }
        if (control.cancelled) throw new Error('演示启动器下载已取消。')
        progress('verifying', 100)
        await new Promise(resolve => setTimeout(resolve, 180))
        progress('restarting', 100)
        await new Promise(resolve => setTimeout(resolve, 180))
        launcherVersion = status.latestVersion
        emit('log', { timestamp: new Date().toISOString(), level: 'info', message: '[演示] 启动器下载、校验及重启流程完成，未更改真实 EXE 或服务。' })
        return true
      } finally {
        selfUpdateControl = null
        emit('job', { ...job, running: false })
      }
    }
    if (command === 'cancel' && selfUpdateControl) {
      if (selfUpdateControl.phase !== 'downloading') throw new Error('校验或重启阶段请等待任务完成。')
      selfUpdateControl.cancelled = true
      return true
    }
    if (command === 'update-source') {
      const status = updateStatus()
      if (!status.canUpdate || payload.version !== status.latestVersion || payload.commit !== status.latestCommit) throw new Error('示例更新目标已变化，请重新检查更新。')
      const job = { running: true, action: 'update-source', startedAt: new Date().toISOString() }
      emit('job', job)
      sourceVersion = status.latestVersion
      emit('log', { timestamp: new Date().toISOString(), level: 'info', message: '[演示] 项目源码已更新到 v0.1.5，运行服务保持不变。' })
      emit('job', { ...job, running: false })
      return updateStatus()
    }
    if (['open-release', 'open-launcher-release'].includes(command)) return true
    if (command === 'action') {
      const { action, service } = payload
      const job = { running: true, action, service, startedAt: new Date().toISOString() }
      emit('job', job)
      emit('log', { timestamp: new Date().toISOString(), level: 'info', message: `[演示] ${actions[action] ?? action}${service ? ` · ${names[service] ?? service}` : ''}` })
      if (action === 'stop-all') states = Object.fromEntries(Object.keys(states).map(id => [id, 'stopped']))
      if (['start-all', 'build'].includes(action)) states = Object.fromEntries(Object.keys(states).map(id => [id, 'healthy']))
      if (service && ['start', 'restart', 'stop'].includes(action)) states[service] = action === 'stop' ? 'stopped' : 'healthy'
      emit('job', { ...job, running: false })
      return true
    }
    if (['cancel', 'open', 'export-diagnostics'].includes(command)) return true
    throw new Error(`预览不支持命令：${command}`)
  } }
}
