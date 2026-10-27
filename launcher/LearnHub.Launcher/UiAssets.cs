using System.IO;
using System.Reflection;
using System.Security.Cryptography;

namespace LearnHub.Launcher;

internal static class UiAssets
{
    public static string Extract()
    {
        var assembly = Assembly.GetExecutingAssembly();
        var resources = assembly.GetManifestResourceNames().Where(n => n.StartsWith("LauncherUi/", StringComparison.Ordinal)).Order().ToArray();
        if (resources.Length == 0) throw new InvalidOperationException("启动器界面未打包，请重新运行 tools/build-launcher.ps1。");
        using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        foreach (var name in resources)
        {
            hash.AppendData(System.Text.Encoding.UTF8.GetBytes(name));
            using var source = assembly.GetManifestResourceStream(name)!;
            using var memory = new MemoryStream();
            source.CopyTo(memory);
            hash.AppendData(memory.GetBuffer().AsSpan(0, (int)memory.Length));
        }
        var fingerprint = Convert.ToHexString(hash.GetHashAndReset())[..20];
        var root = Path.Combine(LauncherPreferences.DataDirectory, "ui", fingerprint);
        var boundary = Path.GetFullPath(root) + Path.DirectorySeparatorChar;
        Directory.CreateDirectory(root);
        foreach (var name in resources)
        {
            var relative = name["LauncherUi/".Length..].Replace('\\', '/');
            var target = Path.GetFullPath(Path.Combine(root, relative));
            if (!target.StartsWith(boundary, StringComparison.OrdinalIgnoreCase)) throw new InvalidOperationException("界面资源路径无效。");
            Directory.CreateDirectory(Path.GetDirectoryName(target)!);
            using var source = assembly.GetManifestResourceStream(name)!;
            // Re-extract each launch: mutable cache files never become a trusted bridge origin.
            using var output = new FileStream(target, FileMode.Create, FileAccess.Write, FileShare.Read);
            source.CopyTo(output);
        }
        return root;
    }
}
