# Synthetic ownership and listener fixtures only; no native process is inspected/stopped.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'restart-local-backend.ps1')

$expectedJar = 'C:\Synthetic LearnHub\backend\target\learn-hub-backend-0.0.1-SNAPSHOT.jar'
function Assert-True([bool] $Condition, [string] $Message) {
    if (-not $Condition) { throw $Message }
}
function Fixture-Process([string] $Name = 'java.exe', [string] $CommandLine = ('java.exe -Dserver.port=18080 -jar "' + $expectedJar + '"')) {
    [pscustomobject]@{ Name = $Name; CommandLine = $CommandLine; CreationDate = '2026-01-01T00:00:00Z' }
}
Assert-LearnHubJavaVersion 'openjdk version "21.0.9" 2025-10-21'
Assert-LearnHubJavaVersion 'java version "25" 2025-09-16'
foreach ($versionText in @('openjdk version "17.0.13"', 'java version "1.8.0_452"', 'unrecognized runtime')) {
    $refused = $false
    try { Assert-LearnHubJavaVersion $versionText }
    catch { $refused = $_.Exception.Message -match 'JDK 21' }
    Assert-True $refused 'Unsupported or unknown JVM must fail before stopping a backend.'
}
$refused = $false
try { Assert-LearnHubJavaVersion 'openjdk version "21.0.9"' 1 }
catch { $refused = $_.Exception.Message -match 'JDK 21' }
Assert-True $refused 'A failed Java version command must fail before stopping a backend.'
Assert-True (Test-LearnHubBackendProcess (Fixture-Process) $expectedJar) 'Own absolute JAR must match.'
Assert-True (Test-LearnHubBackendProcess (Fixture-Process 'javaw.exe') $expectedJar) 'javaw must be accepted.'
Assert-True (-not (Test-LearnHubBackendProcess (Fixture-Process 'docker.exe') $expectedJar)) 'Docker owner must be refused.'
Assert-True (-not (Test-LearnHubBackendProcess (Fixture-Process 'java.exe' 'java -jar C:\other\backend.jar') $expectedJar)) 'Another project must be refused.'
Assert-True (-not (Test-LearnHubBackendProcess (Fixture-Process 'java.exe' 'java -jar target\learn-hub-backend-0.0.1-SNAPSHOT.jar') $expectedJar)) 'Unverifiable relative JAR must be refused.'
Assert-True (-not (Test-LearnHubBackendProcess (Fixture-Process 'java.exe' ('java -Dother="' + $expectedJar + '" -jar C:\other.jar')) $expectedJar)) 'A property containing our path is not ownership.'
Assert-True (-not (Test-LearnHubBackendProcess (Fixture-Process 'java.exe' ('java -jar "' + $expectedJar + '.old"')) $expectedJar)) 'JAR prefixes must not match.'

$script:owners = @()
$script:records = @{}
$script:stopped = @()
function Get-LearnHubBackendListeners { param([int] $Port) @($script:owners) }
function Get-LearnHubProcessInfo { param([int] $ProcessId) $script:records[$ProcessId] }
function Stop-LearnHubOwnedProcess {
    param([int] $ProcessId, [object] $VerifiedProcess)
    $script:stopped += $ProcessId
    $script:owners = @($script:owners | Where-Object { $_ -ne $ProcessId })
}
Stop-LearnHubLocalBackend -ExpectedJar $expectedJar
Assert-True ($script:stopped.Count -eq 0) 'A free port must not stop anything.'

$script:owners = @(101)
$script:records = @{ 101 = (Fixture-Process) }
Stop-LearnHubLocalBackend -ExpectedJar $expectedJar
Assert-True ($script:stopped.Count -eq 1 -and $script:stopped[0] -eq 101) 'Own listener must be stopped once.'

$script:owners = @(101, 202)
$script:records = @{ 101 = (Fixture-Process); 202 = (Fixture-Process 'docker.exe' 'Docker proxy') }
$script:stopped = @()
$refused = $false
try { Stop-LearnHubLocalBackend -ExpectedJar $expectedJar }
catch { $refused = $_.Exception.Message -match 'cannot be verified' }
Assert-True $refused 'Mixed ownership must fail.'
Assert-True ($script:stopped.Count -eq 0) 'Validate all owners before stopping even an owned listener.'

$script:owners = @(303)
$script:records = @{ 303 = $null }
$refused = $false
try { Stop-LearnHubLocalBackend -ExpectedJar $expectedJar }
catch { $refused = $_.Exception.Message -match 'cannot be verified' }
Assert-True $refused 'Missing process metadata must fail closed.'
Write-Host 'PASS: 18 synthetic Java-version, ownership, and restart checks.'
