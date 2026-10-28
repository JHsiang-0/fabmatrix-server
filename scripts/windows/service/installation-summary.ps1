param(
    [string]$InstallDir,
    [string]$OutputPath,
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'

function Get-FabMatrixSummaryPort([string]$MetadataPath) {
    if (-not (Test-Path -LiteralPath $MetadataPath -PathType Leaf)) { return $null }
    $value = [IO.File]::ReadAllText($MetadataPath).Trim()
    if ($value -notmatch '^\d{1,5}$') { return $null }
    $port = 0
    if (-not [int]::TryParse($value, [ref]$port) -or $port -lt 1 -or $port -gt 65535) { return $null }
    return $port
}

function Get-FabMatrixSummaryState([string]$ServiceStatus, [bool]$Healthy) {
    if ($ServiceStatus -eq 'Stopped') { return 'STOPPED' }
    if ($ServiceStatus -ne 'Running') { return 'UNKNOWN' }
    if ($Healthy) { return 'READY' }
    return 'UNAVAILABLE'
}

if ($SelfTest) {
    if ((Get-FabMatrixSummaryState 'Stopped' $false) -ne 'STOPPED' -or
        (Get-FabMatrixSummaryState 'Running' $true) -ne 'READY' -or
        (Get-FabMatrixSummaryState 'Running' $false) -ne 'UNAVAILABLE' -or
        (Get-FabMatrixSummaryState 'Missing' $true) -ne 'UNKNOWN') {
        throw 'Installation summary state classification failed.'
    }
    $fixture = Join-Path ([IO.Path]::GetTempPath()) ('FabMatrix-summary-' + [guid]::NewGuid().ToString('N') + '.txt')
    try {
        foreach ($sample in @('29090', '8080', '1', '65535')) {
            [IO.File]::WriteAllText($fixture, $sample)
            if ((Get-FabMatrixSummaryPort $fixture) -ne [int]$sample) { throw 'Valid effective port rejected.' }
        }
        foreach ($sample in @('0', '65536', 'invalid', '28081/other', '')) {
            [IO.File]::WriteAllText($fixture, $sample)
            if ($null -ne (Get-FabMatrixSummaryPort $fixture)) { throw 'Invalid metadata must not produce a guessed address.' }
        }
    } finally { if (Test-Path -LiteralPath $fixture) { Remove-Item -LiteralPath $fixture } }
    Write-Output 'FabMatrix installation summary self-test passed.'
    exit 0
}

if ([string]::IsNullOrWhiteSpace($InstallDir) -or [string]::IsNullOrWhiteSpace($OutputPath)) {
    throw 'InstallDir and OutputPath are required.'
}
. (Join-Path $PSScriptRoot 'health-check.ps1')
$effectivePort = Get-FabMatrixSummaryPort (Join-Path $InstallDir 'service-state\effective-port.txt')
$service = Get-Service -Name 'FabMatrixServer' -ErrorAction SilentlyContinue
$serviceStatus = if ($null -eq $service) { 'Missing' } else { [string]$service.Status }
$healthy = $false
if ($serviceStatus -eq 'Running' -and $null -ne $effectivePort) {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$effectivePort/actuator/health" -TimeoutSec 3
        $healthy = Test-FabMatrixHealthResponse -Response $response
    } catch { $healthy = $false }
}
$lines = @('[Installation]', ('State=' + (Get-FabMatrixSummaryState $serviceStatus $healthy)))
if ($null -ne $effectivePort) {
    $lines += "LocalAddress=http://127.0.0.1:$effectivePort"
    try {
        $privateAddress = [Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces() |
            Where-Object { $_.OperationalStatus -eq 'Up' -and $_.NetworkInterfaceType -ne 'Loopback' } |
            ForEach-Object { $_.GetIPProperties().UnicastAddresses } |
            Where-Object { $_.Address.AddressFamily -eq 'InterNetwork' -and
                $_.Address.IPAddressToString -match '^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.)' } |
            Select-Object -First 1
        if ($null -ne $privateAddress) { $lines += "LanAddress=http://$($privateAddress.Address.IPAddressToString):$effectivePort" }
    } catch { }
}
# UTF-16 BOM is recognized by the Windows INI API used by Inno Setup.
[IO.File]::WriteAllLines($OutputPath, $lines, [Text.Encoding]::Unicode)
