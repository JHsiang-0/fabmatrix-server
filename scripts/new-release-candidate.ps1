param(
    [ValidatePattern('^v1\.0\.0-rc\.\d+$')]
    [string]$CandidateVersion = 'v1.0.0-rc.1',
    [ValidateSet('PASS', 'NOT RUN', 'FAIL')]
    [string]$AutomaticGateStatus = 'NOT RUN',
    [string]$OutputPath = '',
    [string[]]$ArtifactPath = @()
)

$ErrorActionPreference = 'Stop'
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$serverRoot = (Resolve-Path (Join-Path $scriptRoot '..')).Path
$lines = @(
    '# FabMatrix 1.0.0 准正式版候选验收记录',
    '',
    "候选版本：$CandidateVersion",
    '产品版本：1.0.0',
    "生成时间（UTC）：$([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'))",
    '',
    '## 自动门禁',
    '',
    "- 前端/后端自动发布门禁：$AutomaticGateStatus",
    '- 说明：PASS 仅表示脚本列出的自动检查完成，不代表真实 Windows 或真实设备验收完成。',
    '',
    '## 真实 Windows 验收（必须由现场记录）',
    '',
    '- 首次安装：NOT RUN — 需要干净 Windows 主机、实际安装包和健康检查记录。',
    '- 升级安装：NOT RUN — 需要已有数据的 1.0.0 前版本和升级后数据保留证据。',
    '- 普通卸载（保留数据）：NOT RUN — 需要确认服务、程序文件和业务数据边界。',
    '- 完全卸载（清理数据）：NOT RUN — 需要二次确认、清理结果和重新初始化证据。',
    '',
    '## 真实设备验收',
    '',
    '- 登录、WebSocket、上传、任务和打印控制：NOT RUN — 需要现场账号、真实打印机和可追溯操作记录。',
    '- 任何未执行项目不得改写为 PASS。',
    '',
    '## 产物校验',
    ''
)

if ($ArtifactPath.Count -eq 0) {
    $lines += '- SHA-256：NOT RUN — 未提供产物路径；使用 `Get-FileHash -Algorithm SHA256` 生成后附加。'
} else {
    foreach ($inputPath in $ArtifactPath) {
        $resolved = (Resolve-Path $inputPath).Path
        $item = Get-Item -LiteralPath $resolved
        if ($item.PSIsContainer) {
            $files = @(Get-ChildItem -LiteralPath $resolved -Recurse -File | Sort-Object FullName)
            foreach ($file in $files) {
                $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToUpperInvariant()
                $relative = [IO.Path]::GetRelativePath($serverRoot, $file.FullName).Replace('\', '/')
                $lines += "- $relative — SHA-256: $hash"
            }
        } else {
            $hash = (Get-FileHash -LiteralPath $resolved -Algorithm SHA256).Hash.ToUpperInvariant()
            $relative = [IO.Path]::GetRelativePath($serverRoot, $resolved).Replace('\', '/')
            $lines += "- $relative — SHA-256: $hash"
        }
    }
}

$record = ($lines -join [Environment]::NewLine) + [Environment]::NewLine
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    Write-Output $record
} else {
    $output = [IO.Path]::GetFullPath($OutputPath)
    $parent = Split-Path -Parent $output
    if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    [IO.File]::WriteAllText($output, $record, [Text.UTF8Encoding]::new($false))
    Write-Host "Release candidate acceptance record written: $output"
}
