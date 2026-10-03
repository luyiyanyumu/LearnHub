@echo off
chcp 65001 >nul
REM ============================================================
REM  后端启动器（由 start-all.bat 调用，一般不单独运行）
REM    无参数   ：后台运行，输出写入 logs\backend.log
REM    visible  ：在当前窗口运行，实时看输出（调试用）
REM ============================================================
setlocal
cd /d "%~dp0..\backend"
if errorlevel 1 (
    echo [后端] 找不到 backend 目录
    exit /b 1
)

set "JAVA_BIN=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"

set "JAR=target\learn-hub-backend-0.0.1-SNAPSHOT.jar"
set "LOGDIR=%~dp0..\logs"

if /i "%~1"=="visible" (
    echo [后端] %JAVA_BIN% -Dserver.port=18080 -jar %JAR%
    "%JAVA_BIN%" -Dserver.port=18080 -jar "%JAR%"
    echo.
    echo [后端] 进程已退出，退出码 %errorlevel%
    exit /b %errorlevel%
)

if not exist "%LOGDIR%" mkdir "%LOGDIR%"
if exist "%LOGDIR%\backend.log" move /y "%LOGDIR%\backend.log" "%LOGDIR%\backend.prev.log" >nul

echo [后端] 启动时间 %DATE% %TIME% >  "%LOGDIR%\backend.log"
echo [后端] 工作目录 %CD% >> "%LOGDIR%\backend.log"
"%JAVA_BIN%" -version >> "%LOGDIR%\backend.log" 2>&1
echo [后端] 命令：%JAVA_BIN% -Dserver.port=18080 -jar %JAR% >> "%LOGDIR%\backend.log"
echo ------------------------------------------------------------ >> "%LOGDIR%\backend.log"

"%JAVA_BIN%" -Dserver.port=18080 -jar "%JAR%" >> "%LOGDIR%\backend.log" 2>&1
echo ------------------------------------------------------------ >> "%LOGDIR%\backend.log"
echo [后端] 进程已退出，退出码 %errorlevel% >> "%LOGDIR%\backend.log"
exit /b %errorlevel%
