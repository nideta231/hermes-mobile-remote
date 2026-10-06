# Hermes Mobile Remote - tray app.
#
# Runs the bridge, shows what state it is in, and handles the things Windows users cannot do
# from a console: trusting a Wi-Fi network, pairing a phone, and turning start-at-logon off.
#
# Supervision: the Scheduled Task starts this at logon, and this supervises the bridge using the
# shared policy in supervise.ps1. Closing the tray stops the bridge - a tray is not a service,
# and that trade is why none of the service machinery is needed here.
#
# Every structured answer comes from the bridge CLI's --json output (doctor, devices, trust), so
# this file never parses English and never reads the bridge's own state files.
param(
    [string]$Bin = (Join-Path $PSScriptRoot '..\.venv\Scripts\hermes-remote-bridge.exe'),
    [string]$LogFile = (Join-Path $env:LOCALAPPDATA 'hermes-remote\state\bridge.log')
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName Microsoft.VisualBasic

# ---------------------------------------------------------------- paths and helpers
$StateDir = Split-Path $LogFile
$ScriptDir = $PSScriptRoot
$SuperviseScript = Join-Path $ScriptDir 'supervise.ps1'
$Bin = [System.IO.Path]::GetFullPath($Bin)
$LogFile = [System.IO.Path]::GetFullPath($LogFile)
New-Item -ItemType Directory -Force -Path $StateDir | Out-Null
. $SuperviseScript

function Write-TrayLog($text) {
    # This window is hidden, so the log is the only record of anything that went wrong.
    Add-Content -Path (Join-Path $StateDir 'tray.log') `
        -Value "$([DateTime]::Now.ToString('s')) tray: $text" -Encoding utf8
}

function Show-Problem($title, $text) {
    [System.Windows.Forms.MessageBox]::Show($text, "Hermes Remote - $title", 'OK', 'Warning') | Out-Null
}

function Invoke-Bridge {
    <# Run a bridge command, return its stdout, or '' when it could not be run. #>
    param([string[]]$BridgeArgs, [int]$TimeoutSeconds = 45)
    try {
        # ProcessStartInfo.ArgumentList is .NET Core only; Windows PowerShell 5.1 needs a string.
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = $Bin
        $psi.Arguments = ($BridgeArgs | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $psi.UseShellExecute = $false
        $psi.CreateNoWindow = $true
        $proc = [System.Diagnostics.Process]::Start($psi)
        # Read before waiting: a full pipe buffer would otherwise deadlock the child.
        $stdout = $proc.StandardOutput.ReadToEnd()
        $stderr = $proc.StandardError.ReadToEnd()
        if (-not $proc.WaitForExit($TimeoutSeconds * 1000)) {
            try { $proc.Kill() } catch { }
            return ''
        }
        return $stdout
    } catch {
        Write-TrayLog "bridge call failed: $($_.Exception.Message)"
        return ''
    }
}

function Get-BridgeJson {
    param([string[]]$BridgeArgs)
    $out = Invoke-Bridge -BridgeArgs $BridgeArgs
    if (-not $out) { return $null }
    try { return ($out | ConvertFrom-Json) } catch {
        Write-TrayLog 'unparsable JSON from the bridge'
        return $null
    }
}

# ---------------------------------------------------------------- state
# Written by the health timer, read by the UI thread, so it is a synchronized hashtable rather
# than loose script variables.
$Health = [hashtable]::Synchronized(@{})
$Health['state'] = 'starting'
$Health['problem'] = 'checking...'
$Health['doctor'] = $null
$Health['trust'] = $null
$Health['devices'] = $null
$Health['refreshed'] = $null

$script:BridgeJob = $null     # the supervisor job, when running
$script:BridgeWanted = $true  # false once the user stops it from the tray
$script:Refreshing = $false   # one health refresh at a time
$script:Ballooned = @{}       # network id -> already asked, so we ask once per network
$script:TrayIcon = $null

function Get-BridgeState {
    if (-not $script:BridgeWanted) { return 'stopped' }
    if ($script:SupervisorProc -and -not $script:SupervisorProc.HasExited) { return 'running' }
    return 'starting'
}

function Set-TrayIcon([string]$State) {
    $colour = switch ($State) {
        'running' { [System.Drawing.Color]::FromArgb(34, 139, 94) }   # green
        'starting' { [System.Drawing.Color]::FromArgb(196, 148, 20) }  # amber
        'stopped' { [System.Drawing.Color]::FromArgb(110, 110, 110) }  # grey
        default { [System.Drawing.Color]::FromArgb(178, 52, 52) }       # red
    }
    $size = [System.Windows.Forms.SystemInformation]::SmallIconSize
    # -ArgumentList, not bare parens: `New-Object Type($a, $b)` converts the arguments to the
    # type instead of passing them, and Bitmap has no such overload.
    $w = $size.Width
    $bmp = New-Object System.Drawing.Bitmap -ArgumentList $w, $size.Height
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    try {
        $g.SmoothingMode = 'AntiAlias'
        $g.Clear([System.Drawing.Color]::Transparent)
        $pad = [Math]::Max(1, [int]($w / 8))
        $r = New-Object System.Drawing.Rectangle -ArgumentList $pad, $pad, ($w - 2 * $pad), ($size.Height - 2 * $pad)
        $g.FillEllipse((New-Object System.Drawing.SolidBrush -ArgumentList $colour), $r)
        # A small light ring and stem, so the icon reads as the bridge rather than a plain dot.
        $pen = New-Object System.Drawing.Pen -ArgumentList ([System.Drawing.Color]::White), ([Math]::Max(1.0, $w / 12.0))
        $g.DrawEllipse($pen, [int]($r.X + $r.Width / 4), [int]($r.Y + $r.Height / 4),
                       [int]($r.Width / 2), [int]($r.Height / 2))
        $g.DrawLine($pen, [int]($w / 2), $r.Y, [int]($w / 2), [int]($r.Y + $r.Height))
        $pen.Dispose()
        $icon = [System.Drawing.Icon]::FromHandle($bmp.GetHicon())
        $script:TrayIcon.Icon = $icon
    } finally {
        $g.Dispose()
        $bmp.Dispose()
    }
}

function Set-TrayText([string]$Text) {
    # NotifyIcon.Text over 63 characters throws rather than truncating.
    if ($Text.Length -gt 63) { $Text = $Text.Substring(0, 63) }
    $script:TrayIcon.Text = $Text
}

# ---------------------------------------------------------------- the supervisor
# The supervisor runs as a real child process (run-bridge.ps1), not as a Start-Job. A PowerShell
# background job lives in a runspace owned by this process, so its children are torn down with the
# tray in a way that is hard to predict when the tray itself was started by a Scheduled Task or a
# hidden Start-Process. A separate process has exactly the lifetime we want: it lives while the
# tray does, and killing the tray's tree takes it and the bridge with it.
$script:SupervisorProc = $null

function Start-Bridge {
    if ($script:SupervisorProc -and -not $script:SupervisorProc.HasExited) { return }
    $script:BridgeWanted = $true
    $runner = Join-Path $ScriptDir 'run-bridge.ps1'
    # Start-Process joins an -ArgumentList array with spaces and does not quote the elements, so a
    # path with a space (which the installer is tested against) would split into extra arguments
    # and the supervisor would exit at once, leaving the tray "starting" forever with no bridge.
    # Build the command line the way the task action does: quoted paths.
    $supervisorArgs = '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "{0}" -Bin "{1}" -LogFile "{2}"' -f $runner, $Bin, $LogFile
    $script:SupervisorProc = Start-Process powershell.exe -PassThru -WindowStyle Hidden -ArgumentList $supervisorArgs
    $Health['state'] = 'starting'
    Update-Tray
    Write-TrayLog "bridge supervisor started (pid $($script:SupervisorProc.Id))"
}

function Stop-Bridge {
    $script:BridgeWanted = $false
    $proc = $script:SupervisorProc
    if ($proc -and -not $proc.HasExited) {
        # Kill the tree: Win32 does not walk descendants, and the bridge is a grandchild here.
        & taskkill.exe /PID $proc.Id /T /F 2>&1 | Out-Null
    }
    $script:SupervisorProc = $null
    Get-Process -Name 'hermes-remote-bridge' -ErrorAction SilentlyContinue | Stop-Process -Force
    $Health['state'] = 'stopped'
    Update-Tray
    Write-TrayLog 'bridge stopped from the tray'
}

# ---------------------------------------------------------------- health
# The polling path is Start-HealthWorker / Apply-HealthResult (a background runspace, below).
# Refresh-Health is the synchronous version, used for a one-off check on the UI thread: the
# menu's "Refresh now" and the double-click handler. It blocks for a few seconds, which is
# acceptable when the user asked for it and not acceptable on a timer.
function Refresh-Health {
    # Synchronous, on the UI thread, only where the user asked for it. It must not touch
    # $script:SupervisorProc: reading a Start-Process handle while the UI thread is inside
    # Application.Run() has no defined behaviour.
    if ($script:Refreshing) { return }
    $script:Refreshing = $true
    try {
        $doctor = Get-BridgeJson @('doctor', '--json')
        $Health['doctor'] = $doctor
        $Health['trust'] = Get-BridgeJson @('trust', '--list', '--json')
        $Health['devices'] = Get-BridgeJson @('devices', '--json')
        $Health['refreshed'] = Get-Date
        if ($doctor) {
            $fails = @($doctor.checks | Where-Object { $_.level -eq 'fail' })
            $Health['problem'] = if ($fails.Count -eq 0) { 'all checks passed' } else {
                ($fails | ForEach-Object { $_.id }) -join ', '
            }
        } else {
            $Health['problem'] = 'doctor did not answer'
        }
    } finally {
        $script:Refreshing = $false
    }
}

function Ask-AboutNetwork {
    # Mirrors the Linux notification: once per new network, and never again after a decline.
    $trust = $Health['trust']
    if (-not $trust -or -not $trust.current) { return }
    $net = $trust.current
    if ($net.trusted) { return }
    if ($script:Ballooned.ContainsKey($net.id)) { return }
    $script:Ballooned[$net.id] = $true
    $answer = [System.Windows.Forms.MessageBox]::Show(
        "New network: $($net.name)`n`nLet your phone reach Hermes over this Wi-Fi without Tailscale?`nOnly for a network you control, like home or office.",
        'Hermes Remote - trust this network?', 'YesNo', 'Question')
    if ($answer -eq 'Yes') {
        Invoke-Bridge -BridgeArgs @('trust') | Out-Null
        $Health['trust'] = Get-BridgeJson @('trust', '--list', '--json')
        Update-Tray
    }
}

