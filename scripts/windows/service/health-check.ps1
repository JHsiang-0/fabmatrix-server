function Test-FabMatrixHealthResponse {
    param(
        [Parameter(Mandatory = $true)][object]$Response
    )

    try {
        if ($null -eq $Response -or $Response.StatusCode -ne 200) { return $false }

        $content = $Response.Content
        if ($content -is [byte[]]) {
            $jsonText = [Text.Encoding]::UTF8.GetString($content)
        } elseif ($content -is [string]) {
            $jsonText = [string]$content
        } else {
            return $false
        }
        if ([string]::IsNullOrWhiteSpace($jsonText)) { return $false }

        $jsonText = $jsonText.TrimStart([char]0xFEFF)
        $payload = ConvertFrom-Json -InputObject $jsonText -ErrorAction Stop
        $statusProperty = $payload.PSObject.Properties['status']
        return $null -ne $statusProperty -and $statusProperty.Value -is [string] -and
            $statusProperty.Value -ceq 'UP'
    } catch {
        return $false
    }
}
