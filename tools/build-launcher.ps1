[CmdletBinding()]
param(
    [ValidateSet('win-x64', 'win-arm64')]
    [string] $Runtime = 'win-x64',
    [switch] $SkipNpm,
    [switch] $SkipInstall,
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$')]
    [string] $ReleaseTag
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$uiDirectory = Join-Path $repositoryRoot 'launcher\ui'
$projectFile = Join-Path $repositoryRoot 'launcher\LearnHub.Launcher\LearnHub.Launcher.csproj'
$publishDirectory = Join-Path $repositoryRoot ('launcher\publish\' + $Runtime)
$destinationFile = Join-Path $repositoryRoot 'LearnHub.exe'

function Invoke-BuildCommand {
    param([string] $Command, [string[]] $CommandArguments)
    & $Command @CommandArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Build command failed (exit $LASTEXITCODE): $Command"
    }
}

if (-not (Test-Path -LiteralPath $projectFile -PathType Leaf)) {
    throw "Launcher project not found: $projectFile"
}
$npmCommand = (Get-Command npm.cmd -ErrorAction Stop).Source
$dotnetCommand = (Get-Command dotnet -ErrorAction Stop).Source

if (-not $SkipNpm) {
    Invoke-BuildCommand $npmCommand @('ci', '--prefix', $uiDirectory)
}
Invoke-BuildCommand $npmCommand @('run', 'build', '--prefix', $uiDirectory)

# Only the embedded UI and the launcher projects are published. No application
# configuration, credentials, uploads, or databases are copied to the package.
Invoke-BuildCommand $dotnetCommand @(
    'publish', $projectFile,
    '-c', 'Release', '-r', $Runtime, '--self-contained', 'true',
    '/p:PublishSingleFile=true',
    '/p:IncludeNativeLibrariesForSelfExtract=true',
    '-o', $publishDirectory
)
$publishedFile = Join-Path $publishDirectory 'LearnHub.exe'
if (-not (Test-Path -LiteralPath $publishedFile -PathType Leaf)) {
    throw "Published launcher not found: $publishedFile"
}
[xml] $projectDefinition = Get-Content -LiteralPath $projectFile -Raw
$launcherVersion = [string] $projectDefinition.Project.PropertyGroup.Version
if ($launcherVersion -notmatch '^\d+\.\d+\.\d+$') { throw 'Launcher version must be a stable X.Y.Z version.' }
if (-not $ReleaseTag) { $ReleaseTag = 'launcher-v' + $launcherVersion }
$assetName = 'LearnHub-' + $Runtime + '.exe'
$assetPath = Join-Path $publishDirectory $assetName
Copy-Item -LiteralPath $publishedFile -Destination $assetPath -Force
$manifestPath = Join-Path $publishDirectory ('launcher-update-' + $Runtime + '.json')
$manifest = [ordered] @{
    schemaVersion = 1
    version = $launcherVersion
    runtime = $Runtime
    url = 'https://github.com/luyiyanyumu/LearnHub/releases/download/' + $ReleaseTag + '/' + $assetName
    sha256 = (Get-FileHash -LiteralPath $assetPath -Algorithm SHA256).Hash.ToLowerInvariant()
    size = (Get-Item -LiteralPath $assetPath).Length
    releaseUrl = 'https://github.com/luyiyanyumu/LearnHub/releases/tag/' + $ReleaseTag
}
[IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
if (-not $SkipInstall) {
    Copy-Item -LiteralPath $publishedFile -Destination $destinationFile -Force
    Write-Host "Launcher ready: $destinationFile ($Runtime)"
}
Write-Host "Release EXE: $assetPath"
Write-Host "Update manifest: $manifestPath"
Write-Host 'Building does not start or restart LearnHub services.'
