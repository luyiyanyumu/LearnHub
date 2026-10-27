using LearnHub.Launcher.Core;

static class ServiceHostCacheChecks
{
    public static void Register(List<(string Name, Func<Task> Run)> tests)
    {
        tests.Add(("service host cache survives replacing the GUI executable", async () =>
        {
            await WithFixture(async (root, executable) =>
            {
                var cached = await ServiceHostCache.GetExecutableAsync(executable, Path.Combine(root, "cache"), default);
                var original = await File.ReadAllBytesAsync(executable);
                await File.WriteAllTextAsync(executable, "synthetic next launcher");
                if (!(await File.ReadAllBytesAsync(cached)).SequenceEqual(original) || cached == executable) throw new Exception("Host must use an independent copy.");
                var next = await ServiceHostCache.GetExecutableAsync(executable, Path.Combine(root, "cache"), default);
                if (next == cached) throw new Exception("Different builds need different host copies.");
            });
        }));
        tests.Add(("concurrent service starts share a verified immutable host", async () =>
        {
            await WithFixture(async (root, executable) =>
            {
                var paths = await Task.WhenAll(Enumerable.Range(0, 5).Select(_ => ServiceHostCache.GetExecutableAsync(executable, Path.Combine(root, "cache"), default)));
                if (paths.Distinct().Count() != 1 || Directory.GetFiles(root, "*.part", SearchOption.AllDirectories).Length != 0) throw new Exception("Host cache race left inconsistent files.");
            });
        }));
        tests.Add(("tampered cached host is never executed", async () =>
        {
            await WithFixture(async (root, executable) =>
            {
                var cached = await ServiceHostCache.GetExecutableAsync(executable, Path.Combine(root, "cache"), default);
                await File.WriteAllTextAsync(cached, "synthetic tampered content");
                try { await ServiceHostCache.GetExecutableAsync(executable, Path.Combine(root, "cache"), default); }
                catch (IOException) { return; }
                throw new Exception("Tampered host was accepted.");
            });
        }));
    }

    private static async Task WithFixture(Func<string, string, Task> test)
    {
        var root = Path.GetFullPath(Path.Combine(Path.GetTempPath(), "learnhub-host-cache-" + Guid.NewGuid().ToString("N")));
        Directory.CreateDirectory(root);
        try
        {
            var executable = Path.Combine(root, "LearnHub.exe");
            await File.WriteAllTextAsync(executable, "synthetic launcher binary");
            await test(root, executable);
        }
        finally
        {
            var expectedParent = Path.GetFullPath(Path.GetTempPath()).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
            if (root.StartsWith(expectedParent, StringComparison.OrdinalIgnoreCase) && Path.GetFileName(root).StartsWith("learnhub-host-cache-")) Directory.Delete(root, true);
        }
    }
}
