#Requires -Version 7.0
#Requires -RunAsAdministrator
param([Parameter(Mandatory=$true)][string]$ReportPath)

# 用户要求本机 Android 模拟器环境时使用；只启用 WHPX，不修改 BIOS、不自动重启。
$ErrorActionPreference = 'Stop'
$status = [ordered]@{ Feature='HypervisorPlatform'; Success=$false; RestartNeeded=$false; Error=$null }
try {
    $feature = Enable-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform -All -NoRestart
    $status.Success = $true
    $status.RestartNeeded = [bool]$feature.RestartNeeded
} catch {
    $status.Error = $_.Exception.Message
} finally {
    $directory = Split-Path -Parent $ReportPath
    [System.IO.Directory]::CreateDirectory($directory) | Out-Null
    [System.IO.File]::WriteAllText($ReportPath, ($status | ConvertTo-Json), [System.Text.UTF8Encoding]::new($false))
}
if (-not $status.Success) { exit 1 }
