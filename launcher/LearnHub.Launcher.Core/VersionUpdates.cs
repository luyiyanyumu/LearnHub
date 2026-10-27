using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace LearnHub.Launcher.Core;

public sealed record VersionUpdateStatus(
    string? CurrentVersion, string? SourceDescription, string? CurrentCommit, string? Branch,
    bool Dirty, int ChangeCount, string? LatestVersion, string? LatestCommit,
    string? ReleaseName, string? ReleaseNotes, string? ReleaseUrl, string? PublishedAt,
    bool CanUpdate, bool UpdateAvailable, string? BlockedReason, string CheckedAt,
    string? Repository, string? LauncherAssetUrl);

/// <summary>Checks official stable releases; source updates never change running business services.</summary>
public sealed class VersionUpdateService
{
    public const string OfficialRepository = "luyiyanyumu/LearnHub";
    private const string CanonicalRemote = "https://github.com/" + OfficialRepository + ".git";
    private readonly ProjectConfig _config;
    private readonly ICommandRunner _runner;
    private readonly HttpClient _http;
    private readonly Action<LauncherLog> _log;
    private readonly string _hooksPath;
    private static readonly HttpClient DefaultClient = new(new HttpClientHandler { AllowAutoRedirect = false })
    { Timeout = TimeSpan.FromSeconds(12) };

    public VersionUpdateService(string projectRoot, Action<LauncherLog> log)
    {
        _config = new(projectRoot);
        _log = entry => log(entry with { Message = _config.Redactor.Redact(entry.Message) });
        _runner = new CommandRunner(_log, _config.Redactor); _http = DefaultClient;
        _hooksPath = Path.Combine(_config.StateDirectory, "disabled-hooks", Guid.NewGuid().ToString("N"));
    }

    public VersionUpdateService(ProjectConfig config, ICommandRunner runner, HttpClient http, Action<LauncherLog> log)
    {
        _config = config; _runner = runner; _http = http;
        _log = entry => log(entry with { Message = config.Redactor.Redact(entry.Message) });
        _hooksPath = Path.Combine(config.StateDirectory, "disabled-hooks", Guid.NewGuid().ToString("N"));
    }

    public async Task<VersionUpdateStatus> CheckAsync(CancellationToken ct) => (await CheckCoreAsync(ct)).Status;

    private sealed record LocalState(string? Version, string Description, string Commit, string? Branch,
        int Changes, string? Upstream, string? RemoteName, string? RemoteIdentity,
        bool OfficialRemote, string? BlockedReason);
    private sealed record Release(string Version, string Tag, string Commit, string? Name,
        string? Notes, string? Url, string? PublishedAt, string? AssetUrl);
    private sealed record CheckedRelease(VersionUpdateStatus Status, LocalState Local, Release? Release);

    private async Task<CheckedRelease> CheckCoreAsync(CancellationToken ct)
    {
        var local = await ReadLocalAsync(ct);
        if (!local.OfficialRemote)
        {
            var unavailable = new VersionUpdateStatus(local.Version ?? "未知", local.Description, local.Commit, local.Branch,
                local.Changes > 0, local.Changes, null, null, null, null, null, null, false, false,
                local.BlockedReason ?? "当前分支未跟踪官方 GitHub 仓库，无法安全检查自动更新。",
                DateTimeOffset.Now.ToString("O"), null, null);
            return new(unavailable, local, null);
        }
        var release = await ReadReleaseAsync(ct);
        var available = TryStableVersion(local.Version, out var current) && TryStableVersion(release.Version, out var latest) && latest > current;
        string? blocked = local.BlockedReason;
        if (blocked == null && !TryStableVersion(local.Version, out _)) blocked = "无法识别 cli/package.json 中的稳定版本，已禁用源码更新。";
        if (blocked == null && !available) blocked = "当前源码版本已等于或高于最新稳定版本，无需更新。";
        if (blocked == null)
        {
            // This only works when the release object is already present; Update rechecks after fetching.
            var objectExists = await Git(["cat-file", "-e", release.Commit + "^{commit}"], ct);
            if (objectExists.Success)
            {
                var ancestor = await Git(["merge-base", "--is-ancestor", local.Commit, release.Commit], ct);
                if (ancestor.ExitCode == 1) blocked = "当前源码领先发布版本或与其分叉，不能降级或自动合并。";
                else Ensure(ancestor, "验证发布版本历史");
            }
        }
        var status = new VersionUpdateStatus(local.Version ?? "未知", local.Description, local.Commit, local.Branch,
            local.Changes > 0, local.Changes, release.Version, release.Commit, release.Name, release.Notes, release.Url,
            release.PublishedAt, available && blocked == null, available, blocked, DateTimeOffset.Now.ToString("O"),
            OfficialRepository, release.AssetUrl);
        return new(status, local, release);
    }

