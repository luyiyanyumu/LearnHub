using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace LearnHub.Launcher.Core;

public sealed record LocalProcess(int ProcessId, string CommandLine);
public sealed record PortListener(int Port, int ProcessId);
public sealed record LocalInventory(IReadOnlyList<LocalProcess> Processes, IReadOnlyList<PortListener> Listeners);

public sealed class LocalOperations(ProjectConfig config, ICommandRunner runner, Action<LauncherLog> log)
{
    // Fixed read-only program. Project paths and user input are never interpolated into PowerShell.
    private const string InventoryScript = "[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false); $ErrorActionPreference='Stop'; $p=@(Get-CimInstance Win32_Process -Filter \"Name='java.exe' OR Name='node.exe'\" | Select-Object ProcessId,CommandLine); $l=@(Get-NetTCPConnection -State Listen -ErrorAction Stop | Select-Object LocalPort,OwningProcess); @{processes=$p;listeners=$l}|ConvertTo-Json -Depth 4 -Compress";

    public async Task<LocalInventory> InventoryAsync(CancellationToken ct)
    {
        var result = await runner.RunAsync("powershell.exe", ["-NoProfile", "-NonInteractive", "-Command", InventoryScript],
            config.Root, TimeSpan.FromSeconds(25), ct);
        if (!result.Success) throw new InvalidOperationException("无法读取本机进程与端口状态。请检查 Windows 管理服务；启动器已禁用未核实的进程操作。");
        return ParseInventory(result.Output);
    }

    public static LocalInventory ParseInventory(string json)
    {
        using var doc = JsonDocument.Parse(json); var root = doc.RootElement;
        var processes = new List<LocalProcess>(); var listeners = new List<PortListener>();
        if (root.TryGetProperty("processes", out var ps) && ps.ValueKind == JsonValueKind.Array)
            foreach (var p in ps.EnumerateArray())
                if (p.TryGetProperty("ProcessId", out var pid) && pid.TryGetInt32(out var id))
                    processes.Add(new(id, p.TryGetProperty("CommandLine", out var c) && c.ValueKind == JsonValueKind.String ? c.GetString() ?? "" : ""));
        if (root.TryGetProperty("listeners", out var ls) && ls.ValueKind == JsonValueKind.Array)
            foreach (var l in ls.EnumerateArray())
                if (l.TryGetProperty("LocalPort", out var port) && port.TryGetInt32(out var number) &&
                    l.TryGetProperty("OwningProcess", out var pid) && pid.TryGetInt32(out var id)) listeners.Add(new(number, id));
        return new(processes, listeners);
    }

