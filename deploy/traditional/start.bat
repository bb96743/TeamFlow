@echo off
REM ==========================================================================
REM TeamFlow traditional deployment launcher (Windows CMD wrapper)
REM Delegates to start.ps1 (background mode + health check).
REM Usage:
REM   start.bat
REM   start.bat -DbName teamflow_deploy_check -Port 8080
REM ==========================================================================
setlocal EnableExtensions
cd /d "%~dp0"

where java >nul 2>&1
if errorlevel 1 (
    echo [FAIL] java not found in PATH. Install JDK 21 and add it to PATH.
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" -Background %*
if errorlevel 1 (
    echo [FAIL] backend start failed. See deploy\logs\*.log
    exit /b 1
)
echo [OK] backend start command finished (see deploy\logs\backend.pid / *.log)
exit /b 0