function Update-Tray {
    if (-not $script:TrayIcon) { return }
    $state = Get-BridgeState
    $Health['state'] = $state
    # Red when a check fails, so the icon answers "is it working" without opening anything.
    $shown = if ($state -ne 'running') { $state }
             elseif ($Health['problem'] -ne 'all checks passed') { 'broken' }
             else { 'running' }
    Set-TrayIcon $shown
    Set-TrayText "Hermes Remote: $state - $($Health['problem'])"
}

function Update-TrayAndMenu {
    # After anything that changes what the menu would say. The menu is a snapshot, so refreshing
    # the icon alone leaves the Network and Devices entries describing the previous state.
    Update-Tray
    if ($script:TrayIcon) { $script:TrayIcon.ContextMenuStrip = Build-Menu }
}

# ---------------------------------------------------------------- menu
function New-MenuItem {
    param([string]$Text, [scriptblock]$Action, [bool]$Enabled = $true)
    $item = New-Object System.Windows.Forms.ToolStripMenuItem($Text)
    $item.Enabled = $Enabled
    # Events need add_Click: `.Click = {}` throws, even though Get-Member lists Click.
    if ($Action) { $item.add_Click($Action) }
    return $item
}

function Build-Menu {
    $menu = New-Object System.Windows.Forms.ContextMenuStrip
    $state = Get-BridgeState
    $doctor = $Health['doctor']
    $trust = $Health['trust']
    $devices = $Health['devices']

    $menu.Items.Add((New-MenuItem "Bridge: $state" { } $false)) | Out-Null
    $menu.Items.Add((New-MenuItem "Checks: $($Health['problem'])" { } $false)) | Out-Null
    $menu.Items.Add((New-Object System.Windows.Forms.ToolStripSeparator)) | Out-Null

    if ($doctor) {
        $checksMenu = New-Object System.Windows.Forms.ToolStripMenuItem('Details')
        foreach ($check in $doctor.checks) {
            $mark = switch ($check.level) { 'ok' { 'ok  ' } 'warn' { 'warn' } default { 'FAIL' } }
            $checksMenu.DropDownItems.Add((New-MenuItem "$mark $($check.message)" { } $false)) | Out-Null
        }
        $menu.Items.Add($checksMenu) | Out-Null
    }
    $menu.Items.Add((New-MenuItem 'Refresh now' { Refresh-Health; Update-Tray })) | Out-Null
    $menu.Items.Add((New-Object System.Windows.Forms.ToolStripSeparator)) | Out-Null

    $menu.Items.Add((New-MenuItem 'Start bridge' { Start-Bridge } ($state -ne 'running'))) | Out-Null
    $menu.Items.Add((New-MenuItem 'Stop bridge' { Stop-Bridge } ($state -eq 'running'))) | Out-Null
    $menu.Items.Add((New-MenuItem 'Restart bridge' { Stop-Bridge; Start-Sleep 1; Start-Bridge })) | Out-Null
    $menu.Items.Add((New-Object System.Windows.Forms.ToolStripSeparator)) | Out-Null

    # Network trust
    $netMenu = New-Object System.Windows.Forms.ToolStripMenuItem('Network')
    if ($trust -and $trust.current) {
        $current = $trust.current
        if ($current.trusted) {
            $id = $current.id
            $netMenu.DropDownItems.Add((New-MenuItem "Stop trusting $($current.name)" {
                Invoke-Bridge -BridgeArgs @('trust', '--remove', $id) | Out-Null
                $Health['trust'] = Get-BridgeJson @('trust', '--list', '--json')
                Update-TrayAndMenu
            })) | Out-Null
        } else {
            $netMenu.DropDownItems.Add((New-MenuItem "Trust $($current.name)" {
                Invoke-Bridge -BridgeArgs @('trust') | Out-Null
                $Health['trust'] = Get-BridgeJson @('trust', '--list', '--json')
                Update-TrayAndMenu
            })) | Out-Null
        }
    } elseif ($trust) {
        # The poll has answered, and it says there is no network: genuinely offline. Distinguish
        # that from "no answer yet", which is what an empty $trust means.
        $netMenu.DropDownItems.Add((New-MenuItem 'No network (offline)' { } $false)) | Out-Null
    } else {
        $netMenu.DropDownItems.Add((New-MenuItem 'Checking network...' { } $false)) | Out-Null
    }
    if ($trust -and @($trust.trusted).Count -gt 0) {
        $listMenu = New-Object System.Windows.Forms.ToolStripMenuItem('Trusted networks')
        foreach ($entry in $trust.trusted) {
            # GetNewClosure() for the same reason as the device list below: without it every
            # "remove" item would untrust whichever network happened to be last in the list.
            $id = $entry.id
            $dropOne = { Invoke-Bridge -BridgeArgs @('trust', '--remove', $id) | Out-Null
                         $Health['trust'] = Get-BridgeJson @('trust', '--list', '--json')
                         Update-TrayAndMenu }.GetNewClosure()
            $listMenu.DropDownItems.Add((New-MenuItem "$($entry.name) - remove" $dropOne)) | Out-Null
        }
        $netMenu.DropDownItems.Add($listMenu) | Out-Null
    }
    $menu.Items.Add($netMenu) | Out-Null

    # Devices. Only active ones: revoked devices cannot be used and cannot be revoked again, so a
    # row per dead record is pure clutter, and the list only ever grows. The `devices` CLI and
    # doctor still show the full history, which is where knowing what was paired belongs.
    $devMenu = New-Object System.Windows.Forms.ToolStripMenuItem('Devices')
    $devMenu.DropDownItems.Add((New-MenuItem 'Pair a new device...' { Show-PairWindow })) | Out-Null
    $activeDevices = @($devices.devices | Where-Object { $_.active })
    if ($activeDevices.Count -gt 0) {
        $devMenu.DropDownItems.Add((New-Object System.Windows.Forms.ToolStripSeparator)) | Out-Null
        foreach ($device in $activeDevices) {
            # GetNewClosure() is required, not decoration: a bare { $name } inside a loop shares
            # one variable across every closure, so all the revoke items acted on the LAST device
            # in the list and nothing was revoked when you clicked an earlier one. Verified on
            # Windows PowerShell 5.1: bare closures return "gamma, gamma, gamma", while
            # GetNewClosure() returns "alpha, beta, gamma".
            $name = $device.name
            $revokeOne = { Invoke-Bridge -BridgeArgs @('revoke', $name) | Out-Null
                           $Health['devices'] = Get-BridgeJson @('devices', '--json')
                           Update-TrayAndMenu }.GetNewClosure()
            $devMenu.DropDownItems.Add((New-MenuItem "$name - revoke" $revokeOne)) | Out-Null
        }
    }
    $menu.Items.Add($devMenu) | Out-Null
    $menu.Items.Add((New-Object System.Windows.Forms.ToolStripSeparator)) | Out-Null

    $menu.Items.Add((New-MenuItem 'Open log folder' { Start-Process explorer.exe $StateDir })) | Out-Null

    # Firewall. The installer offers this too, but the tray is where someone goes when the phone
    # cannot connect - and on Windows the firewall blocks port 8650 by default, so this is the
    # single most common reason a fresh install looks broken.
    $fwState = $Health['firewall']
    if ($fwState -eq 'blocked') {
        $menu.Items.Add((New-MenuItem 'Firewall is blocking the phone (fix: one UAC prompt)' {
            Show-FirewallHelp
        })) | Out-Null
    } elseif ($null -eq $fwState) {
        $menu.Items.Add((New-MenuItem 'Check the firewall' { Show-FirewallHelp })) | Out-Null
    }

    # Start at logon. A Scheduled Task is invisible in Task Manager -> Startup, so this checkbox
    # is the only place the user can turn it off.
    $startup = New-MenuItem 'Start at logon' { Set-StartAtLogon -Enabled $startup.Checked }
    $startup.CheckOnClick = $true
    $startup.Checked = (Test-StartAtLogon)
    $menu.Items.Add($startup) | Out-Null

    $menu.Items.Add((New-MenuItem 'Quit Hermes Remote' {
        Stop-Bridge
        $script:TrayIcon.Visible = $false
        [System.Windows.Forms.Application]::Exit()
    })) | Out-Null
    return $menu
}

