using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using LearnHub.Launcher.Core;

static class VersionUpdateChecks
{
    public static void Register(List<(string Name, Func<Task> Run)> tests)
    {
        void Add(string name, Func<Task> body) => tests.Add(("updates: " + name, body));
        Add("stable semantic versions compare numerically", () =>
        {
            Assert(VersionUpdateService.TryStableVersion("v0.1.10", out var larger));
            Assert(VersionUpdateService.TryStableVersion("0.1.9", out var smaller) && larger > smaller);
            foreach (var invalid in new[] { "0.1.6-beta.1", "v1", "1.2.3.4", "01.2.3", "1.2.3;evil", "9999999999999999.1.2" })
                Assert(!VersionUpdateService.TryStableVersion(invalid, out _));
            return Task.CompletedTask;
        });
        Add("only exact official public HTTPS or SSH remote is trusted", () =>
        {
            foreach (var valid in new[] { "https://github.com/luyiyanyumu/LearnHub.git", "git@github.com:luyiyanyumu/LearnHub.git", "https://github.com/LUYIYANYUMU/learnhub" }) Assert(VersionUpdateService.IsOfficialRemote(valid));
            foreach (var invalid in new[] { "https://github.com.evil.invalid/luyiyanyumu/LearnHub.git", "https://user:secret@github.com/luyiyanyumu/LearnHub.git", "https://github.com/fork/LearnHub.git", "ext::command", "file:///synthetic/project", "http://github.com/luyiyanyumu/LearnHub.git", "https://github.com/luyiyanyumu/LearnHub.git?token=synthetic" }) Assert(!VersionUpdateService.IsOfficialRemote(invalid));
            return Task.CompletedTask;
        });
        Add("annotated release tags use peeled commit", () =>
        {
            var tagObject = new string('a', 40); var commit = new string('b', 40);
            Assert(VersionUpdateService.ResolveTagCommit($"{tagObject}\trefs/tags/v0.1.6\n{commit}\trefs/tags/v0.1.6^{{}}\n", "v0.1.6") == commit);
            Assert(VersionUpdateService.ResolveTagCommit("invalid\trefs/tags/v0.1.6", "v0.1.6") == null);
            return Task.CompletedTask;
        });
        Add("NUL porcelain counts rename once and includes untracked names", () =>
        {
            Assert(VersionUpdateService.CountChanges("R  synthetic/new.txt\0synthetic/old.txt\0?? synthetic/newline\nname.txt\0 M cli/package.json\0") == 3);
            Assert(VersionUpdateService.CountChanges("") == 0);
            return Task.CompletedTask;
        });
        Add("dirty source still receives latest release and explicit block reason", async () =>
        {
            using var fixture = new UpdateFixture(); fixture.Git.Porcelain = "?? synthetic.txt\0 M cli/package.json\0";
            var result = await fixture.Service.CheckAsync(default);
            Assert(result.Dirty && result.ChangeCount == 2 && result.LatestVersion == "0.1.6" && !result.CanUpdate && result.UpdateAvailable);
            Assert(result.BlockedReason!.Contains("2 项") && result.Repository == "luyiyanyumu/LearnHub");
            Assert(fixture.Git.FetchCalls == 0 && fixture.Git.MergeCalls == 0 && fixture.Http.Requests == 1);
        });
        Add("current or newer manifest never downgrades", async () =>
        {
            using var fixture = new UpdateFixture(); fixture.WriteVersion("0.2.0");
            var result = await fixture.Service.CheckAsync(default);
            Assert(!result.UpdateAvailable && !result.CanUpdate && result.CurrentVersion == "0.2.0");
        });
        Add("detached HEAD blocks source update", async () =>
        {
            using var fixture = new UpdateFixture(); fixture.Git.Detached = true;
            var result = await fixture.Service.CheckAsync(default);
            Assert(!result.CanUpdate && result.BlockedReason!.Contains("detached"));
        });
        Add("merge in progress blocks updates", async () =>
        {
            using var fixture = new UpdateFixture();
            File.WriteAllText(Path.Combine(fixture.Root, ".git", "MERGE_HEAD"), "synthetic");
            var result = await fixture.Service.CheckAsync(default);
            Assert(!result.CanUpdate && result.BlockedReason!.Contains("Git 正在"));
        });
        Add("stale upstream tracking does not block safe release fast forward", async () =>
        {
            using var fixture = new UpdateFixture(); fixture.Git.Ahead = 2; fixture.Git.ObjectExists = true;
            Assert((await fixture.Service.CheckAsync(default)).CanUpdate);
            var result = await fixture.Service.UpdateSourceAsync("0.1.6", fixture.Git.Target, default);
            Assert(result.CurrentCommit == fixture.Git.Target && result.CurrentVersion == "0.1.6" && fixture.Git.MergeCalls == 1);
            Assert(!fixture.Git.Commands.Any(c => c.Contains("rev-list")));
        });
        Add("known release history rejects local extra commits before fetch", async () =>
        {
            using var fixture = new UpdateFixture(); fixture.Git.Ahead = 1; fixture.Git.ObjectExists = true; fixture.Git.Ancestor = false;
            var status = await fixture.Service.CheckAsync(default);
            Assert(!status.CanUpdate && status.BlockedReason!.Contains("分叉"));
            await Throws(() => fixture.Service.UpdateSourceAsync("0.1.6", fixture.Git.Target, default), "分叉");
            Assert(fixture.Git.FetchCalls == 0 && fixture.Git.MergeCalls == 0);
        });
        Add("official launcher asset requires exact name and official URL", async () =>
        {
            using var fixture = new UpdateFixture();
            Assert((await fixture.Service.CheckAsync(default)).LauncherAssetUrl!.EndsWith("/LearnHub.exe"));
            fixture.Http.AssetName = "learnhub.exe";
            Assert((await fixture.Service.CheckAsync(default)).LauncherAssetUrl == null);
            fixture.Http.AssetName = "LearnHub.exe"; fixture.Http.AssetHost = "downloads.invalid";
            Assert((await fixture.Service.CheckAsync(default)).LauncherAssetUrl == null);
        });
        Add("network or API errors never claim latest", async () =>
        {
            using var fixture = new UpdateFixture(); fixture.Http.Status = HttpStatusCode.ServiceUnavailable;
            await Throws(() => fixture.Service.CheckAsync(default), "HTTP 503");
            fixture.Http.Status = HttpStatusCode.OK; fixture.Http.ThrowNetwork = true;
            await Throws(() => fixture.Service.CheckAsync(default), "无法连接 GitHub");
        });
        Add("403 and 429 use official stable release redirect fallback", async () =>
        {
            foreach (var status in new[] { HttpStatusCode.Forbidden, HttpStatusCode.TooManyRequests })
            {
                using var fixture = new UpdateFixture(); fixture.Http.Status = status;
                var result = await fixture.Service.CheckAsync(default);
                Assert(result.LatestVersion == "0.1.6" && result.LatestCommit == fixture.Git.Target && result.CanUpdate);
                Assert(result.ReleaseNotes!.Contains("暂无法读取完整说明") && result.ReleaseUrl == "https://github.com/luyiyanyumu/LearnHub/releases/tag/v0.1.6");
                Assert(result.PublishedAt == null && result.LauncherAssetUrl == null && result.ReleaseName == null && fixture.Http.Requests == 2);
                Assert(fixture.Git.FetchCalls == 0 && fixture.Git.MergeCalls == 0);
            }
        });
        Add("fallback rejects malicious redirects prereleases and missing locations", async () =>
        {
            foreach (var location in new string?[]
            {
                "https://github.com.evil.invalid/luyiyanyumu/LearnHub/releases/tag/v0.1.6",
                "https://github.com/other/LearnHub/releases/tag/v0.1.6",
                "https://github.com/luyiyanyumu/LearnHub/releases/tag/v0.1.6-beta.1",
                "https://github.com/luyiyanyumu/LearnHub/releases/tag/v0.1.6?token=synthetic",
                "http://github.com/luyiyanyumu/LearnHub/releases/tag/v0.1.6",
                null
            })
            {
                using var fixture = new UpdateFixture(); fixture.Http.Status = HttpStatusCode.Forbidden; fixture.Http.RedirectLocation = location;
                await Throws(() => fixture.Service.CheckAsync(default), "GitHub API 受限");
                Assert(!fixture.Git.Commands.Any(c => c.Contains("ls-remote")));
            }
        });
        Add("fallback accepts exact official relative stable tag redirect", async () =>
        {
            using var fixture = new UpdateFixture(); fixture.Http.Status = HttpStatusCode.TooManyRequests;
            fixture.Http.RedirectLocation = "/luyiyanyumu/LearnHub/releases/tag/v0.1.6";
            Assert((await fixture.Service.CheckAsync(default)).LatestVersion == "0.1.6");
        });
        Add("other API failures do not use release fallback", async () =>
        {
            using var fixture = new UpdateFixture(); fixture.Http.Status = HttpStatusCode.NotFound;
            await Throws(() => fixture.Service.CheckAsync(default), "HTTP 404"); Assert(fixture.Http.Requests == 1);
        });
        Add("prerelease is rejected as stable target", async () =>
        {
            using var fixture = new UpdateFixture(); fixture.Http.Prerelease = true;
            await Throws(() => fixture.Service.CheckAsync(default), "稳定 vX.Y.Z");
        });
        Add("expected release mismatch aborts before fetching", async () =>
        {
            using var fixture = new UpdateFixture();
            await Throws(() => fixture.Service.UpdateSourceAsync("0.1.7", fixture.Git.Target, default), "已变化");
            await Throws(() => fixture.Service.UpdateSourceAsync("0.1.6", new string('c', 40), default), "已变化");
            Assert(fixture.Git.FetchCalls == 0 && fixture.Git.MergeCalls == 0);
        });
        Add("dirty update cannot stash reset or fetch", async () =>
        {
            using var fixture = new UpdateFixture(); fixture.Git.Porcelain = "?? synthetic.txt\0";
            await Throws(() => fixture.Service.UpdateSourceAsync("0.1.6", fixture.Git.Target, default), "未提交");
            Assert(fixture.Git.FetchCalls == 0 && fixture.Git.MergeCalls == 0);
        });
        Add("project file lock excludes concurrent updater", async () =>
        {
            using var fixture = new UpdateFixture(); Directory.CreateDirectory(fixture.Config.StateDirectory);
            using var held = new FileStream(Path.Combine(fixture.Config.StateDirectory, "operation.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None);
            await Throws(() => fixture.Service.UpdateSourceAsync("0.1.6", fixture.Git.Target, default), "另一个启动器");
            Assert(fixture.Git.FetchCalls == 0 && fixture.Http.Requests == 0);
        });
        Add("fetch SHA mismatch rejects before merge", async () =>
        {
            using var fixture = new UpdateFixture(); fixture.Git.FetchedCommit = new string('c', 40);
            await Throws(() => fixture.Service.UpdateSourceAsync("0.1.6", fixture.Git.Target, default), "提交已变化");
            Assert(fixture.Git.FetchCalls == 1 && fixture.Git.MergeCalls == 0);
        });
        Add("target manifest mismatch rejects before merge", async () =>
        {
            using var fixture = new UpdateFixture(); fixture.Git.TargetVersion = "0.1.7";
            await Throws(() => fixture.Service.UpdateSourceAsync("0.1.6", fixture.Git.Target, default), "版本不一致");
            Assert(fixture.Git.MergeCalls == 0);
        });
        Add("local extra commit outside fetched target history rejects merge and downgrade", async () =>
        {
            using var fixture = new UpdateFixture(); fixture.Git.Ahead = 1; fixture.Git.Ancestor = false;
            await Throws(() => fixture.Service.UpdateSourceAsync("0.1.6", fixture.Git.Target, default), "拒绝降级");
            Assert(fixture.Git.FetchCalls == 1 && fixture.Git.MergeCalls == 0);
        });
        Add("external edits after fetch reject before merge", async () =>
        {
            using var fixture = new UpdateFixture(); fixture.Git.DirtyAfterFetch = true;
            await Throws(() => fixture.Service.UpdateSourceAsync("0.1.6", fixture.Git.Target, default), "状态已变化");
            Assert(fixture.Git.MergeCalls == 0);
        });
        Add("safe fast forward uses pinned tag hooks disabled and leaves services alone", async () =>
        {
            using var fixture = new UpdateFixture();
            var result = await fixture.Service.UpdateSourceAsync("0.1.6", fixture.Git.Target, default);
            Assert(result.CurrentVersion == "0.1.6" && result.CurrentCommit == fixture.Git.Target && !result.CanUpdate && !result.Dirty);
            Assert(fixture.Git.FetchCalls == 1 && fixture.Git.MergeCalls == 1 && fixture.Git.EmptyHooksAtMerge);
            var fetch = fixture.Git.Commands.Single(c => c.Contains("fetch"));
            Assert(fetch.Contains("--no-tags") && fetch.Contains("--no-recurse-submodules") && fetch[^1] == "refs/tags/v0.1.6");
            var merge = fixture.Git.Commands.Single(c => c.Contains("merge"));
            Assert(merge.Contains("--ff-only") && merge.Contains("--no-autostash") && merge.Contains("--no-overwrite-ignore") && merge[^1] == fixture.Git.Target);
            Assert(fixture.Git.Commands.All(c => !c.Any(a => a is "reset" or "clean" or "stash" or "checkout" or "pull" or "docker")));
            Assert(fixture.Logs.All(l => !l.Message.Contains("https://") && !l.Message.Contains("synthetic.txt")));
        });
    }

    private static void Assert(bool value) { if (!value) throw new Exception("Version update assertion failed"); }
    private static async Task Throws(Func<Task> action, string contains)
    {
        try { await action(); }
        catch (Exception ex) { Assert(ex.Message.Contains(contains)); return; }
        throw new Exception("Expected version update to be rejected");
    }

    private sealed class UpdateFixture : IDisposable
    {
        public string Root { get; } = Path.Combine(Path.GetTempPath(), "learnhub-version-test-" + Guid.NewGuid().ToString("N"));
        public ProjectConfig Config { get; }
        public UpdateGitRunner Git { get; }
        public ReleaseHandler Http { get; } = new();
        public HttpClient Client { get; }
        public List<LauncherLog> Logs { get; } = [];
        public VersionUpdateService Service { get; }
        public UpdateFixture()
        {
            foreach (var directory in new[] { "frontend", "backend", "deploy", "cli", ".git" }) Directory.CreateDirectory(Path.Combine(Root, directory));
            File.WriteAllText(Path.Combine(Root, "deploy", "docker-compose.yml"), "name: learn-hub\nservices: {}\n");
            WriteVersion("0.1.5"); Config = new(Root); Git = new(this); Client = new(Http);
            Service = new(Config, Git, Client, Logs.Add);
        }
        public void WriteVersion(string version) => File.WriteAllText(Path.Combine(Root, "cli", "package.json"), JsonSerializer.Serialize(new { version }));
        public void Dispose()
        {
            Client.Dispose();
            var resolved = Path.GetFullPath(Root);
            if (resolved.StartsWith(Path.GetFullPath(Path.GetTempPath()), StringComparison.OrdinalIgnoreCase) && Path.GetFileName(resolved).StartsWith("learnhub-version-test-")) Directory.Delete(resolved, true);
            var state = Path.GetFullPath(Config.StateDirectory);
            var parent = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "LearnHub", "Launcher") + Path.DirectorySeparatorChar;
            if (state.StartsWith(parent, StringComparison.OrdinalIgnoreCase) && Directory.Exists(state)) Directory.Delete(state, true);
        }
    }

    private sealed class ReleaseHandler : HttpMessageHandler
    {
        public int Requests { get; private set; }
        public HttpStatusCode Status { get; set; } = HttpStatusCode.OK;
        public string AssetName { get; set; } = "LearnHub.exe";
        public string AssetHost { get; set; } = "github.com";
        public bool ThrowNetwork { get; set; }
        public bool Prerelease { get; set; }
        public string? RedirectLocation { get; set; } = "https://github.com/luyiyanyumu/LearnHub/releases/tag/v0.1.6";
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Requests++;
            if (request.RequestUri!.AbsoluteUri == "https://github.com/luyiyanyumu/LearnHub/releases/latest")
            {
                Assert(request.Headers.UserAgent.Count > 0);
                var fallback = new HttpResponseMessage(HttpStatusCode.Redirect) { Content = new NeverReadContent() };
                if (RedirectLocation != null) fallback.Headers.Location = new Uri(RedirectLocation, UriKind.RelativeOrAbsolute);
                return Task.FromResult(fallback);
            }
            Assert(request.RequestUri!.AbsoluteUri == "https://api.github.com/repos/luyiyanyumu/LearnHub/releases/latest");
            Assert(request.Headers.UserAgent.Count > 0 && request.Headers.Accept.Any(a => a.MediaType == "application/vnd.github+json"));
            if (ThrowNetwork) throw new HttpRequestException("synthetic network unavailable");
            var json = JsonSerializer.Serialize(new
            {
                tag_name = "v0.1.6", draft = false, prerelease = Prerelease, name = "Synthetic release 0.1.6", body = "Synthetic release notes",
                html_url = "https://github.com/luyiyanyumu/LearnHub/releases/tag/v0.1.6", published_at = "2026-10-26T02:00:00Z",
                assets = new[] { new { name = AssetName, browser_download_url = $"https://{AssetHost}/luyiyanyumu/LearnHub/releases/download/v0.1.6/{AssetName}" } }
            });
            return Task.FromResult(new HttpResponseMessage(Status) { Content = new StringContent(json, Encoding.UTF8, "application/json") });
        }
    }

