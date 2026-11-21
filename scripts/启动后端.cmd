@echo off
setlocal
cd /d "%~dp0.."

echo ============================================
echo   LifeMind backend launcher
echo ============================================
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-backend.ps1"

echo.
echo Services are being started in the background.
echo Wait 30-60s for Java services to register with Nacos.
echo.
echo   Check status : scripts\status-backend.ps1
echo   Stop all     : scripts\stop-backend.ps1
echo.
echo This window can be closed now.
pause
