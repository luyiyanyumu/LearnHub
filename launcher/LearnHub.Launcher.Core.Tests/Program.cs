using System.Text.Json;
using System.Net;
using System.Net.Sockets;
using System.Text;
using LearnHub.Launcher.Core;

var tests = new List<(string Name, Func<Task> Run)>();
void Test(string name, Action body) => tests.Add((name, () => { body(); return Task.CompletedTask; }));
void AsyncTest(string name, Func<Task> body) => tests.Add((name, body));
void Assert(bool condition, string message = "Assertion failed") { if (!condition) throw new Exception(message); }
async Task Throws(Func<Task> action, string contains = "")
{
    try { await action(); } catch (Exception ex) { Assert(ex.Message.Contains(contains), ex.Message); return; }
    throw new Exception("Expected operation to be rejected");
}
using var fixture = new Fixture();
var config = fixture.Config;
var recorder = new FakeRunner();
var docker = new DockerOperations(config, recorder, _ => { });
var local = new LocalOperations(config, recorder, _ => { });
var owned = fixture.Container("frontend", 'a');

Test("dotenv parses quotes comments and export", () =>
{
    var env = ProjectConfig.ParseEnvironment("# ignored\nexport WEB_HOST_PORT='8890'\nMYSQL_HOST_PORT=3307 # note\nTOKEN=\"synthetic # secret\"\nBAD LINE\n");
    Assert(env["WEB_HOST_PORT"] == "8890" && env["MYSQL_HOST_PORT"] == "3307" && env["TOKEN"] == "synthetic # secret");
    Assert(env.Count == 3);
});
Test("ports read this instance's dotenv", () => Assert(config.FrontendPort == 8890 && config.BackendPort == 18080 && config.MysqlPort == 3307));
Test("invalid ports never leave valid TCP range", () =>
{
    foreach (var invalid in new[] { "0", "65536", "-1", "hello" }) Assert(ProjectConfig.Port(new Dictionary<string, string> { ["PORT"] = invalid }, "PORT", 5174) == 5174);
});
Test("dotenv Compose project name override is explicit", () => Assert(config.ProjectName == "learn-hub-synthetic"));
Test("redacts exact environment secrets and generic credentials", () =>
{
    var text = config.Redactor.Redact("pass = synthetic-db-password; token=abc123 Authorization: Bearer XYZ123 password=foo DEEPSEEK_API_KEY=xyz https://user:pass@example.invalid sk-proj-abcdefghij");
    foreach (var leaked in new[] { "synthetic-db-password", "abc123", "XYZ123", "password=foo", "KEY=xyz", "user:pass", "sk-proj-abcdefghij" }) Assert(!text.Contains(leaked), text);
    Assert(text.Contains("[REDACTED]"));
});
Test("redacts short known secrets too", () => Assert(new LogRedactor(new Dictionary<string, string> { ["PASSWORD"] = "xy" }).Redact("value xy!") == "value [REDACTED]!"));
Test("Docker Desktop WSL checkout normalization", () => Assert(ProjectConfig.NormalizePath("/host_mnt/c/Projects/LearnHub/deploy/") == ProjectConfig.NormalizePath("C:\\Projects\\LearnHub\\deploy")));
Test("container ownership requires exact directory and project", () =>
{
    Assert(docker.IsOwned(owned));
    Assert(!docker.IsOwned(owned with { Project = "other-project" }));
    Assert(!docker.IsOwned(owned with { WorkingDirectory = config.Deploy + "-other" }));
    Assert(!docker.IsOwned(owned with { WorkingDirectory = "" }));
});
Test("container ownership rejects incomplete labels and unsafe service", () =>
{
    Assert(!docker.IsOwned(owned with { ConfigFiles = [] }));
    Assert(!docker.IsOwned(owned with { Service = "frontend; evil" }));
    Assert(!docker.IsOwned(owned with { Id = "--help" }));
});
Test("compose paths cannot escape deployment directory", () =>
{
    Assert(config.IsAllowedComposeFile(fixture.Overlay));
    Assert(!config.IsAllowedComposeFile(Path.Combine(config.Root, "docker-compose.yml")));
    Assert(!config.IsAllowedComposeFile(Path.Combine(config.Deploy, "..", "docker-compose.yml")));
});
Test("rebuild preserves discovered overlays in order", () =>
{
    var files = docker.ComposeFiles([owned with { ConfigFiles = [config.ComposeFile, fixture.Overlay] }]);
    Assert(files.SequenceEqual(new[] { config.ComposeFile, fixture.Overlay }));
});
AsyncTest("same-name foreign project blocks compose creation", () => Throws(() => Task.Run(() => docker.ComposeFiles([owned, owned with { WorkingDirectory = config.Deploy + "-foreign" }]))));
Test("inspect parser retains bound port and health without environment", () =>
{
    var parsed = DockerOperations.ParseContainer(FakeRunner.Json(owned with { Health = "healthy" }));
    Assert(parsed.Ports["80/tcp"] == 8890 && parsed.Service == "frontend" && parsed.Health == "healthy");
});
AsyncTest("containers without healthcheck remain discoverable", async () =>
{
    recorder.Containers = [owned with { State = "exited", Health = null }]; recorder.Commands.Clear();
    var services = await docker.GetServicesAsync(new DockerStatus(true, true, "synthetic", "synthetic", null), default);
    var frontend = services.Single(s => s.Id == "frontend");
    Assert(frontend.State == "stopped" && frontend.ContainerId == owned.Id);
    var format = recorder.Commands.First(c => c[0] == "inspect")[2];
    Assert(format.Contains("(index .State \"Health\")") && !format.Contains(".State.Health"));
});
AsyncTest("inspect failures never silently hide existing services", async () =>
{
    recorder.Containers = [owned]; recorder.InspectError = "template execution synthetic failure";
    try { await Throws(() => docker.DiscoverAsync(default), "核验容器归属"); }
    finally { recorder.InspectError = null; }
    recorder.MalformedInspect = true;
    try { await Throws(() => docker.DiscoverAsync(default), "解析失败"); }
    finally { recorder.MalformedInspect = false; }
    recorder.Containers = [owned, fixture.Container("backend", 'b')]; recorder.IncompleteInspect = true;
    try { await Throws(() => docker.DiscoverAsync(default), "详情不完整"); }
    finally { recorder.IncompleteInspect = false; }
});
AsyncTest("internal-only containers never borrow another project's host port", async () =>
{
    recorder.Containers = [owned with { Ports = new Dictionary<string, int>() }];
    var services = await docker.GetServicesAsync(new(true, true, "synthetic", "synthetic-linux", null), default);
    var frontend = services.Single(s => s.Id == "frontend");
    Assert(frontend.Port == null && frontend.Address == null);
    Assert(frontend.CanStop && frontend.CanRestart);
});
Test("exact absolute backend JAR establishes ownership", () =>
{
    var jar = config.FindJar()!;
    Assert(local.IsOwned(new(100, $"\"C:\\Java21\\bin\\java.exe\" -Dfile.encoding=UTF-8 -jar \"{jar}\""), "backend"));
    Assert(!local.IsOwned(new(100, "java.exe -jar target\\learn-hub-backend-1.jar"), "backend"));
    Assert(!local.IsOwned(new(100, $"java.exe -jar \"{jar}.foreign\""), "backend"));
    Assert(!local.IsOwned(new(100, $"node.exe \"{jar}\""), "backend"));
});
Test("frontend path substring never establishes ownership", () =>
{
    Assert(local.IsOwned(new(100, $"node.exe \"{config.ViteEntry}\" --port 5174"), "frontend"));
    Assert(!local.IsOwned(new(100, $"node.exe \"{config.ViteEntry}.other\""), "frontend"));
    Assert(!local.IsOwned(new(100, $"node.exe --eval \"process.exit(0)\" \"{config.ViteEntry}\""), "frontend"));
    Assert(!local.IsOwned(new(100, "node.exe node_modules/vite/bin/vite.js"), "frontend"));
    Assert(!local.IsOwned(new(100, ""), "frontend"));
});
Test("backend process specification never exposes credentials in arguments", () =>
{
    var start = LauncherProcessHost.CreateServiceStartInfo(config, "backend");
    Assert(start.ArgumentList.Contains(config.FindJar()!));
    Assert(!string.Join(' ', start.ArgumentList).Contains("synthetic-db-password"));
    Assert(start.Environment["SPRING_DATASOURCE_PASSWORD"] == "synthetic-db-password");
    Assert(start.Environment["MYBATIS_PLUS_CONFIGURATION_LOG_IMPL"]!.Contains("NoLoggingImpl"));
    Assert(!start.UseShellExecute && start.CreateNoWindow);
});
AsyncTest("local restart checks replacement Java before touching process inventory", async () =>
{
    recorder.JavaResult = new(0, "", "openjdk version \"17.0.1\""); recorder.Commands.Clear();
    try
    {
        await Throws(() => local.ExecuteAsync("restart", "backend", default), "需要 Java 21");
        Assert(recorder.Commands.Count == 1 && recorder.Commands[0][0] == "-version");
    }
    finally { recorder.JavaResult = null; }
});
AsyncTest("local restart missing JAR preserves current process", async () =>
{
    var jar = config.FindJar()!; var moved = jar + ".synthetic-disabled"; File.Move(jar, moved); recorder.Commands.Clear();
    try
    {
        await Throws(() => local.ExecuteAsync("restart", "backend", default), "未找到 backend/target");
        Assert(recorder.Commands.Count == 0);
    }
    finally { File.Move(moved, jar); }
});
AsyncTest("local restart missing Vite preserves current process", async () =>
{
    var vite = config.ViteEntry; var moved = vite + ".synthetic-disabled"; File.Move(vite, moved); recorder.Commands.Clear();
    try
    {
        await Throws(() => local.ExecuteAsync("restart", "frontend", default), "未找到本项目 Vite");
        Assert(recorder.Commands.Count == 0);
    }
    finally { File.Move(moved, vite); }
});
Test("local inventory preserves process and port PID", () =>
{
    var data = LocalOperations.ParseInventory("{\"processes\":[{\"ProcessId\":100,\"CommandLine\":\"node.exe example\"}],\"listeners\":[{\"LocalPort\":5174,\"OwningProcess\":999}]}");
    Assert(data.Processes.Single().ProcessId == 100 && data.Listeners.Single().ProcessId == 999);
});
AsyncTest("unknown port listener is conflict and cannot be stopped", async () =>
{
    recorder.LocalJson = "{\"processes\":[],\"listeners\":[{\"LocalPort\":5174,\"OwningProcess\":999}]}";
    var status = (await local.GetServicesAsync(default)).Single(s => s.Id == "frontend");
    Assert(status.State == "conflict" && !status.CanStart && !status.CanStop && !status.CanRestart);
    await Throws(() => local.ExecuteAsync("start", "frontend", default), "被其它进程");
    recorder.LocalJson = "{\"processes\":[],\"listeners\":[]}";
});
AsyncTest("stop-all only targets owned exact container IDs and keeps volumes", async () =>
{
    recorder.Containers = [owned, fixture.Container("backend", 'b'), fixture.Container("mysql", 'c'), fixture.Container("frontend", 'd') with { Project = "foreign" }];
    recorder.Commands.Clear();
    await docker.ExecuteAsync("stop-all", null, default);
    var mutations = recorder.Commands.Where(a => a[0] == "stop").ToArray();
    Assert(mutations.Length == 3 && mutations[0][^1] == owned.Id && mutations[^1][^1] == new string('c', 64));
    Assert(!recorder.Commands.Any(a => a.Contains("down") || a.Contains("-v") || a.Contains("rm")));
});
AsyncTest("build updates only frontend backend and retains overlay flags", async () =>
{
    recorder.Containers = [owned with { ConfigFiles = [config.ComposeFile, fixture.Overlay] }, fixture.Container("code-runner", 'e') with { ConfigFiles = [config.ComposeFile, fixture.Overlay] }];
    recorder.Commands.Clear();
    await docker.ExecuteAsync("build", null, default);
    var build = recorder.Commands.Single(a => a.Contains("build"));
    Assert(build.Contains(fixture.Overlay) && build.TakeLast(2).SequenceEqual(new[] { "backend", "frontend" }));
    var up = recorder.Commands.Single(a => a.Contains("up"));
    Assert(up.Contains("--no-deps") && up.Contains("--no-build") && !up.Contains("code-runner"));
});
AsyncTest("start-all includes already-enabled optional services", async () =>
{
    recorder.Containers = [owned with { ConfigFiles = [config.ComposeFile, fixture.Overlay] }, fixture.Container("code-runner", 'e') with { ConfigFiles = [config.ComposeFile, fixture.Overlay] }];
    recorder.Commands.Clear();
    await docker.ExecuteAsync("start-all", null, default);
    var prepare = recorder.Commands.Single(a => a.Contains("pull"));
    Assert(prepare.TakeLast(4).SequenceEqual(new[] { "pull", "--policy", "missing", "mysql" }));
    var up = recorder.Commands.Single(a => a.Contains("up"));
    Assert(up.Contains("code-runner") && up.Contains(fixture.Overlay) && up.Contains("--pull") && up.Contains("never"));
});
AsyncTest("logs redact before returning to native host", async () =>
{
    recorder.Containers = [owned];
    var output = await docker.GetLogsAsync("frontend", 9999, default);
    Assert(!output.Contains("synthetic-db-password") && output.Contains("[REDACTED]"));
    Assert(recorder.Commands.Last()[3] == "1000");
});
AsyncTest("cross-process project operation lock rejects mutation", async () =>
{
    Directory.CreateDirectory(config.StateDirectory);
    using var held = new FileStream(Path.Combine(config.StateDirectory, "operation.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None);
    var engine = new LauncherEngine(config, recorder, _ => { });
    await Throws(() => engine.ExecuteAsync("stop-all", "docker", null, default), "另一个启动器");
});
AsyncTest("unknown commands modes and arbitrary executable are rejected", async () =>
{
    var engine = new LauncherEngine(config, recorder, _ => { });
    await Throws(() => engine.ExecuteAsync("delete-volumes", "docker", null, default));
    await Throws(() => engine.GetSnapshotAsync("evil", default));
    await Throws(() => new CommandRunner(_ => { }, config.Redactor).RunAsync("cmd.exe", ["/c", "echo"], config.Root, TimeSpan.FromSeconds(1), default));
    await Throws(() => local.ExecuteAsync("build", null, default), "暂不提供自动构建");
});
AsyncTest("background helper ignores regular app arguments", async () => Assert(await LauncherProcessHost.TryRunAsync(["--status"]) == null));
AsyncTest("API 200 HTML fallback is never healthy", async () =>
{
    var result = await Probe("200 OK", "text/html", false);
    Assert(!result.Healthy && result.Detail.Contains("不是 JSON"));
});
AsyncTest("API health reads headers without document response body", async () =>
{
    var result = await Probe("200 OK", "application/json", false);
    Assert(result.Healthy && result.Detail.Contains("HTTP 200"));
});
AsyncTest("frontend health rejects server errors", async () => Assert(!(await Probe("503 Unavailable", "text/html", true)).Healthy));
AsyncTest("frontend page alone is unhealthy when API proxy fails", async () =>
{
    using var listener = new TcpListener(IPAddress.Loopback, 0); listener.Start();
    var port = ((IPEndPoint)listener.LocalEndpoint).Port;
    var serve = Task.Run(async () =>
    {
        foreach (var contentType in new[] { "text/html", "text/html" })
        {
            using var connection = await listener.AcceptTcpClientAsync();
            await using var stream = connection.GetStream();
            using var reader = new StreamReader(stream, Encoding.ASCII, false, 4096, leaveOpen: true);
            while (await reader.ReadLineAsync() is { Length: > 0 }) { }
            var headers = Encoding.ASCII.GetBytes($"HTTP/1.1 200 OK\r\nContent-Type: {contentType}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
            await stream.WriteAsync(headers);
        }
    });
    var probe = await HealthProbe.CheckFrontendAsync($"http://127.0.0.1:{port}", default);
    await serve; Assert(!probe.Healthy && probe.Detail.Contains("API 代理异常"));
});
AsyncTest("local stop-all preserves shared database containers", async () =>
{
    recorder.Commands.Clear(); recorder.LocalJson = "{\"processes\":[],\"listeners\":[]}";
    await new LauncherEngine(config, recorder, _ => { }).ExecuteAsync("stop-all", "local", null, default);
    Assert(!recorder.Commands.Any(c => c[0] is "stop" or "compose"));
});
AsyncTest("command streaming redacts synthetic credentials", async () =>
{
    if (!OperatingSystem.IsWindows()) return;
    var entries = new List<LauncherLog>();
    var runner = new CommandRunner(entries.Add, config.Redactor);
    await runner.RunAsync("powershell.exe", ["-NoProfile", "-NonInteractive", "-Command", "[Console]::WriteLine('password=synthetic-db-password')"],
        config.Root, TimeSpan.FromSeconds(10), default, true);
    Assert(entries.Count > 0 && entries.All(e => !e.Message.Contains("synthetic-db-password")));
});
AsyncTest("child command timeout is bounded and reported", async () =>
{
    if (!OperatingSystem.IsWindows()) return;
    await Throws(() => new CommandRunner(_ => { }, config.Redactor).RunAsync("powershell.exe",
        ["-NoProfile", "-NonInteractive", "-Command", "Start-Sleep -Seconds 30"], config.Root, TimeSpan.FromMilliseconds(500), default), "超时");
});

async Task<HealthResult> Probe(string status, string contentType, bool html)
{
    using var listener = new TcpListener(IPAddress.Loopback, 0);
    listener.Start(); var port = ((IPEndPoint)listener.LocalEndpoint).Port;
    var serve = Task.Run(async () =>
    {
        using var connection = await listener.AcceptTcpClientAsync();
        await using var stream = connection.GetStream();
        using var request = new StreamReader(stream, Encoding.ASCII, false, 4096, leaveOpen: true);
        while (await request.ReadLineAsync() is { Length: > 0 }) { }
        // Deliberately omit the declared body. A header-only health probe must still finish.
        var headers = Encoding.ASCII.GetBytes($"HTTP/1.1 {status}\r\nContent-Type: {contentType}\r\nContent-Length: 102400\r\nConnection: close\r\n\r\n");
        await stream.WriteAsync(headers); await stream.FlushAsync();
    });
    var probe = await HealthProbe.CheckAsync($"http://127.0.0.1:{port}/api/files?page=1&size=1", html, default);
    await serve; return probe;
}

VersionUpdateChecks.Register(tests);
SelfUpdateChecks.Register(tests);
ServiceHostCacheChecks.Register(tests);

var failed = 0;
foreach (var test in tests)
{
    try { await test.Run(); Console.WriteLine($"PASS {test.Name}"); }
    catch (Exception ex) { failed++; Console.Error.WriteLine($"FAIL {test.Name}: {ex.Message}"); }
}
Console.WriteLine($"{tests.Count - failed}/{tests.Count} passed");
return failed == 0 ? 0 : 1;

sealed class Fixture : IDisposable
{
    public string Root { get; } = Path.Combine(Path.GetTempPath(), "learnhub-launcher-test-" + Guid.NewGuid().ToString("N"));
    public ProjectConfig Config { get; }
    public string Overlay => Path.Combine(Root, "deploy", "docker-compose.code-runner.yml");
    public Fixture()
    {
        Directory.CreateDirectory(Path.Combine(Root, "deploy"));
        Directory.CreateDirectory(Path.Combine(Root, "backend", "target"));
        Directory.CreateDirectory(Path.Combine(Root, "frontend", "node_modules", "vite", "bin"));
        File.WriteAllText(Path.Combine(Root, "deploy", "docker-compose.yml"), "name: learn-hub\nservices: {}\n");
        File.WriteAllText(Overlay, "services: {}\n");
        File.WriteAllText(Path.Combine(Root, "deploy", ".env"), "WEB_HOST_PORT=8890\nMYSQL_ROOT_PASSWORD=synthetic-db-password\nCOMPOSE_PROJECT_NAME=learn-hub-synthetic\n");
        File.WriteAllText(Path.Combine(Root, "backend", "target", "learn-hub-backend-1.0.jar"), "synthetic");
        File.WriteAllText(Path.Combine(Root, "frontend", "node_modules", "vite", "bin", "vite.js"), "// synthetic");
        Config = new(Root);
    }
    public ProjectContainer Container(string service, char id) => new(new string(id, 64), "synthetic-" + service, "synthetic:local", Config.ProjectName,
        Config.Deploy, [Config.ComposeFile], service, "running", null, "2026-10-26T01:00:00Z", new Dictionary<string, int> { ["80/tcp"] = 8890 });
    public void Dispose()
    {
        var resolved = Path.GetFullPath(Root);
        if (resolved.StartsWith(Path.GetFullPath(Path.GetTempPath()), StringComparison.OrdinalIgnoreCase) && Path.GetFileName(resolved).StartsWith("learnhub-launcher-test-")) Directory.Delete(resolved, true);
        // A synthetic state directory contains only this fixture's operation lock.
        var state = Path.GetFullPath(Config.StateDirectory);
        var parent = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "LearnHub", "Launcher") + Path.DirectorySeparatorChar;
        if (state.StartsWith(parent, StringComparison.OrdinalIgnoreCase) && Directory.Exists(state)) Directory.Delete(state, true);
    }
}

sealed class FakeRunner : ICommandRunner
{
    public List<string[]> Commands { get; } = [];
    public IReadOnlyList<ProjectContainer> Containers { get; set; } = [];
    public string LocalJson { get; set; } = "{\"processes\":[],\"listeners\":[]}";
    public string? InspectError { get; set; }
    public bool MalformedInspect { get; set; }
    public bool IncompleteInspect { get; set; }
    public CommandResult? JavaResult { get; set; }
    public Task<CommandResult> RunAsync(string executable, IReadOnlyList<string> arguments, string workingDirectory, TimeSpan timeout, CancellationToken cancellationToken,
        bool streamOutput = false, IReadOnlyDictionary<string, string>? environment = null)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var args = arguments.ToArray(); Commands.Add(args);
        if (Path.GetFileName(executable).Equals("java.exe", StringComparison.OrdinalIgnoreCase) && JavaResult != null) return Task.FromResult(JavaResult);
        if (args[0] == "inspect" && InspectError != null) return Task.FromResult(new CommandResult(1, Json(Containers[0]), InspectError));
        var output = executable == "powershell.exe" ? LocalJson
            : args[0] == "--version" ? "Docker synthetic"
            : args[0] == "version" ? "28.0-synthetic"
            : args[0] == "context" ? "synthetic-linux"
            : args[0] == "ps" ? string.Join('\n', Containers.Select(c => c.Id))
            : args[0] == "inspect" ? MalformedInspect ? "{synthetic malformed" : string.Join('\n', Containers.Where(c => args.Contains(c.Id)).Take(IncompleteInspect ? 1 : int.MaxValue).Select(Json))
            : args[0] == "logs" ? "password=synthetic-db-password"
            : "ok";
        return Task.FromResult(new CommandResult(0, output, ""));
    }
    public static string Json(ProjectContainer item) => JsonSerializer.Serialize(new
    {
        id = item.Id, name = item.Name, image = item.Image, project = item.Project, workingDirectory = item.WorkingDirectory,
        configFiles = string.Join(',', item.ConfigFiles), service = item.Service, state = item.State, health = item.Health, startedAt = item.StartedAt,
        ports = item.Ports.ToDictionary(p => p.Key, p => new[] { new { HostPort = p.Value.ToString(), HostIp = "127.0.0.1" } })
    });
}
