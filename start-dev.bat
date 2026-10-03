@echo off
chcp 65001 >nul
title learn-hub dev mode
cd /d "%~dp0"
setlocal

REM ============================================================
REM  learn-hub local dev mode
REM
REM    Use: frontend hot reload (Vite 5174) or debugging the backend jar locally.
REM          For daily use prefer Docker: start-all.bat (port 8888)
REM    Needs: Node.js (npm) and JDK 17+ locally; backend jar already built
REM
REM    default     : backend + frontend run in background, no console window
REM                  output written to logs\backend.log and logs\frontend.log
REM    debug       : start-dev.bat show    both windows become visible
REM    stop        : stop-all.bat
REM
REM  the real work is done by tools\run-backend.cmd and tools\run-frontend.cmd
REM ============================================================

set "SHOW=0"
if /i "%~1"=="show"  set "SHOW=1"
if /i "%~1"=="debug" set "SHOW=1"

set "BE_EXPECT=0"
set "FE_EXPECT=0"
set "SOME_FAIL="

echo ============================================
echo   learn-hub dev mode starting
if "%SHOW%"=="1" echo   [DEBUG] backend/frontend windows will be visible
echo ============================================
echo.

REM ---------- 1. Docker + MySQL ----------
echo [1/4] checking Docker and MySQL...
REM ---------- container names / ports: read deploy\.env ----------
REM This machine may run several projects; a fixed name like learn-hub-mysql can
REM belong to another stack. Reading .env keeps dev mode pointed at our own stack.
set "MYSQL_NAME=learn-hub-mysql"
set "MYSQL_PORT=3307"
if exist "deploy\.env" for /f "usebackq tokens=1,* delims==" %%a in ("deploy\.env") do (
  if /i "%%a"=="MYSQL_CONTAINER_NAME" set "MYSQL_NAME=%%b"
  if /i "%%a"=="MYSQL_HOST_PORT"      set "MYSQL_PORT=%%b"
)

docker info >nul 2>&1
if not errorlevel 1 goto :docker_ok
echo   Docker not running; starting Docker Desktop (~30s on first run)...
start "" "%LocalAppData%\Programs\DockerDesktop\Docker Desktop.exe"
:waitdocker
timeout /t 3 /nobreak >nul
docker info >nul 2>&1
if errorlevel 1 goto :waitdocker
:docker_ok

docker inspect %MYSQL_NAME% >nul 2>&1
if errorlevel 1 goto :no_mysql
docker start %MYSQL_NAME% >nul 2>&1
echo   MySQL container %MYSQL_NAME% ready (port %MYSQL_PORT%)
goto :step_backend

:no_mysql
echo   [ERROR] container %MYSQL_NAME% not found
echo   Deploy first: start-all.bat build
set "SOME_FAIL=1"
goto :summary

REM ---------- 2. backend ----------
:step_backend
echo [2/4] starting backend (port 18080)...
call :port_listening 18080
if not errorlevel 1 goto :backend_running

if not exist "backend\target\learn-hub-backend-0.0.1-SNAPSHOT.jar" goto :backend_no_jar

set "JAVA_OK="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_OK=1"
if defined JAVA_OK goto :backend_launch
where java >nul 2>&1
if not errorlevel 1 set "JAVA_OK=1"
if not defined JAVA_OK goto :backend_no_java

:backend_launch
if not exist "logs" mkdir "logs"
if "%SHOW%"=="1" goto :backend_show
powershell -NoProfile -Command "Start-Process -FilePath '%~dp0tools\run-backend.cmd' -WindowStyle Hidden"
echo   backend started in background (no window); log: logs\backend.log
set "BE_EXPECT=1"
goto :step_frontend

:backend_show
start "learn-hub-backend" cmd /k ""%~dp0tools\run-backend.cmd" visible"
echo   backend window started (debug mode, not logged)
set "BE_EXPECT=1"
goto :step_frontend

:backend_running
echo   port 18080 already listening; skip start
echo   NOTE: if this is the learn-hub-backend container, run: docker stop learn-hub-backend
set "BE_EXPECT=1"
goto :step_frontend

