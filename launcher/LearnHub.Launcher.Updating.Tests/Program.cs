using System.Security.Cryptography;
using System.Text.Json;
using LearnHub.Launcher.Updating;

var tests = new List<(string, Func<Task>)>();
void Test(string name, Func<Task> body) => tests.Add((name, body));
void Assert(bool value, string message = "Assertion failed") { if (!value) throw new Exception(message); }
async Task Throws(Func<Task> body)
{
    try { await body(); } catch { return; }
    throw new Exception("Expected rejection");
}
var options = new UpdateInstallerOptions
{
    PollInterval = TimeSpan.FromMilliseconds(10), ParentExitTimeout = TimeSpan.FromMilliseconds(50),
    AcknowledgementTimeout = TimeSpan.FromMilliseconds(50), StabilityPeriod = TimeSpan.FromMilliseconds(20),
    FileRetryTimeout = TimeSpan.FromMilliseconds(30)
};

Test("replaces, verifies real ACK, preserves backup, passes only fixed arguments", async () =>
{
    using var f = new Fixture();
    f.Runtime.ParentRunning = true;
    f.Runtime.OnDelay = () =>
    {
        Assert(File.Exists(f.Ready));
        Assert(File.ReadAllText(f.Plan.TargetPath) == "synthetic old executable");
        f.Runtime.ParentRunning = false;
        f.Runtime.OnDelay = null;
    };
    var exit = await UpdateInstaller.RunAsync(f.PlanPath, f.Runtime, options);
    Assert(exit == 0 && f.Result().Success && !f.Result().RolledBack);
    Assert(File.ReadAllText(f.Plan.TargetPath) == "synthetic new executable");
    Assert(File.ReadAllText(f.Backup) == "synthetic old executable");
    Assert(f.Runtime.Starts.Single().Arguments.SequenceEqual(new[] { "--launcher-update-ack", f.PlanPath, "--launcher-update-token", f.Plan.Token, "--project", f.Plan.ProjectRoot }));
    Assert(f.Runtime.Clock >= 20 && f.Runtime.Stopped.Count == 0);
});
Test("demo is the only optional command flag", async () =>
{
    using var f = new Fixture(); await f.Write(f.Plan with { Demo = true });
    Assert(await UpdateInstaller.RunAsync(f.PlanPath, f.Runtime, options) == 0);
    Assert(f.Runtime.Starts.Single().Arguments.Last() == "--demo");
});
foreach (var file in new[] { "helper", "target", "staged" })
    Test($"rejects changed {file} before readiness or parent exit", async () =>
    {
        using var f = new Fixture();
        var path = file switch { "helper" => f.Plan.HelperPath, "target" => f.Plan.TargetPath, _ => f.Plan.StagedPath };
        await File.WriteAllTextAsync(path, "synthetic changed bytes");
        Assert(await UpdateInstaller.RunAsync(f.PlanPath, f.Runtime, options) == 1);
        Assert(!File.Exists(f.Ready) && f.Runtime.Starts.Count == 0 && !File.Exists(f.Backup));
    });
Test("rejects helper executable identity mismatch", async () =>
{
    using var f = new Fixture(); f.Runtime.Executable = f.Plan.TargetPath;
    Assert(await UpdateInstaller.RunAsync(f.PlanPath, f.Runtime, options) == 1);
    Assert(!File.Exists(f.Ready) && f.Runtime.Starts.Count == 0);
});
Test("waits for exact parent identity without terminating it", async () =>
{
    using var f = new Fixture(); f.Runtime.ParentRunning = true;
    Assert(await UpdateInstaller.RunAsync(f.PlanPath, f.Runtime, options) == 1);
    Assert(File.Exists(f.Ready) && f.Runtime.Clock >= 50 && f.Runtime.Starts.Count == 0 && f.Runtime.Stopped.Count == 0);
    Assert(File.ReadAllText(f.Plan.TargetPath) == "synthetic old executable");
});
Test("PID reuse is not treated as the original process", async () =>
{
    using var f = new Fixture(); f.Runtime.ParentRunning = true; f.Runtime.ParentStartTicks++;
    Assert(await UpdateInstaller.RunAsync(f.PlanPath, f.Runtime, options) == 0);
});
Test("does not accept Process.Start without ACK; rolls back and restarts", async () =>
{
    using var f = new Fixture(); f.Runtime.Ack = false;
    Assert(await UpdateInstaller.RunAsync(f.PlanPath, f.Runtime, options) == 2);
    Assert(f.Result().RolledBack && !f.Result().Success && f.Runtime.Stopped.Count == 1);
    Assert(File.ReadAllText(f.Plan.TargetPath) == "synthetic old executable" && File.ReadAllText(f.Failed) == "synthetic new executable");
    Assert(f.Runtime.Starts.Count == 2 && f.Runtime.Starts[1].Arguments.SequenceEqual(new[] { "--launcher-update-recovery", f.PlanPath, "--launcher-update-token", f.Plan.Token, "--project", f.Plan.ProjectRoot }));
});
foreach (var field in new[] { "token", "pid", "start", "hash" })
    Test($"rejects forged ACK {field} and restores old executable", async () =>
    {
        using var f = new Fixture(); f.Runtime.CorruptAck = field;
        Assert(await UpdateInstaller.RunAsync(f.PlanPath, f.Runtime, options) == 2);
        Assert(File.ReadAllText(f.Plan.TargetPath) == "synthetic old executable");
    });
