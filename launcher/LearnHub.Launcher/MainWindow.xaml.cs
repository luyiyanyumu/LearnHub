using System.Collections.Concurrent;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Text.Json;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using LearnHub.Launcher.Core;
using LearnHub.Launcher.Updating;
using Microsoft.Web.WebView2.Core;
using Microsoft.Win32;

namespace LearnHub.Launcher;

public partial class MainWindow : Window
{
    private const string UiOrigin = "https://launcher.learnhub.local";
    private readonly LauncherPreferences preferences;
    private readonly bool demo;
    private readonly SemaphoreSlim operation = new(1, 1);
    private readonly SemaphoreSlim inspection = new(1, 1);
    private readonly CancellationTokenSource lifetime = new();
    private readonly ConcurrentQueue<LauncherLog> activity = new();
    private readonly object logFileLock = new();
    private string? stateDirectory;
    private LauncherEngine? engine;
    private VersionUpdateService? versionUpdates;
    private VersionUpdateStatus? checkedUpdate;
    private readonly LauncherSelfUpdateClient launcherUpdater;
    private LauncherUpdateInfo? checkedLauncherUpdate;
    private readonly string? updateAckPath;
    private readonly string? updateAckToken;
    private bool updateAcknowledged;
    private bool restartHandoff;
    private bool updateNoticeRead;
    private CancellationTokenSource? jobCancellation;
    private bool jobRunning;
    private bool closeAfterJob;
    private string projectRoot;

    internal MainWindow(string root, LauncherPreferences settings, bool demoMode, string? ackPath = null, string? ackToken = null)
    {
        InitializeComponent();
        projectRoot = root;
        preferences = settings;
        demo = demoMode;
        launcherUpdater = new LauncherSelfUpdateClient(SelfUpdateCoordinator.CurrentVersion, SelfUpdateCoordinator.Runtime, AddLog);
        updateAckPath = ackPath;
        updateAckToken = ackToken;
        if (!demo)
        {
            engine = new LauncherEngine(root, AddLog);
            versionUpdates = new VersionUpdateService(root, AddLog);
            stateDirectory = new ProjectConfig(root).StateDirectory;
        }
        var screen = DisplayPlacement.Current();
        Width = Math.Clamp(preferences.WindowWidth ?? Math.Min(1380, screen.LogicalWidth * .9), Math.Min(640, screen.LogicalWidth), screen.LogicalWidth);
        Height = Math.Clamp(preferences.WindowHeight ?? Math.Min(940, screen.LogicalHeight * .9), Math.Min(520, screen.LogicalHeight), screen.LogicalHeight);
        MinWidth = Math.Min(640, screen.LogicalWidth);
        MinHeight = Math.Min(520, screen.LogicalHeight);
        if (preferences.Maximized) WindowState = WindowState.Maximized;
        Loaded += async (_, _) => await InitializeBrowserAsync();
        SourceInitialized += (_, _) =>
        {
            if (!preferences.Maximized) DisplayPlacement.Center(new WindowInteropHelper(this).Handle, screen, Width, Height);
            ApplyNativeTheme();
        };
        SystemEvents.UserPreferenceChanged += OnSystemPreferenceChanged;
    }