:backend_no_jar
echo   [ERROR] backend\target\learn-hub-backend-0.0.1-SNAPSHOT.jar not found
echo   local build: cd backend ^&^& mvn package -DskipTests (or run Maven package in IDEA)
echo   no JDK/Maven? use the Docker route: start-all.bat
set "SOME_FAIL=1"
goto :step_frontend

:backend_no_java
echo   [ERROR] java not found. Install JDK 17+ then reopen this window
set "SOME_FAIL=1"
goto :step_frontend

REM ---------- 3. frontend ----------
:step_frontend
echo [3/4] starting frontend (port 5174)...
call :port_listening 5174
if not errorlevel 1 goto :frontend_running

where npm >nul 2>&1
if errorlevel 1 goto :frontend_no_npm
if not exist "frontend\node_modules" goto :frontend_no_modules

if not exist "logs" mkdir "logs"
if "%SHOW%"=="1" goto :frontend_show
powershell -NoProfile -Command "Start-Process -FilePath '%~dp0tools\run-frontend.cmd' -WindowStyle Hidden"
echo   frontend started in background (no window); log: logs\frontend.log
set "FE_EXPECT=1"
goto :step_wait

:frontend_show
start "learn-hub-frontend" cmd /k ""%~dp0tools\run-frontend.cmd" visible"
echo   frontend window started (debug mode, not logged)
set "FE_EXPECT=1"
goto :step_wait

:frontend_running
echo   port 5174 already listening; skip start
set "FE_EXPECT=1"
goto :step_wait

:frontend_no_npm
echo   [ERROR] npm not found. Install Node.js LTS then reopen this window
echo   just want the UI? use Docker: start-all.bat (port 8888)
set "SOME_FAIL=1"
goto :step_wait

:frontend_no_modules
echo   [ERROR] missing frontend\node_modules
echo   install deps first: cd frontend ^&^& npm install
set "SOME_FAIL=1"
goto :step_wait

REM ---------- 4. waiting for ready ----------
:step_wait
echo [4/4] waiting for services (up to 60s)...
set /a tries=0
:waitloop
set "BE_UP="
set "FE_UP="
call :port_listening 18080
if not errorlevel 1 set "BE_UP=1"
call :port_listening 5174
if not errorlevel 1 set "FE_UP=1"
set "ALL_OK=1"
if "%BE_EXPECT%"=="1" if not defined BE_UP set "ALL_OK="
if "%FE_EXPECT%"=="1" if not defined FE_UP set "ALL_OK="
if defined ALL_OK goto :ready
set /a tries+=1
if %tries% geq 30 goto :ready
timeout /t 2 /nobreak >nul
goto :waitloop

:ready
echo.
echo   Service status:
if defined BE_UP echo     backend 18080: ready
if not defined BE_UP if "%BE_EXPECT%"=="1" echo     backend 18080: not ready, see logs\backend.log
if not defined BE_UP if not "%BE_EXPECT%"=="1" echo     backend 18080: not started (see messages above)

if defined FE_UP echo     frontend 5174 : ready
if not defined FE_UP if "%FE_EXPECT%"=="1" echo     frontend 5174 : not ready, see logs\frontend.log
if not defined FE_UP if not "%FE_EXPECT%"=="1" echo     frontend 5174 : not started (see messages above)

if "%BE_EXPECT%"=="1" if not defined BE_UP set "SOME_FAIL=1"
if "%FE_EXPECT%"=="1" if not defined FE_UP set "SOME_FAIL=1"

if defined FE_UP start http://localhost:5174

:summary
echo.
echo ============================================
if defined SOME_FAIL goto :summary_bad
echo   all started: backend + frontend run in background, no console window.
goto :summary_common
:summary_bad
echo   WARNING: some service failed to start; see messages above and logs\.
:summary_common
echo   Log files: logs\backend.log   logs\frontend.log
echo   Follow logs: powershell -Command "Get-Content logs\backend.log -Wait -Tail 50"
echo   Stop all: stop-all.bat
echo   Show windows (debug): start-dev.bat show
echo   Daily use (Docker): start-all.bat
echo ============================================
pause
exit /b 0

REM ---------- helpers ----------
:port_listening
REM %1 = port number; errorlevel 0 means the port is listening
netstat -ano | findstr /r /c:":%~1 .*LISTENING" >nul
exit /b %errorlevel%
