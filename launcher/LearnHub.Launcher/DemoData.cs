namespace LearnHub.Launcher;

// Synthetic data only. --demo never inspects or controls the local computer.
internal static class DemoData
{
    private static readonly Dictionary<string, string> States = new() { ["frontend"] = "healthy", ["backend"] = "healthy", ["mysql"] = "healthy" };
    private static string sourceVersion = "0.1.4";
    private static string launcherVersion = "1.1.0";

    public static void UpdateLauncher() => launcherVersion = "1.2.0";
    public static object LauncherVersionStatus() => new
    {
        currentVersion = launcherVersion, latestVersion = "1.2.0", state = launcherVersion == "1.1.0" ? "available" : "current",
        detail = launcherVersion == "1.1.0" ? "合成预览：可模拟下载、校验并重启启动器。" : "合成预览：启动器已更新。",
        releaseUrl = "https://github.com/luyiyanyumu/LearnHub/releases/tag/launcher-v1.2.0",
        downloadUrl = "https://github.com/luyiyanyumu/LearnHub/releases/download/launcher-v1.2.0/LearnHub-win-x64.exe",
        sha256 = new string('c', 64), size = 102400L, runtime = "win-x64", checkedAt = DateTimeOffset.Now.ToString("O")
    };

    public static void UpdateSource() => sourceVersion = "0.1.5";
    public static object VersionStatus() => new
    {
        currentVersion = sourceVersion, sourceDescription = "v" + sourceVersion, currentCommit = new string(sourceVersion == "0.1.4" ? 'a' : 'b', 40),
        branch = "main", dirty = false, changeCount = 0, latestVersion = "0.1.5", latestCommit = new string('b', 40),
        releaseName = "LearnHub v0.1.5（演示）", releaseNotes = "• 改善服务启动与健康状态检查\n• 优化笔记编辑与资料阅读\n• 修复已知问题\n\n以上是合成发布内容，不代表实际版本。",
        releaseUrl = "https://github.com/luyiyanyumu/LearnHub/releases/tag/v0.1.5", publishedAt = "2026-10-22T08:00:00Z",
        canUpdate = sourceVersion == "0.1.4", updateAvailable = sourceVersion == "0.1.4", blockedReason = sourceVersion == "0.1.4" ? null : "已包含最新稳定版，正在运行的服务可按需重新构建。",
        checkedAt = DateTimeOffset.Now.ToString("O"), repository = "luyiyanyumu/LearnHub", launcherAssetUrl = (string?)null,
    };

    public static void Apply(string action, string mode, string? service)
    {
        if (action is "start-all" or "build") foreach (var id in States.Keys.ToArray()) States[id] = "healthy";
        if (action == "stop-all") foreach (var id in States.Keys.ToArray()) if (mode != "local" || id != "mysql") States[id] = "stopped";
        if (service is not null && States.ContainsKey(service) && action is "start" or "stop" or "restart") States[service] = action == "stop" ? "stopped" : "healthy";
    }

    public static object Snapshot(string mode) => new
    {
        projectRoot = @"C:\Demo\LearnHub", mode, checkedAt = DateTimeOffset.Now.ToString("O"),
        docker = new { installed = true, ready = true, version = "28.0.0", context = "desktop-linux", error = (string?)null },
        services = new[]
        {
            Service("frontend", "前端服务", mode, "healthy", 8888, 8, "页面可访问"),
            Service("backend", "后端服务", mode, "healthy", 18080, 24, "API 与前端代理健康"),
            Service("mysql", "MySQL 数据库", "docker", "healthy", 3307, 3, "容器健康检查通过"),
        },
        diagnostics = new[] { new { level = "success", title = "服务连接正常", detail = "这是合成预览数据，未检查本机服务。" }, new { level = "info", title = "自动适配系统显示", detail = "窗口与界面随系统 DPI 缩放，支持深浅主题。" } },
    };

    private static object Service(string id, string name, string mode, string state, int port, int ms, string detail) => new
    {
        id, name, kind = mode == "docker" ? "container" : "process", state = States[id], detail = States[id] == "stopped" ? "服务已停止（演示）" : detail, port,
        address = id == "mysql" ? null : $"http://localhost:{port}",
        containerId = mode == "docker" ? "demo-" + id : null, containerName = "learnhub-demo-" + id,
        image = "learnhub-demo/" + id + ":1.0", uptime = "2 小时", responseMs = ms, processId = (int?)null,
        canStart = true, canStop = States[id] != "stopped", canRestart = true,
    };

    public static string Logs(string service) => $"{DateTimeOffset.Now:HH:mm:ss} [INFO] [{service}] 合成日志：服务已启动。\n{DateTimeOffset.Now:HH:mm:ss} [INFO] 健康检查通过。\n此预览不会读取实际日志、配置或知识库内容。";
}
