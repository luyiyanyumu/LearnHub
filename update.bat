@echo off
chcp 65001 >nul
title learn-hub 源码更新
cd /d "%~dp0"
setlocal

REM ============================================================
REM  learn-hub 学习工作台 · 从 GitHub 更新源码
REM
REM    默认        ：fetch → 冲突检查 → pull → 重建镜像并重启容器 → 打开界面
REM    pull        ：只更新源码，不动容器
REM    rebuild     ：不拉源码，只重建镜像并重启容器（改完本地代码后用）
REM    force       ：本地文件与远端撞车时（改过同名文件 / 未跟踪的同名文件），
REM                  先备份到 ..\_learnhub-backup\时间戳 再用远端版本覆盖
REM    nobrowser   ：不打开浏览器
REM    nopause     ：结束时不等待按键（供其它脚本调用）
REM
REM    安全说明：
REM      * 只允许快进合并（--ff-only），不会产生合并提交、不会丢你的提交；
REM      * deploy\.env 在 .gitignore 里，本脚本绝不改动它；
REM      * 只对 http/https 远端做连通性预检（本地/镜像远端会跳过）；
REM      * GitHub 访问被间歇性重置是常态，网络类失败会自动重试 3 次。
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
echo   learn-hub 学习工作台 · 从 GitHub 更新源码
echo ============================================
echo.

where git >nul 2>&1
if errorlevel 1 goto :no_git
git rev-parse --git-dir >nul 2>&1
if errorlevel 1 goto :no_repo

for /f "delims=" %%b in ('git rev-parse --abbrev-ref HEAD 2^>nul') do set "BRANCH=%%b"
for /f "delims=" %%u in ('git remote get-url origin 2^>nul') do set "REMOTE_URL=%%u"
echo   远端：%REMOTE_URL%
echo   分支：%BRANCH%
echo.

if "%DO_PULL%"=="0" goto :step_build

REM ---------- 连通性预检（仅 http/https 远端）----------
if not "%REMOTE_URL%"=="" for /f "tokens=1,2 delims=/: " %%a in ("%REMOTE_URL%") do (
    if /i "%%a"=="https" set "HOST=%%b"
    if /i "%%a"=="http"  set "HOST=%%b"
)
if "%HOST%"=="" goto :net_skip
echo ==^> 检查 %HOST%:443 连通性（最长 6 秒）
set "NET_OK="
for /f "delims=" %%r in ('powershell -NoProfile -Command "try{$c=New-Object System.Net.Sockets.TcpClient;$t=$c.BeginConnect('%HOST%',443,$null,$null);if($t.AsyncWaitHandle.WaitOne(6000)){if($c.Connected){'OK'}else{'NO'}}else{'NO'}}catch{'NO'}" 2^>nul') do set "NET_OK=%%r"
if not "%NET_OK%"=="OK" goto :net_fail
echo   可以连上
echo.
:net_skip

REM ---------- 拉取远端信息 ----------
echo ==^> 拉取远端信息（网络失败会自动重试 3 次）
set /a fetch_try=0
:fetch_retry
set /a fetch_try+=1
echo   第 %fetch_try% 次尝试...
git -c http.lowSpeedLimit=1000 -c http.lowSpeedTime=30 fetch --prune > "%TEMP%\lh_fetch.txt" 2>&1
if not errorlevel 1 goto :fetch_ok
if %fetch_try% geq 3 goto :fetch_fail
echo   失败，4 秒后重试（GitHub 连接被重置在国内很常见）
ping -n 5 127.0.0.1 >nul
goto :fetch_retry

:fetch_ok
type "%TEMP%\lh_fetch.txt"
for /f "delims=" %%s in ('git rev-parse --short HEAD') do set "OLD_SHA=%%s"
for /f "delims=" %%s in ('git rev-parse --short origin/%BRANCH%') do set "NEW_SHA=%%s"
for /f "delims=" %%c in ('git rev-list --count HEAD..origin/%BRANCH%') do set "BEHIND=%%c"
for /f "delims=" %%c in ('git rev-list --count origin/%BRANCH%..HEAD') do set "AHEAD=%%c"
echo   本地 %OLD_SHA%    远端 %NEW_SHA%    （落后 %BEHIND% 个提交，领先 %AHEAD% 个）
echo.

