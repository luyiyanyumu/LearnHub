# Launcher implementation contract

The Windows host uses a local embedded Vue / Element Plus UI. JSON uses camelCase.
Only this project's Compose services or verified local processes may be changed.

## Core API

Namespace `LearnHub.Launcher.Core`, target `net10.0`.

`LauncherEngine(string projectRoot, Action<LauncherLog> log)` exposes:

- `Task<LauncherSnapshot> GetSnapshotAsync(string mode, CancellationToken ct)`
- `Task ExecuteAsync(string action, string mode, string? service, CancellationToken ct)`
- `Task<string> GetLogsAsync(string mode, string service, int tail, CancellationToken ct)`
- `string ProjectRoot { get; }`

Public immutable records/classes serialize to the following shapes:

```json
{
  "projectRoot": "C:\\Projects\\LearnHub",
  "mode": "docker",
  "checkedAt": "2026-10-26T10:00:00+08:00",
  "docker": { "installed": true, "ready": true, "version": "28.0", "context": "desktop-linux", "error": null },
  "services": [
    { "id": "frontend", "name": "前端服务", "kind": "container", "state": "healthy", "detail": "页面可访问", "port": 8888, "address": "http://localhost:8888", "containerId": null, "containerName": null, "image": null, "uptime": null, "responseMs": 12, "processId": null, "canStart": true, "canStop": true, "canRestart": true }
  ],
  "diagnostics": [{ "level": "success", "title": "服务可用", "detail": "健康检查已通过" }]
}
```

Types: `LauncherLog(string Timestamp, string Level, string Message)`;
`DockerStatus(bool Installed, bool Ready, string? Version, string? Context, string? Error)`;
`ServiceStatus` with the listed fields; `DiagnosticItem(string Level, string Title, string Detail)`;
`LauncherSnapshot(string ProjectRoot, string Mode, string CheckedAt, DockerStatus Docker, IReadOnlyList<ServiceStatus> Services, IReadOnlyList<DiagnosticItem> Diagnostics)`.

`mode`: `docker` or `local`. `state`: `healthy`, `running`, `starting`, `stopped`, `unhealthy`, `conflict`, `unknown`, `missing`.
Actions: `start-all`, `stop-all`, `restart`, `start`, `stop`, `build`, `diagnose`, `docker-start`.
Service IDs: `frontend`, `backend`, `mysql`, plus discovered project services.
`build` builds/recreates only frontend/backend in Docker mode; `stop-all` stops services and preserves containers/volumes.
No destructive data-volume actions, arbitrary commands, or automatic source-code updates.

## UI / native bridge

UI sends `window.chrome.webview.postMessage({ id, command, payload })`.
Host replies `{ id, result }` or `{ id, error }`; unsolicited events are
`{ type: 'log', entry: LauncherLog }` and `{ type: 'job', job: { running, action, service, startedAt } }`.
Launcher self-update uses job action `launcher-update` and progress events
`{ type: 'launcher-update-progress', phase, downloadedBytes?, totalBytes?, percent, version? }`.
Phases are `downloading`, `verifying`, `restarting`; only `downloading` is cancellable.

Commands:

