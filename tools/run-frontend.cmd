@echo off
chcp 65001 >nul
REM ============================================================
REM  前端启动器（由 start-all.bat 调用，一般不单独运行）
REM    无参数   ：后台运行，输出写入 logs\frontend.log
REM    visible  ：在当前窗口运行，实时看输出（调试用）
REM ============================================================
setlocal
cd /d "%~dp0..\frontend"
if errorlevel 1 (
    echo [前端] 找不到 frontend 目录
    exit /b 1
)

set "LOGDIR=%~dp0..\logs"

if /i "%~1"=="visible" (
    echo [前端] npm run dev（Vite，端口 5174）
    call npm run dev
    echo.
    echo [前端] 进程已退出，退出码 %errorlevel%
    exit /b %errorlevel%
)

if not exist "%LOGDIR%" mkdir "%LOGDIR%"
if exist "%LOGDIR%\frontend.log" move /y "%LOGDIR%\frontend.log" "%LOGDIR%\frontend.prev.log" >nul

echo [前端] 启动时间 %DATE% %TIME% >  "%LOGDIR%\frontend.log"
echo [前端] 工作目录 %CD% >> "%LOGDIR%\frontend.log"
echo [前端] 命令：npm run dev（Vite，端口 5174）>> "%LOGDIR%\frontend.log"
echo ------------------------------------------------------------ >> "%LOGDIR%\frontend.log"

call npm run dev >> "%LOGDIR%\frontend.log" 2>&1
echo ------------------------------------------------------------ >> "%LOGDIR%\frontend.log"
echo [前端] 进程已退出，退出码 %errorlevel% >> "%LOGDIR%\frontend.log"
exit /b %errorlevel%
