# Run the real batch files against synthetic executable stubs. No Docker, Java,
# app HTTP endpoint, browser, or user process is used by these scenarios.
param([switch] $SkipSlow)

$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('learnhub-launcher-test-' + [guid]::NewGuid().ToString('N'))
$savedPath = $env:PATH
$savedJavaHome = $env:JAVA_HOME
$savedScenario = $env:LEARNHUB_LAUNCHER_TEST_SCENARIO
$savedFixture = $env:LEARNHUB_LAUNCHER_TEST_ROOT
function Assert-Test([bool] $Condition, [string] $Message) { if (-not $Condition) { throw $Message } }
function Invoke-TestBatch([string] $BatchFile, [string] $Arguments) {
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $env:ComSpec
    $startInfo.Arguments = '/d /s /c ""' + $BatchFile + '" ' + $Arguments + '"'
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    try {
        [void]$process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(240000)) { $process.Kill(); throw 'Synthetic launcher test timed out.' }
        return @{ ExitCode = $process.ExitCode; Output = ($stdout.Result + $stderr.Result) }
    }
    finally { $process.Dispose() }
}
try {
    New-Item -ItemType Directory -Path $fixtureRoot | Out-Null
    $stubDirectory = Join-Path $fixtureRoot 'bin'
    New-Item -ItemType Directory -Path $stubDirectory | Out-Null
    $stubExe = Join-Path $stubDirectory 'docker.exe'
    Add-Type -OutputAssembly $stubExe -OutputType ConsoleApplication -TypeDefinition @'
using System;
using System.IO;
using System.Linq;
using System.Reflection;
public static class LauncherStub {
    public static int Main(string[] args) {
        string root = Environment.GetEnvironmentVariable("LEARNHUB_LAUNCHER_TEST_ROOT");
        if (String.IsNullOrEmpty(root) || !Directory.Exists(root)) return 99;
        string scenario = Environment.GetEnvironmentVariable("LEARNHUB_LAUNCHER_TEST_SCENARIO") ?? "reuse";
        string command = Path.GetFileNameWithoutExtension(Assembly.GetExecutingAssembly().Location).ToLowerInvariant();
        string line = String.Join(" ", args);
        File.AppendAllText(Path.Combine(root, "calls.log"), command + " " + line + Environment.NewLine);
        if (command == "timeout") return 0;
        if (command == "netstat") {
            Console.WriteLine("  TCP    127.0.0.1:8888    0.0.0.0:0    LISTENING    9999");
            Console.WriteLine("  TCP    127.0.0.1:5174    0.0.0.0:0    LISTENING    9998");
            return 0;
        }
        if (command == "powershell") {
            if (scenario == "foreign-port" && line.Contains("restart-local-backend.ps1")) return 1;
            if (scenario == "dev-health-timeout" && line.Contains("Invoke-WebRequest")) return 1;
            return 0;
        }
        if (command == "java") {
            if (line == "-version") { Console.WriteLine("openjdk version \"21.0.9\""); return 0; }
            if (args.Length != 3 || args[1] != "-jar" || !Path.IsPathRooted(args[2])) return 12;
            return 11;
        }
        if (command != "docker") return 0;
        bool afterUp = File.Exists(Path.Combine(root, "up.state"));
        if (line == "info") return 0;
        if (line.StartsWith("image inspect")) return scenario == "build-missing" ? 1 : 0;
        if (line.StartsWith("compose up")) {
            File.WriteAllText(Path.Combine(root, "up.state"), "started");
            return scenario == "compose-failure" ? 7 : 0;
        }
        if (line.StartsWith("compose restart")) return scenario == "restart-failure" ? 8 : 0;
        if (line.StartsWith("compose ps")) {
            string service = args.Last();
            if (service == "backend") {
                if ((!afterUp && (scenario == "new" || scenario == "build-missing")) || (afterUp && scenario == "missing-backend")) return 0;
                Console.WriteLine(afterUp && scenario == "recreated" ? "synthetic-backend-new" : "synthetic-backend-old");
            } else { Console.WriteLine("synthetic-" + service); }
            return 0;
        }
        if (args.Length > 2 && args[0] == "inspect" && args[1] == "-f") {
            string format = args[2];
            if (format.Contains("State.Running")) {
                Console.WriteLine((scenario == "stopped" ? "false" : "true") + " before-start");
            } else if (format.Contains("State.StartedAt")) {
                Console.WriteLine((scenario == "stopped" || scenario == "recreated" || scenario == "new" || scenario == "build-missing") ? "after-start" : "before-start");
            } else if (format.Contains("Health.Status")) {
                Console.WriteLine(scenario == "health-timeout" ? "unhealthy" : "healthy");
            } else { Console.WriteLine("running"); }
            return 0;
        }
        return 0;
    }
}
'@
    foreach ($name in @('netstat.exe', 'timeout.exe', 'powershell.exe', 'java.exe')) {
        Copy-Item -LiteralPath $stubExe -Destination (Join-Path $stubDirectory $name)
    }
    $env:PATH = $stubDirectory + ';' + $savedPath
    $env:JAVA_HOME = $fixtureRoot
    $scenarios = @('reuse', 'new', 'recreated', 'stopped', 'build-missing', 'restart-failure', 'compose-failure', 'missing-backend', 'health-timeout', 'dev-reuse', 'foreign-port', 'dev-health-timeout')
    if ($SkipSlow) { $scenarios = @($scenarios | Where-Object { $_ -notin @('health-timeout', 'dev-health-timeout') }) }
    foreach ($scenario in $scenarios) {
        $caseRoot = Join-Path $fixtureRoot $scenario
        New-Item -ItemType Directory -Path (Join-Path $caseRoot 'deploy') -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $repositoryRoot 'start-all.bat') -Destination $caseRoot
        Copy-Item -LiteralPath (Join-Path $repositoryRoot 'start-dev.bat') -Destination $caseRoot
        # Only synthetic names/ports; never copy the real deployment configuration.
        Set-Content -LiteralPath (Join-Path $caseRoot 'deploy\.env') -Encoding Ascii -Value "WEB_HOST_PORT=8888`r`nBACKEND_CONTAINER_NAME=synthetic-backend`r`nMYSQL_CONTAINER_NAME=synthetic-mysql"
        $env:LEARNHUB_LAUNCHER_TEST_ROOT = $caseRoot
        $env:LEARNHUB_LAUNCHER_TEST_SCENARIO = $scenario
        $arguments = 'nobrowser nopause'
        if ($scenario -eq 'build-missing') { $arguments = 'build nobrowser nopause' }
        if ($scenario -in @('dev-reuse', 'foreign-port', 'dev-health-timeout')) {
            New-Item -ItemType Directory -Path (Join-Path $caseRoot 'backend\target') -Force | Out-Null
            Set-Content -LiteralPath (Join-Path $caseRoot 'backend\target\learn-hub-backend-0.0.1-SNAPSHOT.jar') -Value 'synthetic fixture, not executable'
            $arguments = 'dev nobrowser nopause'
        }
        $launcher = Join-Path $caseRoot 'start-all.bat'
        $result = Invoke-TestBatch $launcher $arguments
        $output = $result.Output
        $resultCode = $result.ExitCode
        $calls = Get-Content -LiteralPath (Join-Path $caseRoot 'calls.log')
        $restarts = @($calls | Where-Object { $_ -like 'docker compose restart*' })
        $expectedFailure = $scenario -in @('restart-failure', 'compose-failure', 'missing-backend', 'health-timeout', 'foreign-port', 'dev-health-timeout')
        Assert-Test (($resultCode -ne 0) -eq $expectedFailure) "$scenario exit $resultCode (failure expected: $expectedFailure): $output"
        if ($scenario -in @('reuse', 'restart-failure', 'health-timeout')) {
            Assert-Test ($restarts.Count -eq 1 -and $restarts[0] -eq 'docker compose restart --no-deps --timeout 30 backend') "$scenario must restart only the selected backend once: $calls"
        } else {
            Assert-Test ($restarts.Count -eq 0) "$scenario must not restart a newly started/recreated backend: $calls"
        }
        if ($scenario -eq 'build-missing') {
            Assert-Test ($calls -contains 'docker compose up -d --build') 'build must work when images do not yet exist.'
        }
        if ($scenario -eq 'health-timeout') {
            Assert-Test ($output -notmatch 'started\. UI:') 'An unhealthy backend must not be reported ready.'
        }
        if ($scenario -eq 'foreign-port') {
            Assert-Test (@($calls | Where-Object { $_ -match 'Start-Process|Invoke-WebRequest' }).Count -eq 0) 'A refused local restart must not launch or probe another backend.'
        }
        if ($scenario -eq 'dev-reuse') {
            $stopIndex = [array]::FindIndex([string[]]$calls, [Predicate[string]]{ param($x) $x -match 'restart-local-backend.ps1' })
            $launchIndex = [array]::FindIndex([string[]]$calls, [Predicate[string]]{ param($x) $x -match 'Start-Process.*LEARNHUB_BACKEND_RUNNER' })
            Assert-Test ($stopIndex -ge 0 -and $launchIndex -gt $stopIndex) 'Dev mode must verify/stop the owned backend before launching its replacement.'
            Assert-Test (@($calls | Where-Object { $_ -match 'Invoke-WebRequest.*api/files/page' }).Count -eq 1) 'Dev mode must wait for the backend API.'
        }
        Write-Host "PASS: $scenario"
    }
    foreach ($mode in @('visible', 'hidden')) {
        $caseRoot = Join-Path $fixtureRoot ('run-backend-' + $mode + ' path with spaces')
        New-Item -ItemType Directory -Path (Join-Path $caseRoot 'tools') -Force | Out-Null
        New-Item -ItemType Directory -Path (Join-Path $caseRoot 'backend\target') -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $repositoryRoot 'tools\run-backend.cmd') -Destination (Join-Path $caseRoot 'tools')
        $env:LEARNHUB_LAUNCHER_TEST_ROOT = $caseRoot
        $launcher = Join-Path $caseRoot 'tools\run-backend.cmd'
        $arguments = ''
        if ($mode -eq 'visible') { $arguments = 'visible' }
        $result = Invoke-TestBatch $launcher $arguments
        Assert-Test ($result.ExitCode -eq 11) "run-backend $mode must preserve Java's failure code: $($result.Output)"
        $calls = Get-Content -LiteralPath (Join-Path $caseRoot 'calls.log')
        $expectedJar = Join-Path $caseRoot 'backend\target\learn-hub-backend-0.0.1-SNAPSHOT.jar'
        Assert-Test ($calls -contains ('java -Dserver.port=18080 -jar ' + $expectedJar)) "run-backend $mode must pass this project's absolute JAR as one argument: $calls"
        Write-Host "PASS: run-backend $mode absolute path and Java failure code"
    }
}
finally {
    $env:PATH = $savedPath
    $env:JAVA_HOME = $savedJavaHome
    $env:LEARNHUB_LAUNCHER_TEST_ROOT = $savedFixture
    $env:LEARNHUB_LAUNCHER_TEST_SCENARIO = $savedScenario
    # Only this test's unique temporary directory is eligible for cleanup.
    $resolvedFixture = [IO.Path]::GetFullPath($fixtureRoot)
    $temporaryPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\learnhub-launcher-test-'
    if ($resolvedFixture.StartsWith($temporaryPrefix, [StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $resolvedFixture)) {
        Remove-Item -LiteralPath $resolvedFixture -Recurse -Force
    }
}
