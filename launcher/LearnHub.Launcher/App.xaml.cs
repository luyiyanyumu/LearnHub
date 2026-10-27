using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Windows;
using LearnHub.Launcher.Core;
using LearnHub.Launcher.Updating;
using Microsoft.Win32;

namespace LearnHub.Launcher;

public partial class App : Application
{
    private Mutex? instanceMutex;
    private EventWaitHandle? wakeEvent;
    private RegisteredWaitHandle? wakeRegistration;

    protected override async void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        var cli = e.Args.Contains("--status") || e.Args.Contains("--action") || e.Args.Contains("--help")
            || e.Args.Contains("--check-updates") || e.Args.Contains("--update-source") || e.Args.Contains("--check-launcher-update");
        try
        {
            if (e.Args.FirstOrDefault() == "--apply-launcher-update")
            {
                Shutdown(e.Args.Length == 2 ? await UpdateInstaller.RunAsync(e.Args[1]) : 2);
                return;
            }
            var serviceHost = await LauncherProcessHost.TryRunAsync(e.Args);
            if (serviceHost is not null) { Shutdown(serviceHost.Value); return; }

            var recovery = await SelfUpdateCoordinator.IsRecoveryLaunchAsync(Value(e.Args, "--launcher-update-recovery"), Value(e.Args, "--launcher-update-token"));
            if (!recovery && !e.Args.Contains("--launcher-update-ack") && SelfUpdateCoordinator.IsInstallationRunning())
            {
                if (cli) await WriteCliAsync(new { success = false, error = "启动器正在替换并重启，请稍候再试。" }, Value(e.Args, "--output"));
                Shutdown(cli ? 1 : 0);
                return;
            }

            var preferences = LauncherPreferences.Load();
            if (cli) { Shutdown(await RunCommandAsync(e.Args, preferences)); return; }

            var demo = e.Args.Contains("--demo");
            var instanceSuffix = demo ? ".Demo" : "";
            wakeEvent = new EventWaitHandle(false, EventResetMode.AutoReset, @"Local\LearnHub.Launcher.Wake" + instanceSuffix);
            instanceMutex = new Mutex(true, @"Local\LearnHub.Launcher.ControlCenter" + instanceSuffix, out var firstInstance);
            if (!firstInstance)
            {
                // A hidden window has no Process.MainWindowHandle. Signal the
                // owning dispatcher as well, so a background task can be reopened.
                wakeEvent.Set();
                Shutdown();
                return;
            }
            wakeRegistration = ThreadPool.RegisterWaitForSingleObject(wakeEvent,
                (_, _) => Dispatcher.BeginInvoke(() => { if (MainWindow is MainWindow window) window.BringToFront(); }),
                null, Timeout.Infinite, false);

            var root = demo ? @"C:\Demo\LearnHub" : ProjectLocator.Find(Value(e.Args, "--project"), preferences);
            if (root is null)
            {
                var picker = new OpenFolderDialog { Title = "选择 LearnHub 项目根目录", Multiselect = false };
                if (picker.ShowDialog() != true) { Shutdown(); return; }
                root = ProjectLocator.Find(picker.FolderName, preferences)!;
            }
            if (!demo) { preferences.ProjectRoot = root; preferences.Save(); }
            var window = new MainWindow(root, preferences, demo, Value(e.Args, "--launcher-update-ack"), Value(e.Args, "--launcher-update-token"));
            MainWindow = window;
            ShutdownMode = ShutdownMode.OnMainWindowClose;
            window.Show();
        }
        catch (Exception ex)
        {
            if (cli) await WriteCliAsync(new { success = false, error = ex.Message }, Value(e.Args, "--output"));
            else MessageBox.Show(ex.Message, "LearnHub 启动器", MessageBoxButton.OK, MessageBoxImage.Error);
            Shutdown(1);
        }
    }

    private static async Task<int> RunCommandAsync(string[] args, LauncherPreferences preferences)
    {
        if (args.Contains("--help"))
        {
            await WriteCliAsync(new { usage = "LearnHub.exe [--project PATH] [--status | --action start-all|stop-all|start|stop|restart|build|diagnose|docker-start | --check-updates | --update-source --version VERSION --commit SHA | --check-launcher-update] [--service frontend|backend|mysql] [--mode docker|local] [--output FILE]", gui = "LearnHub.exe [--project PATH] [--demo]" }, Value(args, "--output"));
            return 0;
        }
        if (args.Contains("--check-launcher-update"))
        {
            using var checkTimeout = new CancellationTokenSource(TimeSpan.FromSeconds(60));
            var updater = new LauncherSelfUpdateClient(SelfUpdateCoordinator.CurrentVersion, SelfUpdateCoordinator.Runtime, _ => { });
            await WriteCliAsync(new { success = true, launcherUpdate = await updater.CheckAsync(checkTimeout.Token) }, Value(args, "--output"));
            return 0;
        }
        var root = ProjectLocator.Find(Value(args, "--project"), preferences) ?? throw new InvalidOperationException("未找到 LearnHub 项目，请用 --project 指定项目根目录。");
        var mode = Value(args, "--mode") ?? preferences.Mode;
        if (mode is not ("docker" or "local")) throw new InvalidOperationException("模式必须为 docker 或 local。");
        using var cancellation = new CancellationTokenSource(TimeSpan.FromMinutes(25));
        var activity = new List<LauncherLog>();
        if (args.Contains("--check-updates") || args.Contains("--update-source"))
        {
            var versions = new VersionUpdateService(root, entry => { lock (activity) activity.Add(entry); });
            var update = args.Contains("--update-source")
                ? await versions.UpdateSourceAsync(Value(args, "--version") ?? throw new ArgumentException("缺少 --version 参数。"), Value(args, "--commit") ?? throw new ArgumentException("缺少 --commit 参数。"), cancellation.Token)
                : await versions.CheckAsync(cancellation.Token);
            await WriteCliAsync(new { success = true, update, activity }, Value(args, "--output"));
            return 0;
        }
        var engine = new LauncherEngine(root, entry => { lock (activity) activity.Add(entry); });
        var action = Value(args, "--action");
        if (action is not null) await engine.ExecuteAsync(action, mode, Value(args, "--service"), cancellation.Token);
        var snapshot = await engine.GetSnapshotAsync(mode, cancellation.Token);
        await WriteCliAsync(new { success = true, snapshot, activity }, Value(args, "--output"));
        return 0;
    }

    private static async Task WriteCliAsync(object value, string? output)
    {
        var json = JsonSerializer.Serialize(value, LauncherPreferences.JsonOptions);
        if (output is not null)
        {
            var path = Path.GetFullPath(output);
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            await File.WriteAllTextAsync(path, json, new UTF8Encoding(false));
        }
        else
        {
            // WinExe still has usable redirected stdout for automation.
            await using var stream = Console.OpenStandardOutput();
            await stream.WriteAsync(Encoding.UTF8.GetBytes(json + Environment.NewLine));
        }
    }

    internal static string? Value(string[] args, string option)
    {
        var index = Array.IndexOf(args, option);
        if (index < 0) return null;
        if (index + 1 >= args.Length || args[index + 1].StartsWith("--", StringComparison.Ordinal)) throw new ArgumentException($"{option} 缺少参数。");
        return args[index + 1];
    }

    protected override void OnExit(ExitEventArgs e)
    {
        instanceMutex?.Dispose();
        wakeRegistration?.Unregister(null);
        wakeEvent?.Dispose();
        base.OnExit(e);
    }
}

internal static partial class NativeMethods
{
    [DllImport("user32.dll")] internal static extern bool SetForegroundWindow(IntPtr hwnd);
    [DllImport("user32.dll")] internal static extern bool ShowWindow(IntPtr hwnd, int command);
    [DllImport("dwmapi.dll")] internal static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);
}
