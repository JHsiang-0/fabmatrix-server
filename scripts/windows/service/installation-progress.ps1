# Shared, non-sensitive stage reporting for the installer UI and diagnostic log.
$script:installOperationWatch = [Diagnostics.Stopwatch]::StartNew()

function Write-FabMatrixInstallProgress([string]$Path, [string]$Stage, [long]$ElapsedMilliseconds) {
    # Do not emit on PowerShell's success pipeline: wait functions return booleans.
    [Console]::Out.WriteLine(('FABMATRIX_PROGRESS|{0}|{1}' -f $Stage, [long]($script:installOperationWatch.ElapsedMilliseconds / 1000)))
    if ([string]::IsNullOrWhiteSpace($Path)) { return }
    $temporary = $Path + '.' + $PID + '.tmp'
    $previous = $temporary + '.previous'
    try {
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($Path))) | Out-Null
        $content = "[Progress]`r`nStage=$Stage`r`nElapsedSeconds=$([long]($ElapsedMilliseconds / 1000))`r`n"
        [IO.File]::WriteAllText($temporary, $content, [Text.Encoding]::Unicode)
        if ([IO.File]::Exists($Path)) { [IO.File]::Replace($temporary, $Path, $previous, $true) }
        else { [IO.File]::Move($temporary, $Path) }
    } catch {
        Write-Warning 'Installer progress reporting is unavailable; the operation will continue.'
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue }
        if (Test-Path -LiteralPath $previous) { Remove-Item -LiteralPath $previous -Force -ErrorAction SilentlyContinue }
    }
}

function Write-FabMatrixInstallHeartbeat {
    Write-FabMatrixInstallProgress $ProgressFile $script:installStage $script:installStageWatch.ElapsedMilliseconds
}

function Write-FabMatrixInstallEvent([string]$LogPath, [string]$Message) {
    if ([string]::IsNullOrWhiteSpace($LogPath)) { return }
    try {
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($LogPath))) | Out-Null
        [IO.File]::AppendAllText($LogPath, ('{0} {1}{2}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff'),
            $Message, [Environment]::NewLine), (New-Object Text.UTF8Encoding($false)))
    } catch { Write-Warning 'Installer timing log is unavailable.' }
}

function Set-FabMatrixInstallStage([string]$Stage, [string]$ProgressFile, [string]$LogPath) {
    if ($null -ne $script:installStageWatch) {
        Write-FabMatrixInstallEvent $LogPath "STAGE COMPLETE stage=$script:installStage elapsedMs=$($script:installStageWatch.ElapsedMilliseconds)"
    }
    $script:installStage = $Stage
    $script:installStageWatch = [Diagnostics.Stopwatch]::StartNew()
    Write-FabMatrixInstallEvent $LogPath "STAGE BEGIN stage=$Stage"
    Write-FabMatrixInstallProgress $ProgressFile $Stage 0
}