Test("new process must remain alive after ACK", async () =>
{
    using var f = new Fixture(); f.Runtime.DieDuringStability = true;
    Assert(await UpdateInstaller.RunAsync(f.PlanPath, f.Runtime, options) == 2);
    Assert(f.Result().RolledBack && !f.Result().Success);
});
Test("failed new process launch rolls back before restarting old version", async () =>
{
    using var f = new Fixture(); f.Runtime.FailFirstStart = true;
    Assert(await UpdateInstaller.RunAsync(f.PlanPath, f.Runtime, options) == 2);
    Assert(File.ReadAllText(f.Plan.TargetPath) == "synthetic old executable" && f.Runtime.Starts.Count == 2);
});
Test("preexisting update file is never deleted", async () =>
{
    using var f = new Fixture(); await File.WriteAllTextAsync(f.Temporary, "unrelated conflict");
    Assert(await UpdateInstaller.RunAsync(f.PlanPath, f.Runtime, options) == 1);
    Assert(File.ReadAllText(f.Temporary) == "unrelated conflict");
});
Test("target write lock is retried before replacement", async () =>
{
    using var f = new Fixture();
    var handle = new FileStream(f.Plan.TargetPath, FileMode.Open, FileAccess.Read, FileShare.Read);
    f.Runtime.OnDelay = () => { handle.Dispose(); f.Runtime.OnDelay = null; };
    try { Assert(await UpdateInstaller.RunAsync(f.PlanPath, f.Runtime, options) == 0); }
    finally { handle.Dispose(); }
    Assert(f.Runtime.Clock >= 10);
});
Test("persistent file lock leaves original intact and reopens it", async () =>
{
    using var f = new Fixture();
    using var handle = new FileStream(f.Plan.TargetPath, FileMode.Open, FileAccess.Read, FileShare.Read);
    Assert(await UpdateInstaller.RunAsync(f.PlanPath, f.Runtime, options) == 1);
    Assert(File.ReadAllText(f.Plan.TargetPath) == "synthetic old executable" && !File.Exists(f.Backup));
    Assert(f.Runtime.Starts.Count == 1 && !File.Exists(f.Temporary));
});
Test("cancellation before replacement leaves parent and executable untouched", async () =>
{
    using var f = new Fixture(); using var cts = new CancellationTokenSource(); f.Runtime.ParentRunning = true;
    f.Runtime.OnDelay = () => cts.Cancel();
    Assert(await UpdateInstaller.RunAsync(f.PlanPath, f.Runtime, options, cts.Token) == 1);
    Assert(f.Runtime.Starts.Count == 0 && f.Runtime.Stopped.Count == 0 && !File.Exists(f.Backup));
});
Test("cancellation after replacement still performs uncancelled rollback", async () =>
{
    using var f = new Fixture(); using var cts = new CancellationTokenSource();
    f.Runtime.OnStart = () => cts.Cancel();
    Assert(await UpdateInstaller.RunAsync(f.PlanPath, f.Runtime, options, cts.Token) == 2);
    Assert(f.Result().RolledBack && File.ReadAllText(f.Plan.TargetPath) == "synthetic old executable");
});
Test("unsafe relative or escaping paths are rejected", async () =>
{
    using var f = new Fixture(); await f.Write(f.Plan with { ResultPath = Path.Combine(f.Root, "foreign-result.json") });
    Assert(await UpdateInstaller.RunAsync(f.PlanPath, f.Runtime, options) == 1);
    Assert(!File.Exists(Path.Combine(f.Root, "foreign-result.json")) && f.Runtime.Starts.Count == 0);
    await f.Write(f.Plan with { TargetPath = "LearnHub.exe" });
    Assert(await UpdateInstaller.RunAsync(f.PlanPath, f.Runtime, options) == 1);
});
Test("same source and destination hashes are refused", async () =>
{
    using var f = new Fixture(); File.Copy(f.Plan.TargetPath, f.Plan.StagedPath, true);
    await f.Write(f.Plan with { ExpectedNewSha256 = f.Plan.ExpectedOldSha256 });
    Assert(await UpdateInstaller.RunAsync(f.PlanPath, f.Runtime, options) == 1 && !File.Exists(f.Ready));
});
Test("ready handshake requires exact live helper identity", async () =>
{
    using var f = new Fixture();
    await File.WriteAllTextAsync(f.Ready, JsonSerializer.Serialize(new { token = f.Plan.Token, processId = f.Runtime.CurrentProcessId, startTimeUtcTicks = f.Runtime.CurrentProcessStartTimeUtcTicks }));
    await UpdateHandshake.WaitForReadyAsync(f.PlanPath, new(f.Runtime.CurrentProcessId, f.Runtime.CurrentProcessStartTimeUtcTicks), f.Runtime, TimeSpan.FromMilliseconds(30));
    await Throws(() => UpdateHandshake.WaitForReadyAsync(f.PlanPath, new(f.Runtime.CurrentProcessId, f.Runtime.CurrentProcessStartTimeUtcTicks + 1), f.Runtime, TimeSpan.FromMilliseconds(30)));
});
Test("new GUI ACK verifies executable path token and new digest", async () =>
{
    using var f = new Fixture(); File.Copy(f.Plan.StagedPath, f.Plan.TargetPath, true);
    f.Runtime.Executable = f.Plan.TargetPath;
    await Throws(() => UpdateHandshake.AcknowledgeAsync(f.PlanPath, "bad-token", f.Runtime));
    await UpdateHandshake.AcknowledgeAsync(f.PlanPath, f.Plan.Token, f.Runtime);
    using var ack = JsonDocument.Parse(await File.ReadAllTextAsync(f.Ack));
    Assert(ack.RootElement.GetProperty("processId").GetInt32() == f.Runtime.CurrentProcessId);
    File.WriteAllText(f.Plan.TargetPath, "tampered after start");
    await Throws(() => UpdateHandshake.AcknowledgeAsync(f.PlanPath, f.Plan.Token, f.Runtime));
});

