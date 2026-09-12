function Set-FabMatrixPortableConfig {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Local configuration was not found: $Path"
    }

    $portableRoot = '${FARM_DATA_DIR}'
    $content = [IO.File]::ReadAllText($Path) -replace "`r`n", "`n"
    $sqliteUrl = "'jdbc:sqlite:$portableRoot/database/farm.db'"

    if ($content -match '(?im)^\s+url:\s*.*jdbc:sqlite:.*$') {
        $content = [regex]::Replace($content, '(?im)^(\s+url:\s*).*jdbc:sqlite:.*$', {
                param($match) $match.Groups[1].Value + $sqliteUrl
            }, 1)
    } elseif ($content -match '(?m)^  datasource:\s*(?:#.*)?$') {
        $content = [regex]::Replace($content, '(?m)^(  datasource:\s*(?:#.*)?)$', {
                param($match) $match.Groups[1].Value + "`n    url: $sqliteUrl"
            }, 1)
    } elseif ($content -match '(?m)^spring:\s*(?:#.*)?$') {
        $content = [regex]::Replace($content, '(?m)^(spring:\s*(?:#.*)?)$', {
                param($match) $match.Groups[1].Value + "`n  datasource:`n    url: $sqliteUrl"
            }, 1)
    } else {
        $content = "spring:`n  datasource:`n    url: $sqliteUrl`n" + $content
    }

    $content = [regex]::Replace($content, '(?im)^(\s*storage-path:\s*).+$', {
            param($match) $match.Groups[1].Value + "'$portableRoot/files'"
        })
    $content = [regex]::Replace($content, '(?im)^(\s*path:\s*).*[/\\]logs[''\"]?\s*$', {
            param($match) $match.Groups[1].Value + "'$portableRoot/logs'"
        })
    $content = [regex]::Replace($content, '(?im)^(\s*name:\s*)(?:[''\"]?farm[''\"]?|.*[/\\]logs[/\\]farm\.log[''\"]?)\s*$', {
            param($match) $match.Groups[1].Value + "'$portableRoot/logs/farm.log'"
        })

    foreach ($required in @(
            'jdbc:sqlite:${FARM_DATA_DIR}/database/farm.db',
            '${FARM_DATA_DIR}/files',
            '${FARM_DATA_DIR}/logs/farm.log')) {
        if (-not $content.Contains($required)) {
            throw "Unable to normalize Local configuration path: $required"
        }
    }

    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($Path, ($content.TrimEnd() + [Environment]::NewLine), $utf8)
}
