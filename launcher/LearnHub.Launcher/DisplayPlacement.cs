using System.Runtime.InteropServices;

namespace LearnHub.Launcher;

internal sealed record MonitorWorkArea(int Left, int Top, int Width, int Height, double Scale)
{
    public double LogicalWidth => Width / Scale;
    public double LogicalHeight => Height / Scale;
}

internal static class DisplayPlacement
{
    // Match the screen the user is working on, rather than always sizing to
    // the primary monitor. All final bounds are placed in physical pixels;
    // WPF/WebView2 then follow PerMonitorV2 as the window moves between screens.
    public static MonitorWorkArea Current()
    {
        GetCursorPos(out var cursor);
        var monitor = MonitorFromPoint(cursor, 2);
        var info = new MonitorInfo { Size = Marshal.SizeOf<MonitorInfo>() };
        if (!GetMonitorInfo(monitor, ref info)) return new(0, 0, 1280, 800, 1);
        var scale = 1d;
        try { if (GetDpiForMonitor(monitor, 0, out var dpiX, out _) == 0) scale = Math.Max(1, dpiX / 96d); }
        catch (EntryPointNotFoundException) { }
        return new(info.Work.Left, info.Work.Top, info.Work.Right - info.Work.Left, info.Work.Bottom - info.Work.Top, scale);
    }

    public static void Center(IntPtr window, MonitorWorkArea screen, double width, double height)
    {
        var pixelsWide = Math.Min(screen.Width, (int)Math.Round(width * screen.Scale));
        var pixelsHigh = Math.Min(screen.Height, (int)Math.Round(height * screen.Scale));
        SetWindowPos(window, IntPtr.Zero, screen.Left + (screen.Width - pixelsWide) / 2,
            screen.Top + (screen.Height - pixelsHigh) / 2, pixelsWide, pixelsHigh, 0x0014);
    }

    [StructLayout(LayoutKind.Sequential)] private struct Point { public int X, Y; }
    [StructLayout(LayoutKind.Sequential)] private struct Rect { public int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential)] private struct MonitorInfo { public int Size; public Rect Monitor, Work; public int Flags; }
    [DllImport("user32.dll")] private static extern bool GetCursorPos(out Point point);
    [DllImport("user32.dll")] private static extern IntPtr MonitorFromPoint(Point point, int flags);
    [DllImport("user32.dll", CharSet = CharSet.Auto)] private static extern bool GetMonitorInfo(IntPtr monitor, ref MonitorInfo info);
    [DllImport("shcore.dll")] private static extern int GetDpiForMonitor(IntPtr monitor, int kind, out uint dpiX, out uint dpiY);
    [DllImport("user32.dll")] private static extern bool SetWindowPos(IntPtr window, IntPtr after, int x, int y, int width, int height, int flags);
}