var failures = 0;
foreach (var (name, body) in tests)
{
    try { await body(); Console.WriteLine("PASS " + name); }
    catch (Exception ex) { failures++; Console.WriteLine("FAIL " + name + ": " + ex.Message); }
}
Console.WriteLine($"{tests.Count - failures}/{tests.Count} passed");
return failures == 0 ? 0 : 1;

sealed class Fixture : IDisposable
{
    public string Root { get; } = Path.Combine(Path.GetTempPath(), "LearnHub-update-synthetic-" + Guid.NewGuid().ToString("N"));
    public LauncherUpdatePlan Plan { get; private set; }
    public string PlanPath { get; }
    public string Ready => Path.Combine(Path.GetDirectoryName(PlanPath)!, "ready.json");
    public string Ack => Path.Combine(Path.GetDirectoryName(PlanPath)!, "ack.json");
    public string Backup => Path.Combine(Path.GetDirectoryName(Plan.TargetPath)!, $".{Path.GetFileName(Plan.TargetPath)}.{Plan.Token}.backup.exe");
    public string Temporary => Path.Combine(Path.GetDirectoryName(Plan.TargetPath)!, $".{Path.GetFileName(Plan.TargetPath)}.{Plan.Token}.next.exe");
    public string Failed => Path.Combine(Path.GetDirectoryName(Plan.TargetPath)!, $".{Path.GetFileName(Plan.TargetPath)}.{Plan.Token}.failed.exe");
    public FakeRuntime Runtime { get; }
    public Fixture()
    {
        var token = Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
        var update = Path.Combine(Root, "updates", token); Directory.CreateDirectory(update);
        var target = Path.Combine(Root, "LearnHub.exe"); var helper = Path.Combine(update, "updater.exe"); var stage = Path.Combine(update, "next.exe");
        File.WriteAllText(target, "synthetic old executable"); File.Copy(target, helper); File.WriteAllText(stage, "synthetic new executable");
        Plan = new(target, stage, helper, 101, 1001, Hash(target), Hash(stage), "9.9.9", Root, token, Path.Combine(update, "result.json"));
        PlanPath = Path.Combine(update, "plan.json"); File.WriteAllText(PlanPath, JsonSerializer.Serialize(Plan));
        Runtime = new(this);
    }
    public Task Write(LauncherUpdatePlan plan) { Plan = plan; return File.WriteAllTextAsync(PlanPath, JsonSerializer.Serialize(plan)); }
    public LauncherUpdateResult Result() => JsonSerializer.Deserialize<LauncherUpdateResult>(File.ReadAllText(Plan.ResultPath), new JsonSerializerOptions { PropertyNameCaseInsensitive = true })!;
    static string Hash(string path) => Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(path)));
    public void Dispose()
    {
        var resolved = Path.GetFullPath(Root);
        var temporaryRoot = Path.GetFullPath(Path.GetTempPath()).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        if (!resolved.StartsWith(temporaryRoot, StringComparison.OrdinalIgnoreCase)
            || !Path.GetFileName(resolved).StartsWith("LearnHub-update-synthetic-", StringComparison.Ordinal))
            throw new InvalidOperationException("Unsafe test cleanup path");
        if (Directory.Exists(resolved)) Directory.Delete(resolved, true);
    }
}

