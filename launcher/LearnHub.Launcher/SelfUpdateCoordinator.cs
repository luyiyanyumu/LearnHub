using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text.Json;
using LearnHub.Launcher.Core;
using LearnHub.Launcher.Updating;

namespace LearnHub.Launcher;

internal static class SelfUpdateCoordinator
{
    private sealed record ActiveUpdate(string PlanPath, int HelperPid, long HelperStartTimeUtcTicks);
    private static string ActivePath => Path.Combine(LauncherPreferences.DataDirectory, "updates", "active.json");
    internal static string CurrentVersion => typeof(App).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()!
        .InformationalVersion.Split('+')[0];
    internal static string Runtime => RuntimeInformation.ProcessArchitecture switch
    {
        Architecture.X64 => "win-x64",
        Architecture.Arm64 => "win-arm64",
        _ => throw new InvalidOperationException("当前架构暂不支持启动器自动更新。")
    };

    internal static string CreateStagingDirectory()
    {
        var token = Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
        var path = Path.Combine(LauncherPreferences.DataDirectory, "updates", token);
        Directory.CreateDirectory(path);
        return path;
    }

    internal static bool IsInstallationRunning()
    {
        try
        {
            var active = ReadActive();
            if (active is null) return false;
            using var process = Process.GetProcessById(active.HelperPid);
            return !process.HasExited && process.StartTime.ToUniversalTime().Ticks == active.HelperStartTimeUtcTicks
                && Path.GetFullPath(process.MainModule!.FileName).Equals(Path.Combine(Path.GetDirectoryName(active.PlanPath)!, "updater.exe"), StringComparison.OrdinalIgnoreCase);
        }
        catch { return false; }
    }

    internal static async Task<bool> IsRecoveryLaunchAsync(string? path, string? token)
    {
        if (path is null || token is null) return false;
        try
        {
            path = Path.GetFullPath(path);
            if (!File.Exists(path) || new FileInfo(path).Length > 65536 || Path.GetFileName(path) != "plan.json") return false;
            var plan = JsonSerializer.Deserialize<LauncherUpdatePlan>(await File.ReadAllTextAsync(path), LauncherPreferences.JsonOptions);
            return plan is not null && token.Length == 64 && token == plan.Token && Path.GetFileName(Path.GetDirectoryName(path)) == token
                && Path.GetFullPath(plan.TargetPath).Equals(Environment.ProcessPath, StringComparison.OrdinalIgnoreCase)
                && (await HashAsync(plan.TargetPath, default)).Equals(plan.ExpectedOldSha256, StringComparison.OrdinalIgnoreCase);
        }
        catch { return false; }
    }

    internal static string? ReadFailureNotice()
    {
        try
        {
            var active = ReadActive();
            if (active is null) return null;
            var directory = Path.GetDirectoryName(active.PlanPath)!;
            var resultPath = Path.Combine(directory, "result.json");
            var seenPath = Path.Combine(directory, "reported");
            if (!File.Exists(resultPath) || File.Exists(seenPath) || new FileInfo(resultPath).Length > 65536) return null;
            var result = JsonSerializer.Deserialize<LauncherUpdateResult>(File.ReadAllText(resultPath), LauncherPreferences.JsonOptions);
            if (result is null || result.Success || !Path.GetFullPath(result.TargetPath).Equals(Environment.ProcessPath, StringComparison.OrdinalIgnoreCase)) return null;
            using (new FileStream(seenPath, FileMode.CreateNew, FileAccess.Write, FileShare.None)) { }
            return result.Message;
        }
        catch { return null; }
    }

    private static ActiveUpdate? ReadActive()
    {
        if (!File.Exists(ActivePath) || new FileInfo(ActivePath).Length > 4096) return null;
        var active = JsonSerializer.Deserialize<ActiveUpdate>(File.ReadAllText(ActivePath), LauncherPreferences.JsonOptions);
        if (active is null) return null;
        var directory = Path.GetDirectoryName(Path.GetFullPath(active.PlanPath));
        var expectedParent = Path.GetFullPath(Path.Combine(LauncherPreferences.DataDirectory, "updates"));
        if (directory is null || Path.GetDirectoryName(directory) != expectedParent || Path.GetFileName(active.PlanPath) != "plan.json"
            || !System.Text.RegularExpressions.Regex.IsMatch(Path.GetFileName(directory), "^[A-Fa-f0-9]{64}$")) return null;
        return active;
    }

    internal static async Task PrepareRestartAsync(DownloadedLauncherUpdate download, string projectRoot, CancellationToken ct)
    {
        var target = Path.GetFullPath(Environment.ProcessPath ?? throw new InvalidOperationException("无法定位当前启动器。"));
        if (!Path.GetFileName(target).Equals("LearnHub.exe", StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("请先运行项目目录中的 LearnHub.exe 再更新启动器。");
        var fileVersion = FileVersionInfo.GetVersionInfo(download.FilePath).ProductVersion?.Split('+')[0];
        if (!VersionUpdateService.TryStableVersion(fileVersion, out var embedded) ||
            !VersionUpdateService.TryStableVersion(download.Info.LatestVersion, out var published) || embedded != published)
            throw new InvalidOperationException("下载包的内置版本与发布清单不一致，已停止更新。");
        var directory = Path.GetDirectoryName(Path.GetFullPath(download.FilePath))!;
        var token = Path.GetFileName(directory);
        var helper = Path.Combine(directory, "updater.exe");
        var planPath = Path.Combine(directory, "plan.json");
        var oldHash = await HashAsync(target, ct);
        await using (var source = new FileStream(target, FileMode.Open, FileAccess.Read, FileShare.Read | FileShare.Delete))
        await using (var output = new FileStream(helper, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            await source.CopyToAsync(output, ct);
        if (!oldHash.Equals(await HashAsync(helper, ct), StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("更新助手校验失败，当前启动器保持不变。");
        using var current = Process.GetCurrentProcess();
        var plan = new LauncherUpdatePlan(target, download.FilePath, helper, current.Id,
            current.StartTime.ToUniversalTime().Ticks, oldHash, download.Sha256,
            download.Info.LatestVersion!, Path.GetFullPath(projectRoot), token, Path.Combine(directory, "result.json"));
        await File.WriteAllTextAsync(planPath, JsonSerializer.Serialize(plan, LauncherPreferences.JsonOptions), ct);
        var info = new ProcessStartInfo(helper)
        {
            UseShellExecute = false, CreateNoWindow = true, WorkingDirectory = Path.GetDirectoryName(target)!
        };
        info.ArgumentList.Add("--apply-launcher-update");
        info.ArgumentList.Add(planPath);
        using var process = Process.Start(info) ?? throw new InvalidOperationException("无法启动更新助手。");
        try
        {
            await UpdateHandshake.WaitForReadyAsync(planPath,
                new UpdateProcess(process.Id, process.StartTime.ToUniversalTime().Ticks), TimeSpan.FromSeconds(30), ct);
            var marker = new ActiveUpdate(planPath, process.Id, process.StartTime.ToUniversalTime().Ticks);
            await File.WriteAllTextAsync(ActivePath, JsonSerializer.Serialize(marker, LauncherPreferences.JsonOptions), ct);
        }
        catch
        {
            // The helper cannot replace anything until this parent exits. Stop only our exact child.
            try { if (!process.HasExited) process.Kill(); } catch (InvalidOperationException) { }
            throw;
        }
    }

    private static async Task<string> HashAsync(string path, CancellationToken ct)
    {
        await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read | FileShare.Delete);
        return Convert.ToHexString(await SHA256.HashDataAsync(stream, ct));
    }
}
