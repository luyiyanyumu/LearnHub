using System.Diagnostics;
using System.Net.Http;

namespace LearnHub.Launcher.Core;

public sealed record HealthResult(bool Healthy, string Detail, long ResponseMs);
public static class HealthProbe
{
    private static readonly HttpClient Client = new(new HttpClientHandler { AllowAutoRedirect = false, UseProxy = false })
    { Timeout = TimeSpan.FromSeconds(4) };

    public static async Task<HealthResult> CheckFrontendAsync(string address, CancellationToken ct)
    {
        var page = await CheckAsync(address.TrimEnd('/') + "/", true, ct);
        if (!page.Healthy) return page;
        var proxy = await CheckAsync(address.TrimEnd('/') + "/api/files/page?page=1&size=1", false, ct);
        return proxy.Healthy
            ? new(true, "页面与 API 代理均可访问", page.ResponseMs + proxy.ResponseMs)
            : new(false, "页面可访问，API 代理异常：" + proxy.Detail, page.ResponseMs + proxy.ResponseMs);
    }

    public static async Task<HealthResult> CheckAsync(string address, bool expectHtml, CancellationToken ct)
    {
        var timer = Stopwatch.StartNew();
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, address);
            using var response = await Client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);
            // Do not read, retain, log, or export API bodies: they may include user documents.
            var html = response.Content.Headers.ContentType?.MediaType?.Contains("html", StringComparison.OrdinalIgnoreCase) == true;
            var json = response.Content.Headers.ContentType?.MediaType?.Contains("json", StringComparison.OrdinalIgnoreCase) == true;
            return response.IsSuccessStatusCode && (expectHtml ? html : json)
                ? new(true, $"HTTP {(int)response.StatusCode} · {(expectHtml ? "页面可访问" : "API 可访问")}", timer.ElapsedMilliseconds)
                : new(false, $"HTTP {(int)response.StatusCode}" + (expectHtml && !html ? " · 响应不是网页" : !expectHtml && !json ? " · API 响应不是 JSON" : " · 健康检查未通过"), timer.ElapsedMilliseconds);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException)
        { ct.ThrowIfCancellationRequested(); return new(false, "连接失败或健康检查超时", timer.ElapsedMilliseconds); }
    }
}
