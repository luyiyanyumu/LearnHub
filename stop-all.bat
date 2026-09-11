@echo off
chcp 65001 >nul
title learn-hub 一键停止

echo 停止后端(18080)与前端(5174)进程...
powershell -NoProfile -Command "foreach($p in 18080,5174){ Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique | ForEach-Object { try { Stop-Process -Id $_ -Force -ErrorAction Stop; Write-Host ('  已停止进程 pid=' + $_ + ' (端口 ' + $p + ')') } catch {} } }"

echo.
echo 完成。MySQL 容器保留运行（数据不受影响）；
echo 如需连容器一起停：docker stop learn-hub-mysql
pause
