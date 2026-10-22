@echo off
setlocal
echo ========================================================
echo   YouTube (CarTube) - Starting Desktop Head Unit
echo ========================================================
echo.

set ADB="C:\Users\Retrowave\AppData\Local\Android\Sdk\platform-tools\adb.exe"
if not exist %ADB% (
    set ADB=adb
)

taskkill /f /im desktop-head-unit.exe >nul 2>&1

echo Forwarding Android Auto port 5277...
%ADB% forward tcp:5277 tcp:5277
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Could not forward port via ADB.
    pause
    exit /b %ERRORLEVEL%
)

echo Port 5277 forwarded successfully.
echo Launching DHU Widescreen (BMW 10.25 aspect)...
"C:\Users\Retrowave\AppData\Local\Android\Sdk\extras\google\auto\desktop-head-unit.exe" -c "C:\Users\Retrowave\AppData\Local\Android\Sdk\extras\google\auto\config\default_wide.ini"

pause
