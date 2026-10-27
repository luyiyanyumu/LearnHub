using System.Diagnostics;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace LearnHub.Launcher.Updating;

public sealed record LauncherUpdatePlan(
    string TargetPath,
    string StagedPath,
    string HelperPath,
    int ParentPid,
    long ParentStartTimeUtcTicks,
    string ExpectedOldSha256,
    string ExpectedNewSha256,
    string Version,
    string ProjectRoot,
    string Token,
    string ResultPath,
    bool Demo = false);

public sealed record LauncherUpdateResult(
    bool Success, bool RolledBack, string Message, string Version,
    string TargetPath, string? BackupPath, string CompletedAt, int? LaunchedPid);

public sealed record UpdateProcess(int Id, long StartTimeUtcTicks);

public interface IUpdateRuntime
{
    string CurrentExecutablePath { get; }
    int CurrentProcessId { get; }
    long CurrentProcessStartTimeUtcTicks { get; }
    long MonotonicMilliseconds { get; }
    bool IsProcessRunning(UpdateProcess process);
    UpdateProcess Start(string executablePath, IReadOnlyList<string> arguments, string workingDirectory);
    Task StopOwnedProcessAsync(UpdateProcess process, CancellationToken cancellationToken);
    Task DelayAsync(TimeSpan duration, CancellationToken cancellationToken);
}

public sealed record UpdateInstallerOptions
{
    public TimeSpan ParentExitTimeout { get; init; } = TimeSpan.FromSeconds(60);
    public TimeSpan AcknowledgementTimeout { get; init; } = TimeSpan.FromSeconds(45);
    public TimeSpan StabilityPeriod { get; init; } = TimeSpan.FromSeconds(2);
    public TimeSpan FileRetryTimeout { get; init; } = TimeSpan.FromSeconds(15);
    public TimeSpan PollInterval { get; init; } = TimeSpan.FromMilliseconds(250);
}

public sealed class SystemUpdateRuntime : IUpdateRuntime
{
    public string CurrentExecutablePath => Environment.ProcessPath ?? throw new InvalidOperationException("无法核验更新进程路径。");
    public int CurrentProcessId => Environment.ProcessId;
    public long CurrentProcessStartTimeUtcTicks { get { using var process = Process.GetCurrentProcess(); return process.StartTime.ToUniversalTime().Ticks; } }
    public long MonotonicMilliseconds => Environment.TickCount64;

    public bool IsProcessRunning(UpdateProcess process)
    {
        try
        {
            using var value = Process.GetProcessById(process.Id);
            return !value.HasExited && value.StartTime.ToUniversalTime().Ticks == process.StartTimeUtcTicks;
        }
        catch (ArgumentException) { return false; }
        catch (InvalidOperationException) { return false; }
    }

    public UpdateProcess Start(string executablePath, IReadOnlyList<string> arguments, string workingDirectory)
    {
        var info = new ProcessStartInfo(executablePath)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            WorkingDirectory = workingDirectory
        };
        foreach (var argument in arguments) info.ArgumentList.Add(argument);
        using var process = Process.Start(info) ?? throw new IOException("无法启动启动器。");
        return new UpdateProcess(process.Id, process.StartTime.ToUniversalTime().Ticks);
    }

    public async Task StopOwnedProcessAsync(UpdateProcess process, CancellationToken cancellationToken)
    {
        if (!IsProcessRunning(process)) return;
        using var value = Process.GetProcessById(process.Id);
        if (value.StartTime.ToUniversalTime().Ticks != process.StartTimeUtcTicks) return;
        value.CloseMainWindow();
        var deadline = Environment.TickCount64 + 3000;
        while (IsProcessRunning(process) && Environment.TickCount64 < deadline)
            await Task.Delay(100, cancellationToken);
        if (IsProcessRunning(process))
        {
            // Only the exact child launched by this helper may be terminated.
            value.Kill(entireProcessTree: false);
            await value.WaitForExitAsync(cancellationToken).WaitAsync(TimeSpan.FromSeconds(5), cancellationToken);
        }
    }

    public Task DelayAsync(TimeSpan duration, CancellationToken cancellationToken) => Task.Delay(duration, cancellationToken);
}

internal sealed record UpdateAcknowledgement(string Token, int ProcessId, long StartTimeUtcTicks, string Sha256);
internal sealed record UpdateReady(string Token, int ProcessId, long StartTimeUtcTicks);

internal sealed record ValidatedUpdatePlan(LauncherUpdatePlan Plan, string PlanPath, string DirectoryPath,
    string AcknowledgementPath, string ReadyPath, string TemporaryPath, string BackupPath, string FailedPath);

