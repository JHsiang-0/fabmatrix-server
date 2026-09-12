param(
    [string]$InstallDir = 'C:\Program Files\FabMatrix',
    [string]$DataRoot = 'C:\ProgramData\FabMatrix',
    [ValidateRange(1, 65535)]
    [int]$Port = 8080
)

$ErrorActionPreference = 'Stop'

function Get-ServiceIfPresent([string]$Name) {
    return Get-Service -Name $Name -ErrorAction SilentlyContinue
}

function Stop-ServiceIfPresent([string]$Name) {
    $service = Get-ServiceIfPresent $Name
    if ($null -eq $service -or $service.Status -eq [System.ServiceProcess.ServiceControllerStatus]::Stopped) { return }
    Stop-Service -InputObject $service -ErrorAction Stop
    $service.WaitForStatus([System.ServiceProcess.ServiceControllerStatus]::Stopped, [TimeSpan]::FromSeconds(30))
    $service.Refresh()
    if ($service.Status -ne [System.ServiceProcess.ServiceControllerStatus]::Stopped) {
        throw "服务未能停止，卸载已中止: $Name"
    }
}

function Remove-ServiceIfPresent([string]$Name) {
    $service = Get-ServiceIfPresent $Name
    if ($null -eq $service) { return }
    Stop-ServiceIfPresent $Name
    & sc.exe delete $Name | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Windows 服务删除失败 ($LASTEXITCODE): $Name" }
}

Remove-ServiceIfPresent 'FabMatrixServer'
# 兼容清理历史服务注册；不触碰历史 Farm 数据目录。
Remove-ServiceIfPresent 'Farm'

if (-not (Get-Command Get-NetFirewallRule -ErrorAction SilentlyContinue)) {
    throw '当前 Windows 没有可用的 Windows Firewall PowerShell cmdlet，无法确认私有网络规则已移除。'
}
$ruleName = 'FabMatrixServer-Private-TCP'
$rule = Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue
if ($null -ne $rule) {
    Remove-NetFirewallRule -Name $ruleName -ErrorAction Stop
}

Write-Output "FabMatrixServer service and private firewall rule removed. Business data retained at: $DataRoot"
