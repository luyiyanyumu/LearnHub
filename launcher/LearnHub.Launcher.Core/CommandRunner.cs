using System.Diagnostics;
using System.Text;

namespace LearnHub.Launcher.Core;

public sealed class CommandRunner(Action<LauncherLog> log, LogRedactor redactor) : ICommandRunner
{
    private static readonly HashSet<string> Allowed = new(StringComparer.OrdinalIgnoreCase)
    { "docker", "docker.exe", "java", "java.exe", "node", "node.exe", "powershell", "powershell.exe", "git", "git.exe" };

    public async Task<CommandResult> RunAsync(string executable, IReadOnlyList<string> arguments,
        string workingDirectory, TimeSpan timeout, CancellationToken cancellationToken,
        bool streamOutput = false, IReadOnlyDictionary<string, string>? environment = null)
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (!Allowed.Contains(Path.GetFileName(executable))) throw new InvalidOperationException("启动器拒绝执行未授权命令。");
        var info = new ProcessStartInfo(executable)
        { WorkingDirectory = workingDirectory, UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardOutput = true, RedirectStandardError = true, StandardOutputEncoding = Encoding.UTF8, StandardErrorEncoding = Encoding.UTF8 };
        foreach (var argument in arguments) info.ArgumentList.Add(argument);
        if (environment != null) foreach (var pair in environment) info.Environment[pair.Key] = pair.Value;
        using var process = new Process { StartInfo = info };
        try { process.Start(); }
        catch (System.ComponentModel.Win32Exception) { return new CommandResult(127, "", $"找不到 {Path.GetFileName(executable)}，请检查安装和 PATH。"); }
        var output = new StringBuilder(); var error = new StringBuilder();
        async Task ReadAsync(StreamReader reader, StringBuilder target, string level)
        {
            while (await reader.ReadLineAsync() is { } line)
            {
                if (target.Length < 4 * 1024 * 1024) target.AppendLine(line);
                if (streamOutput) log(new LauncherLog(DateTimeOffset.Now.ToString("O"), level, redactor.Redact(line)));
            }
        }
        var readers = Task.WhenAll(ReadAsync(process.StandardOutput, output, "info"), ReadAsync(process.StandardError, error, "warning"));
        using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeoutCts.CancelAfter(timeout);
        try { await process.WaitForExitAsync(timeoutCts.Token); await readers; }
        catch (OperationCanceledException)
        {
            // Only this exact child command is cancelled. Existing business services stay alive.
            try { if (!process.HasExited) process.Kill(entireProcessTree: true); } catch { }
            try { await readers.WaitAsync(TimeSpan.FromSeconds(3)); } catch { }
            cancellationToken.ThrowIfCancellationRequested();
            throw new TimeoutException($"{Path.GetFileName(executable)} 操作超时（{timeout.TotalSeconds:0} 秒）。");
        }
        // Parsing callers require raw stdout (inspect); it is never written to logs without redaction.
        return new CommandResult(process.ExitCode, output.ToString(), redactor.Redact(error.ToString()));
    }
}
