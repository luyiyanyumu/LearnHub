using System.Security.Cryptography;

namespace LearnHub.Launcher.Core;

public static class ServiceHostCache
{
    public static Task<string> GetExecutableAsync(string executable, CancellationToken ct) =>
        GetExecutableAsync(executable, Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "LearnHub", "Launcher", "service-host"), ct);

    public static async Task<string> GetExecutableAsync(string executable, string cacheRoot, CancellationToken ct)
    {
        executable = Path.GetFullPath(executable);
        if (!Path.GetFileName(executable).Equals("LearnHub.exe", StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("请使用已发布的 LearnHub.exe 启动本地服务。");
        var expected = await HashAsync(executable, ct);
        var directory = Path.Combine(Path.GetFullPath(cacheRoot), expected);
        Directory.CreateDirectory(directory);
        var destination = Path.Combine(directory, "LearnHub.ServiceHost.exe");
        if (!File.Exists(destination))
        {
            var temporary = Path.Combine(directory, Guid.NewGuid().ToString("N") + ".part");
            try
            {
                await using (var source = new FileStream(executable, FileMode.Open, FileAccess.Read, FileShare.Read | FileShare.Delete))
                await using (var output = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None))
                    await source.CopyToAsync(output, ct);
                if (await HashAsync(temporary, ct) != expected) throw new IOException("后台宿主复制校验失败。");
                try { File.Move(temporary, destination, overwrite: false); }
                catch (IOException) when (File.Exists(destination)) { /* Another start may have installed the identical version. */ }
            }
            finally { if (File.Exists(temporary)) File.Delete(temporary); }
        }
        if (await HashAsync(destination, ct) != expected) throw new IOException("后台宿主缓存校验失败，请检查启动器缓存目录。");
        return destination;
    }

    private static async Task<string> HashAsync(string path, CancellationToken ct)
    {
        await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read | FileShare.Delete);
        return Convert.ToHexString(await SHA256.HashDataAsync(stream, ct));
    }
}