    public async Task<VersionUpdateStatus> UpdateSourceAsync(string expectedVersion, string expectedCommit, CancellationToken ct)
    {
        if (!TryStableVersion(expectedVersion, out _) || !IsCommit(expectedCommit))
            throw new InvalidOperationException("目标版本或提交无效，请重新检查更新。");
        Directory.CreateDirectory(_config.StateDirectory);
        FileStream operationLock;
        try { operationLock = new(Path.Combine(_config.StateDirectory, "operation.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None); }
        catch (IOException) { throw new InvalidOperationException("另一个启动器操作正在运行，请等待完成后重试。"); }
        await using var heldLock = operationLock;
        Directory.CreateDirectory(_hooksPath);
        try
        {
            var check = await CheckCoreAsync(ct);
            var release = check.Release;
            if (release == null || !string.Equals(release.Version, NormalizeVersion(expectedVersion), StringComparison.Ordinal) ||
                !string.Equals(release.Commit, expectedCommit, StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException("最新发布版本或提交已变化，请重新检查并确认更新。");
            if (!check.Status.CanUpdate) throw new InvalidOperationException(check.Status.BlockedReason ?? "当前项目不满足安全更新条件。");
            var before = await ReadLocalAsync(ct);
            AssertSameLocal(check.Local, before);
            Log("info", $"准备更新源码到 v{release.Version} · {release.Commit[..8]}；运行中的服务保持原状。");
            // The canonical public HTTPS URL avoids invoking a repository's arbitrary remote command.
            Ensure(await Git(["fetch", "--no-tags", "--no-recurse-submodules", "--", CanonicalRemote, "refs/tags/" + release.Tag], ct, 180), "获取稳定版本源码");
            var fetched = await RequiredGit(["rev-parse", "--verify", "FETCH_HEAD^{commit}"], "核验下载的发布提交", ct);
            if (!string.Equals(fetched.Trim(), release.Commit, StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException("发布标签对应的提交已变化，已停止更新；请重新检查版本。");
            var manifest = await RequiredGit(["show", release.Commit + ":cli/package.json"], "读取发布版本清单", ct);
            if (!string.Equals(ReadManifestVersion(manifest), release.Version, StringComparison.Ordinal))
                throw new InvalidOperationException("发布标签与 cli/package.json 的版本不一致，已停止更新。");
            var ancestor = await Git(["merge-base", "--is-ancestor", before.Commit, release.Commit], ct);
            if (ancestor.ExitCode == 1) throw new InvalidOperationException("当前源码领先目标版本或与其分叉，已拒绝降级和自动合并。");
            Ensure(ancestor, "验证快进更新条件");
            var fresh = await ReadLocalAsync(ct);
            AssertSameLocal(before, fresh);
            ct.ThrowIfCancellationRequested();
            // No hooks, submodule checkout, autostash, ignored-file overwrite, merge commit, or branch switching.
            Ensure(await Git(["merge", "--ff-only", "--no-edit", "--no-stat", "--no-autostash", "--no-overwrite-ignore", release.Commit], ct, 60), "快进更新源码");
            var after = await ReadLocalAsync(ct);
            if (!string.Equals(after.Commit, release.Commit, StringComparison.OrdinalIgnoreCase) ||
                !string.Equals(after.Version, release.Version, StringComparison.Ordinal) || after.Changes != 0 || after.Branch != before.Branch)
                throw new InvalidOperationException("源码已尝试更新，但最终版本、分支或工作区状态未通过复核。请检查项目后再构建。");
            Log("success", $"源码已更新至 v{release.Version} · {release.Commit[..8]}。运行中的服务尚未改变，可选择“构建并应用”。");
            return check.Status with
            {
                CurrentVersion = after.Version, SourceDescription = after.Description, CurrentCommit = after.Commit,
                Branch = after.Branch, Dirty = false, ChangeCount = 0, CanUpdate = false, UpdateAvailable = false,
                BlockedReason = "源码已更新。可选择“构建并应用”更新运行中的服务。", CheckedAt = DateTimeOffset.Now.ToString("O")
            };
        }
        catch (Exception ex)
        {
            Log(ex is OperationCanceledException ? "warning" : "error", ex.Message);
            throw;
        }
        finally
        {
            // Remove only this unique empty hook directory; never recurse through user files.
            try { Directory.Delete(_hooksPath, recursive: false); } catch (IOException) { } catch (UnauthorizedAccessException) { }
        }
    }

    private static void AssertSameLocal(LocalState expected, LocalState current)
    {
        if (current.BlockedReason != null || current.Changes != 0 || !current.OfficialRemote ||
            current.Commit != expected.Commit || current.Branch != expected.Branch || current.Version != expected.Version ||
            current.Upstream != expected.Upstream || current.RemoteName != expected.RemoteName || current.RemoteIdentity != expected.RemoteIdentity)
            throw new InvalidOperationException("项目的提交、分支、上游或工作区状态已变化，已停止更新。请重新检查版本。");
    }

    private async Task<LocalState> ReadLocalAsync(CancellationToken ct)
    {
        var top = (await RequiredGit(["rev-parse", "--show-toplevel"], "定位 Git 源码仓库", ct)).Trim();
        if (ProjectConfig.NormalizePath(top) != ProjectConfig.NormalizePath(_config.Root))
            throw new InvalidOperationException("所选目录不是此 Git 仓库的根目录，无法安全检查源码更新。");
        var commit = (await RequiredGit(["rev-parse", "--verify", "HEAD^{commit}"], "读取当前源码提交", ct)).Trim();
        if (!IsCommit(commit)) throw new InvalidOperationException("当前 Git 提交格式无效。");
        var description = (await RequiredGit(["describe", "--tags", "--always", "--dirty"], "读取源码版本描述", ct)).Trim();
        var changes = CountChanges(await RequiredGit(["status", "--porcelain=v1", "-z", "--untracked-files=all"], "检查工作区修改", ct));
        string? version = null;
        try { version = ReadManifestVersion(await File.ReadAllTextAsync(Path.Combine(_config.Root, "cli", "package.json"), ct)); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or JsonException) { }
        var branchResult = await Git(["symbolic-ref", "--quiet", "--short", "HEAD"], ct);
        var branch = branchResult.Success ? branchResult.Output.Trim() : null;
        if (branch != null && (branch.Length == 0 || branch.IndexOfAny(['\r', '\n', '\0']) >= 0)) branch = null;
        var gitDir = (await RequiredGit(["rev-parse", "--git-dir"], "检查 Git 操作状态", ct)).Trim();
        gitDir = Path.GetFullPath(gitDir, _config.Root);
        var inProgress = new[] { "MERGE_HEAD", "REBASE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "rebase-merge", "rebase-apply", "sequencer", "BISECT_LOG" }
            .Any(name => File.Exists(Path.Combine(gitDir, name)) || Directory.Exists(Path.Combine(gitDir, name)));
        string? reason = changes > 0 ? $"本地有 {changes} 项未提交或未跟踪的修改，请先自行提交或处理后再更新。" :
            branch == null ? "当前为 detached HEAD，请先切回有上游跟踪的分支。" :
            inProgress ? "Git 正在进行合并、变基或其它操作，请完成后再检查更新。" : null;
        string? upstream = null, remoteName = null, identity = null;
        var official = false;
        if (branch != null)
        {
            var upstreamResult = await Git(["rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}"], ct);
            if (upstreamResult.Success) upstream = upstreamResult.Output.Trim();
            var remoteResult = await Git(["config", "--get", "branch." + branch + ".remote"], ct);
            if (remoteResult.Success) remoteName = remoteResult.Output.Trim();
            if (upstream == null || string.IsNullOrEmpty(remoteName) || remoteName == "." || remoteName.StartsWith('-') || remoteName.IndexOfAny(['\r', '\n', '\0']) >= 0)
                reason ??= "当前分支没有有效的远端上游跟踪，已禁用自动更新。";
            else
            {
                var remotes = await Git(["remote", "get-url", "--all", remoteName], ct);
                if (remotes.Success)
                {
                    var urls = remotes.Output.Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
                    official = urls.Length > 0 && urls.All(IsOfficialRemote);
                    if (official) identity = string.Join('\n', urls);
                }
                if (!official) reason ??= "当前上游不是官方 GitHub HTTPS/SSH 仓库，已禁用自动更新。";
                // A release-only fetch intentionally leaves cached upstream refs unchanged.
                // The current HEAD must be an ancestor of the exact target release instead;
                // Check verifies this when available, and Update always verifies after fetch.
            }
        }
        return new(version, _config.Redactor.Redact(description), commit.ToLowerInvariant(), branch, changes, upstream, remoteName, identity, official, reason);
    }

    private async Task<Release> ReadReleaseAsync(CancellationToken ct)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, "https://api.github.com/repos/" + OfficialRepository + "/releases/latest");
        request.Headers.UserAgent.Add(new ProductInfoHeaderValue("LearnHub-Launcher", "1.0"));
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/vnd.github+json"));
        request.Headers.Add("X-GitHub-Api-Version", "2022-11-28");
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct); timeout.CancelAfter(TimeSpan.FromSeconds(12));
        string json;
        try
        {
            using var response = await _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, timeout.Token);
            if (response.StatusCode is System.Net.HttpStatusCode.Forbidden or System.Net.HttpStatusCode.TooManyRequests)
                return await ReadFallbackReleaseAsync(ct);
            if (!response.IsSuccessStatusCode) throw new InvalidOperationException($"GitHub 版本检查失败（HTTP {(int)response.StatusCode}），请检查网络或稍后重试。");
            if (response.Content.Headers.ContentLength > 512 * 1024) throw new InvalidOperationException("GitHub 发布信息过大，无法安全读取。");
            await using var stream = await response.Content.ReadAsStreamAsync(timeout.Token);
            using var reader = new StreamReader(stream, Encoding.UTF8);
            var content = new StringBuilder(); var buffer = new char[4096];
            int read;
            while ((read = await reader.ReadAsync(buffer.AsMemory(), timeout.Token)) > 0)
            {
                content.Append(buffer, 0, read);
                if (content.Length > 512 * 1024) throw new InvalidOperationException("GitHub 发布信息过大，无法安全读取。");
            }
            json = content.ToString();
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException)
        {
            ct.ThrowIfCancellationRequested();
            throw new InvalidOperationException("无法连接 GitHub 检查最新稳定版本，请检查网络后重试。", ex);
        }
        try
        {
            using var doc = JsonDocument.Parse(json); var root = doc.RootElement;
            string? Text(string key, int length = 65536) => root.TryGetProperty(key, out var value) && value.ValueKind == JsonValueKind.String ? Limit(value.GetString(), length) : null;
            var tag = Text("tag_name", 100);
            if (!TryStableVersion(tag, out _) || IsTrue(root, "draft") || IsTrue(root, "prerelease"))
                throw new InvalidOperationException("GitHub 最新发布不是有效的稳定 vX.Y.Z 版本，无法自动更新。");
            var version = NormalizeVersion(tag!);
            var commit = await ReadTagCommitAsync(tag!, ct);
            var url = SafeReleaseUrl(Text("html_url", 1000), tag!);
            string? asset = null;
            if (root.TryGetProperty("assets", out var assets) && assets.ValueKind == JsonValueKind.Array)
                foreach (var candidate in assets.EnumerateArray())
                    if (candidate.TryGetProperty("name", out var name) && name.GetString() == "LearnHub.exe" &&
                        candidate.TryGetProperty("browser_download_url", out var download) && download.ValueKind == JsonValueKind.String &&
                        SafeAssetUrl(download.GetString(), tag!)) { asset = download.GetString(); break; }
            return new(version, tag!, commit, _config.Redactor.Redact(Text("name", 300)), _config.Redactor.Redact(Text("body")), url, Text("published_at", 100), asset);
        }
        catch (JsonException) { throw new InvalidOperationException("GitHub 发布信息格式不正确，请稍后重试。"); }
    }

    private async Task<Release> ReadFallbackReleaseAsync(CancellationToken ct)
    {
        var latestUrl = new Uri("https://github.com/" + OfficialRepository + "/releases/latest");
        using var request = new HttpRequestMessage(HttpMethod.Get, latestUrl);
        request.Headers.UserAgent.Add(new ProductInfoHeaderValue("LearnHub-Launcher", "1.0"));
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("text/html"));
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct); timeout.CancelAfter(TimeSpan.FromSeconds(12));
        using var response = await _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, timeout.Token);
        // GitHub's public latest-release redirect is enough. Never read or scrape the HTML body.
        if (response.StatusCode is not (System.Net.HttpStatusCode.MovedPermanently or System.Net.HttpStatusCode.Redirect or
            System.Net.HttpStatusCode.SeeOther or System.Net.HttpStatusCode.TemporaryRedirect or System.Net.HttpStatusCode.PermanentRedirect))
            throw new InvalidOperationException($"GitHub API 受限，官方发布页面未返回可验证的版本跳转（HTTP {(int)response.StatusCode}）。请稍后重试。");
        var location = response.Headers.Location;
        if (location == null || !Uri.TryCreate(latestUrl, location, out var target) ||
            target.Scheme != Uri.UriSchemeHttps || !target.Host.Equals("github.com", StringComparison.OrdinalIgnoreCase) ||
            !target.IsDefaultPort || target.UserInfo.Length != 0 || target.Query.Length != 0 || target.Fragment.Length != 0)
            throw new InvalidOperationException("GitHub API 受限，官方发布页面的跳转地址无效，无法安全检查版本。");
        var prefix = "/" + OfficialRepository + "/releases/tag/";
        var tag = target.AbsolutePath.StartsWith(prefix, StringComparison.OrdinalIgnoreCase) ? target.AbsolutePath[prefix.Length..] : "";
        if (!TryStableVersion(tag, out _) || !SafeGitHubPath(target.AbsoluteUri, prefix + tag))
            throw new InvalidOperationException("GitHub API 受限，发布页面未指向官方稳定 vX.Y.Z 版本，无法安全检查更新。");
        var commit = await ReadTagCommitAsync(tag, ct);
        return new(NormalizeVersion(tag), tag, commit, null, "暂无法读取完整说明，请打开官方发布页面。", target.AbsoluteUri, null, null);
    }

    private async Task<string> ReadTagCommitAsync(string tag, CancellationToken ct)
    {
        var refs = await Git(["ls-remote", "--exit-code", "--tags", "--", CanonicalRemote, "refs/tags/" + tag, "refs/tags/" + tag + "^{}"], ct, 25);
        Ensure(refs, "读取 GitHub 稳定标签（请检查网络）");
        return ResolveTagCommit(refs.Output, tag) ?? throw new InvalidOperationException("GitHub 稳定标签没有返回有效提交，无法检查更新。");
    }

    public static bool IsOfficialRemote(string value)
    {
        var ssh = Regex.Match(value.Trim(), @"^git@github\.com:([^/\s]+/[^/\s]+?)(?:\.git)?$", RegexOptions.IgnoreCase);
        if (ssh.Success) return ssh.Groups[1].Value.Equals(OfficialRepository, StringComparison.OrdinalIgnoreCase);
        if (!Uri.TryCreate(value.Trim(), UriKind.Absolute, out var uri) || uri.Scheme != Uri.UriSchemeHttps ||
            !uri.Host.Equals("github.com", StringComparison.OrdinalIgnoreCase) || !uri.IsDefaultPort ||
            uri.UserInfo.Length != 0 || uri.Query.Length != 0 || uri.Fragment.Length != 0) return false;
        var path = uri.AbsolutePath.Trim('/');
        if (path.EndsWith(".git", StringComparison.OrdinalIgnoreCase)) path = path[..^4];
        return path.Equals(OfficialRepository, StringComparison.OrdinalIgnoreCase);
    }

    public static bool TryStableVersion(string? text, out Version version)
    {
        version = new Version(0, 0, 0);
        if (text == null) return false;
        var match = Regex.Match(text, @"^v?(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$");
        if (!match.Success || !int.TryParse(match.Groups[1].Value, out var major) ||
            !int.TryParse(match.Groups[2].Value, out var minor) || !int.TryParse(match.Groups[3].Value, out var patch)) return false;
        version = new Version(major, minor, patch); return true;
    }

    public static string NormalizeVersion(string text) => text.StartsWith('v') ? text[1..] : text;
    public static string? ReadManifestVersion(string json)
    {
        using var doc = JsonDocument.Parse(json);
        return doc.RootElement.TryGetProperty("version", out var value) && value.ValueKind == JsonValueKind.String && TryStableVersion(value.GetString(), out _)
            ? NormalizeVersion(value.GetString()!) : null;
    }
    public static int CountChanges(string porcelain)
    {
        var entries = porcelain.Split('\0'); var count = 0;
        for (var i = 0; i < entries.Length; i++)
        {
            var entry = entries[i]; if (entry.Length < 3) continue;
            count++;
            if (entry[0] is 'R' or 'C' || entry[1] is 'R' or 'C') i++;
        }
        return count;
    }
    public static string? ResolveTagCommit(string output, string tag)
    {
        string? direct = null, peeled = null;
        foreach (var line in output.Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries))
        {
            var parts = line.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length != 2 || !IsCommit(parts[0])) continue;
            if (parts[1] == "refs/tags/" + tag) direct = parts[0].ToLowerInvariant();
            if (parts[1] == "refs/tags/" + tag + "^{}") peeled = parts[0].ToLowerInvariant();
        }
        return peeled ?? direct;
    }
    private static bool IsCommit(string value) => Regex.IsMatch(value, "^(?:[a-fA-F0-9]{40}|[a-fA-F0-9]{64})$");
    private static bool IsTrue(JsonElement root, string key) => root.TryGetProperty(key, out var value) && value.ValueKind == JsonValueKind.True;
    private static string? Limit(string? text, int length) => text is { Length: > 0 } ? text[..Math.Min(text.Length, length)] : null;
    private static string? SafeReleaseUrl(string? value, string tag) => SafeGitHubPath(value, "/" + OfficialRepository + "/releases/tag/" + Uri.EscapeDataString(tag)) ? value : null;
    private static bool SafeAssetUrl(string? value, string tag) => SafeGitHubPath(value, "/" + OfficialRepository + "/releases/download/" + Uri.EscapeDataString(tag) + "/LearnHub.exe");
    private static bool SafeGitHubPath(string? value, string expected) => Uri.TryCreate(value, UriKind.Absolute, out var uri) &&
        uri.Scheme == Uri.UriSchemeHttps && uri.Host.Equals("github.com", StringComparison.OrdinalIgnoreCase) && uri.IsDefaultPort &&
        uri.UserInfo.Length == 0 && uri.Query.Length == 0 && uri.Fragment.Length == 0 && uri.AbsolutePath.Equals(expected, StringComparison.OrdinalIgnoreCase);

    private async Task<string> RequiredGit(IReadOnlyList<string> args, string operation, CancellationToken ct)
    { var result = await Git(args, ct); Ensure(result, operation); return result.Output; }
    private void Ensure(CommandResult result, string operation)
    { if (!result.Success) throw new InvalidOperationException($"{operation}失败（Git 退出码 {result.ExitCode}），请检查网络或本地 Git 状态后重试。"); }
    private void Log(string level, string message) => _log(new(DateTimeOffset.Now.ToString("O"), level, _config.Redactor.Redact(message)));
    private Task<CommandResult> Git(IReadOnlyList<string> args, CancellationToken ct, int seconds = 20)
    {
        var command = new List<string>
        {
            "-c", "core.hooksPath=" + _hooksPath, "-c", "core.fsmonitor=false", "-c", "submodule.recurse=false",
            "-c", "credential.helper=", "-c", "credential.interactive=never", "-c", "merge.autoStash=false"
        };
        command.AddRange(args);
        var env = new Dictionary<string, string>
        { ["GIT_TERMINAL_PROMPT"] = "0", ["GIT_OPTIONAL_LOCKS"] = "0", ["GIT_MERGE_AUTOEDIT"] = "no" };
        var paths = new[]
        {
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Git", "cmd", "git.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "Git", "cmd", "git.exe")
        };
        return _runner.RunAsync(paths.FirstOrDefault(File.Exists) ?? "git.exe", command, _config.Root, TimeSpan.FromSeconds(seconds), ct, false, env);
    }
}
