@echo off
rem Double-click or run from any prompt. Starts install.ps1 with the execution policy relaxed for
rem this one run only, so a stock Windows ("running scripts is disabled") needs no setup.
rem Extra arguments pass through, e.g.  install.cmd -InstallUv   or   install.cmd -Uninstall
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" %*
set RC=%ERRORLEVEL%
if not "%RC%"=="0" echo.& echo The installer stopped. Read the message above.
echo.
pause
exit /b %RC%