    private async Task InitializeBrowserAsync()
    {
        try
        {
            var folder = UiAssets.Extract();
            var environment = await CoreWebView2Environment.CreateAsync(null, Path.Combine(LauncherPreferences.DataDirectory, demo ? "webview-demo" : "webview"));
            await Browser.EnsureCoreWebView2Async(environment);
            var web = Browser.CoreWebView2;
            web.SetVirtualHostNameToFolderMapping("launcher.learnhub.local", folder, CoreWebView2HostResourceAccessKind.DenyCors);
            web.Settings.AreDefaultContextMenusEnabled = false;
            web.Settings.AreDevToolsEnabled = demo;
            web.Settings.IsStatusBarEnabled = false;
            web.Settings.IsGeneralAutofillEnabled = false;
            web.Settings.IsPasswordAutosaveEnabled = false;
            web.Settings.IsWebMessageEnabled = true;
            web.NavigationStarting += (_, e) =>
            {
                if (!IsUiAddress(e.Uri)) e.Cancel = true;
            };
            web.NewWindowRequested += (_, e) => e.Handled = true;
            web.PermissionRequested += (_, e) => e.State = CoreWebView2PermissionState.Deny;
            web.DownloadStarting += (_, e) => e.Cancel = true;
            web.WebMessageReceived += OnWebMessage;
            web.NavigationCompleted += (_, e) =>
            {
                if (!e.IsSuccess) { LoadingText.Text = "界面加载失败，请关闭后重新打开启动器。"; return; }
                LoadingPanel.Visibility = Visibility.Collapsed;
                Browser.Visibility = Visibility.Visible;
            };
            ApplyNativeTheme();
            web.Navigate(UiOrigin + "/index.html");
        }
        catch (WebView2RuntimeNotFoundException)
        {
            LoadingText.Text = "此电脑尚未安装 Microsoft Edge WebView2 Runtime。安装后即可打开界面，无需安装 .NET。";
            RuntimeLink.Visibility = Visibility.Visible;
        }
        catch (Exception ex)
        {
            LoadingText.Text = "无法打开启动器界面：" + ex.Message;
        }
    }

    private static bool IsUiAddress(string address) => Uri.TryCreate(address, UriKind.Absolute, out var uri)
        && uri.Scheme == "https" && uri.Host == "launcher.learnhub.local" && uri.IsDefaultPort;

    private async void OnWebMessage(object? sender, CoreWebView2WebMessageReceivedEventArgs e)
    {
        if (!IsUiAddress(e.Source)) return;
        string? id = null;
        try
        {
            var json = e.WebMessageAsJson;
            if (json.Length > 32768) throw new InvalidOperationException("请求过大。");
            using var request = JsonDocument.Parse(json);
            var body = request.RootElement;
            id = Text(body, "id");
            if (string.IsNullOrEmpty(id) || id.Length > 100) return;
            var command = Text(body, "command");
            var payload = body.TryGetProperty("payload", out var data) ? data.Clone() : default;
            var result = await HandleCommandAsync(command, payload);
            Post(new { id, result });
        }
        catch (OperationCanceledException) { Post(new { id, error = "操作已取消；已完成的步骤会保留，请查看当前服务状态。" }); }
        catch (Exception ex) { Post(new { id, error = CleanMessage(ex.Message) }); }
    }

