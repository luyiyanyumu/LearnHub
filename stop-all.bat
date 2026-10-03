@echo off
chcp 65001 >nul
title learn-hub stopper
cd /d "%~dp0"
setlocal

REM ============================================================
REM  learn-hub stopper
REM
REM    no args : stop the three containers (keep them + data; start-all.bat restores in seconds)
REM    down    : remove containers and network (volumes kept; start-all.bat recreates)
REM
REM    Also cleans up processes left by local dev mode (start-dev.bat),
REM    but never touches Docker/WSL processes.
REM ============================================================

set "MODE=stop"
if /i "%~1"=="down" set "MODE=down"

echo ============================================
echo   learn-hub stopper
if /i "%MODE%"=="down" echo   [DOWN] containers removed, data volumes kept
echo ============================================
echo.

echo [1/2] stopping containers (MySQL + backend + frontend)...
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
echo   Container status:
docker ps -a --filter name=learn-hub --format "    {{.Names}}  {{.Status}}"
goto :step_dev

:no_docker
echo   Docker engine not running; containers are already stopped, skip.

:step_dev
echo.
echo [2/2] cleaning up local dev-mode processes (if any)...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\stop-services.ps1"

echo.
echo ============================================
echo   data kept in named volumes: learn-hub_mysql-data / learn-hub_uploads
if /i "%MODE%"=="down" echo   containers removed; next start-all.bat recreates them
if /i not "%MODE%"=="down" echo   containers and data kept; start-all.bat restores quickly
echo   Restart: start-all.bat
echo ============================================
pause
