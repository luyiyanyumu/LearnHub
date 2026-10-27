namespace LearnHub.Launcher.Core;

public sealed record LauncherLog(string Timestamp, string Level, string Message);
public sealed record DockerStatus(bool Installed, bool Ready, string? Version, string? Context, string? Error);
public sealed record ServiceStatus(
    string Id, string Name, string Kind, string State, string Detail,
    int? Port = null, string? Address = null, string? ContainerId = null,
    string? ContainerName = null, string? Image = null, string? Uptime = null,
    long? ResponseMs = null, int? ProcessId = null,
    bool CanStart = false, bool CanStop = false, bool CanRestart = false);
public sealed record DiagnosticItem(string Level, string Title, string Detail);
public sealed record LauncherSnapshot(string ProjectRoot, string Mode, string CheckedAt,
    DockerStatus Docker, IReadOnlyList<ServiceStatus> Services, IReadOnlyList<DiagnosticItem> Diagnostics);

public sealed record CommandResult(int ExitCode, string Output, string Error)
{
    public bool Success => ExitCode == 0;
}
public interface ICommandRunner
{
    Task<CommandResult> RunAsync(string executable, IReadOnlyList<string> arguments,
        string workingDirectory, TimeSpan timeout, CancellationToken cancellationToken,
        bool streamOutput = false, IReadOnlyDictionary<string, string>? environment = null);
}
