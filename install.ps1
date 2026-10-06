<#
.SYNOPSIS
    Install the Hermes Mobile Remote bridge on this PC (Windows 10/11, tray app + Scheduled Task).

.DESCRIPTION
    .\install.ps1              install or update, start the task, offer firewall + pairing
    .\install.ps1 -InstallUv   install uv without asking if it is missing
    .\install.ps1 -Unattended  never prompt (answers "no" to every question; for CI)
    .\install.ps1 -Uninstall   stop and remove the tray, the task and the firewall rule
                               (keeps paired devices and config; add -Purge to delete those too)
    .\install.ps1 -Uninstall -Purge
                               also delete %LOCALAPPDATA%\hermes-remote

    The Scheduled Task starts bridge\windows\tray.ps1, which runs the bridge, shows its state in
    the notification area, and handles trusting a network and pairing a phone. Turn start-at-logon
    off from the tray menu. bridge\windows
un-bridge.ps1 is the headless equivalent for a session
    with no desktop (an RDP session).

    Safe to re-run. Needs no admin rights except for the optional firewall rule, which asks first
    (one UAC prompt). If PowerShell blocks the script, run it as:
        powershell -ExecutionPolicy Bypass -File .\install.ps1

    EXPERIMENTAL: written against Microsoft's documented cmdlets, not yet run on real hardware by
    its author. See docs\PLATFORMS.md for what is verified and what is not.
#>
[CmdletBinding()]
param([switch]$Uninstall, [switch]$Unattended, [switch]$InstallUv, [switch]$Purge)

$ErrorActionPreference = 'Stop'
$Repo     = $PSScriptRoot
$Bridge   = Join-Path $Repo 'bridge'
$TaskName = 'Hermes Mobile Remote'
$Local    = if ($env:LOCALAPPDATA) { $env:LOCALAPPDATA } else { Join-Path $HOME 'AppData\Local' }
$ConfDir  = Join-Path $Local 'hermes-remote\config'
$StateDir = Join-Path $Local 'hermes-remote\state'
$HermesHome = if ($env:HERMES_HOME) { $env:HERMES_HOME } else { Join-Path $Local 'hermes' }
$Bin      = Join-Path $Bridge '.venv\Scripts\hermes-remote-bridge.exe'
$script:tray = Get-Item (Join-Path $Bridge 'windows\tray.ps1') -ErrorAction SilentlyContinue

function Bold($t) { Write-Host $t -ForegroundColor White }
function Info($t) { Write-Host "  $t" }
function Die($t)  { Write-Host "Error: $t" -ForegroundColor Red; exit 1 }
function Ask($q)  { if ($Unattended) { return $false }; $a = Read-Host "  $q [Y/n]"; return ($a -eq '' -or $a -match '^[Yy]') }

# A fresh install of uv (or anything else) edits the PATH stored in the registry, which the running
# PowerShell never re-reads. Pull the persistent entries into this session so the next command
# can find it without asking the user to reopen the terminal.
function Update-SessionPath {
    $have = @($env:Path -split ';')
    $stored = @([Environment]::GetEnvironmentVariable('Path', 'Machine'),
                [Environment]::GetEnvironmentVariable('Path', 'User'),
                (Join-Path $HOME '.local\bin'), (Join-Path $HOME '.cargo\bin')) -join ';'
    foreach ($p in ($stored -split ';')) {
        if ($p -and ($have -notcontains $p)) { $env:Path += ";$p" }
    }
}

