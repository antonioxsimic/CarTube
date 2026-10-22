@echo off
setlocal
echo ========================================================
echo   YouTube (CarTube) - Build and Install to Phone
echo ========================================================
echo.

set ADB="C:\Users\Retrowave\AppData\Local\Android\Sdk\platform-tools\adb.exe"
if not exist %ADB% (
    set ADB=adb
)

call gradlew.bat assembleDebug
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Build failed! Check the errors above.
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo Installing CarTube APK to connected device...
%ADB% install -r app\build\outputs\apk\debug\app-debug.apk
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Failed to install APK via ADB. Is your phone connected with USB Debugging on?
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [SUCCESS] YouTube (CarTube) installed successfully!
echo You can now share any video from YouTube ReVanced to "Play on Car".
pause
