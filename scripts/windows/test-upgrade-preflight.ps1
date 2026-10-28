# Exercises only disposable fixture processes, never the installed service/tray.
$ErrorActionPreference = 'Stop'
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $scriptRoot 'service\installation-progress.ps1')
$tokens = $null
$errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile(
    (Join-Path $scriptRoot 'service\capture-upgrade-state.ps1'), [ref]$tokens, [ref]$errors)
if ($errors.Count -ne 0) { throw 'Preflight source failed to parse.' }
foreach ($name in @('Get-NormalizedFullPath', 'Test-PathWithinDirectory', 'Test-FabMatrixExecutablePath',
    'Get-FabMatrixProcessSnapshot', 'Get-FabMatrixTrayProcesses', 'Request-FabMatrixTrayExit', 'Wait-FabMatrixProcessesExited')) {
    $function = $ast.Find({ param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
    }, $true)
    if ($null -eq $function) { throw "Missing preflight function: $name" }
    Invoke-Expression $function.Extent.Text
}
$trayExecutableNames = @('FabMatrixTray.exe', 'FarmTray.exe')
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$fixtureRoot = Join-Path $tempRoot ('FabMatrix-preflight-performance-' + [guid]::NewGuid().ToString('N'))
$ownedRoot = Join-Path $fixtureRoot 'installation'
$outsideRoot = Join-Path $fixtureRoot 'another-installation'
$LogPath = Join-Path $fixtureRoot 'timing.log'
$ProgressFile = Join-Path $fixtureRoot 'progress.ini'
$processes = @()
try {
    New-Item -ItemType Directory -Force -Path $ownedRoot, $outsideRoot | Out-Null
    $source = Join-Path $fixtureRoot 'fixture.cs'
    [IO.File]::WriteAllText($source, 'class Fixture { static void Main() { System.Threading.Thread.Sleep(System.Threading.Timeout.Infinite); } }')
    $compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
    if (-not (Test-Path -LiteralPath $compiler)) { throw '.NET Framework C# compiler is required for fixture regression.' }
    $ownedExe = Join-Path $ownedRoot 'FabMatrixTray.exe'
    & $compiler /nologo /target:winexe "/out:$ownedExe" $source
    if ($LASTEXITCODE -ne 0) { throw 'Fixture compilation failed.' }
    $outsideExe = Join-Path $outsideRoot 'FabMatrixTray.exe'
    $serverExe = Join-Path $ownedRoot 'FabMatrixServer.exe'
    Copy-Item -LiteralPath $ownedExe -Destination $outsideExe
    Copy-Item -LiteralPath $ownedExe -Destination $serverExe
    $owned = Start-Process -FilePath $ownedExe -WindowStyle Hidden -PassThru
    $processes += $owned
    $outside = Start-Process -FilePath $outsideExe -WindowStyle Hidden -PassThru
    $processes += $outside
    $server = Start-Process -FilePath $serverExe -WindowStyle Hidden -PassThru
    $processes += $server
    if ($owned.MainWindowHandle -ne 0) { throw 'Expected a windowless tray fixture.' }
    Set-FabMatrixInstallStage 'close previous tray' $ProgressFile $LogPath
    $watch = [Diagnostics.Stopwatch]::StartNew()
    Request-FabMatrixTrayExit $ownedRoot
    if (-not $owned.WaitForExit(1000)) { throw 'Windowless tray was not closed.' }
    if ($watch.Elapsed.TotalSeconds -ge 15) { throw 'Tray cleanup exceeded its bounded performance budget.' }
    if ($outside.HasExited -or $server.HasExited) { throw 'Tray cleanup affected another installation or a backend process.' }
    Write-Output "PASS: windowless tray closed in $($watch.ElapsedMilliseconds)ms; outside tray and backend untouched."

    $pending = Wait-FabMatrixProcessesExited $ownedRoot 0
    if ($pending -isnot [bool] -or $pending) { throw 'Progress output corrupted the false process-wait result.' }
    Stop-Process -InputObject $server -Force
    [void]$server.WaitForExit(3000)
    $finished = Wait-FabMatrixProcessesExited $ownedRoot 1
    if ($finished -isnot [bool] -or -not $finished) { throw 'Process-wait result was not a single true boolean.' }
    Set-FabMatrixInstallStage 'preflight complete' $ProgressFile $LogPath
    if ([IO.File]::ReadAllText($ProgressFile) -notmatch 'Stage=preflight complete' -or
        [IO.File]::ReadAllText($LogPath) -notmatch 'STAGE COMPLETE.*elapsedMs=') { throw 'Stage progress or timing was not published.' }
    Write-Output 'PASS: process wait booleans, progress and phase timings.'
} finally {
    foreach ($process in $processes) {
        try { if (-not $process.HasExited) { Stop-Process -InputObject $process -Force; [void]$process.WaitForExit(3000) } }
        finally { $process.Dispose() }
    }
    $resolvedFixture = [IO.Path]::GetFullPath($fixtureRoot)
    if (-not $resolvedFixture.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
        (Split-Path $resolvedFixture -Leaf) -notmatch '^FabMatrix-preflight-performance-[a-f0-9]{32}$') { throw 'Unsafe fixture cleanup path.' }
    if (Test-Path -LiteralPath $resolvedFixture) { Remove-Item -LiteralPath $resolvedFixture -Recurse -Force }
}
