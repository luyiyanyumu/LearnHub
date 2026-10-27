using System.Buffers.Binary;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace LearnHub.Launcher.Core;

public sealed record LauncherUpdateInfo(string CurrentVersion, string? LatestVersion, string State, string Detail,
    string? ReleaseUrl, string? DownloadUrl, string? Sha256, long? Size, string Runtime, string CheckedAt);
public sealed record LauncherDownloadProgress(long DownloadedBytes, long TotalBytes, double Percent);
public sealed record DownloadedLauncherUpdate(string FilePath, LauncherUpdateInfo Info, string Sha256, long Size);

/// <summary>Downloads and verifies an official launcher update without executing or installing it.</summary>
public sealed class LauncherSelfUpdateClient
{
    public const long MaximumDownloadSize = 256L * 1024 * 1024;
    private const int MaximumManifestSize = 512 * 1024;
    private const string GitHub = "github.com";
    private const string RepositoryPath = "/luyiyanyumu/LearnHub";
    private readonly string _currentVersion;
    private readonly string _runtime;
    private readonly HttpClient _http;
    private readonly Action<LauncherLog> _log;
    private static readonly HttpClient DefaultClient = new(new HttpClientHandler
    { AllowAutoRedirect = false, AutomaticDecompression = DecompressionMethods.None })
    { Timeout = Timeout.InfiniteTimeSpan };
    private static readonly HashSet<string> AssetHosts = new(StringComparer.OrdinalIgnoreCase)
    { "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com" };

    public LauncherSelfUpdateClient(string currentVersion, string runtime, Action<LauncherLog> log)
        : this(currentVersion, runtime, DefaultClient, log) { }

    public LauncherSelfUpdateClient(string currentVersion, string runtime, HttpClient http, Action<LauncherLog> log)
    {
        if (!VersionUpdateService.TryStableVersion(currentVersion, out _)) throw new ArgumentException("启动器当前版本必须是稳定 X.Y.Z 格式。", nameof(currentVersion));
        if (runtime is not ("win-x64" or "win-arm64")) throw new ArgumentException("启动器更新仅支持 win-x64 或 win-arm64。", nameof(runtime));
        _currentVersion = VersionUpdateService.NormalizeVersion(currentVersion); _runtime = runtime; _http = http;
        var redactor = new LogRedactor(); _log = entry => log(entry with { Message = redactor.Redact(entry.Message) });
    }