- `bootstrap`: result `{ preferences: { projectRoot, mode, theme, autoRefresh }, display: { scale, width, height }, version, updateNotice? }`. `version` is the launcher version (currently `1.1.0`); optional `updateNotice` reports an update failure/recovery message.
- `snapshot`: payload `{ mode }`, returns core snapshot.
- `action`: payload `{ action, mode, service }`, resolves after completion. Native host rejects duplicate mutations.
- `logs`: payload `{ mode, service, tail }`, result string (redacted).
- `preferences`: payload `{ mode?, theme?, autoRefresh? }`, returns preferences.
- `select-project`: native folder picker, returns preferences or null if cancelled.
- `open`: payload `{ target: 'workspace' | 'project' | 'logs' | 'docker' }`, returns true.
- `export-diagnostics`: native save dialog, exports sanitized status + launcher activity, no env files or document contents.
- `check-updates`: manually reads local Git/manifest state and the official stable release; returns `VersionUpdateStatus`. No source or service changes.
- `check-launcher-update`: manually reads the official architecture-specific launcher channel; returns `LauncherUpdateInfo`. Independent of project Git status and source-update checks.
- `install-launcher-update`: payload `{ version, sha256 }` must match the retained available check result. Rechecks the channel, downloads and validates the pinned asset, then hands installation to a trusted helper. Shares the native job lock; returns `{ restarting: true, version }` before the current UI exits. The helper determines success through the relaunched UI acknowledgement, not this response.
- `update-source`: payload `{ version, commit }` from the checked release; shares the service-operation lock and job/cancel events. Rechecks local edits, branch and pinned target, then permits only a fast-forward source update. Returns the updated version status; running services remain unchanged.
- `open-release`, `open-launcher-release`: opens only the source or launcher release URL retained by the corresponding native version check. No arbitrary payload URL is used. The older `open-launcher-download` command remains a manual-download compatibility entry.
- `cancel`: cancels the running task, returns true (services already started remain visible in status).

`VersionUpdateService.CheckAsync(ct)` and `UpdateSourceAsync(expectedVersion, expectedCommit, ct)` return
`VersionUpdateStatus`: `currentVersion`, `sourceDescription`, `currentCommit`, `branch`, `dirty`,
`changeCount`, `latestVersion`, `latestCommit`, `releaseName`, `releaseNotes`, `releaseUrl`, `publishedAt`,
`canUpdate`, `updateAvailable`, `blockedReason`, `checkedAt`, `repository`, `launcherAssetUrl`.
Release notes are plain text. Project and launcher versions are independent. Checks are manual;
source updates require a separate explicit build/apply action to change running services.

## Independent launcher update channel

`LauncherUpdateInfo` fields: `currentVersion`, `latestVersion`, `state`, `detail`, `releaseUrl`,
`downloadUrl`, `sha256`, `size`, `runtime`, `checkedAt`. States are `available`, `current`,
`unavailable`; network or invalid-manifest failures reject the request.

Channel URL: `https://github.com/luyiyanyumu/LearnHub/releases/download/launcher-updates/launcher-update-<runtime>.json`,
where runtime is `win-x64` or `win-arm64`. Manifest schema 1 contains `schemaVersion`, `version`,
`runtime`, `url`, `sha256`, `size`, `releaseUrl`. A missing official manifest/package is shown explicitly;
the feature does not imply that a public channel has already been published.

Downloads stream to staging and must pass size, SHA-256, PE architecture and embedded-version checks.
The native host copies its current trusted EXE as the helper and waits for helper readiness before exiting.
The helper verifies parent identity, waits for its exit, atomically replaces the target while retaining a backup,
and launches the normal UI. Only the matching UI acknowledgement completes installation; failed startup or
missing acknowledgement triggers rollback and reopening the old launcher. Business services keep running;
local service hosts run from cached EXE copies so they do not hold the replacement target open.

`--check-launcher-update` checks this channel without requiring `--project` or Git and supports `--output`.
Build output includes `LearnHub-<runtime>.exe` and `launcher-update-<runtime>.json`.
`tools/build-launcher.ps1 -ReleaseTag TAG` selects the versioned asset tag (default `launcher-v<version>`).
Publish the EXE to that tag and its manifest to the fixed `launcher-updates` tag; the build never publishes.
Mark launcher-only and channel releases as GitHub prereleases so product-source `/releases/latest`
remains independent. The manifest's stable version controls launcher update eligibility.

The UI shows an explicit demo banner and synthetic fixtures when opened outside the native host.
This permits layout/state checks without changing services. Native `--demo` uses the same synthetic data and never calls Docker.
