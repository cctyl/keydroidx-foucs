@echo off
setlocal

REM ============================================================
REM  Build debug APK.
REM
REM  Output: app\build\outputs\apk\debug\app-debug.apk
REM ============================================================

set "ROOT=%~dp0"

echo Building debug APK ...
call "%ROOT%gradlew.bat" assembleDebug
if errorlevel 1 (
    echo [ERROR] Build failed. See log above.
    pause
    exit /b 1
)

echo Done. APK at: app\build\outputs\apk\debug\
endlocal
pause
