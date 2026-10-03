@echo off
chcp 65001 >nul
title learn-hub launcher
cd /d "%~dp0"
setlocal

REM ============================================================
REM  learn-hub launcher (Docker)
REM
REM    Does: start Docker engine -> compose up -> wait ready -> open UI
REM    Entry   : http://localhost:<WEB_HOST_PORT from deploy\.env, default 8888>
REM              (nginx serves UI, /api proxied to backend)
REM
REM    Args (any combination):
REM      build           rebuild images first, then start
REM      nobrowser       do not open browser
REM      nopause         no pause at exit (for scripting)
REM      dev             local dev mode (Vite 5174 + local jar; needs Node/JDK)
REM
REM    Stop: stop-all.bat (keep data)   Remove: stop-all.bat down
REM    Update source: update.bat
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
echo   learn-hub launcher
if /i "%MODE%"=="build" echo   [BUILD] rebuild images first, then start
echo ============================================
echo.

REM ---------- ports: read deploy\.env so messages match reality ----------
REM Without this the script printed hardcoded 3307/18080/8888 even when .env
REM maps other ports (common when a legacy container already owns the defaults).
set "WEB_PORT=8888"
set "BACKEND_PORT=18080"
set "MYSQL_PORT=3307"
if exist "deploy\.env" for /f "usebackq tokens=1,* delims==" %%a in ("deploy\.env") do (
  if /i "%%a"=="WEB_HOST_PORT"     set "WEB_PORT=%%b"
  if /i "%%a"=="BACKEND_HOST_PORT" set "BACKEND_PORT=%%b"
  if /i "%%a"=="MYSQL_HOST_PORT"   set "MYSQL_PORT=%%b"
  if /i "%%a"=="MYSQL_CONTAINER_NAME"    set "MYSQL_NAME=%%b"
  if /i "%%a"=="FRONTEND_CONTAINER_NAME" set "FRONTEND_NAME=%%b"
  if /i "%%a"=="BACKEND_CONTAINER_NAME"  set "BACKEND_NAME=%%b"
)
if not defined FRONTEND_NAME set "FRONTEND_NAME=learn-hub-frontend"
if not defined MYSQL_NAME    set "MYSQL_NAME=learn-hub-mysql"
if not defined BACKEND_NAME  set "BACKEND_NAME=learn-hub-backend"
echo   UI port %WEB_PORT%  (from deploy\.env; edit it to change)
echo.

REM ---------- 1/4 Docker engine ----------
echo [1/4] checking Docker engine...
docker info >nul 2>&1
if not errorlevel 1 goto :docker_ok

echo   Docker not running; starting Docker Desktop (ready in ~30-60s)...
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
echo   [ERROR] Docker not ready after %waited%s
echo   Open Docker Desktop and check its message (common: WSL2 not ready / restart Docker Desktop)
set "SOME_FAIL=1"
goto :summary

:no_dd
echo   [ERROR] Docker Desktop not found
echo   Start Docker Desktop manually, then run this script again
set "SOME_FAIL=1"
goto :summary

:docker_ok
if %waited% gtr 0 echo.
echo   Docker engine ready

REM ---------- 2/4 starting containers ----------
echo [2/4] starting containers (MySQL + backend + frontend)...
if not exist "deploy\.env" echo   NOTE: deploy\.env missing -> AI features disabled (copy .env.example to .env and fill the key)

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
echo   [ERROR] missing local images: learn-hub-backend:local / learn-hub-frontend:local
echo   Build first: start-all.bat build
set "SOME_FAIL=1"
goto :summary

:compose_fail
echo   [ERROR] docker compose up failed (exit %COMPOSE_RC%), see output above
set "SOME_FAIL=1"
goto :summary

REM ---------- 3/4 waiting for ready ----------
:wait_stack
echo [3/4] waiting for services (backend healthcheck usually 20-40s)...
set /a waited=0
:waitstack
set "MYSQLST="
set "BEST="
set "FEST="
for /f "delims=" %%s in ('docker inspect -f "{{.State.Health.Status}}" %MYSQL_NAME% 2^>nul') do set "MYSQLST=%%s"
for /f "delims=" %%s in ('docker inspect -f "{{.State.Health.Status}}" %BACKEND_NAME% 2^>nul') do set "BEST=%%s"
for /f "delims=" %%s in ('docker inspect -f "{{.State.Status}}" %FRONTEND_NAME% 2^>nul') do set "FEST=%%s"
if /i "%BEST%"=="healthy" if /i "%FEST%"=="running" goto :stack_ready
set /a waited+=3
if %waited% geq 180 goto :stack_ready
timeout /t 3 /nobreak >nul
goto :waitstack

:stack_ready
if "%MYSQLST%"=="" set "MYSQLST=not found"
if "%BEST%"==""    set "BEST=not found"
if "%FEST%"==""    set "FEST=not found"
echo.
echo   Service status:
echo     MySQL   %MYSQL_PORT% : %MYSQLST%
echo     backend %BACKEND_PORT%: %BEST%
echo     frontend    %WEB_PORT% : %FEST%

REM ---------- 4/4 opening UI ----------
call :port_listening %WEB_PORT%
if errorlevel 1 goto :web_down
echo [4/4] frontend port %WEB_PORT% is ready
if "%OPEN%"=="1" goto :open_browser
echo   skip opening browser (nobrowser)
goto :summary

:open_browser
start "" http://localhost:%WEB_PORT%
echo   opened in browser: http://localhost:%WEB_PORT%
goto :summary

:web_down
echo [4/4] frontend port %WEB_PORT% not listening yet; not opening browser
echo   Frontend log: docker logs %FRONTEND_NAME%
set "SOME_FAIL=1"
goto :summary

:summary
echo.
echo ============================================
if defined SOME_FAIL goto :summary_bad
echo   started. UI: http://localhost:%WEB_PORT%
goto :summary_common
:summary_bad
echo   WARNING: some step failed, see messages above.
:summary_common
echo   Backend log: docker logs -f %BACKEND_NAME%
echo   Stop     : stop-all.bat          (keep data; next start is fast)
echo   Remove   : stop-all.bat down
echo   Rebuild  : start-all.bat build
echo   Update from GitHub: update.bat
echo ============================================
if "%NOPAUSE%"=="1" goto :end_ok
pause
:end_ok
if defined SOME_FAIL exit /b 1
exit /b 0

REM ---------- dev mode: handled by start-dev.bat ----------
:dev_mode
call "%~dp0start-dev.bat" %2 %3
exit /b %errorlevel%

REM ---------- helpers ----------
:port_listening
REM %1 = port number; errorlevel 0 means the port is listening
netstat -ano | findstr /r /c:":%~1 .*LISTENING" >nul
exit /b %errorlevel%
