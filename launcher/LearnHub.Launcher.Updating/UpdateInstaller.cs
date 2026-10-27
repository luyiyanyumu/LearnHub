using System.Text.Json;

namespace LearnHub.Launcher.Updating;

public static class UpdateInstaller
{
    public static Task<int> RunAsync(string planPath, CancellationToken cancellationToken = default) =>
        RunAsync(planPath, new SystemUpdateRuntime(), null, cancellationToken);

    public static async Task<int> RunAsync(string planPath, IUpdateRuntime runtime, UpdateInstallerOptions? options = null,
        CancellationToken cancellationToken = default)
    {
        options ??= new UpdateInstallerOptions();
        ValidatedUpdatePlan? validated = null;
        UpdateProcess? launched = null;
        bool replaced = false;
        bool rolledBack = false;
        bool createdTemporary = false;
        bool parentExited = false;
        try
        {
            if (options.PollInterval <= TimeSpan.Zero || options.ParentExitTimeout < TimeSpan.Zero || options.AcknowledgementTimeout < TimeSpan.Zero
                || options.StabilityPeriod < TimeSpan.Zero || options.FileRetryTimeout < TimeSpan.Zero)
                throw new ArgumentOutOfRangeException(nameof(options));
            validated = await UpdateProtocol.ReadAsync(planPath, cancellationToken);
            var plan = validated.Plan;
            if (!UpdateProtocol.SamePath(runtime.CurrentExecutablePath, plan.HelperPath) || runtime.CurrentProcessId == plan.ParentPid)
                throw new InvalidDataException("更新辅助进程身份不匹配。");
            await UpdateProtocol.RequireHashAsync(plan.HelperPath, plan.ExpectedOldSha256, cancellationToken);
            await UpdateProtocol.RequireHashAsync(plan.TargetPath, plan.ExpectedOldSha256, cancellationToken);
            await UpdateProtocol.RequireHashAsync(plan.StagedPath, plan.ExpectedNewSha256, cancellationToken);
            if (UpdateProtocol.SameHash(plan.ExpectedOldSha256, plan.ExpectedNewSha256))
                throw new InvalidDataException("新版启动器与当前文件相同。");
            if (File.Exists(validated.AcknowledgementPath) || File.Exists(validated.ReadyPath) || File.Exists(validated.TemporaryPath) || File.Exists(validated.BackupPath) || File.Exists(validated.FailedPath))
                throw new InvalidDataException("本次更新已执行或更新目录存在冲突。");
            // This exact file probes destination write access before the parent exits.
            await using (var probe = new FileStream(validated.TemporaryPath, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                createdTemporary = true;
                await probe.FlushAsync(cancellationToken);
            }
            File.Delete(validated.TemporaryPath);
            createdTemporary = false;
            await UpdateProtocol.AtomicWriteAsync(validated.ReadyPath,
                new UpdateReady(plan.Token, runtime.CurrentProcessId, runtime.CurrentProcessStartTimeUtcTicks), cancellationToken);
            await WaitForParentExitAsync(plan, runtime, options, cancellationToken);
            parentExited = true;
            await UpdateProtocol.RequireHashAsync(plan.TargetPath, plan.ExpectedOldSha256, cancellationToken);
            await UpdateProtocol.RequireHashAsync(plan.StagedPath, plan.ExpectedNewSha256, cancellationToken);
            // Copy beside the destination so replacement stays on the same volume.
            File.Copy(plan.StagedPath, validated.TemporaryPath, overwrite: false);
            createdTemporary = true;
            await UpdateProtocol.RequireHashAsync(validated.TemporaryPath, plan.ExpectedNewSha256, cancellationToken);
            await RetryFileAsync(async () =>
            {
                await UpdateProtocol.RequireHashAsync(plan.TargetPath, plan.ExpectedOldSha256, cancellationToken);
                await UpdateProtocol.RequireHashAsync(validated.TemporaryPath, plan.ExpectedNewSha256, cancellationToken);
                File.Replace(validated.TemporaryPath, plan.TargetPath, validated.BackupPath, ignoreMetadataErrors: false);
            },
                runtime, options, cancellationToken);
            replaced = true;
            await UpdateProtocol.RequireHashAsync(validated.BackupPath, plan.ExpectedOldSha256, cancellationToken);
            await UpdateProtocol.RequireHashAsync(plan.TargetPath, plan.ExpectedNewSha256, cancellationToken);
            var arguments = new List<string> { "--launcher-update-ack", validated.PlanPath, "--launcher-update-token", plan.Token, "--project", plan.ProjectRoot };
            if (plan.Demo) arguments.Add("--demo");
            launched = runtime.Start(plan.TargetPath, arguments, plan.ProjectRoot);
            await WaitForAcknowledgementAsync(validated, launched, runtime, options, cancellationToken);
            await WriteResultAsync(validated, true, false, "启动器已更新并重新打开。", launched.Id);
            return 0;
        }
        catch (Exception error)
        {
            string message = SafeFailure(error);
            // A filesystem may report an error after completing the atomic replacement.
            // Recognize only our expected two hashes before attempting recovery.
            if (validated is not null && parentExited && !replaced && File.Exists(validated.BackupPath))
            {
                try
                {
                    await UpdateProtocol.RequireHashAsync(validated.Plan.TargetPath, validated.Plan.ExpectedNewSha256, CancellationToken.None);
                    await UpdateProtocol.RequireHashAsync(validated.BackupPath, validated.Plan.ExpectedOldSha256, CancellationToken.None);
                    replaced = true;
                }
                catch { }
            }
            if (validated is not null && replaced)
            {
                try
                {
                    if (launched is not null) await runtime.StopOwnedProcessAsync(launched, CancellationToken.None);
                    if (launched is not null && runtime.IsProcessRunning(launched)) throw new IOException("新版启动器仍占用目标文件。");
                    var plan = validated.Plan;
                    await UpdateProtocol.RequireHashAsync(plan.TargetPath, plan.ExpectedNewSha256, CancellationToken.None);
                    await UpdateProtocol.RequireHashAsync(validated.BackupPath, plan.ExpectedOldSha256, CancellationToken.None);
                    await RetryFileAsync(async () =>
                    {
                        await UpdateProtocol.RequireHashAsync(plan.TargetPath, plan.ExpectedNewSha256, CancellationToken.None);
                        await UpdateProtocol.RequireHashAsync(validated.BackupPath, plan.ExpectedOldSha256, CancellationToken.None);
                        File.Replace(validated.BackupPath, plan.TargetPath, validated.FailedPath, ignoreMetadataErrors: false);
                    },
                        runtime, options, CancellationToken.None);
                    rolledBack = true;
                    await UpdateProtocol.RequireHashAsync(plan.TargetPath, plan.ExpectedOldSha256, CancellationToken.None);
                    launched = RestartOriginal(validated, runtime);
                    message += " 已恢复并重新打开原启动器。";
                }
                catch
                {
                    message += rolledBack ? " 原启动器已恢复，但未能自动重新打开，请双击启动。" : " 自动恢复未完成；备份已保留，请检查更新结果。";
                }
            }
            else if (validated is not null)
            {
                message += " 原启动器文件保持不变。";
                if (parentExited)
                {
                    try { launched = RestartOriginal(validated, runtime); message += " 已重新打开原启动器。"; }
                    catch { message += " 请双击原启动器重新打开。"; }
                }
            }
            if (validated is not null)
            {
                try { await WriteResultAsync(validated, false, rolledBack, message, launched?.Id); } catch { /* Never endanger the backup to write a report. */ }
            }
            return replaced ? rolledBack ? 2 : 3 : 1;
        }
        finally
        {
            // These exact files were created by this attempt; never remove a directory or old backup.
            if (createdTemporary && validated is not null && File.Exists(validated.TemporaryPath))
            {
                try { File.Delete(validated.TemporaryPath); } catch { }
            }
        }
    }

    private static UpdateProcess RestartOriginal(ValidatedUpdatePlan value, IUpdateRuntime runtime)
    {
        var arguments = new List<string> { "--launcher-update-recovery", value.PlanPath, "--launcher-update-token", value.Plan.Token, "--project", value.Plan.ProjectRoot };
        if (value.Plan.Demo) arguments.Add("--demo");
        return runtime.Start(value.Plan.TargetPath, arguments, value.Plan.ProjectRoot);
    }

    private static async Task WaitForParentExitAsync(LauncherUpdatePlan plan, IUpdateRuntime runtime, UpdateInstallerOptions options, CancellationToken ct)
    {
        var parent = new UpdateProcess(plan.ParentPid, plan.ParentStartTimeUtcTicks);
        var start = runtime.MonotonicMilliseconds;
        while (runtime.IsProcessRunning(parent))
        {
            ct.ThrowIfCancellationRequested();
            if (runtime.MonotonicMilliseconds - start >= options.ParentExitTimeout.TotalMilliseconds)
                throw new TimeoutException("原启动器未能及时退出，更新已取消。");
            await runtime.DelayAsync(options.PollInterval, ct);
        }
    }

    private static async Task WaitForAcknowledgementAsync(ValidatedUpdatePlan value, UpdateProcess launched, IUpdateRuntime runtime,
        UpdateInstallerOptions options, CancellationToken ct)
    {
        var start = runtime.MonotonicMilliseconds;
        long? acknowledgedAt = null;
        while (true)
        {
            ct.ThrowIfCancellationRequested();
            if (!runtime.IsProcessRunning(launched)) throw new IOException("新版启动器在启动验证时退出。");
            if (File.Exists(value.AcknowledgementPath))
            {
                UpdateProtocol.RejectReparsePoints(value.AcknowledgementPath);
                if (new FileInfo(value.AcknowledgementPath).Length > 8192) throw new InvalidDataException("新版启动器握手格式无效。");
                var ack = JsonSerializer.Deserialize<UpdateAcknowledgement>(await File.ReadAllTextAsync(value.AcknowledgementPath, ct), UpdateProtocol.Json);
                if (ack is null || !string.Equals(ack.Token, value.Plan.Token, StringComparison.Ordinal) || ack.ProcessId != launched.Id
                    || ack.StartTimeUtcTicks != launched.StartTimeUtcTicks || !UpdateProtocol.SameHash(ack.Sha256, value.Plan.ExpectedNewSha256))
                    throw new InvalidDataException("新版启动器握手身份不匹配。");
                acknowledgedAt ??= runtime.MonotonicMilliseconds;
                if (runtime.MonotonicMilliseconds - acknowledgedAt >= options.StabilityPeriod.TotalMilliseconds)
                {
                    await UpdateProtocol.RequireHashAsync(value.Plan.TargetPath, value.Plan.ExpectedNewSha256, ct);
                    return;
                }
            }
            if (runtime.MonotonicMilliseconds - start >= options.AcknowledgementTimeout.TotalMilliseconds)
                throw new TimeoutException("新版启动器未通过启动验证。");
            await runtime.DelayAsync(options.PollInterval, ct);
        }
    }

    private static async Task RetryFileAsync(Func<Task> action, IUpdateRuntime runtime, UpdateInstallerOptions options, CancellationToken ct)
    {
        var start = runtime.MonotonicMilliseconds;
        while (true)
        {
            ct.ThrowIfCancellationRequested();
            try { await action(); return; }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                if (runtime.MonotonicMilliseconds - start >= options.FileRetryTimeout.TotalMilliseconds) throw;
                await runtime.DelayAsync(options.PollInterval, ct);
            }
        }
    }

    private static Task WriteResultAsync(ValidatedUpdatePlan value, bool success, bool rolledBack, string message, int? pid) =>
        UpdateProtocol.AtomicWriteAsync(value.Plan.ResultPath,
            new LauncherUpdateResult(success, rolledBack, message, value.Plan.Version, value.Plan.TargetPath,
                File.Exists(value.BackupPath) ? value.BackupPath : null, DateTimeOffset.UtcNow.ToString("O"), pid), CancellationToken.None);

    private static string SafeFailure(Exception error) => error switch
    {
        OperationCanceledException => "启动器更新已取消。",
        TimeoutException => error.Message,
        InvalidDataException => error.Message,
        _ => "启动器更新失败。"
    };
}
