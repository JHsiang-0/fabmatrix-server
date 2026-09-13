function ConvertTo-XmlText([string]$Value) {
    return [System.Security.SecurityElement]::Escape($Value)
}

function Resolve-FabMatrixServicePlan {
    param(
        $PreservedState,
        [bool]$AutoStartRequested
    )

    $isUpgrade = $null -ne $PreservedState -and
        -not [string]::IsNullOrWhiteSpace([string]$PreservedState.ExistingServiceName)
    $startMode = if ($isUpgrade -and [string]$PreservedState.StartMode -eq 'Auto') {
        'Automatic'
    } elseif ($isUpgrade) {
        'Manual'
    } elseif ($AutoStartRequested) {
        'Automatic'
    } else {
        'Manual'
    }
    $disableAfterInstall = $isUpgrade -and [string]$PreservedState.StartMode -eq 'Disabled'
    $shouldStart = if ($isUpgrade) {
        [bool]$PreservedState.WasRunning -and -not $disableAfterInstall
    } else {
        $AutoStartRequested
    }
    return [pscustomobject]@{
        IsUpgrade = $isUpgrade
        StartMode = $startMode
        DisableAfterInstall = $disableAfterInstall
        ShouldStart = $shouldStart
    }
}

function New-FabMatrixServiceXml {
    param(
        [Parameter(Mandatory = $true)][string]$InstallDir,
        [Parameter(Mandatory = $true)][string]$DataRoot,
        [Parameter(Mandatory = $true)][ValidateRange(1, 65535)][int]$Port,
        [ValidateSet('Automatic', 'Manual')][string]$StartMode = 'Manual'
    )

    $installPath = [IO.Path]::GetFullPath($InstallDir)
    $dataPath = [IO.Path]::GetFullPath($DataRoot)
    $configLocation = 'file:' + (($dataPath -replace '\\', '/').TrimEnd('/') + '/config/')
    $arguments = '--spring.config.additional-location="' + $configLocation + '"'
    $executableXml = ConvertTo-XmlText (Join-Path $installPath 'FabMatrixServer.exe')
    $workingDirectoryXml = ConvertTo-XmlText $installPath
    $argumentsXml = ConvertTo-XmlText $arguments
    $dataXml = ConvertTo-XmlText $dataPath
    $portXml = ConvertTo-XmlText ([string]$Port)
    $serviceLogsXml = ConvertTo-XmlText (Join-Path $dataPath 'logs\service')

    return @"
<service>
  <id>FabMatrixServer</id>
  <name>FabMatrixServer</name>
  <description>FabMatrix Server local 3D printer farm management backend.</description>
  <executable>$executableXml</executable>
  <workingdirectory>$workingDirectoryXml</workingdirectory>
  <arguments>$argumentsXml</arguments>
  <env name="FARM_DATA_DIR" value="$dataXml" />
  <env name="SERVER_PORT" value="$portXml" />
  <logpath>$serviceLogsXml</logpath>
  <log mode="roll-by-size">
    <sizeThreshold>10485760</sizeThreshold>
    <keepFiles>5</keepFiles>
  </log>
  <startmode>$StartMode</startmode>
  <stoptimeout>30sec</stoptimeout>
  <stopparentprocessfirst>true</stopparentprocessfirst>
  <onfailure action="restart" delay="10 sec" />
</service>
"@
}