sealed class FakeRuntime(Fixture fixture) : IUpdateRuntime
{
    public string Executable { get; set; } = fixture.Plan.HelperPath;
    public string CurrentExecutablePath => Executable;
    public int CurrentProcessId => 202;
    public long CurrentProcessStartTimeUtcTicks => 2002;
    public long Clock;
    public long MonotonicMilliseconds => Clock;
    public bool ParentRunning;
    public long ParentStartTicks = fixture.Plan.ParentStartTimeUtcTicks;
    public bool Ack = true;
    public string? CorruptAck;
    public bool DieDuringStability;
    public bool FailFirstStart;
    public Action? OnDelay;
    public Action? OnStart;
    public List<(string Path, IReadOnlyList<string> Arguments)> Starts = [];
    public List<UpdateProcess> Stopped = [];
    private readonly HashSet<UpdateProcess> running = [];
    public bool IsProcessRunning(UpdateProcess process) =>
        (process.Id == fixture.Plan.ParentPid && process.StartTimeUtcTicks == ParentStartTicks && ParentRunning)
        || process == new UpdateProcess(CurrentProcessId, CurrentProcessStartTimeUtcTicks)
        || (running.Contains(process) && !(DieDuringStability && Clock >= 10));
    public UpdateProcess Start(string executablePath, IReadOnlyList<string> arguments, string workingDirectory)
    {
        Starts.Add((executablePath, arguments.ToArray()));
        if (FailFirstStart && Starts.Count == 1) throw new IOException("synthetic start failure");
        var process = new UpdateProcess(300 + Starts.Count, 3000 + Starts.Count); running.Add(process);
        if (Ack && arguments.Contains("--launcher-update-ack"))
            File.WriteAllText(fixture.Ack, JsonSerializer.Serialize(new
            {
                token = CorruptAck == "token" ? "invalid" : fixture.Plan.Token,
                processId = CorruptAck == "pid" ? process.Id + 1 : process.Id,
                startTimeUtcTicks = CorruptAck == "start" ? process.StartTimeUtcTicks + 1 : process.StartTimeUtcTicks,
                sha256 = CorruptAck == "hash" ? new string('0', 64) : fixture.Plan.ExpectedNewSha256
            }));
        OnStart?.Invoke();
        return process;
    }
    public Task StopOwnedProcessAsync(UpdateProcess process, CancellationToken ct) { Stopped.Add(process); running.Remove(process); return Task.CompletedTask; }
    public Task DelayAsync(TimeSpan duration, CancellationToken ct) { ct.ThrowIfCancellationRequested(); Clock += (long)duration.TotalMilliseconds; OnDelay?.Invoke(); return Task.CompletedTask; }
}