if not "%AHEAD%"=="0" goto :diverged
if "%BEHIND%"=="0" goto :up_to_date

REM ---------- 冲突检查（受跟踪改动 + 未跟踪文件，交给 PowerShell 助手）----------
echo ==^> 检查本地文件是否会挡住这次更新
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\check-update-conflicts.ps1" -Branch "%BRANCH%" > "%TEMP%\lh_conflict.txt" 2>nul
findstr /i /c:"__NO_CONFLICT__" "%TEMP%\lh_conflict.txt" >nul
if errorlevel 1 goto :conflict
echo   没有冲突，可以安全快进
echo.

REM ---------- 拉取 ----------
:pull_now
echo ==^> 拉取新代码（--ff-only，网络失败会自动重试 3 次）
set /a pull_try=0
:pull_retry
set /a pull_try+=1
echo   第 %pull_try% 次尝试...
git -c http.lowSpeedLimit=1000 -c http.lowSpeedTime=30 pull --ff-only > "%TEMP%\lh_pull.txt" 2>&1
if not errorlevel 1 goto :pull_ok
findstr /i /c:"would be overwritten" "%TEMP%\lh_pull.txt" >nul
if not errorlevel 1 goto :pull_blocked
if %pull_try% geq 3 goto :pull_fail
echo   失败，4 秒后重试
ping -n 5 127.0.0.1 >nul
goto :pull_retry

:pull_ok
type "%TEMP%\lh_pull.txt"
for /f "delims=" %%s in ('git rev-parse --short HEAD') do set "CUR_SHA=%%s"
echo   已更新：%OLD_SHA% ^-^> %CUR_SHA%
echo.
echo   本次包含的提交：
git log --oneline %OLD_SHA%..%CUR_SHA%
echo.
echo   改动的文件：
git diff --name-only %OLD_SHA% %CUR_SHA% > "%TEMP%\lh_changed.txt"
git diff --stat %OLD_SHA% %CUR_SHA%
findstr /i /c:"deploy/.env.example" "%TEMP%\lh_changed.txt" >nul
if not errorlevel 1 echo.
if not errorlevel 1 echo   [注意] deploy\.env.example 变了，可能新增配置项；本脚本不会动你的 deploy\.env，请自行比对
findstr /i /c:"deploy/docker-compose.yml" "%TEMP%\lh_changed.txt" >nul
if not errorlevel 1 echo   [注意] docker-compose.yml 变了，下面会重建镜像
echo.

REM ---------- 重建并重启 ----------
:step_build
if "%DO_BUILD%"=="0" goto :done_ok
echo ==^> 重建镜像并重启容器（首次或有改动时约 1~3 分钟）
call "%~dp0start-all.bat" build nobrowser nopause
if errorlevel 1 goto :build_fail

echo.
echo ==^> 确认服务状态
docker ps --filter name=learn-hub --format "    {{.Names}}  {{.Status}}"
netstat -ano | findstr /r /c:":8888 .*LISTENING" >nul
if errorlevel 1 goto :web_down
if "%OPEN%"=="1" start "" http://localhost:8888
if "%OPEN%"=="1" echo   已在浏览器打开：http://localhost:8888
goto :done_ok

REM ================= 结果汇总 =================
:done_ok
echo.
echo ============================================
if "%OLD_SHA%"=="" goto :done_latest
if /i "%OLD_SHA%"=="%CUR_SHA%" goto :done_none
echo   源码已更新：%OLD_SHA% ^-^> %CUR_SHA%
goto :done_tail
:done_none
echo   源码已是最新（%CUR_SHA%），本次没有拉取新提交
goto :done_tail
:done_latest
echo   源码已是最新（%CUR_SHA%）
:done_tail
if "%DO_BUILD%"=="1" echo   界面：http://localhost:8888
echo   可选清理旧镜像：docker image prune -f
echo ============================================
goto :end

REM ================= 各种分支 =================
:up_to_date
set "CUR_SHA=%OLD_SHA%"
echo   已经是最新版本（%OLD_SHA%），没有新提交，无需重建。
echo   如果只是改完本地代码想重建镜像：update.bat rebuild
goto :done_ok

