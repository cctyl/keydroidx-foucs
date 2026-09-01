@echo off
setlocal

REM ============================================================
REM  Install the latest debug APK via adb.
REM  When multiple devices are connected the install runs in
REM  parallel for every device; one device failing does NOT
REM  block the others.
REM
REM  Usage:
REM    install_debug.bat            (install to ALL connected devices)
REM    install_debug.bat <serial>   (install only to the given device)
REM ============================================================

set "SCRIPT=%~dp0install_debug.py"

if not exist "%SCRIPT%" (
    echo [ERROR] install_debug.py not found next to this bat.
    pause
    exit /b 1
)

python "%SCRIPT%" %*
set "RC=%errorlevel%"

if not "%RC%"=="0" (
    echo [ERROR] Install failed. Check the per-device results above.
    pause
    exit /b %RC%
)

echo Install done.
endlocal
pause
