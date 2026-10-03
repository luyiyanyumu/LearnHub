@echo off
chcp 65001 >nul
title learn-hub update
cd /d "%~dp0"
setlocal

REM ============================================================
REM  learn-hub source updater (GitHub)
REM
REM    default     : fetch -> conflict check -> pull -> rebuild+restart -> open UI
REM    pull        : update source only, leave containers alone
REM    rebuild     : no pull; rebuild images and restart containers (after local code changes)
REM    force       : when local files collide with remote (modified or untracked same-name files),
REM                  back up to ..\_learnhub-backup\<timestamp> then overwrite with the remote version
REM    nobrowser   : do not open browser
REM    nopause     : no pause at exit (for scripting)
REM
REM    Safety:
REM      * fast-forward only (--ff-only): no merge commits, no lost commits;
REM      * deploy\.env is gitignored; this script never touches it;
REM      * connectivity precheck only for http/https remotes (local/mirror remotes are skipped);
REM      * GitHub resets are common; network failures retry 3 times.
REM ============================================================

set "DO_PULL=1"
set "DO_BUILD=1"
set "OPEN=1"
set "FORCE=0"
set "NOPAUSE=0"
set "SOME_FAIL="
set "BRANCH=master"
set "OLD_SHA="
set "CUR_SHA="
set "HOST="

if not "%~1"=="" for %%a in (%*) do (
    if /i "%%a"=="pull"      set "DO_BUILD=0"
    if /i "%%a"=="rebuild"   set "DO_PULL=0"
    if /i "%%a"=="nobrowser" set "OPEN=0"
    if /i "%%a"=="force"     set "FORCE=1"
    if /i "%%a"=="nopause"   set "NOPAUSE=1"
)

echo ============================================
echo   learn-hub source updater (GitHub)
echo ============================================
echo.

where git >nul 2>&1
if errorlevel 1 goto :no_git
git rev-parse --git-dir >nul 2>&1
if errorlevel 1 goto :no_repo

for /f "delims=" %%b in ('git rev-parse --abbrev-ref HEAD 2^>nul') do set "BRANCH=%%b"
for /f "delims=" %%u in ('git remote get-url origin 2^>nul') do set "REMOTE_URL=%%u"
echo   remote: %REMOTE_URL%
echo   branch: %BRANCH%
echo.

if "%DO_PULL%"=="0" goto :step_build

REM ---------- connectivity precheck (http/https remotes only)----------
if not "%REMOTE_URL%"=="" for /f "tokens=1,2 delims=/: " %%a in ("%REMOTE_URL%") do (
    if /i "%%a"=="https" set "HOST=%%b"
    if /i "%%a"=="http"  set "HOST=%%b"
)
if "%HOST%"=="" goto :net_skip
echo ==^> checking %HOST%:443 (up to 6s)
set "NET_OK="
for /f "delims=" %%r in ('powershell -NoProfile -Command "try{$c=New-Object System.Net.Sockets.TcpClient;$t=$c.BeginConnect('%HOST%',443,$null,$null);if($t.AsyncWaitHandle.WaitOne(6000)){if($c.Connected){'OK'}else{'NO'}}else{'NO'}}catch{'NO'}" 2^>nul') do set "NET_OK=%%r"
if not "%NET_OK%"=="OK" goto :net_fail
echo   reachable
echo.
:net_skip

REM ---------- fetch remote info ----------
echo ==^> fetching remote info (3 retries on network failure)
set /a fetch_try=0
:fetch_retry
set /a fetch_try+=1
echo   fetch attempt %fetch_try%...
git -c http.lowSpeedLimit=1000 -c http.lowSpeedTime=30 fetch --prune > "%TEMP%\lh_fetch.txt" 2>&1
if not errorlevel 1 goto :fetch_ok
if %fetch_try% geq 3 goto :fetch_fail
echo   failed; retry in 4s (GitHub resets are common in CN)
ping -n 5 127.0.0.1 >nul
goto :fetch_retry

