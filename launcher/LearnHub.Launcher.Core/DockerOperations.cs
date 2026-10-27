using System.Diagnostics;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace LearnHub.Launcher.Core;

public sealed record ProjectContainer(string Id, string Name, string Image, string Project,
    string WorkingDirectory, IReadOnlyList<string> ConfigFiles, string Service, string State,
    string? Health, string? StartedAt, IReadOnlyDictionary<string, int> Ports);

public sealed class DockerOperations(ProjectConfig config, ICommandRunner runner, Action<LauncherLog> log)
{
    private const string InspectFormat = "{\"id\":{{json .Id}},\"name\":{{json .Name}},\"image\":{{json .Config.Image}},\"project\":{{json (index .Config.Labels \"com.docker.compose.project\")}},\"workingDirectory\":{{json (index .Config.Labels \"com.docker.compose.project.working_dir\")}},\"configFiles\":{{json (index .Config.Labels \"com.docker.compose.project.config_files\")}},\"service\":{{json (index .Config.Labels \"com.docker.compose.service\")}},\"state\":{{json .State.Status}},\"health\":{{with (index .State \"Health\")}}{{json (index . \"Status\")}}{{else}}null{{end}},\"startedAt\":{{json .State.StartedAt}},\"ports\":{{json .NetworkSettings.Ports}}}";

    public async Task<DockerStatus> GetStatusAsync(CancellationToken ct)
    {
        try
        {
            var version = await Run(["--version"], ct);
            if (!version.Success) return new(false, false, null, null, version.Error);
            var server = await Run(["version", "--format", "{{.Server.Version}}"], ct);
            var context = await Run(["context", "show"], ct);
            return new(true, server.Success, server.Success ? server.Output.Trim() : null,
                context.Success ? context.Output.Trim() : null,
                server.Success ? null : config.Redactor.Redact(server.Error.Trim()));
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        { return new(true, false, null, null, config.Redactor.Redact(ex.Message)); }
    }

    private Task<CommandResult> Run(IReadOnlyList<string> args, CancellationToken ct, bool stream = false, int seconds = 25) =>
        runner.RunAsync(DockerExecutable(), args, config.Deploy, TimeSpan.FromSeconds(seconds), ct, stream);

    private static string DockerExecutable()
    {
        var candidates = new[]
        {
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Docker", "Docker", "resources", "bin", "docker.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "DockerDesktop", "resources", "bin", "docker.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Docker", "resources", "bin", "docker.exe")
        };
        return candidates.FirstOrDefault(File.Exists) ?? "docker.exe";
    }

