[CmdletBinding()]
param([string] $Launcher, [string] $NewLauncher, [switch] $Unattended)
$ErrorActionPreference = 'Stop'
$repositoryDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (-not $Launcher) { $Launcher = Join-Path $repositoryDirectory 'LearnHub.exe' }
$sourceExecutable = [IO.Path]::GetFullPath($Launcher)
if (-not $NewLauncher) { throw 'Pass -NewLauncher with a separately built newer demo EXE.' }
$newExecutable = [IO.Path]::GetFullPath($NewLauncher)
$fixtureDirectory = Join-Path $repositoryDirectory ('tmp/launcher-update-smoke-' + [Guid]::NewGuid().ToString('N'))
$applicationDirectory = Join-Path $fixtureDirectory 'app'
$projectDirectory = Join-Path $fixtureDirectory 'synthetic-project'
$tokenBytes = [byte[]]::new(32)
[Security.Cryptography.RandomNumberGenerator]::Fill($tokenBytes)
$token = [Convert]::ToHexString($tokenBytes)
$stageDirectory = Join-Path $fixtureDirectory ('updates/' + $token)
New-Item -ItemType Directory -Path $applicationDirectory, $projectDirectory, $stageDirectory | Out-Null
$targetExecutable = Join-Path $applicationDirectory 'LearnHub.exe'
$stagedExecutable = Join-Path $stageDirectory 'next.exe'
$helperExecutable = Join-Path $stageDirectory 'updater.exe'
Copy-Item -LiteralPath $sourceExecutable -Destination $targetExecutable
Copy-Item -LiteralPath $newExecutable -Destination $stagedExecutable
Copy-Item -LiteralPath $sourceExecutable -Destination $helperExecutable
$hash = (Get-FileHash -LiteralPath $sourceExecutable -Algorithm SHA256).Hash
$newHash = (Get-FileHash -LiteralPath $newExecutable -Algorithm SHA256).Hash
if ($hash -eq $newHash) { throw 'A distinct newer EXE is required.' }
$parent = Start-Process -FilePath $targetExecutable -ArgumentList '--demo' -WindowStyle Hidden -PassThru
$planPath = Join-Path $stageDirectory 'plan.json'
$resultPath = Join-Path $stageDirectory 'result.json'
$plan = [ordered] @{
    targetPath = $targetExecutable
    stagedPath = $stagedExecutable
    helperPath = $helperExecutable
    parentPid = $parent.Id
    parentStartTimeUtcTicks = $parent.StartTime.ToUniversalTime().Ticks
    expectedOldSha256 = $hash
    expectedNewSha256 = $newHash
    version = ([Diagnostics.FileVersionInfo]::GetVersionInfo($newExecutable).ProductVersion -split '\+')[0]
    projectRoot = $projectDirectory
    token = $token
    resultPath = $resultPath
    demo = $true
}
[IO.File]::WriteAllText($planPath, ($plan | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
$helper = Start-Process -FilePath $helperExecutable -ArgumentList @('--apply-launcher-update', ('"' + $planPath + '"')) -WindowStyle Hidden -PassThru
$readyPath = Join-Path $stageDirectory 'ready.json'
$deadline = [DateTime]::UtcNow.AddSeconds(30)
while (-not (Test-Path -LiteralPath $readyPath)) {
    if ($helper.HasExited -or [DateTime]::UtcNow -gt $deadline) { throw ('Update helper failed before Ready: ' + $resultPath) }
    Start-Sleep -Milliseconds 200
}
Write-Output ('READY: close only the synthetic preview at ' + $targetExecutable)
Write-Output ('Fixture: ' + $fixtureDirectory)
if ($Unattended) {
    $windowDeadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $parent.Refresh()
        if ($parent.HasExited) { throw 'Synthetic parent exited before the test requested its close.' }
        if ($parent.MainWindowHandle -ne [IntPtr]::Zero -and $parent.CloseMainWindow()) { break }
        if ([DateTime]::UtcNow -gt $windowDeadline) { throw 'Synthetic parent window was not ready to close.' }
        Start-Sleep -Milliseconds 200
    } while ($true)
}
$deadline = [DateTime]::UtcNow.AddSeconds(90)
while (-not (Test-Path -LiteralPath $resultPath)) {
    if ([DateTime]::UtcNow -gt $deadline) { throw ('Update handshake timed out. See ' + $resultPath) }
    Start-Sleep -Milliseconds 250
}
$result = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
if (-not $result.success) { throw ('Update failed: ' + $result.message) }
if (-not $parent.HasExited -or $result.launchedPid -eq $parent.Id) { throw 'The original launcher did not exit and restart.' }
if ((Get-FileHash -LiteralPath $targetExecutable -Algorithm SHA256).Hash -ne $newHash) { throw 'Target did not match the new EXE.' }
if (-not $result.backupPath -or (Get-FileHash -LiteralPath $result.backupPath -Algorithm SHA256).Hash -ne $hash) { throw 'Verified original backup missing.' }
Write-Output 'PASS real EXE helper Ready, parent exit, atomic replacement, new UI acknowledgement, restart and original backup'
Write-Output ('Reopened demo PID: ' + $result.launchedPid)
Write-Output 'Synthetic files retained under tmp for inspection. Close the reopened demo after verification.'
if ($Unattended) {
    $ack = Get-Content -LiteralPath (Join-Path $stageDirectory 'ack.json') -Raw | ConvertFrom-Json
    $reopened = Get-Process -Id $result.launchedPid -ErrorAction Stop
    if ($reopened.StartTime.ToUniversalTime().Ticks -ne $ack.startTimeUtcTicks -or
        [IO.Path]::GetFullPath($reopened.MainModule.FileName) -ne $targetExecutable) {
        throw 'Refusing to close a process that does not match the acknowledged synthetic launcher.'
    }
    if (-not $reopened.CloseMainWindow() -or -not $reopened.WaitForExit(10000)) {
        throw 'The acknowledged synthetic launcher did not close after verification.'
    }
    Write-Output 'PASS unattended smoke closed only the acknowledged synthetic launcher'
}
