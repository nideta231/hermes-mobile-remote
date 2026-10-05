# Keeps the bridge running. Started by the "Hermes Mobile Remote" Scheduled Task at logon when no
# desktop is available; also fine to run by hand.
#
# The supervisor policy itself lives in supervise.ps1, shared with the tray app, so there is one
# implementation of "restart on exit 75, back off on a crash" rather than two that can drift.
param(
    [Parameter(Mandatory)][string]$Bin,
    [Parameter(Mandatory)][string]$LogFile
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'supervise.ps1')
Invoke-BridgeSupervisor -Bin $Bin -LogFile $LogFile
