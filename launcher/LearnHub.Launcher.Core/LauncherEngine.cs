namespace LearnHub.Launcher.Core;

public sealed class LauncherEngine
{
    private readonly ProjectConfig _config;
    private readonly DockerOperations _docker;
    private readonly LocalOperations _local;
    private readonly Action<LauncherLog> _log;
    public string ProjectRoot => _config.Root;

    public LauncherEngine(string projectRoot, Action<LauncherLog> log)
    {
        _config = new ProjectConfig(projectRoot);
        _log = entry => log(entry with { Message = _config.Redactor.Redact(entry.Message) });
        var runner = new CommandRunner(_log, _config.Redactor);
        _docker = new(_config, runner, _log); _local = new(_config, runner, _log);
    }

    // Injection boundary for deterministic behavior / command-safety tests.
    public LauncherEngine(ProjectConfig config, ICommandRunner runner, Action<LauncherLog> log)
    {
        _config = config; _log = entry => log(entry with { Message = config.Redactor.Redact(entry.Message) });
        _docker = new(config, runner, _log); _local = new(config, runner, _log);
    }

    public async Task<LauncherSnapshot> GetSnapshotAsync(string mode, CancellationToken ct)
    {
        ValidateMode(mode);
        var dockerStatus = await _docker.GetStatusAsync(ct);
        var diagnostics = new List<DiagnosticItem>();
        IReadOnlyList<ServiceStatus> services;
        try
        {
            if (mode == "docker") services = await _docker.GetServicesAsync(dockerStatus, ct);
            else
            {
                var localTask = _local.GetServicesAsync(ct);
                var dockerTask = _docker.GetServicesAsync(dockerStatus, ct);
                await Task.WhenAll(localTask, dockerTask);
                services = localTask.Result.Concat(dockerTask.Result.Where(s => s.Id is not ("frontend" or "backend"))).ToArray();
            }
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            diagnostics.Add(new("error", "状态读取失败", _config.Redactor.Redact(ex.Message)));
            services = new[] { "frontend", "backend", "mysql" }.Select(id => new ServiceStatus(id, ProjectConfig.ServiceName(id),
                mode == "docker" || id == "mysql" ? "container" : "process", "unknown", "状态无法核实，请查看诊断", _config.GetPort(id, mode == "local"))).ToArray();
        }
        diagnostics.Add(new(dockerStatus.Ready ? "success" : "warning", "Docker 引擎", dockerStatus.Ready ? $"引擎就绪 · {dockerStatus.Version}" : dockerStatus.Error ?? "引擎未就绪"));
        diagnostics.Add(new("info", "项目边界", "仅管理目录标签匹配的 Compose 容器及命令行路径匹配的本地进程。停止操作保留数据卷。"));
        if (mode == "local")
        {
            diagnostics.Add(new(_config.FindJar() != null ? "success" : "warning", "后端构建产物", _config.FindJar() != null ? "已找到可执行 JAR" : "需先构建 backend/target 的可执行 JAR"));
            diagnostics.Add(new(File.Exists(_config.ViteEntry) ? "success" : "warning", "前端依赖", File.Exists(_config.ViteEntry) ? "已找到本项目 Vite" : "需安装 frontend 的 Node 依赖"));
        }
        foreach (var service in services.Where(s => s.State is "unhealthy" or "conflict" or "unknown"))
            diagnostics.Add(new(service.State == "conflict" ? "error" : "warning", service.Name, service.Detail));
        if (services.Count > 0 && services.All(s => s.State == "healthy")) diagnostics.Add(new("success", "服务可用", "全部服务健康检查通过"));
        return new(ProjectRoot, mode, DateTimeOffset.Now.ToString("O"), dockerStatus, services,
            diagnostics.Select(d => d with { Detail = _config.Redactor.Redact(d.Detail) }).ToArray());
    }

    public async Task ExecuteAsync(string action, string mode, string? service, CancellationToken ct)
    {
        ValidateMode(mode);
        if (action is not ("start-all" or "stop-all" or "restart" or "start" or "stop" or "build" or "diagnose" or "docker-start"))
            throw new InvalidOperationException("不支持的启动器操作。");
        if (service is { Length: > 100 } || service?.IndexOfAny(['\r', '\n', '\0']) >= 0) throw new InvalidOperationException("无效服务标识。");
        if (action == "diagnose")
        {
            var snapshot = await GetSnapshotAsync(mode, ct);
            foreach (var item in snapshot.Diagnostics) _log(new(DateTimeOffset.Now.ToString("O"), item.Level, $"{item.Title}：{item.Detail}"));
            return;
        }
        Directory.CreateDirectory(_config.StateDirectory);
        FileStream fileLock;
        try { fileLock = new FileStream(Path.Combine(_config.StateDirectory, "operation.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None); }
        catch (IOException) { throw new InvalidOperationException("另一个启动器或命令行操作正在运行，请等待完成后重试。"); }
        await using var operationLock = fileLock;
        _log(new(DateTimeOffset.Now.ToString("O"), "info", $"开始操作：{action}" + (service == null ? "" : $" · {ProjectConfig.ServiceName(service)}")));
        try
        {
            if (mode == "docker" || action == "docker-start" || service is not null && service is not ("frontend" or "backend"))
                await _docker.ExecuteAsync(action, service, ct);
            else if (action == "start-all")
            {
                await _docker.ExecuteAsync("start", "mysql", ct);
                // Compose dependencies are absent in local mode: wait for DB readiness before Java.
                var deadline = DateTimeOffset.UtcNow.AddSeconds(100);
                while (true)
                {
                    ct.ThrowIfCancellationRequested();
                    var mysql = (await _docker.GetServicesAsync(await _docker.GetStatusAsync(ct), ct)).First(s => s.Id == "mysql");
                    if (mysql.State == "healthy") break;
                    if (mysql.State is "unhealthy" or "conflict" || DateTimeOffset.UtcNow > deadline)
                        throw new InvalidOperationException("数据库尚未健康，请查看 MySQL 日志后重试。");
                    await Task.Delay(2000, ct);
                }
                await _local.ExecuteAsync(action, service, ct);
            }
            else if (action == "stop-all")
            {
                await _local.ExecuteAsync(action, service, ct);
                _log(new(DateTimeOffset.Now.ToString("O"), "info", "本地前后端已停止；共享 MySQL 与可选容器服务继续运行，可在服务卡片中单独管理。"));
            }
            else await _local.ExecuteAsync(action, service, ct);
            _log(new(DateTimeOffset.Now.ToString("O"), "success", "操作完成，请等待健康检查刷新。"));
        }
        catch (Exception ex)
        {
            _log(new(DateTimeOffset.Now.ToString("O"), ex is OperationCanceledException ? "warning" : "error", _config.Redactor.Redact(ex.Message)));
            throw;
        }
    }

    public Task<string> GetLogsAsync(string mode, string service, int tail, CancellationToken ct)
    {
        ValidateMode(mode);
        if (string.IsNullOrWhiteSpace(service) || service.Length > 100) throw new InvalidOperationException("无效服务标识。");
        return mode == "docker" || service is not ("frontend" or "backend")
            ? _docker.GetLogsAsync(service, tail, ct) : _local.GetLogsAsync(service, tail, ct);
    }

    private static void ValidateMode(string mode)
    { if (mode is not ("docker" or "local")) throw new InvalidOperationException("运行模式必须是 docker 或 local。"); }
}
