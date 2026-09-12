param(
  [string]$OutputDir = 'dist\windows-installer',
  [ValidatePattern('^\d+\.\d+\.\d+$')]
  [string]$Version = '2.0.0',
  [ValidateSet('Full', 'AppImage', 'Payload')]
  [string]$Mode = 'Full',
  [switch]$SkipTests,
  [switch]$NoClean
)

$ErrorActionPreference = 'Stop'
$formalEntryPoint = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) 'windows\build-installer.ps1'
if (-not (Test-Path -LiteralPath $formalEntryPoint -PathType Leaf)) {
  throw "正式 Windows 构建入口不存在: $formalEntryPoint"
}

Write-Host 'scripts/build-local-installer.ps1 is a compatibility wrapper.'
& $formalEntryPoint -OutputDir $OutputDir -Version $Version -Mode $Mode -SkipTests:$SkipTests -NoClean:$NoClean
if ($LASTEXITCODE -ne 0) { throw "正式构建入口失败: $LASTEXITCODE" }