    public static IReadOnlyList<string> SplitCommandLine(string commandLine)
    {
        if (OperatingSystem.IsWindows())
        {
            var ptr = CommandLineToArgvW(commandLine, out var count);
            if (ptr == IntPtr.Zero) return [];
            try { return Enumerable.Range(0, count).Select(i => Marshal.PtrToStringUni(Marshal.ReadIntPtr(ptr, i * IntPtr.Size)) ?? "").ToArray(); }
            finally { LocalFree(ptr); }
        }
        // Portable parser for synthetic tests; native Windows uses CommandLineToArgvW above.
        return Regex.Matches(commandLine, "\"([^\"]*)\"|([^\\s]+)").Select(m => m.Groups[1].Success ? m.Groups[1].Value : m.Groups[2].Value).ToArray();
    }
    [DllImport("shell32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern IntPtr CommandLineToArgvW(string commandLine, out int argc);
    [DllImport("kernel32.dll")] private static extern IntPtr LocalFree(IntPtr pointer);

    public bool IsOwned(LocalProcess process, string service)
    {
        if (string.IsNullOrWhiteSpace(process.CommandLine)) return false;
        var args = SplitCommandLine(process.CommandLine);
        if (args.Count < 2) return false;
        var exe = Path.GetFileName(args[0]);
        if (service == "frontend")
            return exe.Equals("node.exe", StringComparison.OrdinalIgnoreCase) &&
                Path.IsPathFullyQualified(args[1]) && ProjectConfig.NormalizePath(args[1]) == ProjectConfig.NormalizePath(config.ViteEntry);
        if (service != "backend" || !exe.Equals("java.exe", StringComparison.OrdinalIgnoreCase)) return false;
        var jarIndex = args.ToList().IndexOf("-jar");
        if (jarIndex < 1 || jarIndex + 1 >= args.Count) return false;
        // A class / module / eval argument followed by a JAR path is not a JAR launch.
        if (args.Skip(1).Take(jarIndex - 1).Any(a => !a.StartsWith('-') || a is "--source" or "--module" or "-m" or "-cp" or "-classpath" or "--class-path")) return false;
        var jar = args[jarIndex + 1];
        if (!Path.IsPathFullyQualified(jar)) return false;
        try
        {
            return ProjectConfig.NormalizePath(Path.GetDirectoryName(jar)!) == ProjectConfig.NormalizePath(Path.Combine(config.Root, "backend", "target")) &&
                Regex.IsMatch(Path.GetFileName(jar), @"^learn-hub-backend-[A-Za-z0-9._-]+\.jar$") &&
                !jar.EndsWith("-sources.jar", StringComparison.OrdinalIgnoreCase) && !jar.EndsWith("-javadoc.jar", StringComparison.OrdinalIgnoreCase);
        }
        catch { return false; }
    }

    public async Task<IReadOnlyList<ServiceStatus>> GetServicesAsync(CancellationToken ct)
    {
        LocalInventory inventory;
        try { inventory = await InventoryAsync(ct); }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            return new[] { "frontend", "backend" }.Select(id => new ServiceStatus(id, ProjectConfig.ServiceName(id), "process", "unknown",
                config.Redactor.Redact(ex.Message), config.GetPort(id, true))).ToArray();
        }
        return await Task.WhenAll(new[] { "frontend", "backend" }.Select(async service =>
        {
            var port = config.GetPort(service, true);
            var own = inventory.Processes.Where(p => IsOwned(p, service)).ToArray();
            var listeners = inventory.Listeners.Where(p => p.Port == port).ToArray();
            var conflict = listeners.Any(l => !own.Any(p => p.ProcessId == l.ProcessId)) || own.Length > 1;
            var exists = service == "frontend" ? File.Exists(config.ViteEntry) : config.FindJar() != null;
            var address = $"http://localhost:{port}";
            if (conflict) return new ServiceStatus(service, ProjectConfig.ServiceName(service), "process", "conflict",
                $"端口 {port} 被其它进程/容器占用，或存在多个项目进程；已阻止启停", port, address);
            if (own.Length == 0) return new ServiceStatus(service, ProjectConfig.ServiceName(service), "process", exists ? "stopped" : "missing",
                exists ? "项目进程未运行" : service == "backend" ? "未找到 backend/target 的可执行 JAR，请先构建后端" : "未找到 Vite，请先在 frontend 安装依赖",
                port, address, CanStart: exists);
            if (!listeners.Any()) return new ServiceStatus(service, ProjectConfig.ServiceName(service), "process", "starting",
                "项目进程已启动，正在等待端口", port, address, ProcessId: own[0].ProcessId, CanStop: true, CanRestart: true);
            var probe = service == "frontend" ? await HealthProbe.CheckFrontendAsync(address, ct)
                : await HealthProbe.CheckAsync(address + "/api/files/page?page=1&size=1", false, ct);
            return new ServiceStatus(service, ProjectConfig.ServiceName(service), "process", probe.Healthy ? "healthy" : "unhealthy", probe.Detail,
                port, address, ResponseMs: probe.ResponseMs, ProcessId: own[0].ProcessId, CanStop: true, CanRestart: true);
        }));
    }

    public async Task ExecuteAsync(string action, string? service, CancellationToken ct)
    {
        if (action == "build") throw new InvalidOperationException("本地模式暂不提供自动构建。请先构建后端 JAR、安装前端依赖，或切换到 Docker 使用构建更新。");
        if (action == "start-all")
        { await StartAsync("backend", ct); await StartAsync("frontend", ct); return; }
        if (action == "stop-all")
        { await StopAsync("frontend", ct); await StopAsync("backend", ct); return; }
        if (service is not ("frontend" or "backend")) throw new InvalidOperationException("本地模式只能管理已验证的前端、后端进程。");
        // Validate replacement runtime and artifact before ending a healthy current process.
        if (action == "restart") await PreflightAsync(service, ct);
        if (action is "stop" or "restart") await StopAsync(service, ct);
        if (action is "start" or "restart") await StartAsync(service, ct);
    }

    private async Task StartAsync(string service, CancellationToken ct)
    {
        var current = await InventoryAsync(ct);
        var port = config.GetPort(service, true);
        var owned = current.Processes.Where(p => IsOwned(p, service)).ToArray();
        if (current.Listeners.Any(l => l.Port == port && !owned.Any(p => p.ProcessId == l.ProcessId)))
            throw new InvalidOperationException($"端口 {port} 已被其它进程或容器占用；启动器不会结束占用者。请停止当前项目的 Docker 服务或更换配置。");
        if (owned.Length > 1) throw new InvalidOperationException("发现多个本项目进程，请先核对后再启动。");
        if (owned.Length == 1) { log(new(DateTimeOffset.Now.ToString("O"), "info", $"{ProjectConfig.ServiceName(service)}已在运行。")); return; }
        await PreflightAsync(service, ct);
        // Recheck after dependency checks so a concurrent external service is not accidentally shadowed.
        current = await InventoryAsync(ct);
        if (current.Listeners.Any(l => l.Port == port) || current.Processes.Any(p => IsOwned(p, service)))
            throw new InvalidOperationException("端口或进程状态已变化，请刷新后重试。");
        // Long-running service hosts use a verified cached copy so they never lock the GUI EXE during an update.
        var self = await ServiceHostCache.GetExecutableAsync(Environment.ProcessPath ?? throw new InvalidOperationException("无法确定启动器路径。"), ct);
        var info = new ProcessStartInfo(self) { UseShellExecute = false, CreateNoWindow = true, WindowStyle = ProcessWindowStyle.Hidden, WorkingDirectory = config.Root };
        info.ArgumentList.Add("--learnhub-service-host"); info.ArgumentList.Add(config.Root); info.ArgumentList.Add(service);
        using var helper = Process.Start(info) ?? throw new InvalidOperationException("无法启动后台服务宿主。");
        for (var i = 0; i < 16; i++)
        {
            await Task.Delay(500, ct);
            if (helper.HasExited && helper.ExitCode != 0)
                throw new InvalidOperationException("后台服务启动失败，请查看服务日志。");
            if ((await InventoryAsync(ct)).Processes.Any(p => IsOwned(p, service)))
            { log(new(DateTimeOffset.Now.ToString("O"), "success", $"{ProjectConfig.ServiceName(service)}进程已启动，等待健康检查。")); return; }
        }
        throw new TimeoutException("未在预期时间内发现服务进程，请查看日志。");
    }

    private async Task PreflightAsync(string service, CancellationToken ct)
    {
        if (service == "backend" && config.FindJar() == null) throw new InvalidOperationException("未找到 backend/target 中的可执行 JAR。请先构建后端。");
        if (service == "frontend" && !File.Exists(config.ViteEntry)) throw new InvalidOperationException("未找到本项目 Vite，请先安装 frontend 的依赖。");
        var executable = service == "backend" ? config.JavaExecutable : "node.exe";
        var version = await runner.RunAsync(executable, [service == "backend" ? "-version" : "--version"], config.Root, TimeSpan.FromSeconds(10), ct);
        if (!version.Success) throw new InvalidOperationException(service == "backend" ? "未找到 Java 21 或更高版本，请检查 JAVA_HOME 和 PATH。" : "未找到 Node.js，请安装并配置 PATH。");
        if (service == "backend")
        {
            var match = Regex.Match(version.Output + version.Error, @"(?:version\s+\""|openjdk\s+)(\d+)(?:[.\""\s])");
            if (!match.Success || !int.TryParse(match.Groups[1].Value, out var major) || major < 21)
                throw new InvalidOperationException("本地后端需要 Java 21 或更高版本。请检查 JAVA_HOME 和 PATH。");
        }
    }

    private async Task StopAsync(string service, CancellationToken ct)
    {
        var inventory = await InventoryAsync(ct);
        var matches = inventory.Processes.Where(p => IsOwned(p, service)).ToArray();
        if (matches.Length == 0) return;
        if (matches.Length > 1) throw new InvalidOperationException("发现多个本项目进程，已拒绝自动结束，请先人工核对。");
        var candidate = matches[0];
        if (inventory.Listeners.Any(l => l.Port == config.GetPort(service, true) && l.ProcessId != candidate.ProcessId))
            throw new InvalidOperationException("目标端口包含其它进程或容器监听，已拒绝结束操作。");
        using var process = Process.GetProcessById(candidate.ProcessId);
        var startTime = process.StartTime.ToUniversalTime();
        var fresh = await InventoryAsync(ct);
        var verified = fresh.Processes.SingleOrDefault(p => p.ProcessId == candidate.ProcessId);
        if (verified == null || !IsOwned(verified, service) || process.HasExited || process.StartTime.ToUniversalTime() != startTime)
            throw new InvalidOperationException("目标进程已发生变化，已取消结束操作。");
        // Kill only the verified process, never a port owner, process name, or an unverified tree.
        process.Kill();
        await process.WaitForExitAsync(ct).WaitAsync(TimeSpan.FromSeconds(20), ct);
        log(new(DateTimeOffset.Now.ToString("O"), "success", $"{ProjectConfig.ServiceName(service)}已停止。"));
    }

    public Task<string> GetLogsAsync(string service, int tail, CancellationToken ct)
    {
        if (service is not ("frontend" or "backend")) return Task.FromResult("请选择前端或后端服务。");
        var path = Path.Combine(config.StateDirectory, service + ".log");
        if (!File.Exists(path)) return Task.FromResult("暂无启动器服务日志。已有手动启动进程的日志不会被读取。");
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
        // Keep reads bounded even when a service has been running for a long time.
        if (stream.Length > 512 * 1024) stream.Seek(-512 * 1024, SeekOrigin.End);
        using var reader = new StreamReader(stream);
        var lines = reader.ReadToEnd().Split('\n').TakeLast(Math.Clamp(tail, 1, 1000));
        return Task.FromResult(config.Redactor.Redact(string.Join('\n', lines)));
    }
}
