@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\build.ps1" %*
set "BUILD_EXIT=%ERRORLEVEL%"
endlocal & exit /b %BUILD_EXIT%