@echo off
chcp 65001 >nul
title learn-hub 开发模式
cd /d "%~dp0"
setlocal

REM ============================================================
REM  learn-hub 学习工作台 · 本机开发模式
REM
REM    用途：改前端要热更新（Vite 5174）、或要在本机调试后端 jar 时用。
REM          日常使用请走 Docker 版：start-all.bat（入口 8888）
REM    前提：本机装了 Node.js（npm）与 JDK 17+，后端 jar 已构建过
REM
REM    默认        ：后端 + 前端在后台运行，不弹黑窗口
REM                  输出写入 logs\backend.log、logs\frontend.log
REM    调试        ：start-dev.bat show    前后端窗口显示出来
REM    停止        ：stop-all.bat
REM
REM  实际干活的是 tools\run-backend.cmd 与 tools\run-frontend.cmd
REM ============================================================

set "SHOW=0"
if /i "%~1"=="show"  set "SHOW=1"
if /i "%~1"=="debug" set "SHOW=1"

set "BE_EXPECT=0"
set "FE_EXPECT=0"
set "SOME_FAIL="

echo ============================================
echo   learn-hub 学习工作台 · 开发模式启动
if "%SHOW%"=="1" echo   [调试模式] 前后端窗口会显示出来
echo ============================================
echo.

REM ---------- 1. Docker + MySQL ----------
echo [1/4] 检查 Docker 与 MySQL...
docker info >nul 2>&1
if not errorlevel 1 goto :docker_ok
echo   Docker 未运行，正在启动 Docker Desktop（首次可能要等 30 秒左右）...
start "" "%LocalAppData%\Programs\DockerDesktop\Docker Desktop.exe"
:waitdocker
timeout /t 3 /nobreak >nul
docker info >nul 2>&1
if errorlevel 1 goto :waitdocker
:docker_ok

docker inspect learn-hub-mysql >nul 2>&1
if errorlevel 1 goto :no_mysql
docker start learn-hub-mysql >nul 2>&1
echo   MySQL 容器 learn-hub-mysql 已就绪（端口 3307）
goto :step_backend

:no_mysql
echo   [错误] 没有找到容器 learn-hub-mysql
echo   请先部署一次：start-all.bat build
set "SOME_FAIL=1"
goto :summary

REM ---------- 2. 后端 ----------
:step_backend
echo [2/4] 启动后端 (端口 18080)...
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
echo   后端已后台启动（无窗口），日志：logs\backend.log
set "BE_EXPECT=1"
goto :step_frontend

:backend_show
start "learn-hub-backend" cmd /k ""%~dp0tools\run-backend.cmd" visible"
echo   后端窗口已启动（调试模式，输出不写入日志）
set "BE_EXPECT=1"
goto :step_frontend

:backend_running
echo   端口 18080 已在监听，跳过启动
echo   提示：若这是 Docker 里的 learn-hub-backend 容器，开发模式请先 docker stop learn-hub-backend
set "BE_EXPECT=1"
goto :step_frontend

:backend_no_jar
echo   [错误] 未找到 backend\target\learn-hub-backend-0.0.1-SNAPSHOT.jar
echo   本机开发：cd backend ^&^& mvn package -DskipTests（或在 IDEA 里跑 Maven package）
echo   不想装 JDK/Maven：直接走 Docker 版 start-all.bat
set "SOME_FAIL=1"
goto :step_frontend

:backend_no_java
echo   [错误] 没找到 java，请先安装 JDK 17 或更高版本（装完重新打开本窗口）
set "SOME_FAIL=1"
goto :step_frontend

REM ---------- 3. 前端 ----------
:step_frontend
echo [3/4] 启动前端 (端口 5174)...
call :port_listening 5174
if not errorlevel 1 goto :frontend_running

where npm >nul 2>&1
if errorlevel 1 goto :frontend_no_npm
if not exist "frontend\node_modules" goto :frontend_no_modules

if not exist "logs" mkdir "logs"
if "%SHOW%"=="1" goto :frontend_show
powershell -NoProfile -Command "Start-Process -FilePath '%~dp0tools\run-frontend.cmd' -WindowStyle Hidden"
echo   前端已后台启动（无窗口），日志：logs\frontend.log
set "FE_EXPECT=1"
goto :step_wait

:frontend_show
start "learn-hub-frontend" cmd /k ""%~dp0tools\run-frontend.cmd" visible"
echo   前端窗口已启动（调试模式，输出不写入日志）
set "FE_EXPECT=1"
goto :step_wait

:frontend_running
echo   端口 5174 已在监听，跳过启动
set "FE_EXPECT=1"
goto :step_wait

:frontend_no_npm
echo   [错误] 没找到 npm，请先安装 Node.js LTS（装完重新打开本窗口）
echo   只想用现成界面：直接走 Docker 版 start-all.bat（入口 8888）
set "SOME_FAIL=1"
goto :step_wait

:frontend_no_modules
echo   [错误] 缺少 frontend\node_modules
echo   请先安装依赖：cd frontend ^&^& npm install
set "SOME_FAIL=1"
goto :step_wait

REM ---------- 4. 等待就绪 ----------
:step_wait
echo [4/4] 等待服务就绪（最多 60 秒）...
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
echo   服务状态：
if defined BE_UP echo     后端 18080：已就绪
if not defined BE_UP if "%BE_EXPECT%"=="1" echo     后端 18080：未就绪，请看日志 logs\backend.log
if not defined BE_UP if not "%BE_EXPECT%"=="1" echo     后端 18080：未启动（见上面的提示）

if defined FE_UP echo     前端 5174 ：已就绪
if not defined FE_UP if "%FE_EXPECT%"=="1" echo     前端 5174 ：未就绪，请看日志 logs\frontend.log
if not defined FE_UP if not "%FE_EXPECT%"=="1" echo     前端 5174 ：未启动（见上面的提示）

if "%BE_EXPECT%"=="1" if not defined BE_UP set "SOME_FAIL=1"
if "%FE_EXPECT%"=="1" if not defined FE_UP set "SOME_FAIL=1"

if defined FE_UP start http://localhost:5174

:summary
echo.
echo ============================================
if defined SOME_FAIL goto :summary_bad
echo   全部启动完成：前后端在后台运行，不占用任何命令行窗口。
goto :summary_common
:summary_bad
echo   注意：有服务没启动成功，请看上面的提示，并检查 logs 目录里的日志。
:summary_common
echo   日志文件：logs\backend.log   logs\frontend.log
echo   实时看日志：powershell -Command "Get-Content logs\backend.log -Wait -Tail 50"
echo   停止全部服务：stop-all.bat
echo   想看到黑窗口（调试排查）：start-dev.bat show
echo   日常使用（Docker 版）：start-all.bat
echo ============================================
pause
exit /b 0

REM ---------- 工具 ----------
:port_listening
REM %1 = 端口号；返回 errorlevel 0 表示该端口正在监听
netstat -ano | findstr /r /c:":%~1 .*LISTENING" >nul
exit /b %errorlevel%
