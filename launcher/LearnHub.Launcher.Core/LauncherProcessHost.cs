using System.Diagnostics;
using System.Text;

namespace LearnHub.Launcher.Core;

/// <summary>Called by the executable before any GUI / single-instance initialization.</summary>
public static class LauncherProcessHost
{
    public static async Task<int?> TryRunAsync(string[] args)
    {
        if (args.Length == 0 || args[0] != "--learnhub-service-host") return null;
        if (args.Length != 3 || args[2] is not ("frontend" or "backend")) return 2;
        ProjectConfig config;
        try { config = new ProjectConfig(args[1]); } catch { return 2; }
        var service = args[2];
        Directory.CreateDirectory(config.StateDirectory);
        // A background host owns this lock for its entire service lifetime.
        FileStream lease;
        try { lease = new FileStream(Path.Combine(config.StateDirectory, service + ".host.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None); }
        catch (IOException) { return 3; }
        await using var heldLease = lease;
        var logPath = Path.Combine(config.StateDirectory, service + ".log");
        try
        {
            // Bound previous sessions without deleting current service output.
            if (File.Exists(logPath) && new FileInfo(logPath).Length > 5 * 1024 * 1024)
            {
                var previous = logPath + ".previous";
                File.Move(logPath, previous, true);
            }
            await using var stream = new FileStream(logPath, FileMode.Append, FileAccess.Write, FileShare.ReadWrite | FileShare.Delete);
            await using var writer = new StreamWriter(stream, new UTF8Encoding(false)) { AutoFlush = true };
            using var writeLock = new SemaphoreSlim(1, 1);
            async Task Write(string text)
            {
                await writeLock.WaitAsync();
                try
                {
                    if (stream.Length > 10 * 1024 * 1024)
                    {
                        await writer.FlushAsync(); stream.SetLength(0); stream.Position = 0;
                        await writer.WriteLineAsync($"{DateTimeOffset.Now:O} 日志达到上限，已清理本次会话的早期记录。");
                    }
                    await writer.WriteLineAsync($"{DateTimeOffset.Now:O} {config.Redactor.Redact(text)}");
                }
                finally { writeLock.Release(); }
            }
            var processInfo = CreateServiceStartInfo(config, service);
            // Own processes are checked once again in the detached helper, which might have started late.
            var checker = new LocalOperations(config, new CommandRunner(_ => { }, config.Redactor), _ => { });
            var current = await checker.InventoryAsync(CancellationToken.None);
            if (current.Processes.Any(p => checker.IsOwned(p, service)) || current.Listeners.Any(l => l.Port == config.GetPort(service, true)))
            { await Write("服务或端口已被使用，后台启动取消。"); return 3; }
            using var child = new Process { StartInfo = processInfo };
            child.Start();
            await Write($"{ProjectConfig.ServiceName(service)}后台进程已启动，PID {child.Id}。");
            async Task Read(StreamReader reader)
            { while (await reader.ReadLineAsync() is { } line) await Write(line); }
            await Task.WhenAll(Read(child.StandardOutput), Read(child.StandardError), child.WaitForExitAsync());
            await Write($"服务进程已退出，退出码 {child.ExitCode}。");
            return child.ExitCode;
        }
        catch (Exception ex)
        {
            try { await File.AppendAllTextAsync(logPath, $"{DateTimeOffset.Now:O} {config.Redactor.Redact(ex.Message)}\n"); } catch { }
            return 1;
        }
    }

    public static ProcessStartInfo CreateServiceStartInfo(ProjectConfig config, string service)
    {
        if (service is not ("frontend" or "backend")) throw new InvalidOperationException("无效服务。");
        var info = new ProcessStartInfo(service == "backend" ? config.JavaExecutable : "node.exe")
        {
            WorkingDirectory = Path.Combine(config.Root, service), UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardOutput = true, RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8, StandardErrorEncoding = Encoding.UTF8
        };
        if (service == "frontend")
        {
            if (!File.Exists(config.ViteEntry)) throw new InvalidOperationException("本项目 Vite 入口不存在。");
            foreach (var arg in new[] { config.ViteEntry, "--host", "127.0.0.1", "--port", config.LocalFrontendPort.ToString(), "--strictPort" }) info.ArgumentList.Add(arg);
        }
        else
        {
            info.ArgumentList.Add("-Dfile.encoding=UTF-8"); info.ArgumentList.Add("-jar");
            info.ArgumentList.Add(config.FindJar() ?? throw new InvalidOperationException("可执行 JAR 不存在。"));
            info.ArgumentList.Add($"--server.port={config.LocalBackendPort}");
            var database = config.Environment.GetValueOrDefault("MYSQL_DATABASE", "learn_hub");
            if (!System.Text.RegularExpressions.Regex.IsMatch(database, "^[A-Za-z0-9_]+$")) throw new InvalidOperationException("MYSQL_DATABASE 仅支持字母、数字和下划线。");
            info.Environment["SPRING_DATASOURCE_URL"] = $"jdbc:mysql://localhost:{config.MysqlPort}/{database}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
            info.Environment["SPRING_DATASOURCE_USERNAME"] = "root";
            if (config.Environment.TryGetValue("MYSQL_ROOT_PASSWORD", out var password)) info.Environment["SPRING_DATASOURCE_PASSWORD"] = password;
            foreach (var key in new[] { "DEEPSEEK_API_KEY", "KB_EMBED_BASE_URL", "KB_EMBED_MODEL" })
                if (config.Environment.TryGetValue(key, out var value)) info.Environment[key] = value;
            info.Environment["MYBATIS_PLUS_CONFIGURATION_LOG_IMPL"] = "org.apache.ibatis.logging.nologging.NoLoggingImpl";
            info.Environment["LEARNHUB_SKILLS_DIR"] = Path.Combine(config.Root, "skills");
        }
        return info;
    }
}
