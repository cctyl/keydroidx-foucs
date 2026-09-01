@echo off
setlocal

REM ============================================================
REM  Build release APK (skip lint, as lint breaks the
REM  release build), then open the output folder in Explorer.
REM
REM  Output: app\build\outputs\apk\release\app-release.apk
REM
REM  Usage: build_release.bat
REM ============================================================

set "ROOT=%~dp0"
set "OUTDIR=%ROOT%app\build\outputs\apk\release"

echo [1/2] Building release APK (may take a while) ...
call "%ROOT%gradlew.bat" assembleRelease -x lint
if errorlevel 1 (
    echo [ERROR] Build failed. See log above.
    pause
    exit /b 1
)

echo [2/2] Opening output folder:
echo   %OUTDIR%
start "" "%OUTDIR%"

echo Done.
endlocal
pause