    private async Task<object?> HandleCommandAsync(string? command, JsonElement payload)
    {
        if (restartHandoff) throw new InvalidOperationException("启动器正在自动重启，请稍候。");
        var mode = Text(payload, "mode") ?? preferences.Mode;
        if (mode is not ("docker" or "local")) throw new InvalidOperationException("未知运行模式。");
        switch (command)
        {
            case "bootstrap":
                string? updateNotice = null;
                if (!demo && !updateNoticeRead)
                {
                    updateNoticeRead = true;
                    updateNotice = SelfUpdateCoordinator.ReadFailureNotice();
                    if (updateNotice is not null) AddLog(new(DateTimeOffset.Now.ToString("O"), "warning", updateNotice));
                }
                if (updateAckPath is not null && !updateAcknowledged)
                {
                    await UpdateHandshake.AcknowledgeAsync(updateAckPath, updateAckToken ?? throw new InvalidOperationException("更新启动凭据缺失。"), lifetime.Token);
                    updateAcknowledged = true;
                    AddLog(new(DateTimeOffset.Now.ToString("O"), "success", $"启动器已更新至 v{SelfUpdateCoordinator.CurrentVersion}，界面启动正常。"));
                }
                return new { preferences = PublicPreferences(), display = new { scale = VisualTreeHelper.GetDpi(this).DpiScaleX, width = ActualWidth, height = ActualHeight }, version = SelfUpdateCoordinator.CurrentVersion, demo, updateNotice };
            case "snapshot":
                return await GetSnapshotAsync(mode);
            case "action":
                await ExecuteOperationAsync(Text(payload, "action") ?? "", mode, Text(payload, "service"));
                return true;
            case "logs":
                var service = Text(payload, "service") ?? "backend";
                var tail = payload.ValueKind == JsonValueKind.Object && payload.TryGetProperty("tail", out var count) && count.TryGetInt32(out var n) ? Math.Clamp(n, 20, 1000) : 200;
                return demo ? DemoData.Logs(service) : await engine!.GetLogsAsync(mode, service, tail, lifetime.Token);
            case "preferences":
                var requestedMode = Text(payload, "mode");
                if (requestedMode is not null)
                {
                    if (jobRunning) throw new InvalidOperationException("请等待当前操作结束后再切换运行模式。");
                    if (requestedMode is not ("docker" or "local")) throw new InvalidOperationException("未知运行模式。");
                    preferences.Mode = requestedMode;
                }
                var theme = Text(payload, "theme");
                if (theme is not null)
                {
                    if (theme is not ("system" or "dark" or "light")) throw new InvalidOperationException("未知主题。");
                    preferences.Theme = theme;
                    ApplyNativeTheme();
                }
                if (payload.ValueKind == JsonValueKind.Object && payload.TryGetProperty("autoRefresh", out var auto) && auto.ValueKind is JsonValueKind.True or JsonValueKind.False)
                    preferences.AutoRefresh = auto.GetBoolean();
                if (!demo) preferences.Save();
                return PublicPreferences();
            case "select-project":
                if (jobRunning) throw new InvalidOperationException("请等待当前操作结束后再切换项目。");
                if (demo) return PublicPreferences();
                var picker = new OpenFolderDialog { Title = "选择 LearnHub 项目根目录", InitialDirectory = projectRoot, Multiselect = false };
                if (picker.ShowDialog(this) != true) return null;
                if (!ProjectLocator.IsProject(picker.FolderName)) throw new InvalidOperationException("请选择包含 frontend、backend 和 deploy 的 LearnHub 根目录。");
                projectRoot = Path.GetFullPath(picker.FolderName);
                engine = new LauncherEngine(projectRoot, AddLog);
                versionUpdates = new VersionUpdateService(projectRoot, AddLog);
                checkedUpdate = null;
                stateDirectory = new ProjectConfig(projectRoot).StateDirectory;
                preferences.ProjectRoot = projectRoot;
                preferences.Save();
                return PublicPreferences();
            case "open":
                if (demo) { AddLog(new(DateTimeOffset.Now.ToString("O"), "info", "界面预览：已模拟打开入口。")); return true; }
                await OpenTargetAsync(Text(payload, "target"), mode);
                return true;
            case "export-diagnostics":
                if (demo) return new { demo = true, message = "预览模式不导出本机信息。" };
                return await ExportDiagnosticsAsync(mode);
            case "check-updates":
                if (demo) return DemoData.VersionStatus();
                using (var checkTimeout = CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token))
                {
                    checkTimeout.CancelAfter(TimeSpan.FromSeconds(90));
                    var selectedUpdates = versionUpdates!;
                    var result = await selectedUpdates.CheckAsync(checkTimeout.Token);
                    if (!ReferenceEquals(selectedUpdates, versionUpdates)) throw new InvalidOperationException("项目已切换，请重新检查当前项目的版本。");
                    checkedUpdate = result;
                    return checkedUpdate;
                }
            case "check-launcher-update":
                if (demo) return DemoData.LauncherVersionStatus();
                using (var launcherCheckTimeout = CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token))
                {
                    launcherCheckTimeout.CancelAfter(TimeSpan.FromSeconds(60));
                    checkedLauncherUpdate = await launcherUpdater.CheckAsync(launcherCheckTimeout.Token);
                    return checkedLauncherUpdate;
                }
            case "install-launcher-update":
                return await RunJobAsync<object>("launcher-update", null, async ct =>
                {
                    if (demo)
                    {
                        foreach (var percent in new[] { 15, 45, 75, 100 })
                        {
                            Post(new { type = "launcher-update-progress", phase = "downloading", downloadedBytes = percent * 1024L, totalBytes = 102400L, percent });
                            await Task.Delay(150, ct);
                        }
                        Post(new { type = "launcher-update-progress", phase = "verifying", percent = 100 });
                        await Task.Delay(250, ct);
                        Post(new { type = "launcher-update-progress", phase = "restarting", percent = 100 });
                        DemoData.UpdateLauncher();
                        return new { demo = true, restarting = false, version = "1.2.0" };
                    }
                    var expected = checkedLauncherUpdate;
                    if (expected?.State != "available" || Text(payload, "version") != expected.LatestVersion ||
                        !string.Equals(Text(payload, "sha256"), expected.Sha256, StringComparison.OrdinalIgnoreCase))
                        throw new InvalidOperationException("请先检查启动器新版，再执行一键更新。");
                    var stage = SelfUpdateCoordinator.CreateStagingDirectory();
                    var downloadComplete = false;
                    var progress = new Progress<LauncherDownloadProgress>(value =>
                    {
                        if (downloadComplete) return;
                        Post(new { type = "launcher-update-progress", phase = "downloading", value.DownloadedBytes, value.TotalBytes,
                            value.Percent, version = expected.LatestVersion });
                    });
                    var download = await launcherUpdater.DownloadAsync(expected, stage, progress, ct);
                    downloadComplete = true;
                    Post(new { type = "launcher-update-progress", phase = "verifying", percent = 100, version = expected.LatestVersion });
                    await SelfUpdateCoordinator.PrepareRestartAsync(download, projectRoot, ct);
                    restartHandoff = true;
                    Post(new { type = "launcher-update-progress", phase = "restarting", percent = 100, version = expected.LatestVersion });
                    AddLog(new(DateTimeOffset.Now.ToString("O"), "info", "新版已校验，更新助手已就绪。正在退出、替换启动器并自动重新打开。"));
                    _ = Dispatcher.BeginInvoke(async () => { await Task.Delay(500); Application.Current.Shutdown(); });
                    return new { restarting = true, version = expected.LatestVersion };
                });
            case "update-source":
                var expectedVersion = Text(payload, "version") ?? throw new InvalidOperationException("请先检查最新版本。");
                var expectedCommit = Text(payload, "commit") ?? throw new InvalidOperationException("缺少已检查的目标版本，请重新检查更新。");
                return await RunJobAsync<object>("update-source", null, async ct =>
                {
                    if (demo)
                    {
                        AddLog(new(DateTimeOffset.Now.ToString("O"), "info", "界面预览：模拟更新源码，真实项目与服务保持不变。"));
                        await Task.Delay(600, ct);
                        DemoData.UpdateSource();
                        return DemoData.VersionStatus();
                    }
                    checkedUpdate = await versionUpdates!.UpdateSourceAsync(expectedVersion, expectedCommit, ct);
                    return checkedUpdate;
                });
            case "open-release":
                if (demo) return true;
                OpenReleaseAddress(checkedUpdate?.ReleaseUrl);
                return true;
            case "open-launcher-release":
                if (demo) return true;
                var launcherRelease = checkedLauncherUpdate?.ReleaseUrl;
                if (launcherRelease is null || !Uri.TryCreate(launcherRelease, UriKind.Absolute, out var launcherUri)
                    || launcherUri.Scheme != "https" || launcherUri.Host != "github.com" || !launcherUri.IsDefaultPort
                    || !string.IsNullOrEmpty(launcherUri.UserInfo)
                    || !launcherUri.AbsolutePath.StartsWith("/" + VersionUpdateService.OfficialRepository + "/releases/tag/", StringComparison.OrdinalIgnoreCase))
                    throw new InvalidOperationException("请先检查启动器版本，再打开已验证的官方发布页面。");
                Process.Start(new ProcessStartInfo(launcherUri.AbsoluteUri) { UseShellExecute = true });
                return true;
            case "open-launcher-download":
                if (demo) return true;
                OpenReleaseAddress(checkedUpdate?.LauncherAssetUrl ?? throw new InvalidOperationException("当前官方发布尚未提供启动器更新包。"));
                return true;
            case "cancel":
                jobCancellation?.Cancel();
                return true;
            default:
                throw new InvalidOperationException("此操作不受支持。");
        }
    }

    private object PublicPreferences() => new { projectRoot, mode = preferences.Mode, theme = preferences.Theme, autoRefresh = preferences.AutoRefresh };

    private async Task<object> GetSnapshotAsync(string mode)
    {
        await inspection.WaitAsync(lifetime.Token);
        try { return demo ? DemoData.Snapshot(mode) : await engine!.GetSnapshotAsync(mode, lifetime.Token); }
        finally { inspection.Release(); }
    }

    private async Task ExecuteOperationAsync(string action, string mode, string? service)
    {
        if (!new[] { "start-all", "stop-all", "restart", "start", "stop", "build", "diagnose", "docker-start" }.Contains(action))
            throw new InvalidOperationException("此服务操作不受支持。");
        await RunJobAsync(action, service, async ct =>
        {
            if (demo)
            {
                AddLog(new(DateTimeOffset.Now.ToString("O"), "info", "界面预览：正在模拟服务操作，不会启动或停止真实服务。"));
                await Task.Delay(600, ct);
                DemoData.Apply(action, mode, service);
            }
            else await engine!.ExecuteAsync(action, mode, service, ct);
            return true;
        });
    }

    private async Task<T> RunJobAsync<T>(string action, string? service, Func<CancellationToken, Task<T>> work)
    {
        if (!await operation.WaitAsync(0)) throw new InvalidOperationException("已有操作正在执行，请等待完成。");
        jobRunning = true;
        jobCancellation = CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token);
        jobCancellation.CancelAfter(TimeSpan.FromMinutes(25));
        Post(new { type = "job", job = new { running = true, action, service, startedAt = DateTimeOffset.Now.ToString("O") } });
        try
        {
            return await work(jobCancellation.Token);
        }
        finally
        {
            jobRunning = false;
            jobCancellation.Dispose();
            jobCancellation = null;
            operation.Release();
            Post(new { type = "job", job = new { running = false, action, service } });
            if (closeAfterJob) Close();
        }
    }

    private void OpenReleaseAddress(string? address)
    {
        if (checkedUpdate is null || address is null || !Uri.TryCreate(address, UriKind.Absolute, out var uri)
            || uri.Scheme != "https" || uri.Host != "github.com" || !uri.IsDefaultPort || !string.IsNullOrEmpty(uri.UserInfo)
            || !uri.AbsolutePath.StartsWith("/" + checkedUpdate.Repository + "/releases/", StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("请先检查版本，再打开已验证的官方发布页面。");
        Process.Start(new ProcessStartInfo(uri.AbsoluteUri) { UseShellExecute = true });
    }

    private async Task OpenTargetAsync(string? target, string mode)
    {
        string address;
        switch (target)
        {
            case "workspace":
                var status = await engine!.GetSnapshotAsync(mode, lifetime.Token);
                address = status.Services.FirstOrDefault(s => s.Id == "frontend")?.Address ?? throw new InvalidOperationException("尚未找到前端地址，请先刷新服务状态。");
                if (!Uri.TryCreate(address, UriKind.Absolute, out var uri) || !uri.IsLoopback || uri.Scheme != "http")
                    throw new InvalidOperationException("工作台地址无效。");
                break;
            case "project": address = projectRoot; break;
            case "logs": address = stateDirectory!; Directory.CreateDirectory(address); break;
            case "docker": address = "docker-desktop://dashboard/containers"; break;
            default: throw new InvalidOperationException("未知打开目标。");
        }
        Process.Start(new ProcessStartInfo(address) { UseShellExecute = true });
    }

    private async Task<object?> ExportDiagnosticsAsync(string mode)
    {
        var snapshot = await engine!.GetSnapshotAsync(mode, lifetime.Token);
        var picker = new SaveFileDialog { Title = "导出脱敏诊断", FileName = $"LearnHub-diagnostics-{DateTime.Now:yyyyMMdd-HHmmss}.json", Filter = "诊断文件 (*.json)|*.json", AddExtension = true };
        if (picker.ShowDialog(this) != true) return null;
        var report = new
        {
            launcherVersion = SelfUpdateCoordinator.CurrentVersion, exportedAt = DateTimeOffset.Now, project = new DirectoryInfo(projectRoot).Name,
            snapshot.Mode, snapshot.CheckedAt, snapshot.Docker, snapshot.Services, snapshot.Diagnostics,
            activity = activity.TakeLast(160).Select(e => new { e.Timestamp, e.Level, message = CleanMessage(e.Message).Replace(projectRoot, "[项目目录]", StringComparison.OrdinalIgnoreCase) }),
        };
        await File.WriteAllTextAsync(picker.FileName, JsonSerializer.Serialize(report, LauncherPreferences.JsonOptions), lifetime.Token);
        return new { saved = true, fileName = Path.GetFileName(picker.FileName) };
    }

    private void AddLog(LauncherLog entry)
    {
        activity.Enqueue(entry);
        while (activity.Count > 600) activity.TryDequeue(out _);
        if (stateDirectory is not null)
        {
            try
            {
                lock (logFileLock)
                {
                    Directory.CreateDirectory(stateDirectory);
                    var path = Path.Combine(stateDirectory, "launcher.log");
                    if (File.Exists(path) && new FileInfo(path).Length > 2 * 1024 * 1024) File.Move(path, path + ".previous", true);
                    File.AppendAllText(path, $"{entry.Timestamp} [{entry.Level}] {entry.Message}{Environment.NewLine}");
                }
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { }
        }
        Dispatcher.BeginInvoke(() => Post(new { type = "log", entry }));
    }

    private static string? Text(JsonElement element, string name) => element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() : null;

    private static string CleanMessage(string message)
    {
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        return string.IsNullOrEmpty(home) ? message : message.Replace(home, "[用户目录]", StringComparison.OrdinalIgnoreCase);
    }

    private void Post(object message)
    {
        if (Browser.CoreWebView2 is null || lifetime.IsCancellationRequested) return;
        Browser.CoreWebView2.PostWebMessageAsJson(JsonSerializer.Serialize(message, LauncherPreferences.JsonOptions));
    }

    private void ApplyNativeTheme()
    {
        var dark = preferences.Theme == "dark" || preferences.Theme == "system" && Registry.GetValue(@"HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize", "AppsUseLightTheme", 1) is int value && value == 0;
        var flag = dark ? 1 : 0;
        var handle = new WindowInteropHelper(this).Handle;
        if (handle != IntPtr.Zero) NativeMethods.DwmSetWindowAttribute(handle, 20, ref flag, sizeof(int));
        Background = new SolidColorBrush(dark ? Color.FromRgb(14, 21, 20) : Color.FromRgb(247, 248, 250));
        if (Browser.CoreWebView2 is not null)
        {
            Browser.CoreWebView2.Profile.PreferredColorScheme = preferences.Theme switch { "dark" => CoreWebView2PreferredColorScheme.Dark, "light" => CoreWebView2PreferredColorScheme.Light, _ => CoreWebView2PreferredColorScheme.Auto };
            Browser.DefaultBackgroundColor = dark ? System.Drawing.Color.FromArgb(14, 21, 20) : System.Drawing.Color.FromArgb(247, 248, 250);
        }
    }

    private void OnSystemPreferenceChanged(object sender, UserPreferenceChangedEventArgs e) => Dispatcher.BeginInvoke(ApplyNativeTheme);
    private void RuntimeLink_Click(object sender, RoutedEventArgs e) => Process.Start(new ProcessStartInfo("https://developer.microsoft.com/microsoft-edge/webview2/") { UseShellExecute = true });

    internal void BringToFront()
    {
        closeAfterJob = false;
        Show();
        if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
        Activate();
        NativeMethods.SetForegroundWindow(new WindowInteropHelper(this).Handle);
    }

    protected override void OnClosing(CancelEventArgs e)
    {
        // Let a running task finish if the user closes the window. Business
        // processes are detached and never tied to the launcher's lifetime.
        if (jobRunning) { e.Cancel = true; closeAfterJob = true; Hide(); return; }
        if (!demo)
        {
            preferences.WindowWidth = RestoreBounds.Width > 0 ? RestoreBounds.Width : Width;
            preferences.WindowHeight = RestoreBounds.Height > 0 ? RestoreBounds.Height : Height;
            preferences.Maximized = WindowState == WindowState.Maximized;
            try { preferences.Save(); } catch (IOException) { }
        }
        lifetime.Cancel();
        SystemEvents.UserPreferenceChanged -= OnSystemPreferenceChanged;
        Browser.Dispose();
        base.OnClosing(e);
    }
}
