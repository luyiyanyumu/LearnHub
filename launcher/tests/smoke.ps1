[CmdletBinding()]
param([string] $Image = 'learn-hub-frontend:local', [string] $Launcher)

# Real Docker acceptance against a disposable, synthetic project. Never starts,
# stops or rebuilds the user's LearnHub project, and never mounts data volumes.
$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$launcherFile = if ($Launcher) { [IO.Path]::GetFullPath($Launcher) } else { Join-Path $repositoryRoot 'LearnHub.exe' }
if (-not (Test-Path -LiteralPath $launcherFile)) { throw 'Build LearnHub.exe first.' }
if ($Image -notmatch '^[A-Za-z0-9._/:@-]+$') { throw 'Invalid fixture image name.' }
& docker image inspect $Image --format '{{.Id}}' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Use an already-installed Linux image with /bin/sh; this test does not pull images.' }

$smokeName = 'learnhub-launcher-smoke-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$smokeRoot = Join-Path $repositoryRoot ('tmp\' + $smokeName)
$smokeDeploy = Join-Path $smokeRoot 'deploy'
foreach ($directory in @($smokeDeploy, (Join-Path $smokeRoot 'backend'), (Join-Path $smokeRoot 'frontend'))) {
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
}
[IO.File]::WriteAllText((Join-Path $smokeRoot 'backend\pom.xml'), '<project />')
[IO.File]::WriteAllText((Join-Path $smokeRoot 'frontend\package.json'), '{"name":"synthetic-launcher-smoke","private":true}')
$composeFile = Join-Path $smokeDeploy 'docker-compose.yml'
$compose = @"
name: $smokeName
x-synthetic: &synthetic
  image: $Image
  stop_signal: SIGTERM
  entrypoint: ["/bin/sh", "-c"]
  command: ["echo 'LearnHub synthetic service ready'; trap 'exit 0' TERM INT; while :; do sleep 1; done"]
  healthcheck:
    test: ["CMD", "/bin/true"]
    interval: 1s
    timeout: 1s
    retries: 3
services:
  frontend: *synthetic
  backend: *synthetic
  mysql: *synthetic
"@
[IO.File]::WriteAllText($composeFile, $compose)
$script:smokeSequence = 0
function Invoke-SmokeLauncher {
    param([string] $Action, [string] $Service)
    $script:smokeSequence++
    $reportFile = Join-Path $smokeRoot ('result-' + $script:smokeSequence + '.json')
    $launcherArguments = @('--project', ('"' + $smokeRoot + '"'), '--mode', 'docker', '--output', ('"' + $reportFile + '"'))
    if ($Action -eq 'status') { $launcherArguments += '--status' }
    else { $launcherArguments += @('--action', $Action) }
    if ($Service) { $launcherArguments += @('--service', $Service) }
    $process = Start-Process -FilePath $launcherFile -ArgumentList $launcherArguments -WindowStyle Hidden -PassThru -Wait
    if ($process.ExitCode -ne 0) { throw "Launcher fixture action failed: $Action ($Service). See the synthetic report in $reportFile" }
    $result = Get-Content -LiteralPath $reportFile -Raw | ConvertFrom-Json
    if (-not $result.success) { throw "Launcher fixture action was unsuccessful: $Action" }
    return $result.snapshot
}
function Assert-Smoke {
    param([bool] $Condition, [string] $Message)
    if (-not $Condition) { throw $Message }
    Write-Host ('PASS ' + $Message)
}
try {
    $started = Invoke-SmokeLauncher 'start-all'
    Assert-Smoke ($started.services.Count -eq 3) 'EXE starts exactly the three synthetic services'
    Assert-Smoke (@($started.services | Where-Object { -not $_.containerId }).Count -eq 0) 'Every synthetic container is discovered with verified ownership'
    $backendId = ($started.services | Where-Object id -eq 'backend').containerId
    $stopped = Invoke-SmokeLauncher 'stop' 'frontend'
    Assert-Smoke (($stopped.services | Where-Object id -eq 'frontend').state -eq 'stopped') 'EXE stops the selected frontend'
    Assert-Smoke ((($stopped.services | Where-Object id -eq 'backend').containerId -eq $backendId) -and (($stopped.services | Where-Object id -eq 'backend').state -ne 'stopped')) 'Stopping frontend preserves backend'
    $restarted = Invoke-SmokeLauncher 'restart' 'frontend'
    Assert-Smoke (($restarted.services | Where-Object id -eq 'frontend').state -in @('running', 'starting', 'healthy')) 'EXE restarts frontend'
    $beforeStart = & docker inspect $backendId --format '{{.State.StartedAt}}'
    $restartedBackend = Invoke-SmokeLauncher 'restart' 'backend'
    $afterStart = & docker inspect $backendId --format '{{.State.StartedAt}}'
    Assert-Smoke ($beforeStart -ne $afterStart) 'EXE restarts backend with the same verified container identity'
    $allStopped = Invoke-SmokeLauncher 'stop-all'
    Assert-Smoke (@($allStopped.services | Where-Object state -ne 'stopped').Count -eq 0) 'EXE stops all synthetic services'
    Assert-Smoke (@($allStopped.services | Where-Object { $_.containerId }).Count -eq 3) 'Stopping preserves the synthetic containers'
    Write-Host 'Docker EXE smoke checks passed. Only this disposable fixture was changed.'
}
finally {
    # Validate exact working-directory labels before removing our fixture stack.
    $fixtureIds = @(& docker ps -aq --filter ('label=com.docker.compose.project=' + $smokeName))
    foreach ($containerId in $fixtureIds) {
        $owner = & docker inspect $containerId --format '{{index .Config.Labels "com.docker.compose.project.working_dir"}}'
        if ([IO.Path]::GetFullPath($owner.Trim()) -ne [IO.Path]::GetFullPath($smokeDeploy)) { throw 'Fixture cleanup refused: working-directory label changed.' }
    }
    & docker compose --project-name $smokeName --project-directory $smokeDeploy --file $composeFile down --timeout 10
    if ($LASTEXITCODE -ne 0) { Write-Warning 'Synthetic containers could not be cleaned up; inspect the fixture project before retrying.' }
}
