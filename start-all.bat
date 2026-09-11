@echo off
chcp 65001 >nul
title learn-hub 一键启动
cd /d "%~dp0"

echo ============================================
echo   learn-hub 学习工作台 · 一键启动
echo ============================================

REM ---------- 1. Docker + MySQL ----------
echo [1/4] 检查 Docker...
docker info >nul 2>&1
if errorlevel 1 (
    echo   Docker 未运行，正在启动 Docker Desktop（首次可能要等 30 秒左右）...
    start "" "%LocalAppData%\Programs\DockerDesktop\Docker Desktop.exe"
:waitdocker
    timeout /t 3 /nobreak >nul
    docker info >nul 2>&1
    if errorlevel 1 goto waitdocker
)
docker start learn-hub-mysql >nul 2>&1
echo   MySQL 容器 learn-hub-mysql 已就绪

REM ---------- 2. 后端 ----------
echo [2/4] 启动后端 (端口 18080)...
if not exist "backend\target\learn-hub-backend-0.0.1-SNAPSHOT.jar" (
    echo   [错误] 未找到 backend\target\learn-hub-backend-0.0.1-SNAPSHOT.jar
    echo   请先执行构建（IDEA 里运行 Maven package，或找我要 build 脚本）
    pause
    exit /b 1
)
if defined JAVA_HOME (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA_EXE=java"
)
start "learn-hub-backend" /D "%~dp0backend" cmd /k ""%JAVA_EXE%" -Dserver.port=18080 -jar target\learn-hub-backend-0.0.1-SNAPSHOT.jar"
echo   后端窗口已启动（.env 中的 API Key 会自动加载）

REM ---------- 3. 前端 ----------
echo [3/4] 启动前端 (端口 5174)...
start "learn-hub-frontend" /D "%~dp0frontend" cmd /k "npm run dev"
echo   前端窗口已启动（若 5174 被占用会直接报错，注意看窗口）

REM ---------- 4. 打开浏览器 ----------
echo [4/4] 等待服务就绪...
timeout /t 8 /nobreak >nul
start http://localhost:5174

echo.
echo ============================================
echo   全部启动完成！
echo   关闭后端/前端：直接关掉对应命令行窗口，
echo   或运行 stop-all.bat
echo ============================================
pause