:fetch_ok
type "%TEMP%\lh_fetch.txt"
for /f "delims=" %%s in ('git rev-parse --short HEAD') do set "OLD_SHA=%%s"
for /f "delims=" %%s in ('git rev-parse --short origin/%BRANCH%') do set "NEW_SHA=%%s"
for /f "delims=" %%c in ('git rev-list --count HEAD..origin/%BRANCH%') do set "BEHIND=%%c"
for /f "delims=" %%c in ('git rev-list --count origin/%BRANCH%..HEAD') do set "AHEAD=%%c"
echo   local %OLD_SHA%    remote %NEW_SHA%    (behind %BEHIND%, ahead %AHEAD%)
echo.

if not "%AHEAD%"=="0" goto :diverged
if "%BEHIND%"=="0" goto :up_to_date

REM ---------- conflict check (tracked changes + untracked files, via PowerShell helper)----------
echo ==^> checking whether local files block this update
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\check-update-conflicts.ps1" -Branch "%BRANCH%" > "%TEMP%\lh_conflict.txt" 2>nul
findstr /i /c:"__NO_CONFLICT__" "%TEMP%\lh_conflict.txt" >nul
if errorlevel 1 goto :conflict
echo   no conflict; fast-forward is safe
echo.

REM ---------- pull ----------
:pull_now
echo ==^> pulling new code (--ff-only, 3 retries on network failure)
set /a pull_try=0
:pull_retry
set /a pull_try+=1
echo   pull attempt %pull_try%...
git -c http.lowSpeedLimit=1000 -c http.lowSpeedTime=30 pull --ff-only > "%TEMP%\lh_pull.txt" 2>&1
if not errorlevel 1 goto :pull_ok
findstr /i /c:"would be overwritten" "%TEMP%\lh_pull.txt" >nul
if not errorlevel 1 goto :pull_blocked
if %pull_try% geq 3 goto :pull_fail
echo   failed; retry in 4s
ping -n 5 127.0.0.1 >nul
goto :pull_retry

:pull_ok
type "%TEMP%\lh_pull.txt"
for /f "delims=" %%s in ('git rev-parse --short HEAD') do set "CUR_SHA=%%s"
echo   updated: %OLD_SHA% ^-^> %CUR_SHA%
echo.
echo   commits in this update:
git log --oneline %OLD_SHA%..%CUR_SHA%
echo.
echo   changed files:
git diff --name-only %OLD_SHA% %CUR_SHA% > "%TEMP%\lh_changed.txt"
git diff --stat %OLD_SHA% %CUR_SHA%
findstr /i /c:"deploy/.env.example" "%TEMP%\lh_changed.txt" >nul
if not errorlevel 1 echo.
if not errorlevel 1 echo   [NOTE] deploy\.env.example changed - compare with your deploy\.env manually
findstr /i /c:"deploy/docker-compose.yml" "%TEMP%\lh_changed.txt" >nul
if not errorlevel 1 echo   [NOTE] docker-compose.yml changed - images will be rebuilt
echo.

REM ---------- rebuild and restart ----------
:step_build
if "%DO_BUILD%"=="0" goto :done_ok
echo ==^> rebuilding images and restarting containers (1-3 min on first run or changes)
call "%~dp0start-all.bat" build nobrowser nopause
if errorlevel 1 goto :build_fail

echo.
echo ==^> checking service status
docker ps --filter name=learn-hub --format "    {{.Names}}  {{.Status}}"
netstat -ano | findstr /r /c:":8888 .*LISTENING" >nul
if errorlevel 1 goto :web_down
if "%OPEN%"=="1" start "" http://localhost:8888
if "%OPEN%"=="1" echo   opened in browser: http://localhost:8888
goto :done_ok

REM ================= summary =================
:done_ok
echo.
echo ============================================
if "%OLD_SHA%"=="" goto :done_latest
if /i "%OLD_SHA%"=="%CUR_SHA%" goto :done_none
echo   source updated: %OLD_SHA% ^-^> %CUR_SHA%
goto :done_tail
:done_none
echo   source is up to date (%CUR_SHA%); no new commits
goto :done_tail
:done_latest
echo   source is up to date (%CUR_SHA%)
:done_tail
if "%DO_BUILD%"=="1" echo   UI: http://localhost:8888
echo   optional: docker image prune -f
echo ============================================
goto :end

REM ================= branches =================
:up_to_date
set "CUR_SHA=%OLD_SHA%"
echo   already up to date (%OLD_SHA%); no new commits, no rebuild needed.
echo   to rebuild images after local code changes: update.bat rebuild
goto :done_ok