    public async Task<LauncherUpdateInfo> CheckAsync(CancellationToken ct)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct); timeout.CancelAfter(TimeSpan.FromSeconds(20));
        try
        {
            var uri = new Uri($"https://{GitHub}{RepositoryPath}/releases/download/launcher-updates/launcher-update-{_runtime}.json");
            using var response = await GetFollowingRedirectsAsync(uri, manifest: true, timeout.Token);
            if (response.StatusCode == HttpStatusCode.NotFound)
                return new(_currentVersion, null, "unavailable", "官方尚未发布适用于此架构的启动器更新清单。", null, null, null, null, _runtime, DateTimeOffset.Now.ToString("O"));
            EnsureResponse(response, "检查启动器更新");
            if (response.Content.Headers.ContentLength > MaximumManifestSize)
                throw new InvalidOperationException("启动器更新清单超过 512 KiB 上限，已拒绝读取。");
            await using var stream = await response.Content.ReadAsStreamAsync(timeout.Token);
            using var content = new MemoryStream(); var buffer = new byte[16 * 1024];
            int read;
            while ((read = await stream.ReadAsync(buffer, timeout.Token)) > 0)
            {
                if (content.Length + read > MaximumManifestSize) throw new InvalidOperationException("启动器更新清单超过 512 KiB 上限，已拒绝读取。");
                content.Write(buffer, 0, read);
            }
            return ParseManifest(Encoding.UTF8.GetString(content.ToArray()));
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        { throw new TimeoutException("检查启动器更新超时，请检查网络后重试。"); }
        catch (HttpRequestException ex)
        { throw new InvalidOperationException("无法连接官方发布服务器检查启动器更新，请检查网络后重试。", ex); }
    }

    public async Task<DownloadedLauncherUpdate> DownloadAsync(LauncherUpdateInfo expectedInfo, string stagingDir,
        IProgress<LauncherDownloadProgress>? progress, CancellationToken ct)
    {
        if (expectedInfo.State != "available" || expectedInfo.Runtime != _runtime || expectedInfo.CurrentVersion != _currentVersion)
            throw new InvalidOperationException("当前没有可安装的启动器更新，请重新检查。");
        var current = await CheckAsync(ct);
        if (current.State != "available" || current.LatestVersion != expectedInfo.LatestVersion || current.Runtime != expectedInfo.Runtime ||
            current.DownloadUrl != expectedInfo.DownloadUrl || !string.Equals(current.Sha256, expectedInfo.Sha256, StringComparison.OrdinalIgnoreCase) ||
            current.Size != expectedInfo.Size || current.ReleaseUrl != expectedInfo.ReleaseUrl)
            throw new InvalidOperationException("启动器更新清单已变化，请重新检查后再下载。");
        var directory = Path.GetFullPath(stagingDir); Directory.CreateDirectory(directory);
        var destination = Path.Combine(directory, $"LearnHub-{_runtime}-{current.LatestVersion}-{Guid.NewGuid():N}.exe");
        var part = destination + ".part";
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct); timeout.CancelAfter(TimeSpan.FromMinutes(10));
        try
        {
            Log("info", $"正在下载启动器 v{current.LatestVersion}，下载后将校验完整性和系统架构。");
            using var response = await GetFollowingRedirectsAsync(new Uri(current.DownloadUrl!), manifest: false, timeout.Token);
            EnsureResponse(response, "下载启动器更新");
            var expectedSize = current.Size!.Value;
            if (response.Content.Headers.ContentLength is { } length && length != expectedSize)
                throw new InvalidOperationException("下载文件大小与更新清单不一致，已取消下载。");
            await using var input = await response.Content.ReadAsStreamAsync(timeout.Token);
            long total = 0; string digest;
            using (var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256))
            {
                await using (var output = new FileStream(part, FileMode.CreateNew, FileAccess.Write, FileShare.None, 64 * 1024, FileOptions.Asynchronous))
                {
                    progress?.Report(new(0, expectedSize, 0));
                    var buffer = new byte[64 * 1024]; int read;
                    while ((read = await input.ReadAsync(buffer, timeout.Token)) > 0)
                    {
                        total += read;
                        if (total > expectedSize || total > MaximumDownloadSize)
                            throw new InvalidOperationException("下载内容超过更新清单声明的大小，已拒绝保存。");
                        hash.AppendData(buffer, 0, read);
                        await output.WriteAsync(buffer.AsMemory(0, read), timeout.Token);
                        progress?.Report(new(total, expectedSize, total * 100d / expectedSize));
                    }
                    await output.FlushAsync(timeout.Token);
                }
                if (total != expectedSize) throw new InvalidOperationException("下载文件不完整，已删除临时文件，请重试。");
                var bytes = hash.GetHashAndReset(); digest = Convert.ToHexString(bytes).ToLowerInvariant();
                if (!CryptographicOperations.FixedTimeEquals(bytes, Convert.FromHexString(current.Sha256!)))
                    throw new InvalidOperationException("启动器文件 SHA-256 校验失败，已拒绝使用下载文件。");
            }
            ValidatePortableExecutable(part, _runtime);
            timeout.Token.ThrowIfCancellationRequested();
            File.Move(part, destination);
            Log("success", $"启动器 v{current.LatestVersion} 已下载并通过 SHA-256、文件大小和架构校验，等待安装。");
            return new(destination, current, digest, total);
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        { throw new TimeoutException("下载启动器更新超时，请检查网络后重试。"); }
        catch (HttpRequestException ex)
        { throw new InvalidOperationException("下载启动器更新失败，请检查网络后重试。", ex); }
        finally
        {
            // Only this operation's unpredictable temporary file is ever removed.
            try { if (File.Exists(part)) File.Delete(part); } catch (IOException) { } catch (UnauthorizedAccessException) { }
        }
    }

    private LauncherUpdateInfo ParseManifest(string json)
    {
        try
        {
            using var doc = JsonDocument.Parse(json); var root = doc.RootElement;
            if (root.ValueKind != JsonValueKind.Object || !root.TryGetProperty("schemaVersion", out var schema) ||
                schema.ValueKind != JsonValueKind.Number || !schema.TryGetInt32(out var schemaVersion) || schemaVersion != 1)
                throw new InvalidOperationException("启动器更新清单版本不受支持。");
            string? Text(string name) => root.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() : null;
            var version = Text("version"); var runtime = Text("runtime"); var url = Text("url"); var sha = Text("sha256"); var releaseUrl = Text("releaseUrl");
            if (!VersionUpdateService.TryStableVersion(version, out var latest) || runtime != _runtime || sha == null || !Regex.IsMatch(sha, "^[a-fA-F0-9]{64}$") ||
                !root.TryGetProperty("size", out var sizeValue) || sizeValue.ValueKind != JsonValueKind.Number || !sizeValue.TryGetInt64(out var size) || size <= 0 || size > MaximumDownloadSize)
                throw new InvalidOperationException("启动器更新清单的版本、架构、校验码或文件大小无效。");
            if (!TryOfficialAssetUrl(url, _runtime, manifest: false, out var downloadTag) || !TryReleaseUrl(releaseUrl, out var releaseTag) || downloadTag != releaseTag)
                throw new InvalidOperationException("启动器更新清单没有指向官方仓库同一稳定发布的文件。");
            VersionUpdateService.TryStableVersion(_currentVersion, out var current);
            var available = latest > current;
            return new(_currentVersion, VersionUpdateService.NormalizeVersion(version!), available ? "available" : "current",
                available ? "发现新版启动器，可一键下载并校验后安装。" : "当前启动器已是最新版本，或当前版本高于已发布版本。",
                releaseUrl, url, sha.ToLowerInvariant(), size, _runtime, DateTimeOffset.Now.ToString("O"));
        }
        catch (JsonException) { throw new InvalidOperationException("启动器更新清单不是有效 JSON，请稍后重试。"); }
        catch (InvalidOperationException ex) when (ex.Message.StartsWith("The requested operation", StringComparison.Ordinal))
        { throw new InvalidOperationException("启动器更新清单字段类型无效。"); }
    }

    private async Task<HttpResponseMessage> GetFollowingRedirectsAsync(Uri original, bool manifest, CancellationToken ct)
    {
        var current = original; var visited = new HashSet<string>(StringComparer.Ordinal);
        for (var redirects = 0; redirects <= 5; redirects++)
        {
            if (!IsAllowedRequestUri(current, manifest) || !visited.Add(current.AbsoluteUri))
                throw new InvalidOperationException("官方更新下载出现不受信任或循环的跳转，已停止请求。");
            using var request = new HttpRequestMessage(HttpMethod.Get, current);
            request.Headers.UserAgent.Add(new ProductInfoHeaderValue("LearnHub-Launcher", _currentVersion));
            request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue(manifest ? "application/json" : "application/octet-stream"));
            using var headerTimeout = CancellationTokenSource.CreateLinkedTokenSource(ct); headerTimeout.CancelAfter(TimeSpan.FromSeconds(12));
            var response = await _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, headerTimeout.Token);
            if (response.StatusCode is not (HttpStatusCode.MovedPermanently or HttpStatusCode.Redirect or HttpStatusCode.SeeOther or HttpStatusCode.TemporaryRedirect or HttpStatusCode.PermanentRedirect))
                return response;
            var location = response.Headers.Location;
            response.Dispose();
            if (redirects == 5 || location == null || !Uri.TryCreate(current, location, out var next))
                throw new InvalidOperationException("官方更新下载跳转次数过多或缺少有效地址。");
            current = next;
        }
        throw new InvalidOperationException("官方更新下载跳转次数超过上限。");
    }

    private bool IsAllowedRequestUri(Uri uri, bool manifest)
    {
        if (!SafeHttps(uri)) return false;
        if (AssetHosts.Contains(uri.Host)) return true;
        if (!uri.Host.Equals(GitHub, StringComparison.OrdinalIgnoreCase) || uri.Query.Length != 0) return false;
        return TryOfficialAssetUrl(uri.AbsoluteUri, _runtime, manifest, out _);
    }

    private static bool TryOfficialAssetUrl(string? value, string runtime, bool manifest, out string? tag)
    {
        tag = null;
        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri) || !SafeHttps(uri) ||
            !uri.Host.Equals(GitHub, StringComparison.OrdinalIgnoreCase) || uri.Query.Length != 0) return false;
        var prefix = RepositoryPath + "/releases/download/";
        if (!uri.AbsolutePath.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) return false;
        var parts = uri.AbsolutePath[prefix.Length..].Split('/');
        if (parts.Length != 2 || !(IsStableTag(parts[0]) || manifest && parts[0] == "launcher-updates")) return false;
        var names = manifest ? new[] { "launcher-update-" + runtime + ".json" } : new[] { "LearnHub-" + runtime + ".exe", "LearnHub.exe" };
        if (!names.Contains(parts[1], StringComparer.Ordinal)) return false;
        tag = parts[0]; return true;
    }
    private static bool TryReleaseUrl(string? value, out string? tag)
    {
        tag = null;
        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri) || !SafeHttps(uri) || !uri.Host.Equals(GitHub, StringComparison.OrdinalIgnoreCase) || uri.Query.Length != 0) return false;
        var prefix = RepositoryPath + "/releases/tag/";
        if (!uri.AbsolutePath.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) return false;
        var candidate = uri.AbsolutePath[prefix.Length..];
        if (!IsStableTag(candidate)) return false;
        tag = candidate; return true;
    }
    private static bool IsStableTag(string tag) => VersionUpdateService.TryStableVersion(tag.StartsWith("launcher-", StringComparison.Ordinal) ? tag[9..] : tag, out _);
    private static bool SafeHttps(Uri uri) => uri.Scheme == Uri.UriSchemeHttps && uri.IsDefaultPort && uri.UserInfo.Length == 0 && uri.Fragment.Length == 0;
    private static void EnsureResponse(HttpResponseMessage response, string operation)
    {
        if (response.StatusCode is HttpStatusCode.Forbidden or HttpStatusCode.TooManyRequests)
            throw new InvalidOperationException($"GitHub 暂时限制更新请求（HTTP {(int)response.StatusCode}），请稍后重试。");
        if (!response.IsSuccessStatusCode) throw new InvalidOperationException($"{operation}失败（HTTP {(int)response.StatusCode}），请稍后重试。");
    }

    public static void ValidatePortableExecutable(string path, string runtime)
    {
        if (runtime is not ("win-x64" or "win-arm64")) throw new InvalidOperationException("无法识别启动器更新架构。");
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
        if (stream.Length < 88 || stream.Length > MaximumDownloadSize) throw new InvalidOperationException("下载文件不是有效的 Windows 启动器。");
        Span<byte> dos = stackalloc byte[64]; stream.ReadExactly(dos);
        if (dos[0] != 'M' || dos[1] != 'Z') throw new InvalidOperationException("下载文件缺少 Windows MZ 标记，已拒绝使用。");
        var offset = BinaryPrimitives.ReadInt32LittleEndian(dos[60..64]);
        if (offset < 64 || offset > stream.Length - 26) throw new InvalidOperationException("下载文件 PE 头位置无效。");
        stream.Position = offset; Span<byte> header = stackalloc byte[26]; stream.ReadExactly(header);
        if (header[0] != 'P' || header[1] != 'E' || header[2] != 0 || header[3] != 0) throw new InvalidOperationException("下载文件缺少有效 PE 标记。");
        var machine = BinaryPrimitives.ReadUInt16LittleEndian(header[4..6]);
        var expected = runtime == "win-x64" ? 0x8664 : 0xAA64;
        if (machine != expected) throw new InvalidOperationException("下载的启动器架构与当前进程不匹配，已拒绝使用。");
        var optionalSize = BinaryPrimitives.ReadUInt16LittleEndian(header[20..22]);
        var flags = BinaryPrimitives.ReadUInt16LittleEndian(header[22..24]);
        if (optionalSize < 112 || offset + 24L + optionalSize > stream.Length || (flags & 0x0002) == 0 || (flags & 0x2000) != 0 ||
            BinaryPrimitives.ReadUInt16LittleEndian(header[24..26]) != 0x20B)
            throw new InvalidOperationException("下载文件不是可执行的 64 位 Windows 启动器。");
    }

    private void Log(string level, string message) => _log(new(DateTimeOffset.Now.ToString("O"), level, message));
}