    private sealed class NeverReadContent : HttpContent
    {
        protected override Task SerializeToStreamAsync(Stream stream, TransportContext? context) => throw new InvalidOperationException("Fallback must not read release HTML");
        protected override bool TryComputeLength(out long length) { length = 0; return false; }
    }

    private sealed class UpdateGitRunner(UpdateFixture fixture) : ICommandRunner
    {
        public string Head { get; private set; } = new('a', 40);
        public string Target { get; } = new('b', 40);
        public string? FetchedCommit { get; set; }
        public string Porcelain { get; set; } = "";
        public bool Detached { get; set; }
        public int Ahead { get; set; }
        public bool Ancestor { get; set; } = true;
        public bool ObjectExists { get; set; }
        public string TargetVersion { get; set; } = "0.1.6";
        public bool DirtyAfterFetch { get; set; }
        public int FetchCalls { get; private set; }
        public int MergeCalls { get; private set; }
        public bool EmptyHooksAtMerge { get; private set; }
        public List<string[]> Commands { get; } = [];
        public Task<CommandResult> RunAsync(string executable, IReadOnlyList<string> arguments, string workingDirectory, TimeSpan timeout, CancellationToken cancellationToken,
            bool streamOutput = false, IReadOnlyDictionary<string, string>? environment = null)
        {
            Assert(Path.GetFileName(executable) == "git.exe" && !streamOutput && environment!["GIT_TERMINAL_PROMPT"] == "0");
            var original = arguments.ToArray(); Commands.Add(original);
            var index = 0; while (index < original.Length && original[index] == "-c") index += 2;
            var args = original[index..]; var output = ""; var exitCode = 0;
            switch (args[0])
            {
                case "rev-parse": output = args.Contains("--show-toplevel") ? fixture.Root : args.Contains("--git-dir") ? ".git" : args.Contains("--abbrev-ref") ? "origin/main" : args.Contains("FETCH_HEAD^{commit}") ? FetchedCommit ?? Target : Head; break;
                case "describe": output = MergeCalls > 0 ? "v0.1.6" : "v0.1.5-2-gsynthetic"; break;
                case "status": output = DirtyAfterFetch && FetchCalls > 0 ? "?? synthetic.txt\0" : Porcelain; break;
                case "symbolic-ref": output = Detached ? "" : "main"; exitCode = Detached ? 1 : 0; break;
                case "config": output = "origin"; break;
                case "remote": output = "https://github.com/luyiyanyumu/LearnHub.git"; break;
                case "rev-list": output = $"{Ahead}\t0"; break;
                case "cat-file": exitCode = ObjectExists ? 0 : 1; break;
                case "ls-remote": output = Target + "\trefs/tags/v0.1.6\n"; break;
                case "fetch": FetchCalls++; break;
                case "show": output = JsonSerializer.Serialize(new { version = TargetVersion }); break;
                case "merge-base": exitCode = Ancestor ? 0 : 1; break;
                case "merge":
                    var hooks = original.First(a => a.StartsWith("core.hooksPath=", StringComparison.Ordinal))["core.hooksPath=".Length..];
                    EmptyHooksAtMerge = Directory.Exists(hooks) && !Directory.EnumerateFileSystemEntries(hooks).Any();
                    MergeCalls++; Head = Target; fixture.WriteVersion(TargetVersion); break;
                default: throw new Exception("Unexpected synthetic Git command: " + args[0]);
            }
            return Task.FromResult(new CommandResult(exitCode, output, ""));
        }
    }
}
