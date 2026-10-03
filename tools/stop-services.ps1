<#
    stop-services.ps1  ——  停止 learn-hub 的前后端开发进程（默认后端 18080 / 前端 5174）

    安全设计（避免误杀 Docker）：
      1. 端口若由 Docker 容器发布（docker ps 里能找到 :端口->），只提示不结束进程；
      2. 监听者若属于 Docker Desktop / WSL 端口转发（wslrelay、com.docker.backend 等），
         或可执行文件路径在 Docker 安装目录下，一律跳过；
      3. 只结束其余的进程（开发模式下就是 java / node）。
#>
[CmdletBinding()]
param(
    [int[]]$Ports = @(18080, 5174)
)

$ErrorActionPreference = 'Continue'

# 属于 Docker Desktop / WSL 的进程，一律不结束
$dockerNames = @(
    'wslrelay', 'wslhost', 'wslservice', 'wsl',
    'com.docker.backend', 'com.docker.proxy', 'com.docker.build', 'com.docker.dev-envs',
    'com.docker.service', 'docker', 'dockerd', 'docker-proxy', 'vpnkit'
)

# 端口 -> 发布该端口的容器名
$containerByPort = @{}
try {
    $lines = & docker ps --format '{{.Names}}|{{.Ports}}' 2>$null
    foreach ($line in $lines) {
        $parts = $line -split '\|', 2
        if ($parts.Count -eq 2) {
            foreach ($m in [regex]::Matches($parts[1], ':(\d+)->')) {
                $containerByPort[[int]$m.Groups[1].Value] = $parts[0]
            }
        }
    }
} catch { }

$stoppedCount = 0
$skipped = New-Object System.Collections.Generic.List[string]

foreach ($port in $Ports) {
    $listenerIds = @()
    try {
        $listenerIds = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
                       Select-Object -ExpandProperty OwningProcess -Unique
    } catch { }

    if (-not $listenerIds -or $listenerIds.Count -eq 0) {
        Write-Host ("  端口 {0}：没有正在监听的进程" -f $port)
        continue
    }

    foreach ($procId in $listenerIds) {
        $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
        if (-not $proc) {
            Write-Host ("  端口 {0}：进程 pid={1} 已不存在" -f $port, $procId)
            continue
        }

        $name = $proc.ProcessName
        $path = ''
        try { $path = $proc.Path } catch { }

        if ($containerByPort.ContainsKey($port)) {
            $skipped.Add(("端口 {0} 由 Docker 容器 {1} 发布（当前转发进程 {2}, pid={3}）——未结束。要停容器：docker stop {1}" -f `
                          $port, $containerByPort[$port], $name, $procId))
            continue
        }

        if (($dockerNames -contains $name) -or ($path -match 'DockerDesktop|\\Docker\\')) {
            $skipped.Add(("端口 {0} 的监听者是 Docker/WSL 组件（{1}, pid={2}）——未结束。请通过 Docker Desktop 或 docker 命令管理" -f `
                          $port, $name, $procId))
            continue
        }

        try {
            Stop-Process -Id $procId -Force -ErrorAction Stop
            Write-Host ("  已停止 {0} (pid={1}, 端口 {2})" -f $name, $procId, $port)
            $stoppedCount++
        } catch {
            Write-Host ("  无法停止 {0} (pid={1})：{2}" -f $name, $procId, $_.Exception.Message)
        }
    }
}

Start-Sleep -Milliseconds 1200

Write-Host ''
if ($stoppedCount -gt 0) {
    Write-Host ("  共结束 {0} 个进程。" -f $stoppedCount)
} else {
    Write-Host '  没有需要结束的进程。'
}

if ($skipped.Count -gt 0) {
    Write-Host '  已跳过（保护 Docker，不会误杀）：'
    foreach ($s in $skipped) { Write-Host ('    - ' + $s) }
}

Write-Host ''
Write-Host '  端口复查：'
foreach ($port in $Ports) {
    $still = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($still) {
        Write-Host ("    端口 {0}：仍在监听（若为容器端口属正常）" -f $port)
    } else {
        Write-Host ("    端口 {0}：已释放" -f $port)
    }
}

exit 0