function Install-Uv {
    if (Get-Command winget -ErrorAction SilentlyContinue) {
        Info 'Installing uv with winget...'
        try { winget install --id=astral-sh.uv -e --accept-source-agreements --accept-package-agreements | Out-Host } catch { }
        Update-SessionPath
        if (Get-Command uv -ErrorAction SilentlyContinue) { return $true }
    }
    Info 'Installing uv with the official installer (astral.sh)...'
    try { Invoke-RestMethod https://astral.sh/uv/install.ps1 | Invoke-Expression } catch { Info "That failed: $_" }
    Update-SessionPath
    return [bool](Get-Command uv -ErrorAction SilentlyContinue)
}

if ($Uninstall) {
    # The tray supervises the bridge and can be running detached (launched by hand, not as a
    # task child), so it has to be stopped first: unregistering the task while the tray is alive
    # would just let the tray start the bridge again.
    #
    # Two traps here, both hit on a real machine:
    #  - Match on a path-shaped -File argument. A looser "*tray.ps1*" also matches this script's
    #    own command line, and the installer kills itself - which is how this went unnoticed for
    #    several runs. The quote is optional because launchers differ: the Scheduled Task and this
    #    installer pass an unquoted path, tray.cmd passes a quoted one. \S+ covers both without a
    #    character class, and a character class is a trap here: [^\"] is an INVALID .NET regex (a
    #    backslash cannot escape " inside a set) and fails at run time with "Unterminated [] set".
    #    Verified against all four real command-line shapes: 4/4 match, no self-match.
    #  - Kill tolerantly. A process can exit between being listed and being killed, and taskkill
    #    then writes to stderr, which $ErrorActionPreference='Stop' turns into a fatal error that
    #    aborts the uninstall half-done.
    $trayProcs = @(Get-CimInstance Win32_Process -Filter "Name = 'powershell.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine -match '-File\s+\S*[\\/](tray|run-bridge)\.ps1' })
    foreach ($proc in $trayProcs) {
        Info "Stopping the Hermes Remote tray (pid $($proc.ProcessId))..."
        $ErrorActionPreference = 'Continue'
        & taskkill.exe /PID $proc.ProcessId /T /F 2>&1 | Out-Null
        $ErrorActionPreference = 'Stop'
    }
    if ($trayProcs) { Start-Sleep -Seconds 2 }

    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    Get-Process -Name hermes-remote-bridge -ErrorAction SilentlyContinue | Stop-Process -Force
    if (Get-NetFirewallRule -DisplayName $TaskName -ErrorAction SilentlyContinue) {
        Info 'A firewall rule named "Hermes Mobile Remote" exists; removing it needs admin (UAC).'
        $rm = "Remove-NetFirewallRule -DisplayName '$TaskName'"
        Start-Process powershell -Verb RunAs -Wait -ArgumentList '-NoProfile', '-Command', $rm
    }
    if ($Purge -and (Test-Path $Local\hermes-remote)) {
        Remove-Item -Recurse -Force (Join-Path $Local 'hermes-remote')
        Write-Host "Deleted $Local\hermes-remote (devices, config, trusted networks)."
    } else {
        Write-Host "Task and tray removed. Paired devices and settings are still in $ConfDir (delete it to forget them)."
    }
    Write-Host ''
    Write-Host "This installer only enabled Hermes' API server; it left that in place."
    Write-Host "To remove it too, delete the API_SERVER_ENABLED and API_SERVER_KEY lines from"
    Write-Host "$HermesHome\.env"
    exit 0
}

# A ZIP downloaded in a browser marks every file "from the internet", and PowerShell then refuses
# the helper script the Scheduled Task runs. Clearing the mark is harmless on a git clone.
Get-ChildItem -Path $Repo -Recurse -Include *.ps1 -ErrorAction SilentlyContinue |
    Unblock-File -ErrorAction SilentlyContinue

Bold '1/5 Checking requirements'
if ($PSVersionTable.PSVersion.Major -lt 5) { Die 'PowerShell 5.1 or newer is required.' }
Update-SessionPath
if (-not (Get-Command uv -ErrorAction SilentlyContinue)) {
    Info 'uv (the Python package manager the bridge is installed with) was not found.'
    if ($InstallUv -or (Ask 'Install it now? (no admin needed)')) {
        if (-not (Install-Uv)) {
            Die 'Could not install uv. Install it yourself from https://docs.astral.sh/uv/getting-started/installation/ , open a NEW PowerShell window, and run this again.'
        }
        Info 'uv installed.'
    } else {
        Die 'uv is required. Run again with -InstallUv, or install it from https://docs.astral.sh/uv/getting-started/installation/ (winget install --id=astral-sh.uv) and open a NEW PowerShell window.'
    }
}
if (-not (Test-Path $HermesHome)) {
    Die "Hermes Agent not found at $HermesHome. Install Hermes first: https://hermes-agent.nousresearch.com"
}
if (-not (Get-Command tailscale -ErrorAction SilentlyContinue) -and
    -not (Test-Path (Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'))) {
    Info 'Optional: Tailscale is not installed; the phone will only connect on trusted Wi-Fi.'
}
Info 'ok'

Bold '2/5 Hermes API server'
$EnvFile = Join-Path $HermesHome '.env'
if (-not (Test-Path $EnvFile)) { New-Item -ItemType File -Path $EnvFile | Out-Null }
$lines = @(Get-Content $EnvFile -ErrorAction SilentlyContinue)
$changed = $false
if (-not ($lines -match '^API_SERVER_ENABLED=true')) {
    $lines = @($lines | Where-Object { $_ -notmatch '^API_SERVER_ENABLED=' }) + 'API_SERVER_ENABLED=true'
    $changed = $true
}
if (-not ($lines -match '^API_SERVER_KEY=.+') -or ($lines -match '^API_SERVER_KEY=change-me')) {
    $bytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $key = ([Convert]::ToBase64String($bytes) -replace '[/+=]', '').Substring(0, 40)
    $lines = @($lines | Where-Object { $_ -notmatch '^API_SERVER_KEY=' }) + "API_SERVER_KEY=$key"
    $changed = $true
}
if ($changed) {
    # UTF-8 without a BOM: a BOM would glue itself to the first variable name.
    [System.IO.File]::WriteAllLines($EnvFile, [string[]]$lines, (New-Object System.Text.UTF8Encoding($false)))
    Info "Enabled the API server with a fresh key in $EnvFile (loopback only; the key never leaves this PC)."
    Info 'Restarting the Hermes gateway so it picks this up...'
    try { hermes gateway restart *> $null } catch { Info "Couldn't restart it; run 'hermes gateway restart' (or start Hermes) yourself." }
} else {
    Info 'already enabled'
}

Bold '3/5 Installing the bridge'
Push-Location $Bridge
try { uv sync --quiet --no-dev --inexact; if ($LASTEXITCODE -ne 0) { Die 'uv sync failed' } } finally { Pop-Location }
New-Item -ItemType Directory -Force -Path $ConfDir, $StateDir | Out-Null
$ConfFile = Join-Path $ConfDir 'config.toml'
if (-not (Test-Path $ConfFile)) {
    Set-Content -Path $ConfFile -Encoding ASCII -Value "# See bridge\hermes_remote_bridge\config.py for all keys.`nlan = true"
}
Info "installed into $Bridge\.venv"

Bold '4/5 Background task'
# The task starts the tray, and the tray supervises the bridge. run-bridge.ps1 stays as the
# headless path for a session with no desktop (an RDP session, where a tray icon cannot be drawn);
# swap -File below to point the task at it instead.
$script = $script:tray.FullName
$log    = Join-Path $StateDir 'bridge.log'
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument (
    "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$script`" -Bin `"$Bin`" -LogFile `"$log`"")
$trigger  = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -StartWhenAvailable -RestartCount 99 -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited
# An update may have left the previous tray running; it holds the bridge and the port. Same
# path-shaped pattern as the uninstaller, and for the same reasons: a loose "*tray.ps1*" matches
# this script's own command line (so the installer killed itself), while a quoted-only pattern
# misses the Scheduled Task's unquoted path (so it never cleaned up).
$stale = @(Get-CimInstance Win32_Process -Filter "Name = 'powershell.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -and $_.CommandLine -match '-File\s+\S*[\\/](tray|run-bridge)\.ps1' })
foreach ($proc in $stale) {
    Info "Stopping the previous Hermes Remote (pid $($proc.ProcessId))..."
    $ErrorActionPreference = 'Continue'
    & taskkill.exe /PID $proc.ProcessId /T /F 2>&1 | Out-Null
    $ErrorActionPreference = 'Stop'
}
if ($stale) { Start-Sleep -Seconds 2 }
Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings `
    -Principal $principal -Force | Out-Null
Start-ScheduledTask -TaskName $TaskName
Info "starting the tray (bridge log: $log)"

# An AtLogOn task that fails to launch leaves State=Ready and LastTaskResult=2 ("could not
# start"), which looks identical to "not run yet". Start the tray directly instead, so the
# bridge is up in this session whether or not the trigger fires, and the task remains the
# logon path for next time. The tray's single-instance mutex makes the overlap harmless.
#
# Start-Process joins an -ArgumentList array with spaces and does not quote the elements, so a
# path with a space (which this installer is tested against) would split into extra arguments and
# the tray would exit at once. Build the command line the way the task action does: quoted paths.
$trayArgs = '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "{0}" -Bin "{1}" -LogFile "{2}"' -f $script, $Bin, $log
$trayProc = Start-Process powershell.exe -PassThru -WindowStyle Hidden -ArgumentList $trayArgs
Info "tray started (pid $($trayProc.Id))"

# Don't claim success until the bridge answers. 401 is the right answer: it means the bridge is up
# and refusing an unauthenticated request. A bridge that crashes on start would otherwise sit in
# the task's restart loop while this installer printed "running".
$up = $false
for ($i = 0; $i -lt 30 -and -not $up; $i++) {
    Start-Sleep -Seconds 1
    try { $null = Invoke-WebRequest -Uri 'http://127.0.0.1:8650/v1/me' -UseBasicParsing -TimeoutSec 2 }
    catch { if ($_.Exception.Response) { $up = $true } }
}
if ($up) {
    Info 'running; the tray is in the notification area and starts at every logon'
    Info 'Turn that off from the tray menu: Start at logon'
} else {
    Write-Host '  The bridge did not start. Last lines of its log:' -ForegroundColor Yellow
    if (Test-Path $log) { Get-Content $log -Tail 40 | ForEach-Object { Write-Host "    $_" } }
    else { Write-Host '    (no log file was written; the task itself may not have started)' }
    $info = Get-ScheduledTaskInfo -TaskName $TaskName -ErrorAction SilentlyContinue
    if ($info) {
        Write-Host "  Scheduled task last result: $($info.LastTaskResult)" -ForegroundColor Yellow
        Write-Host '  (2 means the task could not start; 267009 means it is running now)' -ForegroundColor Yellow
    }
    if (-not $trayProc.HasExited) {
        Write-Host "  The tray is running (pid $($trayProc.Id)) but the bridge is not answering." -ForegroundColor Yellow
    }
    Die "Fix the problem above, then run the installer again. It is safe to re-run."
}

Bold '5/5 Network'
# The trust question stays here even though the tray can do it: this is the one moment where the
# question can be explained properly, and it is the only step that needs no tray to be running.
if (Ask 'Is this your home or office network (let the phone connect over this Wi-Fi without Tailscale)?') {
    try { & $Bin trust } catch { Info "Couldn't trust it yet: $_" }
}
# The firewall rule is offered in both places on purpose. Here, before a phone has ever been
# paired, so the first connection attempt works; and in the tray menu, which is where someone goes
# when the phone cannot connect and the rule turns out to be missing.
if (-not $Unattended) {
    try { & $Bin firewall --if-needed } catch { }
} else {
    Info 'Skipped the firewall rule (-Unattended). Add it later with: hermes-remote-bridge firewall'
}

Write-Host ''
try { & $Bin doctor } catch { }
Write-Host ''
Bold 'Pair your phone'
# Deliberately not offered here. `pair` from the console writes a QR PNG and hands it to the
# default image handler - on Windows, the Photos app - and the token is then sitting in a file on
# disk. The tray pairs through its own window instead (Devices > Pair a new device), shows the QR
# there, and deletes the picture when the window closes. So the installer only points at it.
Info 'The tray is in the notification area, near the clock.'
Info 'Pair from there: Devices > Pair a new device, then scan the code in the app.'
Info 'The app itself is an APK from the GitHub releases page.'
