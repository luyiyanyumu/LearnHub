using System.Buffers.Binary;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using LearnHub.Launcher.Core;

static class SelfUpdateChecks
{
    public static void Register(List<(string Name, Func<Task> Run)> tests)
    {
        void Add(string name, Func<Task> body) => tests.Add(("self update: " + name, body));
        Add("fixed channel works without Git or a project directory", async () =>
        {
            using var f = new Fixture(); var info = await f.Client.CheckAsync(default);
            Assert(info.State == "available" && info.CurrentVersion == "1.0.0" && info.LatestVersion == "1.0.1" && info.Runtime == "win-x64");
            Assert(f.Handler.Requests.Single() == "https://github.com/luyiyanyumu/LearnHub/releases/download/launcher-updates/launcher-update-win-x64.json");
        });
        Add("404 clearly reports unpublished launcher manifest", async () =>
        {
            using var f = new Fixture(); f.Handler.Status = HttpStatusCode.NotFound;
            var info = await f.Client.CheckAsync(default);
            Assert(info.State == "unavailable" && info.Detail.Contains("尚未发布") && info.DownloadUrl == null);
        });
        Add("403 and 429 are explicit request limit errors", async () =>
        {
            foreach (var status in new[] { HttpStatusCode.Forbidden, HttpStatusCode.TooManyRequests })
            {
                using var f = new Fixture(); f.Handler.Status = status;
                await Throws(() => f.Client.CheckAsync(default), "HTTP " + (int)status);
            }
        });
        Add("current and newer installed versions never downgrade", async () =>
        {
            foreach (var current in new[] { "1.0.1", "1.0.2", "2.0.0" })
            {
                using var f = new Fixture(currentVersion: current);
                var info = await f.Client.CheckAsync(default); Assert(info.State == "current");
                await Throws(() => f.Client.DownloadAsync(info, f.Root, null, default), "没有可安装");
                Assert(f.Handler.DownloadRequests == 0);
            }
        });
        Add("manifest schema stable version runtime hash and size are validated", async () =>
        {
            var edits = new Action<Dictionary<string, object?>>[]
            {
                m => m["schemaVersion"] = 2, m => m["schemaVersion"] = "1", m => m["version"] = "1.0.1-beta.1",
                m => m["runtime"] = "win-arm64", m => m["sha256"] = "abc", m => m["sha256"] = new string('z', 64),
                m => m["size"] = 0, m => m["size"] = LauncherSelfUpdateClient.MaximumDownloadSize + 1, m => m["size"] = "512"
            };
            foreach (var edit in edits)
            {
                using var f = new Fixture(); f.Handler.EditManifest = (m, _) => edit(m);
                await Throws(() => f.Client.CheckAsync(default), "清单");
            }
        });
        Add("manifest rejects arbitrary URLs mismatched releases and wrong asset names", async () =>
        {
            var edits = new Action<Dictionary<string, object?>>[]
            {
                m => m["url"] = "https://evil.invalid/LearnHub.exe",
                m => m["url"] = "https://github.com/other/LearnHub/releases/download/launcher-v1.0.1/LearnHub.exe",
                m => m["url"] = "https://github.com/luyiyanyumu/LearnHub/releases/download/launcher-v1.0.1/Other.exe",
                m => m["url"] = "https://github.com/luyiyanyumu/LearnHub/releases/download/launcher-v1.0.1/LearnHub-win-arm64.exe",
                m => m["releaseUrl"] = "https://github.com/luyiyanyumu/LearnHub/releases/tag/launcher-v1.0.2",
                m => m["url"] = "http://github.com/luyiyanyumu/LearnHub/releases/download/launcher-v1.0.1/LearnHub.exe"
            };
            foreach (var edit in edits)
            {
                using var f = new Fixture(); f.Handler.EditManifest = (m, _) => edit(m);
                await Throws(() => f.Client.CheckAsync(default), "官方仓库");
            }
        });
        Add("manifest body has a streaming 512 KiB upper bound", async () =>
        {
            using var f = new Fixture(); f.Handler.ManifestBody = new string(' ', 512 * 1024 + 1); f.Handler.NoLength = true;
            await Throws(() => f.Client.CheckAsync(default), "512 KiB");
        });
        Add("allowed signed GitHub asset CDN redirects work", async () =>
        {
            using var f = new Fixture();
            f.Handler.ManifestRedirect = "https://release-assets.githubusercontent.com/synthetic-manifest?sig=synthetic";
            f.Handler.AssetRedirect = "https://objects.githubusercontent.com/synthetic-launcher?sig=synthetic";
            var info = await f.Client.CheckAsync(default);
            var result = await f.Client.DownloadAsync(info, f.Root, null, default);
            Assert(File.Exists(result.FilePath) && result.Size == f.Handler.Binary.Length);
            Assert(f.Logs.All(l => !l.Message.Contains("https://") && !l.Message.Contains("sig=")));
        });
        Add("malicious redirect targets are rejected before request", async () =>
        {
            foreach (var url in new[]
            {
                "https://evil.invalid/update.json", "http://release-assets.githubusercontent.com/update.json",
                "https://user:secret@release-assets.githubusercontent.com/update.json", "https://127.0.0.1/update.json",
                "https://raw.githubusercontent.com/other/repo/main/update.json", "https://github.com/other/repo/releases/download/v1.0.1/update.json"
            })
            {
                using var f = new Fixture(); f.Handler.ManifestRedirect = url;
                await Throws(() => f.Client.CheckAsync(default), "不受信任");
                Assert(f.Handler.Requests.Count == 1);
            }
        });
        Add("redirect loops and long chains are bounded", async () =>
        {
            using var loop = new Fixture(); loop.Handler.ManifestRedirect = FixtureHandler.ManifestUrl("win-x64");
            await Throws(() => loop.Client.CheckAsync(default), "循环");
            using var chain = new Fixture(); chain.Handler.LongRedirectChain = true;
            await Throws(() => chain.Client.CheckAsync(default), "跳转次数");
            Assert(chain.Handler.Requests.Count <= 6);
        });
        Add("download rechecks locked version URL hash and size", async () =>
        {
            foreach (var edit in new Action<Dictionary<string, object?>>[]
            {
                m => m["version"] = "1.0.2", m => m["sha256"] = new string('a', 64), m => m["size"] = 513,
                m => m["url"] = "https://github.com/luyiyanyumu/LearnHub/releases/download/launcher-v1.0.1/LearnHub.exe"
            })
            {
                using var f = new Fixture(); var expected = await f.Client.CheckAsync(default);
                f.Handler.EditManifest = (m, _) => edit(m);
                await Throws(() => f.Client.DownloadAsync(expected, f.Root, null, default), "清单已变化");
                Assert(f.Handler.DownloadRequests == 0 && !Directory.EnumerateFiles(f.Root).Any());
            }
        });
        Add("valid x64 download verifies SHA size PE and reports progress", async () =>
        {
            using var f = new Fixture(); var info = await f.Client.CheckAsync(default);
            var progress = new List<LauncherDownloadProgress>();
            var result = await f.Client.DownloadAsync(info, f.Root, new InlineProgress(progress.Add), default);
            Assert(result.Sha256 == Convert.ToHexString(SHA256.HashData(f.Handler.Binary)).ToLowerInvariant());
            Assert(result.Size == f.Handler.Binary.Length && Path.IsPathFullyQualified(result.FilePath) && File.Exists(result.FilePath));
            Assert(progress.First().Percent == 0 && progress.Last().Percent == 100 && !Directory.EnumerateFiles(f.Root, "*.part").Any());
            LauncherSelfUpdateClient.ValidatePortableExecutable(result.FilePath, "win-x64");
        });
        Add("valid arm64 download uses separate channel and machine type", async () =>
        {
            using var f = new Fixture(runtime: "win-arm64"); var info = await f.Client.CheckAsync(default);
            var result = await f.Client.DownloadAsync(info, f.Root, null, default);
            Assert(f.Handler.Requests[0].EndsWith("launcher-update-win-arm64.json"));
            LauncherSelfUpdateClient.ValidatePortableExecutable(result.FilePath, "win-arm64");
        });
        Add("SHA mismatch removes only this download temporary file", async () =>
        {
            using var f = new Fixture(); var protectedFile = Path.Combine(f.Root, "LearnHub.exe"); File.WriteAllText(protectedFile, "synthetic existing user file");
            f.Handler.EditManifest = (m, _) => m["sha256"] = new string('a', 64);
            var info = await f.Client.CheckAsync(default);
            await Throws(() => f.Client.DownloadAsync(info, f.Root, null, default), "SHA-256");
            Assert(Directory.EnumerateFiles(f.Root).Count() == 1 && File.ReadAllText(protectedFile) == "synthetic existing user file");
        });
        Add("truncated oversized and false content length files are rejected", async () =>
        {
            foreach (var size in new[] { 500, 600 })
            {
                using var f = new Fixture(); f.Handler.EditManifest = (m, _) => m["size"] = size; f.Handler.NoLength = true;
                var info = await f.Client.CheckAsync(default);
                await Throws(() => f.Client.DownloadAsync(info, f.Root, null, default), size < 512 ? "超过" : "不完整");
                Assert(!Directory.EnumerateFiles(f.Root).Any());
            }
            using var mismatch = new Fixture(); mismatch.Handler.EditManifest = (m, _) => m["size"] = 600;
            var expected = await mismatch.Client.CheckAsync(default);
            await Throws(() => mismatch.Client.DownloadAsync(expected, mismatch.Root, null, default), "大小与更新清单不一致");
        });
        Add("wrong PE machine and missing MZ marker are rejected after hash", async () =>
        {
            foreach (var kind in new[] { "arm64", "not-pe" })
            {
                using var f = new Fixture(); f.Handler.Binary = MakePe(kind == "arm64" ? (ushort)0xAA64 : (ushort)0x8664);
                if (kind == "not-pe") f.Handler.Binary[0] = 0;
                var info = await f.Client.CheckAsync(default);
                await Throws(() => f.Client.DownloadAsync(info, f.Root, null, default), kind == "arm64" ? "架构" : "MZ");
                Assert(!Directory.EnumerateFiles(f.Root).Any());
            }
        });
        Add("cancelled streaming download removes its partial file", async () =>
        {
            using var f = new Fixture(); var info = await f.Client.CheckAsync(default); using var cancellation = new CancellationTokenSource();
            try
            {
                await f.Client.DownloadAsync(info, f.Root, new InlineProgress(_ => cancellation.Cancel()), cancellation.Token);
                throw new Exception("Expected cancellation");
            }
            catch (OperationCanceledException) { Assert(cancellation.IsCancellationRequested); }
            Assert(!Directory.EnumerateFiles(f.Root).Any());
        });
    }

