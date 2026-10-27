using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace LearnHub.Launcher.Core;

public sealed class ProjectConfig
{
    public string Root { get; }
    public string Deploy { get; }
    public string ComposeFile { get; }
    public string ProjectName { get; }
    public int FrontendPort { get; }
    public int BackendPort { get; }
    public int MysqlPort { get; }
    public int LocalFrontendPort { get; } = 5174;
    public int LocalBackendPort { get; } = 18080;
    public string StateDirectory { get; }
    public IReadOnlyDictionary<string, string> Environment { get; }
    public LogRedactor Redactor { get; }

    public ProjectConfig(string root)
    {
        Root = Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar);
        Deploy = Path.Combine(Root, "deploy");
        ComposeFile = Path.Combine(Deploy, "docker-compose.yml");
        if (!File.Exists(ComposeFile) || !Directory.Exists(Path.Combine(Root, "frontend")) ||
            !Directory.Exists(Path.Combine(Root, "backend")))
            throw new InvalidOperationException("所选目录不是 LearnHub 项目：需包含 frontend、backend 和 deploy/docker-compose.yml。");
        var envFile = Path.Combine(Deploy, ".env");
        Environment = File.Exists(envFile) ? ParseEnvironment(File.ReadAllText(envFile)) : new Dictionary<string, string>();
        Redactor = new LogRedactor(Environment);
        var source = File.ReadAllText(ComposeFile);
        var name = Regex.Match(source, @"(?m)^name:\s*([a-zA-Z0-9_-]+)\s*$");
        ProjectName = Environment.TryGetValue("COMPOSE_PROJECT_NAME", out var configuredName) && Regex.IsMatch(configuredName, "^[a-z0-9][a-z0-9_-]*$")
            ? configuredName : name.Success ? name.Groups[1].Value : "learn-hub";
        FrontendPort = Port(Environment, "WEB_HOST_PORT", 8888);
        BackendPort = Port(Environment, "BACKEND_HOST_PORT", 18080);
        MysqlPort = Port(Environment, "MYSQL_HOST_PORT", 3307);
        var hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(NormalizePath(Root))))[..16];
        StateDirectory = Path.Combine(System.Environment.GetFolderPath(System.Environment.SpecialFolder.LocalApplicationData), "LearnHub", "Launcher", hash);
    }

    public static Dictionary<string, string> ParseEnvironment(string content)
    {
        var values = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var line in content.Split('\n'))
        {
            var value = line.Trim().TrimStart('\uFEFF');
            if (value.StartsWith("export ", StringComparison.Ordinal)) value = value[7..].TrimStart();
            var match = Regex.Match(value, @"^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$");
            if (!match.Success) continue;
            value = match.Groups[2].Value.Trim();
            if (value.Length >= 2 && ((value[0] == '\'' && value[^1] == '\'') || (value[0] == '"' && value[^1] == '"')))
                value = value[1..^1];
            else value = Regex.Replace(value, @"\s+#.*$", "").TrimEnd();
            values[match.Groups[1].Value] = value;
        }
        return values;
    }

    public static int Port(IReadOnlyDictionary<string, string> values, string key, int fallback) =>
        values.TryGetValue(key, out var raw) && int.TryParse(raw, out var port) && port is >= 1 and <= 65535 ? port : fallback;

    public static string NormalizePath(string value)
    {
        var path = value.Trim().Trim('"').Replace('/', '\\').TrimEnd('\\');
        // Docker Desktop may record the Windows checkout through its WSL mount.
        var match = Regex.Match(path, @"^\\(?:host_mnt|mnt)\\([A-Za-z])\\(.*)$");
        if (match.Success) path = match.Groups[1].Value + ":\\" + match.Groups[2].Value;
        return path.ToUpperInvariant();
    }

    public bool IsAllowedComposeFile(string file)
    {
        try
        {
            var path = Path.GetFullPath(file);
            var name = Path.GetFileName(path);
            return NormalizePath(Path.GetDirectoryName(path)!) == NormalizePath(Deploy) && File.Exists(path) &&
                Regex.IsMatch(name, @"^docker-compose(?:\.[a-z0-9-]+)?\.ya?ml$", RegexOptions.IgnoreCase);
        }
        catch { return false; }
    }

    public string? FindJar() => Directory.Exists(Path.Combine(Root, "backend", "target"))
        ? Directory.EnumerateFiles(Path.Combine(Root, "backend", "target"), "learn-hub-backend-*.jar")
            .Where(p => !p.EndsWith("-sources.jar", StringComparison.OrdinalIgnoreCase) && !p.EndsWith("-javadoc.jar", StringComparison.OrdinalIgnoreCase))
            .OrderByDescending(File.GetLastWriteTimeUtc).FirstOrDefault() : null;
    public string ViteEntry => Path.Combine(Root, "frontend", "node_modules", "vite", "bin", "vite.js");
    public string JavaExecutable
    {
        get
        {
            var javaHome = System.Environment.GetEnvironmentVariable("JAVA_HOME");
            var executable = string.IsNullOrWhiteSpace(javaHome) ? null : Path.Combine(javaHome, "bin", "java.exe");
            return executable != null && File.Exists(executable) ? executable : "java.exe";
        }
    }
    public int GetPort(string service, bool local = false) => service switch
    { "frontend" => local ? LocalFrontendPort : FrontendPort, "backend" => local ? LocalBackendPort : BackendPort, "mysql" => MysqlPort, _ => 0 };
    public static string ServiceName(string id) => id switch
    { "frontend" => "前端服务", "backend" => "后端服务", "mysql" => "MySQL 数据库", "ollama" => "Ollama 模型", "code-runner" => "代码运行服务", "web-fetcher" => "网页抓取服务", _ => id };
}
