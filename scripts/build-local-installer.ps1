param(
  [string]$OutputDir = "./dist/local-installer",
  [string]$Version = "2.0.0"
)

$ErrorActionPreference = "Stop"

if (-not (Get-Command jpackage -ErrorAction SilentlyContinue)) {
  throw "未找到 jpackage。请在 Windows 上安装并配置 Java 25 JDK。"
}

Write-Host "Building Farm Local Edition jar..."
mvn -q clean package -DskipTests

$jar = Join-Path (Get-Location) "target/Farm-0.0.1-SNAPSHOT.jar"
if (-not (Test-Path $jar -PathType Leaf)) {
  throw "未找到后端 jar: $jar"
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$output = (Resolve-Path $OutputDir).Path
$inputDir = Join-Path $output "input"
New-Item -ItemType Directory -Force -Path $inputDir | Out-Null
Copy-Item $jar (Join-Path $inputDir (Split-Path $jar -Leaf)) -Force

$args = @(
  "--type", "exe",
  "--name", "FarmLocal",
  "--app-version", $Version,
  "--vendor", "Farm",
  "--description", "Farm 局域网 3D 打印农场管理系统（Local Edition）",
  "--input", $inputDir,
  "--main-jar", (Split-Path $jar -Leaf),
  "--dest", $output,
  "--java-options", "-Dfile.encoding=UTF-8",
  "--java-options", "-Dspring.profiles.active=local",
  "--win-dir-chooser",
  "--win-menu",
  "--win-menu-group", "Farm",
  "--win-shortcut",
  "--win-console"
)

Write-Host "Building Windows installer..."
& jpackage @args
if ($LASTEXITCODE -ne 0) { throw "jpackage 构建失败，退出码: $LASTEXITCODE" }
Write-Host "Installer created under: $output"
