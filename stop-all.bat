@echo off
chcp 65001 >nul
title learn-hub 一键停止
cd /d "%~dp0"
setlocal

REM ============================================================
REM  learn-hub 学习工作台 · 一键停止
REM
REM    无参数 ：停止三个容器（保留容器与数据，start-all.bat 可秒级恢复）
REM    down   ：移除容器与网络（数据卷保留，下次 start-all.bat 会重建）
REM
REM    另外会顺带清理本机开发模式（start-dev.bat）留下的进程，
REM    但绝不会碰 Docker/WSL 自身的进程。
REM ============================================================

set "MODE=stop"
if /i "%~1"=="down" set "MODE=down"

echo ============================================
echo   learn-hub 学习工作台 · 一键停止
if /i "%MODE%"=="down" echo   [彻底移除] 容器会被删除，数据卷保留
echo ============================================
echo.

echo [1/2] 停止容器（MySQL + 后端 + 前端）...
docker info >nul 2>&1
if errorlevel 1 goto :no_docker

pushd "%~dp0deploy"
if /i "%MODE%"=="down" goto :do_down
docker compose stop
goto :compose_done
:do_down
docker compose down
:compose_done
popd
echo   容器当前状态：
docker ps -a --filter name=learn-hub --format "    {{.Names}}  {{.Status}}"
goto :step_dev

:no_docker
echo   Docker 引擎没在运行，容器本来就是停的，跳过。

:step_dev
echo.
echo [2/2] 清理本机开发模式的进程（若有）...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\stop-services.ps1"

echo.
echo ============================================
echo   数据保留在具名卷：learn-hub_mysql-data / learn-hub_uploads
if /i "%MODE%"=="down" echo   容器已移除，下次 start-all.bat 会自动重建
if /i not "%MODE%"=="down" echo   容器与数据都保留，start-all.bat 会很快恢复
echo   重新启动：start-all.bat
echo ============================================
pause
