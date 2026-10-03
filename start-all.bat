@echo off
chcp 65001 >nul
title learn-hub 一键启动
cd /d "%~dp0"
setlocal

REM ============================================================
REM  learn-hub 学习工作台 · 一键启动（Docker 版）
REM
REM    做的事：启动 Docker 引擎 → 拉起容器 → 等就绪 → 打开前端界面
REM    入口    ：http://localhost:8888
REM              （nginx 托管前端静态文件，/api 反代到后端容器）
REM
REM    参数（可任意组合）：
REM      build           先重建镜像再启动（改了后端/前端代码之后）
REM      nobrowser       不打开浏览器
REM      nopause         结束时不等待按键（供其它脚本调用）
REM      dev             本机开发模式（Vite 5174 + 本机 jar，需要 Node/JDK）
REM
REM    停止：stop-all.bat（保留数据）   彻底移除容器：stop-all.bat down
REM    更新源码：update.bat
REM ============================================================

set "MODE=docker"
set "OPEN=1"
set "NOPAUSE=0"
set "SOME_FAIL="
set /a waited=0

if not "%~1"=="" for %%a in (%*) do (
    if /i "%%a"=="build"     set "MODE=build"
    if /i "%%a"=="dev"       set "MODE=dev"
    if /i "%%a"=="nobrowser" set "OPEN=0"
    if /i "%%a"=="nopause"   set "NOPAUSE=1"
)
if /i "%MODE%"=="dev" goto :dev_mode

echo ============================================
echo   learn-hub 学习工作台 · 一键启动
if /i "%MODE%"=="build" echo   [重建模式] 先重新构建镜像，再启动
echo ============================================
echo.

REM ---------- 1/4 Docker 引擎 ----------
echo [1/4] 检查 Docker 引擎...
docker info >nul 2>&1
if not errorlevel 1 goto :docker_ok

echo   Docker 未运行，正在启动 Docker Desktop（引擎就绪一般要 30~60 秒）...
set "DDPATH=%LocalAppData%\Programs\DockerDesktop\Docker Desktop.exe"
if not exist "%DDPATH%" set "DDPATH=C:\Program Files\Docker\Docker\Docker Desktop.exe"
if not exist "%DDPATH%" goto :no_dd
start "" "%DDPATH%"
:waitdocker
timeout /t 3 /nobreak >nul
set /a waited+=3
docker info >nul 2>&1
if not errorlevel 1 goto :docker_ok
if %waited% geq 180 goto :docker_timeout
<nul set /p "=."
goto :waitdocker

:docker_timeout
echo.
echo   [错误] 等了 %waited% 秒，Docker 引擎仍未就绪
echo   请打开 Docker Desktop 看它的提示（常见：WSL2 未就绪、需要重启 Docker Desktop）
set "SOME_FAIL=1"
goto :summary

:no_dd
echo   [错误] 找不到 Docker Desktop 的安装位置
echo   请手动启动 Docker Desktop，然后再运行本脚本
set "SOME_FAIL=1"
goto :summary

:docker_ok
if %waited% gtr 0 echo.
echo   Docker 引擎就绪

REM ---------- 2/4 启动容器 ----------
echo [2/4] 启动容器（MySQL + 后端 + 前端）...
if not exist "deploy\.env" echo   提示：deploy\.env 不存在，AI 功能不可用（可复制 .env.example 为 .env 再填 key）

docker image inspect learn-hub-backend:local >nul 2>&1
if errorlevel 1 goto :need_build
docker image inspect learn-hub-frontend:local >nul 2>&1
if errorlevel 1 goto :need_build

pushd "%~dp0deploy"
if /i "%MODE%"=="build" goto :compose_build
docker compose up -d
set "COMPOSE_RC=%errorlevel%"
popd
if not "%COMPOSE_RC%"=="0" goto :compose_fail
goto :wait_stack

:compose_build
docker compose up -d --build
set "COMPOSE_RC=%errorlevel%"
popd
if not "%COMPOSE_RC%"=="0" goto :compose_fail
goto :wait_stack

:need_build
echo   [错误] 本地缺少 learn-hub-backend:local / learn-hub-frontend:local 镜像
echo   请先构建一次：start-all.bat build
set "SOME_FAIL=1"
goto :summary

:compose_fail
echo   [错误] docker compose up 失败（退出码 %COMPOSE_RC%），原因见上面的输出
set "SOME_FAIL=1"
goto :summary

REM ---------- 3/4 等待就绪 ----------
:wait_stack
echo [3/4] 等待服务就绪（后端健康检查一般 20~40 秒）...
set /a waited=0
:waitstack
set "MYSQLST="
set "BEST="
set "FEST="
for /f "delims=" %%s in ('docker inspect -f "{{.State.Health.Status}}" learn-hub-mysql 2^>nul') do set "MYSQLST=%%s"
for /f "delims=" %%s in ('docker inspect -f "{{.State.Health.Status}}" learn-hub-backend 2^>nul') do set "BEST=%%s"
for /f "delims=" %%s in ('docker inspect -f "{{.State.Status}}" learn-hub-frontend 2^>nul') do set "FEST=%%s"
if /i "%BEST%"=="healthy" if /i "%FEST%"=="running" goto :stack_ready
set /a waited+=3
if %waited% geq 180 goto :stack_ready
timeout /t 3 /nobreak >nul
goto :waitstack

:stack_ready
if "%MYSQLST%"=="" set "MYSQLST=未找到容器"
if "%BEST%"==""    set "BEST=未找到容器"
if "%FEST%"==""    set "FEST=未找到容器"
echo.
echo   服务状态：
echo     MySQL   3307 ：%MYSQLST%
echo     后端    18080：%BEST%
echo     前端    8888 ：%FEST%

REM ---------- 4/4 打开界面 ----------
call :port_listening 8888
if errorlevel 1 goto :web_down
echo [4/4] 前端端口 8888 已就绪
if "%OPEN%"=="1" goto :open_browser
echo   已跳过打开浏览器（nobrowser）
goto :summary

:open_browser
start "" http://localhost:8888
echo   已在浏览器打开：http://localhost:8888
goto :summary

:web_down
echo [4/4] 前端端口 8888 还没监听，暂不打开浏览器
echo   看前端日志：docker logs learn-hub-frontend
set "SOME_FAIL=1"
goto :summary

:summary
echo.
echo ============================================
if defined SOME_FAIL goto :summary_bad
echo   启动完成，浏览器入口：http://localhost:8888
goto :summary_common
:summary_bad
echo   注意：有环节没成功，请看上面的提示。
:summary_common
echo   看后端日志：docker logs -f learn-hub-backend
echo   停止服务  ：stop-all.bat          （保留数据，下次启动很快）
echo   彻底移除  ：stop-all.bat down
echo   改代码后重建镜像：start-all.bat build
echo   从 GitHub 更新源码：update.bat
echo ============================================
if "%NOPAUSE%"=="1" goto :end_ok
pause
:end_ok
if defined SOME_FAIL exit /b 1
exit /b 0

REM ---------- 开发模式：交给 start-dev.bat ----------
:dev_mode
call "%~dp0start-dev.bat" %2 %3
exit /b %errorlevel%

REM ---------- 工具 ----------
:port_listening
REM %1 = 端口号；返回 errorlevel 0 表示该端口正在监听
netstat -ano | findstr /r /c:":%~1 .*LISTENING" >nul
exit /b %errorlevel%