    private static void Assert(bool condition) { if (!condition) throw new Exception("Launcher self-update assertion failed"); }
    private static async Task Throws(Func<Task> action, string contains)
    {
        try { await action(); }
        catch (Exception ex) { if (!ex.Message.Contains(contains)) throw new Exception("Expected " + contains + ", got: " + ex.Message); return; }
        throw new Exception("Expected launcher self-update rejection");
    }
    private sealed class InlineProgress(Action<LauncherDownloadProgress> report) : IProgress<LauncherDownloadProgress>
    { public void Report(LauncherDownloadProgress value) => report(value); }
    private static byte[] MakePe(ushort machine)
    {
        var bytes = new byte[512]; bytes[0] = (byte)'M'; bytes[1] = (byte)'Z';
        BinaryPrimitives.WriteInt32LittleEndian(bytes.AsSpan(60, 4), 128);
        bytes[128] = (byte)'P'; bytes[129] = (byte)'E';
        BinaryPrimitives.WriteUInt16LittleEndian(bytes.AsSpan(132, 2), machine);
        BinaryPrimitives.WriteUInt16LittleEndian(bytes.AsSpan(148, 2), 240);
        BinaryPrimitives.WriteUInt16LittleEndian(bytes.AsSpan(150, 2), 0x0002);
        BinaryPrimitives.WriteUInt16LittleEndian(bytes.AsSpan(152, 2), 0x20B);
        return bytes;
    }

