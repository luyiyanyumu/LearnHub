using System.IO;
using System.Text.Json;

namespace LearnHub.Launcher;

internal sealed class LauncherPreferences
{
    public string ProjectRoot { get; set; } = "";
    public string Mode { get; set; } = "docker";
    public string Theme { get; set; } = "system";
    public bool AutoRefresh { get; set; } = true;
    public double? WindowWidth { get; set; }
    public double? WindowHeight { get; set; }
    public bool Maximized { get; set; }

    public static string DataDirectory => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "LearnHub", "Launcher");
    public static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web) { WriteIndented = true };

    public static LauncherPreferences Load()
    {
        try
        {
            var value = JsonSerializer.Deserialize<LauncherPreferences>(File.ReadAllText(Path.Combine(DataDirectory, "preferences.json")), JsonOptions);
            if (value is not null)
            {
                if (value.Mode is not ("docker" or "local")) value.Mode = "docker";
                if (value.Theme is not ("system" or "light" or "dark")) value.Theme = "system";
                return value;
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or JsonException) { }
        return new();
    }

    public void Save()
    {
        Directory.CreateDirectory(DataDirectory);
        var target = Path.Combine(DataDirectory, "preferences.json");
        var temporary = target + "." + Guid.NewGuid().ToString("N") + ".tmp";
        File.WriteAllText(temporary, JsonSerializer.Serialize(this, JsonOptions));
        File.Move(temporary, target, true);
    }
}

internal static class ProjectLocator
{
    public static bool IsProject(string? path)
    {
        if (string.IsNullOrWhiteSpace(path)) return false;
        return File.Exists(Path.Combine(path, "deploy", "docker-compose.yml"))
            && File.Exists(Path.Combine(path, "backend", "pom.xml"))
            && File.Exists(Path.Combine(path, "frontend", "package.json"));
    }

    public static string? Find(string? explicitPath, LauncherPreferences preferences)
    {
        if (!string.IsNullOrWhiteSpace(explicitPath))
        {
            var full = Path.GetFullPath(explicitPath);
            if (!IsProject(full)) throw new InvalidOperationException("指定目录不是 LearnHub 项目，请选择包含 frontend、backend 和 deploy 的根目录。");
            return full;
        }
        // Prefer the EXE's project so copying it to another checkout is predictable.
        foreach (var start in new[] { AppContext.BaseDirectory, Environment.CurrentDirectory })
        {
            var current = new DirectoryInfo(start);
            for (var i = 0; current is not null && i < 9; i++, current = current.Parent)
                if (IsProject(current.FullName)) return current.FullName;
        }
        return IsProject(preferences.ProjectRoot) ? Path.GetFullPath(preferences.ProjectRoot) : null;
    }
}