:conflict
echo   these local files block the update (remote changes them too):
type "%TEMP%\lh_conflict.txt"
if "%FORCE%"=="0" goto :conflict_stop
echo.
echo   force mode: back up first, then overwrite with remote version
set "STAMP="
for /f "delims=" %%t in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set "STAMP=%%t"
set "BK=%~dp0..\_learnhub-backup\%STAMP%"
mkdir "%BK%" 2>nul
for /f "usebackq delims=" %%f in ("%TEMP%\lh_conflict.txt") do (
    if not "%%~pf"=="." mkdir "%BK%\%%~pf" 2>nul
    copy /y "%%f" "%BK%\%%~pf" >nul
    echo     backup %%f
)
echo   backup dir: %BK%
for /f "usebackq delims=" %%f in ("%TEMP%\lh_conflict.txt") do (
    git ls-files --error-unmatch "%%f" >nul 2>&1
    if errorlevel 1 (del /q "%%f") else (git checkout -- "%%f")
)
echo   overwritten with remote version; continuing pull
echo.
goto :pull_now

:conflict_stop
echo.
echo   How to fix (pick one):
echo     1) auto backup + overwrite: update.bat force
echo     2) commit / save-as / delete these files, then re-run update.bat
goto :fail

:net_fail
echo   [ERROR] cannot reach %HOST%:443 (no connection in 6s)
echo   network/proxy issue, not a script or repo problem. Options:
call :show_proxy_hint
echo     2) use a CN mirror: git remote set-url origin <mirror-url>
echo     3) retry in a few minutes (GitHub resets are intermittent)
goto :fail

:fetch_fail
echo   [ERROR] cannot reach GitHub after 3 tries. git output:
type "%TEMP%\lh_fetch.txt"
echo   Options:
call :show_proxy_hint
echo     2) use a CN mirror: git remote set-url origin <mirror-url>
echo     3) retry in a few minutes (GitHub resets are intermittent)
goto :fail

:pull_blocked
echo   [ERROR] git refused to pull: local files would be overwritten
type "%TEMP%\lh_pull.txt"
echo.
echo   How to fix:
echo     1) update.bat force   back these files up, then overwrite with the remote version
echo     2) move them away or commit, then retry
goto :fail

:pull_fail
echo   [ERROR] pull failed after 3 tries. git output:
type "%TEMP%\lh_pull.txt"
goto :fail

:no_git
echo   [ERROR] git not found. Install Git for Windows
goto :fail

:no_repo
echo   [ERROR] not a git repo: %CD%
echo   put this script in the root of the cloned repo
goto :fail

:diverged
echo   [STOP] %AHEAD% local commits not pushed; cannot fast-forward.
echo   inspect first: git log origin/%BRANCH%..HEAD
echo   then re-run this script after push / rebase / discard.
goto :fail

:build_fail
echo   [ERROR] image rebuild failed, see start-all.bat output
goto :fail

:web_down
echo   [ERROR] port 8888 not listening, skip opening browser
echo   log: docker logs learn-hub-frontend
goto :fail

:fail
set "SOME_FAIL=1"
echo.
echo ============================================
echo   not finished; fix per messages above and retry.
echo ============================================

:end
del "%TEMP%\lh_fetch.txt" "%TEMP%\lh_pull.txt" "%TEMP%\lh_upstream.txt" "%TEMP%\lh_conflict.txt" "%TEMP%\lh_changed.txt" 2>nul
if "%NOPAUSE%"=="1" goto :end_nopause
pause
:end_nopause
if defined SOME_FAIL exit /b 1
exit /b 0

REM ================= helpers =================
:show_proxy_hint
echo     1) with a proxy tool (Clash / v2ray etc.) set the proxy:
for %%p in (7890 7891 7897 10809 10808 1080) do (
    netstat -ano | findstr /r /c:":%%p .*LISTENING" >nul
    if not errorlevel 1 echo        [proxy found on port %%p] git config --global http.proxy http://127.0.0.1:%%p
)
echo        syntax: git config --global http.proxy http://127.0.0.1:<port>
echo        unset:  git config --global --unset http.proxy
exit /b 0