    public static ProjectContainer ParseContainer(string json)
    {
        using var doc = JsonDocument.Parse(json); var root = doc.RootElement;
        string Text(string name) => root.TryGetProperty(name, out var p) && p.ValueKind == JsonValueKind.String ? p.GetString() ?? "" : "";
        var ports = new Dictionary<string, int>();
        if (root.TryGetProperty("ports", out var portMap) && portMap.ValueKind == JsonValueKind.Object)
            foreach (var port in portMap.EnumerateObject())
                if (port.Value.ValueKind == JsonValueKind.Array)
                    foreach (var binding in port.Value.EnumerateArray())
                        if (binding.TryGetProperty("HostPort", out var host) && int.TryParse(host.GetString(), out var number))
                            ports.TryAdd(port.Name, number);
        return new(Text("id"), Text("name").TrimStart('/'), Text("image"), Text("project"), Text("workingDirectory"),
            Text("configFiles").Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries),
            Text("service"), Text("state"), Text("health") is { Length: > 0 } h ? h : null, Text("startedAt"), ports);
    }

    public bool IsOwned(ProjectContainer item) =>
        item.Project == config.ProjectName &&
        ProjectConfig.NormalizePath(item.WorkingDirectory) == ProjectConfig.NormalizePath(config.Deploy) &&
        item.ConfigFiles.Count > 0 && item.ConfigFiles.All(config.IsAllowedComposeFile) &&
        Regex.IsMatch(item.Service, "^[a-zA-Z0-9][a-zA-Z0-9_.-]*$") && Regex.IsMatch(item.Id, "^[a-fA-F0-9]{12,64}$");

    public async Task<IReadOnlyList<ProjectContainer>> DiscoverAsync(CancellationToken ct)
    {
        var list = await Run(["ps", "-aq", "--no-trunc"], ct);
        Ensure(list, "读取容器列表");
        var ids = list.Output.Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries)
            .Where(x => Regex.IsMatch(x, "^[a-fA-F0-9]{12,64}$")).ToArray();
        var items = new List<ProjectContainer>();
        foreach (var batch in ids.Chunk(40))
        {
            var args = new List<string> { "inspect", "--format", InspectFormat }; args.AddRange(batch);
            var result = await Run(args, ct);
            // An incomplete inspect result must never turn an existing service into "missing".
            Ensure(result, "核验容器归属（容器状态可能正在变化，请刷新重试）");
            foreach (var line in result.Output.Split('\n', StringSplitOptions.RemoveEmptyEntries))
            {
                try { items.Add(ParseContainer(line)); }
                catch (JsonException)
                { throw new InvalidOperationException("Docker 容器详情解析失败，无法确认完整服务列表；请刷新重试或查看 Docker 状态。"); }
            }
            if (items.Count(x => batch.Contains(x.Id)) != batch.Length)
                throw new InvalidOperationException("Docker 返回的容器详情不完整，已将状态标记为未知；请刷新重试。");
        }
        return items;
    }

    private void Ensure(CommandResult result, string operation)
    {
        if (!result.Success) throw new InvalidOperationException(config.Redactor.Redact($"{operation}失败：{(string.IsNullOrWhiteSpace(result.Error) ? result.Output : result.Error).Trim()}"));
    }

    public IReadOnlyList<string> ComposeFiles(IReadOnlyList<ProjectContainer> containers)
    {
        var owned = containers.Where(IsOwned).ToList();
        if (containers.Any(x => x.Project == config.ProjectName && !IsOwned(x)))
            throw new InvalidOperationException("检测到同名 Compose 项目位于其它目录或归属无法确认，已拒绝 Compose 操作；请先核对项目目录与容器标签。");
        var files = owned.SelectMany(x => x.ConfigFiles).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
        if (files.Count == 0) files.Add(config.ComposeFile);
        // Preserve the ordering that Compose wrote into the labels: base first, overlays after.
        if (files.Any(x => !config.IsAllowedComposeFile(x))) throw new InvalidOperationException("项目的 Compose 文件已移动或不在 deploy 内，无法安全重建。");
        return files;
    }

    private List<string> ComposeArguments(IReadOnlyList<ProjectContainer> containers)
    {
        var args = new List<string> { "compose", "--project-name", config.ProjectName, "--project-directory", config.Deploy };
        foreach (var file in ComposeFiles(containers)) { args.Add("--file"); args.Add(Path.GetFullPath(file)); }
        return args;
    }

    public async Task EnsureReadyAsync(CancellationToken ct)
    {
        var status = await GetStatusAsync(ct);
        if (status.Ready) return;
        if (!status.Installed) throw new InvalidOperationException("尚未安装 Docker Desktop，请安装后重试。");
        var paths = new[]
        {
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Docker", "Docker", "Docker Desktop.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "DockerDesktop", "Docker Desktop.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Docker", "Docker Desktop.exe")
        };
        var desktop = paths.FirstOrDefault(File.Exists) ?? throw new InvalidOperationException("未找到 Docker Desktop，请手动启动 Docker 引擎。");
        log(new(DateTimeOffset.Now.ToString("O"), "info", "正在启动 Docker Desktop，等待引擎就绪…"));
        Process.Start(new ProcessStartInfo(desktop) { UseShellExecute = false, CreateNoWindow = true, WindowStyle = ProcessWindowStyle.Hidden });
        var timer = Stopwatch.StartNew();
        while (timer.Elapsed < TimeSpan.FromSeconds(180))
        {
            ct.ThrowIfCancellationRequested();
            if ((await GetStatusAsync(ct)).Ready) return;
            await Task.Delay(TimeSpan.FromSeconds(3), ct);
        }
        throw new TimeoutException("Docker 引擎在 180 秒内未就绪，请在 Docker Desktop 中检查状态。");
    }

    public async Task ExecuteAsync(string action, string? service, CancellationToken ct)
    {
        if (action is "start-all" or "start" or "build" or "docker-start") await EnsureReadyAsync(ct);
        if (action == "docker-start") return;
        var containers = await DiscoverAsync(ct);
        var owned = containers.Where(IsOwned).ToList();
        if (action == "start-all")
        {
            // First-run preparation: fetch only a missing database image.
            // Existing images, database containers and data volumes are preserved.
            var prepare = ComposeArguments(containers);
            prepare.AddRange(["pull", "--policy", "missing", "mysql"]);
            Ensure(await Run(prepare, ct, true, 600), "准备数据库镜像");
            var args = ComposeArguments(containers);
            args.AddRange(["up", "--detach", "--no-build", "--pull", "never"]);
            args.AddRange(new[] { "mysql", "backend", "frontend" }.Concat(owned.Select(x => x.Service)).Distinct());
            Ensure(await Run(args, ct, true, 300), "启动项目（若缺少镜像，请先使用构建更新）");
            return;
        }
        if (action == "build")
        {
            var args = ComposeArguments(containers); args.AddRange(["build", "backend", "frontend"]);
            Ensure(await Run(args, ct, true, 1800), "构建前后端镜像");
            args = ComposeArguments(await DiscoverAsync(ct));
            args.AddRange(["up", "--detach", "--no-deps", "--no-build", "--pull", "never", "backend", "frontend"]);
            Ensure(await Run(args, ct, true, 300), "应用前后端镜像"); return;
        }
        if (action == "stop-all")
        {
            foreach (var item in owned.Where(x => x.State is "running" or "restarting" or "paused")
                .OrderBy(x => x.Service == "frontend" ? 0 : x.Service == "mysql" ? 2 : 1))
                await OnContainer("stop", item, ct);
            return;
        }
        if (string.IsNullOrEmpty(service)) throw new InvalidOperationException("请先选择服务。");
        var matches = owned.Where(x => x.Service == service).ToArray();
        if (matches.Length > 1) throw new InvalidOperationException("服务存在多个副本，请通过 Docker Desktop 核对后再操作。");
        if (matches.Length == 1) { await OnContainer(action, matches[0], ct); return; }
        if (action != "start") throw new InvalidOperationException("未找到可安全操作的项目容器。");
        if (service is not ("frontend" or "backend" or "mysql")) throw new InvalidOperationException("此服务尚未创建，请先在项目的部署配置中启用。");
        var startArgs = ComposeArguments(containers); startArgs.AddRange(["up", "--detach", "--no-build", "--pull", "never", service]);
        Ensure(await Run(startArgs, ct, true, 300), "启动服务");
    }

    private async Task OnContainer(string action, ProjectContainer item, CancellationToken ct)
    {
        if (action is not ("start" or "stop" or "restart")) throw new InvalidOperationException("不支持的容器操作。");
        var current = (await DiscoverAsync(ct)).SingleOrDefault(x => x.Id == item.Id);
        if (current == null || !IsOwned(current) || current.Service != item.Service)
            throw new InvalidOperationException("容器归属发生变化，操作已取消。");
        var args = new List<string> { action };
        if (action is "stop" or "restart") args.AddRange(["--time", "30"]);
        args.Add(current.Id);
        Ensure(await Run(args, ct, true, 90), $"{ProjectConfig.ServiceName(item.Service)}操作");
    }

    public async Task<string> GetLogsAsync(string service, int tail, CancellationToken ct)
    {
        var matches = (await DiscoverAsync(ct)).Where(x => x.Service == service && IsOwned(x)).ToArray();
        if (matches.Length != 1) return "未找到唯一、归属已验证的服务容器。";
        var result = await Run(["logs", "--timestamps", "--tail", Math.Clamp(tail, 1, 1000).ToString(), matches[0].Id], ct);
        return config.Redactor.Redact(result.Output + result.Error);
    }

    public async Task<IReadOnlyList<ServiceStatus>> GetServicesAsync(DockerStatus status, CancellationToken ct)
    {
        if (!status.Ready)
            return new[] { "frontend", "backend", "mysql" }.Select(id => new ServiceStatus(id, ProjectConfig.ServiceName(id), "container",
                "unknown", "Docker 引擎未就绪，无法确认容器状态", config.GetPort(id))).ToArray();
        var all = await DiscoverAsync(ct);
        var owned = all.Where(IsOwned).ToArray();
        var projectConflict = all.Any(x => x.Project == config.ProjectName && !IsOwned(x));
        var ids = new[] { "frontend", "backend", "mysql" }.Concat(owned.Select(x => x.Service)).Distinct();
        var tasks = ids.Select(async id =>
        {
            var matches = owned.Where(x => x.Service == id).ToArray();
            if (matches.Length > 1) return new ServiceStatus(id, ProjectConfig.ServiceName(id), "container", "conflict", "存在多个服务副本，需人工核对");
            var item = matches.FirstOrDefault();
            var port = config.GetPort(id);
            if (item != null)
            {
                var internalPort = id switch { "frontend" => "80/tcp", "backend" => "18080/tcp", "mysql" => "3306/tcp", _ => "" };
                // A running internal-only service must never borrow a configured
                // host port, which may belong to another project.
                port = item.Ports.TryGetValue(internalPort, out var actual) ? actual : 0;
            }
            if (item == null)
                return new ServiceStatus(id, ProjectConfig.ServiceName(id), "container", projectConflict ? "conflict" : "missing",
                    projectConflict ? "同名项目归属不一致，已阻止操作" : "容器尚未创建", port > 0 ? port : null,
                    CanStart: !projectConflict);
            var state = item.State switch
            {
                "running" => item.Health switch { "healthy" => "healthy", "unhealthy" => "unhealthy", "starting" => "starting", _ => "running" },
                "restarting" => "starting", "created" or "exited" or "dead" => "stopped", _ => "unknown"
            };
            var detail = state switch { "healthy" => "容器健康检查通过", "running" => "容器运行中", "starting" => "服务正在启动", "unhealthy" => "容器健康检查未通过", "stopped" => "容器已停止，数据卷保留", _ => $"容器状态：{item.State}" };
            long? elapsed = null;
            var address = port > 0 && id is "frontend" or "backend" ? $"http://localhost:{port}" : null;
            if (item.State == "running" && address != null && item.Ports.Count > 0)
            {
                var health = id == "frontend" ? await HealthProbe.CheckFrontendAsync(address, ct)
                    : await HealthProbe.CheckAsync(address + "/api/files/page?page=1&size=1", false, ct);
                elapsed = health.ResponseMs;
                if (health.Healthy && state != "unhealthy") { state = "healthy"; detail = health.Detail; }
                else if (!health.Healthy) { state = state == "starting" ? "starting" : "unhealthy"; detail = health.Detail; }
            }
            return new ServiceStatus(id, ProjectConfig.ServiceName(id), "container", state, detail,
                port > 0 ? port : null, address, item.Id, item.Name, item.Image, Uptime(item.StartedAt, item.State), elapsed,
                CanStart: item.State != "running", CanStop: item.State is "running" or "restarting" or "paused", CanRestart: true);
        });
        return await Task.WhenAll(tasks);
    }

    private static string? Uptime(string? started, string state)
    {
        if (state != "running" || !DateTimeOffset.TryParse(started, out var timestamp)) return null;
        var span = DateTimeOffset.UtcNow - timestamp;
        return span.TotalDays >= 1 ? $"{(int)span.TotalDays} 天 {span.Hours} 小时" : span.TotalHours >= 1 ? $"{(int)span.TotalHours} 小时 {span.Minutes} 分钟" : $"{Math.Max(0, (int)span.TotalMinutes)} 分钟";
    }
}