# ---------------------------------------------------------------- firewall
function Show-FirewallHelp {
    # Mirror what `hermes-remote-bridge firewall` does: print the exact rule, then ask. The rule
    # is Private-profile and private ranges only, and the UAC prompt comes from the bridge's own
    # run_elevated, not from here.
    $answer = [System.Windows.Forms.MessageBox]::Show(
        "The Windows firewall is blocking port 8650, so nothing on this network can reach the bridge.`n`n" +
        "Add a rule for your local networks only (private profile, private address ranges).`n" +
        "This needs one UAC prompt.`n`nAdd it now?",
        'Hermes Remote - firewall', 'YesNo', 'Question')
    if ($answer -ne 'Yes') { return }
    $out = Invoke-Bridge -BridgeArgs @('firewall', '--yes') -TimeoutSeconds 180
    $Health['doctor'] = $null   # force the next poll to re-read the checks
    if ($out) {
        [System.Windows.Forms.MessageBox]::Show($out.Trim(), 'Hermes Remote - firewall') | Out-Null
    } else {
        Show-Problem 'firewall' "The rule was not applied. You can add it later with:`n`n" +
            "hermes-remote-bridge firewall`n`nor by hand in Windows Security > Firewall settings."
    }
}

# ---------------------------------------------------------------- start at logon
$TaskName = 'Hermes Mobile Remote'

