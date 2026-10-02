@echo off
rem Pair a phone with the bridge. Double-click this, or run it from any prompt.
rem Checks first that a phone could actually reach this PC, then shows a QR code (opened as a
rem picture) for the Hermes Remote app to scan.
set BIN=%~dp0bridge\.venv\Scripts\hermes-remote-bridge.exe
if not exist "%BIN%" (
  echo The bridge is not installed yet. Run install.cmd first.
  echo.
  pause
  exit /b 1
)
echo === Can a phone reach this PC? ===
"%BIN%" doctor
echo.
set NAME=phone
set /p NAME=Name for this phone [phone]: 
if "%NAME%"=="" set NAME=phone
echo.
"%BIN%" pair "%NAME%"
set RC=%ERRORLEVEL%
if not "%RC%"=="0" (
  echo.
  echo Pairing failed. The lines above say why.
)
echo.
pause
exit /b %RC%
