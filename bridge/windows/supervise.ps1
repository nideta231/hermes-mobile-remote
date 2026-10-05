# The bridge supervisor: run the bridge, and keep it running.
#
# Shared by two entry points so the policy exists once:
#   bridge/windows/run-bridge.ps1  headless (a Scheduled Task, an RDP session, no desktop)
#   bridge/windows/tray.ps1        the tray app, which also draws a status icon
#
#   exit 75      the set of addresses to serve changed (new Wi-Fi): restart straight away
#   any other    a crash or a kill: restart after a short pause, longer if it keeps failing fast
#
# This loop does the restarting rather than the Scheduled Task's own "restart on failure", which
# only retries at one-minute granularity and is easy to defeat. It stops when the task is stopped
# (install.cmd -Uninstall, or Stop-ScheduledTask), which ends the calling script with it.
function Invoke-BridgeSupervisor {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Bin,
        [Parameter(Mandatory)][string]$LogFile,
        # Called instead of restarting when the process should not be started at all (tray stopped).
        [int]$MaxRestarts = -1
    )

    $ErrorActionPreference = 'Continue'
    New-Item -ItemType Directory -Force -Path (Split-Path $LogFile) | Out-Null

    $quickFailures = 0
    $restarts = 0
    while ($true) {
        if ($MaxRestarts -ge 0 -and $restarts -gt $MaxRestarts) {
            Write-SupervisorLog $LogFile "not restarting again (stopped from the tray)"
            return
        }
        if ((Test-Path $LogFile) -and (Get-Item $LogFile).Length -gt 2MB) {
            Move-Item -Force $LogFile "$LogFile.1"
        }
        $started = Get-Date
        # Windows PowerShell 5.1's `*>>` writes UTF-16, which most tools show as garbage. Stringify each
        # line and append it as UTF-8 instead.
        & $Bin serve 2>&1 | ForEach-Object { "$_" } | Out-File -FilePath $LogFile -Append -Encoding utf8
        $code = $LASTEXITCODE
        $ranFor = ((Get-Date) - $started).TotalSeconds

        # Did this run stop because the port was taken? The bridge exits 1 with one clear sentence,
        # and the bridge is long-lived, so a short run that mentions the port is a conflict.
        $seenPortConflict = $false
        if ($ranFor -lt 20) {
            try {
                $fresh = Get-Content $LogFile -Tail 20 -Encoding utf8 -ErrorAction SilentlyContinue |
                    Select-Object -Last 8
                if ($fresh -and ($fresh -match 'already in use')) { $seenPortConflict = $true }
            } catch { }
        }

        if ($code -eq 75) {
            $quickFailures = 0
            $restarts++
            Write-SupervisorLog $LogFile 'network changed; restarting'
            Start-Sleep -Seconds 2
            continue
        }
        # A port conflict cannot fix itself: another bridge owns 8650 and will keep it. Retrying
        # forever turns one clear message into an endless log, so stop and let the human decide.
        if ($code -eq 1 -and $seenPortConflict) {
            Write-SupervisorLog $LogFile 'another bridge is using the port; not restarting'
            return
        }
        # Failing within 20 s of starting means something is wrong (bad config, port taken): back off
        # instead of spinning. A bridge that ran for a while and then died restarts quickly.
        $quickFailures = if ($ranFor -lt 20) { [Math]::Min($quickFailures + 1, 12) } else { 0 }
        $restarts++
        $wait = [Math]::Max(3, [Math]::Min(60, 5 * $quickFailures))
        Write-SupervisorLog $LogFile "bridge exited with code $code after $([int]$ranFor)s; restarting in ${wait}s"
        Start-Sleep -Seconds $wait
    }
}

# Defined before use: the loop above calls this on every exit, and PowerShell runs top to bottom.
function Write-SupervisorLog($LogFile, $text) {
    "$([DateTime]::Now.ToString('s')) supervisor: $text" | Out-File -FilePath $LogFile -Append -Encoding utf8
}
