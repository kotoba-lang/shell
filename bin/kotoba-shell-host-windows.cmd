@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0kotoba-shell-host-windows.ps1" %*
exit /b %ERRORLEVEL%