internal static partial class UpdateProtocol
{
    internal static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web) { PropertyNameCaseInsensitive = true, WriteIndented = true };
    [GeneratedRegex("\\A[a-fA-F0-9]{64}\\z", RegexOptions.CultureInvariant)]
    private static partial Regex Hex64();
    [GeneratedRegex("\\A[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?\\z", RegexOptions.CultureInvariant)]
    private static partial Regex ReleaseVersion();

    internal static async Task<ValidatedUpdatePlan> ReadAsync(string planPath, CancellationToken ct)
    {
        planPath = AbsolutePath(planPath);
        RejectReparsePoints(planPath);
        if (!File.Exists(planPath) || new FileInfo(planPath).Length > 64 * 1024)
            throw new InvalidDataException("更新计划不存在或格式无效。");
        var plan = JsonSerializer.Deserialize<LauncherUpdatePlan>(await File.ReadAllTextAsync(planPath, ct), Json)
            ?? throw new InvalidDataException("更新计划格式无效。");
        if (plan.Token is null || !Hex64().IsMatch(plan.Token) || plan.ExpectedOldSha256 is null || !Hex64().IsMatch(plan.ExpectedOldSha256)
            || plan.ExpectedNewSha256 is null || !Hex64().IsMatch(plan.ExpectedNewSha256)
            || plan.Version is null || plan.Version.Length > 100 || !ReleaseVersion().IsMatch(plan.Version)
            || plan.ParentPid <= 0 || plan.ParentStartTimeUtcTicks <= 0)
            throw new InvalidDataException("更新计划身份或版本无效。");
        var directory = Path.GetDirectoryName(planPath)!;
        if (!string.Equals(Path.GetFileName(directory), plan.Token, StringComparison.Ordinal))
            throw new InvalidDataException("更新目录身份不匹配。");
        var paths = new[] { plan.TargetPath, plan.StagedPath, plan.HelperPath, plan.ResultPath, plan.ProjectRoot };
        foreach (var path in paths) { AbsolutePath(path); RejectReparsePoints(path); }
        foreach (var path in new[] { plan.StagedPath, plan.HelperPath, plan.ResultPath })
            if (!SamePath(Path.GetDirectoryName(path)!, directory)) throw new InvalidDataException("更新文件越出本次更新目录。");
        if (paths.Distinct(StringComparer.OrdinalIgnoreCase).Count() != paths.Length || SamePath(planPath, plan.ResultPath)
            || SamePath(planPath, plan.StagedPath) || SamePath(planPath, plan.HelperPath)
            || SamePath(Path.GetDirectoryName(plan.TargetPath)!, directory))
            throw new InvalidDataException("更新文件路径互相冲突。");
        if (!string.Equals(Path.GetExtension(plan.TargetPath), ".exe", StringComparison.OrdinalIgnoreCase)
            || !string.Equals(Path.GetExtension(plan.StagedPath), ".exe", StringComparison.OrdinalIgnoreCase)
            || !string.Equals(Path.GetExtension(plan.HelperPath), ".exe", StringComparison.OrdinalIgnoreCase)
            || !Directory.Exists(plan.ProjectRoot)) throw new InvalidDataException("更新路径无效。");
        var targetDirectory = Path.GetDirectoryName(plan.TargetPath)!;
        var prefix = $".{Path.GetFileName(plan.TargetPath)}.{plan.Token}";
        var ack = Path.Combine(directory, "ack.json");
        var ready = Path.Combine(directory, "ready.json");
        if (SamePath(ack, plan.ResultPath) || SamePath(ack, planPath) || SamePath(ready, plan.ResultPath) || SamePath(ready, planPath))
            throw new InvalidDataException("更新握手路径冲突。");
        return new(plan, planPath, directory, ack, ready,
            Path.Combine(targetDirectory, prefix + ".next.exe"),
            Path.Combine(targetDirectory, prefix + ".backup.exe"),
            Path.Combine(targetDirectory, prefix + ".failed.exe"));
    }

    internal static string AbsolutePath(string? path)
    {
        if (string.IsNullOrWhiteSpace(path) || !Path.IsPathFullyQualified(path) || path.StartsWith("\\\\", StringComparison.Ordinal)
            || !string.Equals(Path.GetFullPath(path).TrimEnd(Path.DirectorySeparatorChar), path.TrimEnd(Path.DirectorySeparatorChar), StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException("更新路径必须为规范的本机绝对路径。");
        return Path.GetFullPath(path);
    }

    internal static void RejectReparsePoints(string path)
    {
        for (string? current = path; !string.IsNullOrEmpty(current); current = Path.GetDirectoryName(current))
            if ((File.Exists(current) || Directory.Exists(current)) && (File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0)
                throw new InvalidDataException("更新文件路径不能包含链接或重解析点。");
    }

    internal static bool SamePath(string first, string second) => string.Equals(Path.GetFullPath(first), Path.GetFullPath(second), StringComparison.OrdinalIgnoreCase);
    internal static bool SameHash(string first, string second) => string.Equals(first, second, StringComparison.OrdinalIgnoreCase);
    internal static async Task<string> HashAsync(string path, CancellationToken ct)
    {
        await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 81920, FileOptions.Asynchronous | FileOptions.SequentialScan);
        return Convert.ToHexString(await SHA256.HashDataAsync(stream, ct));
    }
    internal static async Task RequireHashAsync(string path, string expected, CancellationToken ct)
    {
        RejectReparsePoints(path);
        if (!SameHash(await HashAsync(path, ct), expected)) throw new InvalidDataException("更新文件校验失败，已保留原启动器。");
    }

    internal static async Task AtomicWriteAsync<T>(string path, T value, CancellationToken ct)
    {
        RejectReparsePoints(path);
        var temporary = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            await File.WriteAllTextAsync(temporary, JsonSerializer.Serialize(value, Json), ct);
            File.Move(temporary, path, overwrite: true);
        }
        finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }
}

