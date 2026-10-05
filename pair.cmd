@echo off
rem Pair a phone with the bridge. Double-click this, or run it from any prompt.
rem Checks first that a phone could actually reach this PC, then shows a QR code for the Hermes
rem Remote app to scan. The QR is opened as a picture, because the Windows console draws block
rem characters badly. The picture is deleted once you close it.
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