:conflict
echo   这些本地文件会挡住本次更新（远端也要动它们）：
type "%TEMP%\lh_conflict.txt"
if "%FORCE%"=="0" goto :conflict_stop
echo.
echo   按 force 处理：先备份，再用远端版本覆盖
set "STAMP="
for /f "delims=" %%t in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set "STAMP=%%t"
set "BK=%~dp0..\_learnhub-backup\%STAMP%"
mkdir "%BK%" 2>nul
for /f "usebackq delims=" %%f in ("%TEMP%\lh_conflict.txt") do (
    if not "%%~pf"=="." mkdir "%BK%\%%~pf" 2>nul
    copy /y "%%f" "%BK%\%%~pf" >nul
    echo     备份 %%f
)
echo   备份目录：%BK%
for /f "usebackq delims=" %%f in ("%TEMP%\lh_conflict.txt") do (
    git ls-files --error-unmatch "%%f" >nul 2>&1
    if errorlevel 1 (del /q "%%f") else (git checkout -- "%%f")
)
echo   已用远端版本覆盖上述文件，继续拉取
echo.
goto :pull_now

:conflict_stop
echo.
echo   处理办法（任选其一）：
echo     1) 自动备份并覆盖：update.bat force
echo     2) 自己把这些文件提交 / 另存 / 删除后，再运行 update.bat
goto :fail

:net_fail
echo   [错误] %HOST%:443 连不上（6 秒内没建立连接）
echo   这是网络/代理问题，不是脚本或仓库的问题。可选办法：
call :show_proxy_hint
echo     2) 用国内镜像：git remote set-url origin 镜像地址
echo     3) 过几分钟再试（GitHub 访问被重置是间歇性的）
goto :fail

:fetch_fail
echo   [错误] 试了 3 次都没能连上 GitHub 拉取信息，git 输出：
type "%TEMP%\lh_fetch.txt"
echo   可选办法：
call :show_proxy_hint
echo     2) 用国内镜像：git remote set-url origin 镜像地址
echo     3) 过几分钟再试（GitHub 访问被重置是间歇性的）
goto :fail

:pull_blocked
echo   [错误] git 拒绝拉取：本地有文件会被远端覆盖（重试无用）
type "%TEMP%\lh_pull.txt"
echo.
echo   处理办法：
echo     1) update.bat force   自动备份这些文件，再用远端版本覆盖
echo     2) 手动把它们移走 / 提交后重试
goto :fail

:pull_fail
echo   [错误] 试了 3 次都没能拉取成功，git 输出：
type "%TEMP%\lh_pull.txt"
goto :fail

:no_git
echo   [错误] 没找到 git，请先安装 Git for Windows
goto :fail

:no_repo
echo   [错误] 当前目录不是 git 仓库：%CD%
echo   请把本脚本放在从 GitHub clone 下来的仓库根目录里
goto :fail

:diverged
echo   [停止] 本地有 %AHEAD% 个未推送的提交，无法快进合并（--ff-only）。
echo   先看看是什么：git log origin/%BRANCH%..HEAD
echo   处理完（push / rebase / 丢弃）之后再运行本脚本。
goto :fail

:build_fail
echo   [错误] 重建镜像失败，请看 start-all.bat 的输出
goto :fail

:web_down
echo   [错误] 前端端口 8888 没在监听，暂不打开浏览器
echo   看日志：docker logs learn-hub-frontend
goto :fail

:fail
set "SOME_FAIL=1"
echo.
echo ============================================
echo   本次未完成，请按上面的提示处理后重试。
echo ============================================

:end
del "%TEMP%\lh_fetch.txt" "%TEMP%\lh_pull.txt" "%TEMP%\lh_upstream.txt" "%TEMP%\lh_conflict.txt" "%TEMP%\lh_changed.txt" 2>nul
if "%NOPAUSE%"=="1" goto :end_nopause
pause
:end_nopause
if defined SOME_FAIL exit /b 1
exit /b 0

REM ================= 工具 =================
:show_proxy_hint
echo     1) 有代理工具（Clash / v2ray / 猫猫云 等）时设代理：
for %%p in (7890 7891 7897 10809 10808 1080) do (
    netstat -ano | findstr /r /c:":%%p .*LISTENING" >nul
    if not errorlevel 1 echo        [检测到本机 %%p 端口有代理] git config --global http.proxy http://127.0.0.1:%%p
)
echo        通用写法：git config --global http.proxy http://127.0.0.1:你的端口
echo        取消代理：git config --global --unset http.proxy
exit /b 0