public static class UpdateHandshake
{
    /// <summary>Call after the updated GUI is initialized and ready for normal use.</summary>
    public static Task AcknowledgeAsync(string planPath, string token, CancellationToken cancellationToken = default) =>
        AcknowledgeAsync(planPath, token, new SystemUpdateRuntime(), cancellationToken);

    public static async Task AcknowledgeAsync(string planPath, string token, IUpdateRuntime runtime, CancellationToken cancellationToken = default)
    {
        var value = await UpdateProtocol.ReadAsync(planPath, cancellationToken);
        var plan = value.Plan;
        if (!string.Equals(token, plan.Token, StringComparison.Ordinal) || !UpdateProtocol.SamePath(runtime.CurrentExecutablePath, plan.TargetPath))
            throw new InvalidDataException("启动器更新握手身份不匹配。");
        await UpdateProtocol.RequireHashAsync(plan.TargetPath, plan.ExpectedNewSha256, cancellationToken);
        var ack = new UpdateAcknowledgement(token, runtime.CurrentProcessId, runtime.CurrentProcessStartTimeUtcTicks, plan.ExpectedNewSha256);
        await UpdateProtocol.AtomicWriteAsync(value.AcknowledgementPath, ack, cancellationToken);
    }

    /// <summary>The running launcher must wait for this before shutting down.</summary>
    public static Task WaitForReadyAsync(string planPath, UpdateProcess helper, TimeSpan? timeout = null, CancellationToken cancellationToken = default) =>
        WaitForReadyAsync(planPath, helper, new SystemUpdateRuntime(), timeout, cancellationToken);

    public static async Task WaitForReadyAsync(string planPath, UpdateProcess helper, IUpdateRuntime runtime,
        TimeSpan? timeout = null, CancellationToken cancellationToken = default)
    {
        var value = await UpdateProtocol.ReadAsync(planPath, cancellationToken);
        var start = runtime.MonotonicMilliseconds;
        var limit = timeout ?? TimeSpan.FromSeconds(15);
        while (true)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (!runtime.IsProcessRunning(helper)) throw new IOException("更新辅助进程未能启动，当前启动器保持打开。");
            if (File.Exists(value.ReadyPath))
            {
                UpdateProtocol.RejectReparsePoints(value.ReadyPath);
                if (new FileInfo(value.ReadyPath).Length > 8192) throw new InvalidDataException("更新辅助进程握手格式无效。");
                var ready = JsonSerializer.Deserialize<UpdateReady>(await File.ReadAllTextAsync(value.ReadyPath, cancellationToken), UpdateProtocol.Json);
                if (ready is null || !string.Equals(ready.Token, value.Plan.Token, StringComparison.Ordinal)
                    || ready.ProcessId != helper.Id || ready.StartTimeUtcTicks != helper.StartTimeUtcTicks)
                    throw new InvalidDataException("更新辅助进程握手身份不匹配。");
                return;
            }
            if (runtime.MonotonicMilliseconds - start >= limit.TotalMilliseconds)
                throw new TimeoutException("更新辅助进程准备超时，当前启动器保持打开。");
            await runtime.DelayAsync(TimeSpan.FromMilliseconds(100), cancellationToken);
        }
    }
}
