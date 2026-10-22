# This helper only stops a verified local backend; start-dev.bat launches its replacement.
# Dot sourcing defines functions without inspecting or stopping any real process.
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-LearnHubJavaVersion {
    param([string] $VersionText, [int] $ExitCode = 0)
    if ($ExitCode -ne 0 -or $VersionText -notmatch '(?m)^(?:openjdk|java) version "(?<major>\d+)' -or [int]$Matches.major -lt 21) {
        throw 'JDK 21 or newer is required. Check JAVA_HOME and PATH before restarting the backend.'
    }
}

function Assert-LearnHubJavaRuntime {
    $javaBinary = 'java.exe'
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        $javaBinary = Join-Path $env:JAVA_HOME 'bin\java.exe'
    }
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $javaBinary
    $startInfo.Arguments = '-version'
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    try {
        [void]$process.Start()
        if (-not $process.WaitForExit(10000)) {
            $process.Kill()
            throw 'The Java version check timed out; the existing backend was not stopped.'
        }
        $versionText = $process.StandardOutput.ReadToEnd() + $process.StandardError.ReadToEnd()
        Assert-LearnHubJavaVersion -VersionText $versionText -ExitCode $process.ExitCode
    }
    finally { $process.Dispose() }
}

function Test-LearnHubBackendProcess {
    param([object] $ProcessInfo, [string] $ExpectedJar)

    if ($null -eq $ProcessInfo -or $ProcessInfo.Name -notmatch '^javaw?\.exe$') { return $false }
    if ([string]::IsNullOrWhiteSpace($ProcessInfo.CommandLine)) { return $false }
    # Java receives the JAR as one argument; compare that argument, not a substring
    # which could also occur in a property, log filename, or another project's path.
    $arguments = @([regex]::Matches($ProcessInfo.CommandLine, '"[^"\r\n]*"|[^\s"]+') | ForEach-Object { $_.Value.Trim('"') })
    for ($i = 0; $i -lt $arguments.Count - 1; $i++) {
        if ($arguments[$i] -cne '-jar') { continue }
        $jarArgument = $arguments[$i + 1]
        if ($jarArgument -notmatch '^(?:[A-Za-z]:[\\/]|\\\\)') { return $false }
        try { $jarPath = [System.IO.Path]::GetFullPath($jarArgument) }
        catch { return $false }
        return [string]::Equals($jarPath, [System.IO.Path]::GetFullPath($ExpectedJar), [StringComparison]::OrdinalIgnoreCase)
    }
    return $false
}

function Get-LearnHubBackendListeners {
    param([int] $Port)
    @(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object { $_.LocalPort -eq $Port } | Select-Object -ExpandProperty OwningProcess -Unique)
}

function Get-LearnHubProcessInfo {
    param([int] $ProcessId)
    Get-CimInstance -ClassName Win32_Process -Filter "ProcessId = $ProcessId" -ErrorAction Stop
}

function Stop-LearnHubOwnedProcess {
    param([int] $ProcessId, [object] $VerifiedProcess)
    $processHandle = Get-Process -Id $ProcessId -ErrorAction Stop
    # Recheck the creation time after opening a handle, before stopping that process.
    $current = Get-LearnHubProcessInfo -ProcessId $ProcessId
    if ($null -eq $current -or $current.CreationDate -ne $VerifiedProcess.CreationDate -or
        $current.CommandLine -cne $VerifiedProcess.CommandLine) {
        throw "Process $ProcessId changed during inspection; refusing to stop it."
    }
    Stop-Process -InputObject $processHandle -ErrorAction Stop
    if (-not $processHandle.WaitForExit(10000)) { throw "Backend process $ProcessId did not exit within 10 seconds." }
}

function Stop-LearnHubLocalBackend {
    param([string] $ExpectedJar, [int] $Port = 18080)
    $owners = @(Get-LearnHubBackendListeners -Port $Port)
    if ($owners.Count -eq 0) {
        Write-Host "  Backend port $Port is free; starting a new local backend."
        return
    }
    $verified = @{}
    # Validate every IPv4/IPv6 owner before stopping any of them.
    foreach ($processId in $owners) {
        $processInfo = Get-LearnHubProcessInfo -ProcessId $processId
        if (-not (Test-LearnHubBackendProcess -ProcessInfo $processInfo -ExpectedJar $ExpectedJar)) {
            throw "Port $Port is occupied by process $processId, which cannot be verified as this project's Java backend. Stop the old backend manually (including an older launcher using a relative JAR path), or use Docker mode. No unverified process will be stopped."
        }
        $verified[$processId] = $processInfo
    }
    foreach ($processId in $owners) {
        if (@(Get-LearnHubBackendListeners -Port $Port) -notcontains $processId) { continue }
        Stop-LearnHubOwnedProcess -ProcessId $processId -VerifiedProcess $verified[$processId]
        Write-Host "  Stopped this project's backend (PID $processId); restarting it."
    }
    if (@(Get-LearnHubBackendListeners -Port $Port).Count -gt 0) {
        throw "Port $Port is still occupied; refusing to launch another backend."
    }
}

if ($MyInvocation.InvocationName -ne '.') {
    try {
        $projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
        $expectedJar = Join-Path $projectRoot 'backend\target\learn-hub-backend-0.0.1-SNAPSHOT.jar'
        Assert-LearnHubJavaRuntime
        Stop-LearnHubLocalBackend -ExpectedJar $expectedJar
        exit 0
    }
    catch {
        Write-Host "  [ERROR] $($_.Exception.Message)"
        exit 1
    }
}
