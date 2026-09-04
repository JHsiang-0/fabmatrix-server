param(
    [string]$InstallDir = 'C:\Program Files\Farm',
    [string]$DataRoot = 'C:\ProgramData\Farm'
)

$ErrorActionPreference = 'Continue'
$winSw = Join-Path (Join-Path $InstallDir 'service') 'FarmService.exe'
if (Test-Path -LiteralPath $winSw) {
    & $winSw stop 2>$null | Out-Null
    Start-Sleep -Seconds 2
    & $winSw uninstall 2>$null | Out-Null
}

$service = Get-Service -Name 'Farm' -ErrorAction SilentlyContinue
if ($null -ne $service) {
    & sc.exe delete Farm | Out-Null
}

Write-Output "Farm service removed. Data retained at: $DataRoot"