function Test-StartAtLogon {
    $null -ne (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue)
}

function Set-StartAtLogon([bool]$Enabled) {
    try {
        if ($Enabled) {
            $tray = Join-Path $ScriptDir 'tray.ps1'
            $action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument (
                "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$tray`" -Bin `"$Bin`" -LogFile `"$LogFile`"")
            $trigger = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"
            $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
                -StartWhenAvailable -RestartCount 99 -RestartInterval (New-TimeSpan -Minutes 1) `
                -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew
            $principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" `
                -LogonType Interactive -RunLevel Limited
            Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings `
                -Principal $principal -Force | Out-Null
        } else {
            Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
            Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
        }
    } catch {
        Show-Problem 'start at logon' "Could not change it:`n$($_.Exception.Message)"
    }
}

# ---------------------------------------------------------------- pairing
function Show-PairWindow {
    # The bridge writes the QR as a PNG; draw it in our own window instead of handing the file
    # to the Photos app, which is what the CLI does when nothing can draw it.
    $qr = Join-Path $StateDir 'pairing-qr.png'
    if (Test-Path $qr) { Remove-Item $qr -Force }
    $name = [Microsoft.VisualBasic.Interaction]::InputBox('Name for this phone:', 'Pair a device', 'phone')
    if (-not $name) { return }

    $details = Invoke-Bridge -BridgeArgs @('pair', $name, '--qr-png', $qr) -TimeoutSeconds 60
    if (-not (Test-Path $qr)) {
        Show-Problem 'pairing failed' "No QR code was produced.`n`n$details"
        return
    }

    $form = New-Object System.Windows.Forms.Form
    $form.Text = "Pair $name - scan this in the Hermes Remote app"
    $form.FormBorderStyle = 'FixedDialog'
    $form.MaximizeBox = $false
    $form.MinimizeBox = $false
    $form.TopMost = $true
    $form.StartPosition = 'CenterScreen'
    $form.ClientSize = New-Object System.Drawing.Size(420, 520)

    $pic = New-Object System.Windows.Forms.PictureBox
    $pic.Dock = 'Fill'
    $pic.SizeMode = 'Zoom'
    try { $pic.Image = [System.Drawing.Image]::FromFile($qr) } catch { }
    $form.Controls.Add($pic)

    # Manual entry, for a phone with no usable camera: the token is in the CLI's own output.
    $entry = (($details -split "`r?`n") | Where-Object { $_ -match 'URL:|Token:' }) -join "`r`n"
    $copy = New-Object System.Windows.Forms.Button
    $copy.Text = 'Copy URL and token'
    $copy.Dock = 'Bottom'
    $copy.Height = 32
    $copy.add_Click({
        if ($entry) {
            [System.Windows.Forms.Clipboard]::SetText($entry)
            $copy.Text = 'Copied'
        } else {
            Show-Problem 'copy' 'The pairing details were not in the output, so there is nothing to copy.'
        }
    })
    $form.Controls.Add($copy)
    $form.add_FormClosed({ try { $pic.Image.Dispose() } catch { } })
    $form.ShowDialog() | Out-Null
    try { Remove-Item $qr -Force } catch { }
    $Health['devices'] = Get-BridgeJson @('devices', '--json')
}

# ---------------------------------------------------------------- startup
# Single instance: a second launch would fight over the port and the log.
$mutex = New-Object System.Threading.Mutex($false, 'Global\HermesRemoteTray')
if (-not $mutex.WaitOne(0, $false)) {
    [System.Windows.Forms.MessageBox]::Show(
        'Hermes Remote is already running - look for it in the notification area.', 'Hermes Remote') | Out-Null
    return
}

if (-not (Test-Path $Bin)) {
    [System.Windows.Forms.MessageBox]::Show(
        "The bridge is not installed yet.`n`nRun install.cmd in the project folder first.`n`nExpected:`n$Bin",
        'Hermes Remote') | Out-Null
    $mutex.ReleaseMutex()
    return
}

$script:TrayIcon = New-Object System.Windows.Forms.NotifyIcon
$script:TrayIcon.Text = 'Hermes Remote'
Set-TrayIcon 'starting'
$script:TrayIcon.Visible = $true
$script:TrayIcon.add_MouseDoubleClick({ Refresh-Health; Update-Tray })

# Rebuild the menu just before it opens, so what you read is at most one poll old. Opening a
# stale menu is how "No network (offline)" and an empty device list survive long after the data
# has arrived.
$script:TrayIcon.add_MouseUp({
    if ($_.Button -eq 'Right') { $script:TrayIcon.ContextMenuStrip = Build-Menu }
})

# Health polling. This has to be a WinForms.Timer on the UI thread, NOT a System.Threading.Timer:
# a script block handed to a thread-pool thread has no PowerShell session state there, and calling
# it raises the fatal Management.Automation.ScriptBlock.GetContextFromTLS, which kills the whole
# process with no message and no exit code. That is not theoretical: it is what made the tray
# vanish about fifteen seconds after starting, on a real machine, with an empty log.
#
# To keep the UI responsive anyway, the tick does the slow part (three bridge CLI calls) on a
# background runspace and applies the result on the next tick, so the message loop is never blocked
# for more than a few milliseconds.
$script:HealthPending = $false
$script:HealthShell = $null
$script:HealthHandle = $null

function Start-HealthWorker {
    if ($script:HealthPending) { return }
    $script:HealthPending = $true
    # A real background runspace, not a thread-pool thread. A PowerShell script block moved to a
    # thread-pool thread has no session state there and dies with
    # Management.Automation.ScriptBlock.GetContextFromTLS, taking the tray with it and printing
    # nothing. [PowerShell]::Create() gets its own runspace, so the block has the context it
    # needs and the result comes back through a handle the UI thread collects.
    $script:HealthShell = [PowerShell]::Create()
    $script:HealthShell.AddScript({
        param($BridgeExe)
        $result = @{ doctor = $null; trust = $null; devices = $null; firewall = $null }
        # All three at once: each call is a fresh Python start-up costing several seconds, and
        # running them in sequence made a full poll take ~20 s - long enough that the menu still
        # read "checking..." half a minute after launch.
        $running = @{}
        foreach ($call in @(
            @{ key = 'doctor';  args = @('doctor', '--json') },
            @{ key = 'trust';   args = @('trust', '--list', '--json') },
            @{ key = 'devices'; args = @('devices', '--json') })) {
            try {
                $psi = New-Object System.Diagnostics.ProcessStartInfo
                $psi.FileName = $BridgeExe
                $psi.Arguments = ($call.args | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '
                $psi.RedirectStandardOutput = $true
                $psi.UseShellExecute = $false
                $psi.CreateNoWindow = $true
                $running[$call.key] = [System.Diagnostics.Process]::Start($psi)
            } catch { }
        }
        # Drain every pipe before waiting on any process: a full pipe buffer would block the
        # child, and one blocked child would stall the others.
        foreach ($key in @($running.Keys)) {
            try { $result[$key] = $running[$key].StandardOutput.ReadToEnd() | ConvertFrom-Json } catch { }
        }
        foreach ($key in @($running.Keys)) {
            try { $running[$key].WaitForExit(30000) | Out-Null; $running[$key].Dispose() } catch { }
        }
        $result
    }).AddArgument($Bin)
    $script:HealthHandle = $script:HealthShell.BeginInvoke()
}

function Apply-HealthResult {
    if (-not $script:HealthHandle) { $script:HealthPending = $false; return }
    if (-not $script:HealthHandle.IsCompleted) { return }
    try { $result = $script:HealthShell.EndInvoke($script:HealthHandle) }
    catch { $result = $null }
    finally {
        if ($script:HealthShell) { $script:HealthShell.Dispose() }
        $script:HealthShell = $null
        $script:HealthHandle = $null
        $script:HealthPending = $false
    }
    if (-not $result) { return }
    $Health['doctor'] = $result.doctor
    $Health['trust'] = $result.trust
    $Health['devices'] = $result.devices
    $Health['refreshed'] = Get-Date
    if ($result.doctor) {
        $fails = @($result.doctor.checks | Where-Object { $_.level -eq 'fail' })
        $Health['problem'] = if ($fails.Count -eq 0) { 'all checks passed' } else {
            ($fails | ForEach-Object { $_.id }) -join ', '
        }
        # The firewall check is a fail-level doctor check, so the menu can offer the fix without
        # the bridge growing a second endpoint for it.
        $fwCheck = @($result.doctor.checks | Where-Object { $_.id -eq 'firewall' })[0]
        if ($fwCheck) {
            $Health['firewall'] = if ($fwCheck.level -eq 'fail') { 'blocked' }
                                  elseif ($fwCheck.level -eq 'warn') { 'unknown' }
                                  else { 'open' }
        }
    } else {
        $Health['problem'] = 'doctor did not answer'
    }
    # The menu is a snapshot: without rebuilding it here the Network entry keeps saying
    # "No network (offline)" from the empty menu built at startup, and devices never appear.
    Update-TrayAndMenu
}

$healthTimer = New-Object System.Windows.Forms.Timer
$healthTimer.Interval = 2000
$healthTimer.add_Tick({
    # Collect a finished run first, so the UI thread only ever applies results.
    Apply-HealthResult
    if (-not $script:HealthPending) { Start-HealthWorker }
})
$healthTimer.Start()

# The new-network prompt fires once the first poll has actually reported a network, and only from
# inside the message loop: Ask-AboutNetwork shows a modal dialog, which is only safe when there is
# a pump to service it. Asking before Application.Run() left the tray with a dialog nothing could
# answer, and it died silently a few seconds later.
$script:PromptDone = $false
$promptTimer = New-Object System.Windows.Forms.Timer
$promptTimer.Interval = 2000
$promptTimer.add_Tick({
    if ($script:PromptDone) { $promptTimer.Stop(); return }
    # Wait for real data: asking before the first poll lands would either skip the prompt or ask
    # about a network we have not identified yet.
    if (-not $Health['trust']) { return }
    $script:PromptDone = $true
    $promptTimer.Stop()
    Ask-AboutNetwork
})
$promptTimer.Start()

# ---------------------------------------------------------------- main loop
Start-Bridge
Update-Tray
# Build the menu once so the icon has something to show, then rebuild it as data arrives. Built
# once and left alone it would say "No network (offline)" forever, because at this point no poll
# has completed yet.
$script:TrayIcon.ContextMenuStrip = Build-Menu

try {
    [System.Windows.Forms.Application]::Run()
} finally {
    $healthTimer.Stop()
    $promptTimer.Stop()
    $promptTimer.Dispose()
    if ($script:BridgeWanted) { Stop-Bridge }
    $script:TrayIcon.Dispose()
    $mutex.ReleaseMutex()
}
