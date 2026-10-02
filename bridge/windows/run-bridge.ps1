# Keeps the bridge running. Started by the "Hermes Mobile Remote" Scheduled Task at logon; also fine
# to run by hand.
#
#   exit 75      the set of addresses to serve changed (new Wi-Fi): restart straight away
#   any other    a crash or a kill: restart after a short pause, longer if it keeps failing fast
#
# This loop does the restarting rather than the Scheduled Task's own "restart on failure", which
# only retries at one-minute granularity and is easy to defeat. It stops when the task is stopped
# (install.cmd -Uninstall, or Stop-ScheduledTask), which ends this script with it.
param([Parameter(Mandatory)][string]$Bin, [Parameter(Mandatory)][string]$LogFile)

$ErrorActionPreference = 'Continue'
New-Item -ItemType Directory -Force -Path (Split-Path $LogFile) | Out-Null

function Write-Log($text) {
    "$([DateTime]::Now.ToString('s')) runner: $text" | Out-File -FilePath $LogFile -Append -Encoding utf8
}

$quickFailures = 0
while ($true) {
    if ((Test-Path $LogFile) -and (Get-Item $LogFile).Length -gt 2MB) {
        Move-Item -Force $LogFile "$LogFile.1"
    }
    $started = Get-Date
    # Windows PowerShell 5.1's `*>>` writes UTF-16, which most tools show as garbage. Stringify each
    # line and append it as UTF-8 instead.
    & $Bin serve 2>&1 | ForEach-Object { "$_" } | Out-File -FilePath $LogFile -Append -Encoding utf8
    $code = $LASTEXITCODE
    $ranFor = ((Get-Date) - $started).TotalSeconds

    if ($code -eq 75) {
        $quickFailures = 0
        Write-Log 'network changed; restarting'
        Start-Sleep -Seconds 2
        continue
    }
    # Failing within 20 s of starting means something is wrong (bad config, port taken): back off
    # instead of spinning. A bridge that ran for a while and then died restarts quickly.
    $quickFailures = if ($ranFor -lt 20) { [Math]::Min($quickFailures + 1, 12) } else { 0 }
    $wait = [Math]::Max(3, [Math]::Min(60, 5 * $quickFailures))
    Write-Log "bridge exited with code $code after $([int]$ranFor)s; restarting in ${wait}s"
    Start-Sleep -Seconds $wait
}
