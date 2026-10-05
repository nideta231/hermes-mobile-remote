@echo off
rem Start the Hermes Remote tray app. Double-click this, or run it from any prompt.
rem
rem You only need this if the tray is not already running - it starts itself at every logon.
rem If it is already running, this says so and exits; two trays cannot coexist.
rem
rem Closing this window does not stop anything: the tray lives in the notification area. To stop
rem the bridge, use the tray menu (Quit Hermes Remote).
set BIN=%~dp0bridge\.venv\Scripts\hermes-remote-bridge.exe
set TRAY=%~dp0bridge\windows\tray.ps1
if not exist "%BIN%" (
  echo The bridge is not installed yet. Run install.cmd first.
  echo.
  pause
  exit /b 1
)

rem Already up? Then there is nothing to do, and a second tray would only hit the mutex.
rem Two traps here, both hit on a real machine:
rem  - This probe's own command line contains the pattern text, so the pattern must anchor on the
rem    quoted -File form that a real launch does not use. Launchers differ: tray.cmd quotes the
rem    path, the Scheduled Task and install.ps1 do not, so accept both, and require a path
rem    separator before the file name so the pattern's own text cannot match.
powershell -NoProfile -Command "if (Get-CimInstance Win32_Process -Filter \"Name = 'powershell.exe'\" | Where-Object { $_.CommandLine -match '-File\s+\S*[\\/]tray\.ps1' }) { exit 0 } else { exit 1 }"
if "%ERRORLEVEL%"=="0" (
  echo Hermes Remote is already running - look for it in the notification area.
  echo.
  pause
  exit /b 0
)

echo Starting Hermes Remote. Look for it in the notification area, near the clock.
start "" powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%TRAY%" -Bin "%BIN%"
ping -n 3 127.0.0.1 >nul
echo.
echo If nothing appears, the tray log says why: "%LOCALAPPDATA%\hermes-remote\state\tray.log"
echo.
pause