    private sealed class Fixture : IDisposable
    {
        public string Root { get; } = Path.Combine(Path.GetTempPath(), "learnhub-self-update-tests-" + Guid.NewGuid().ToString("N"));
        public FixtureHandler Handler { get; }
        public HttpClient Http { get; }
        public LauncherSelfUpdateClient Client { get; }
        public List<LauncherLog> Logs { get; } = [];
        public Fixture(string currentVersion = "1.0.0", string runtime = "win-x64")
        {
            Directory.CreateDirectory(Root); Handler = new(runtime); Http = new(Handler);
            Client = new(currentVersion, runtime, Http, Logs.Add);
        }
        public void Dispose()
        {
            Http.Dispose(); var resolved = Path.GetFullPath(Root);
            if (resolved.StartsWith(Path.GetFullPath(Path.GetTempPath()), StringComparison.OrdinalIgnoreCase) && Path.GetFileName(resolved).StartsWith("learnhub-self-update-tests-")) Directory.Delete(resolved, true);
        }
    }

    private sealed class FixtureHandler(string runtime) : HttpMessageHandler
    {
        public HttpStatusCode Status { get; set; } = HttpStatusCode.OK;
        public byte[] Binary { get; set; } = MakePe(runtime == "win-arm64" ? (ushort)0xAA64 : (ushort)0x8664);
        public string? ManifestBody { get; set; }
        public bool NoLength { get; set; }
        public string? ManifestRedirect { get; set; }
        public string? AssetRedirect { get; set; }
        public bool LongRedirectChain { get; set; }
        public Action<Dictionary<string, object?>, int>? EditManifest { get; set; }
        public List<string> Requests { get; } = [];
        public int DownloadRequests { get; private set; }
        private int _manifestRequests;
        public static string ManifestUrl(string runtime) => $"https://github.com/luyiyanyumu/LearnHub/releases/download/launcher-updates/launcher-update-{runtime}.json";
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var url = request.RequestUri!.AbsoluteUri; Requests.Add(url); Assert(request.Headers.UserAgent.Count > 0);
            if (LongRedirectChain)
            {
                var number = request.RequestUri.Host == "github.com" ? 0 : int.Parse(request.RequestUri.AbsolutePath.Trim('/')[4..]) + 1;
                return Task.FromResult(Redirect("https://release-assets.githubusercontent.com/hop/" + number));
            }
            if (url == ManifestUrl(runtime) && ManifestRedirect != null) return Task.FromResult(Redirect(ManifestRedirect));
            var isManifest = url == ManifestUrl(runtime) || request.RequestUri.AbsolutePath == "/synthetic-manifest";
            if (isManifest)
            {
                _manifestRequests++;
                var values = new Dictionary<string, object?>
                {
                    ["schemaVersion"] = 1, ["version"] = "1.0.1", ["runtime"] = runtime,
                    ["url"] = $"https://github.com/luyiyanyumu/LearnHub/releases/download/launcher-v1.0.1/LearnHub-{runtime}.exe",
                    ["sha256"] = Convert.ToHexString(SHA256.HashData(Binary)).ToLowerInvariant(), ["size"] = Binary.Length,
                    ["releaseUrl"] = "https://github.com/luyiyanyumu/LearnHub/releases/tag/launcher-v1.0.1"
                };
                EditManifest?.Invoke(values, _manifestRequests);
                var bytes = Encoding.UTF8.GetBytes(ManifestBody ?? JsonSerializer.Serialize(values));
                return Task.FromResult(new HttpResponseMessage(Status) { Content = NoLength ? new NoLengthContent(bytes) : new ByteArrayContent(bytes) });
            }
            DownloadRequests++;
            if (request.RequestUri.Host == "github.com" && AssetRedirect != null) return Task.FromResult(Redirect(AssetRedirect));
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = NoLength ? new NoLengthContent(Binary) : new ByteArrayContent(Binary) });
        }
        private static HttpResponseMessage Redirect(string location) => new(HttpStatusCode.Redirect) { Headers = { Location = new Uri(location, UriKind.RelativeOrAbsolute) } };
    }

    private sealed class NoLengthContent(byte[] bytes) : HttpContent
    {
        protected override Task SerializeToStreamAsync(Stream stream, TransportContext? context) => stream.WriteAsync(bytes).AsTask();
        protected override bool TryComputeLength(out long length) { length = 0; return false; }
        protected override Task<Stream> CreateContentReadStreamAsync() => Task.FromResult<Stream>(new MemoryStream(bytes));
    }
}
