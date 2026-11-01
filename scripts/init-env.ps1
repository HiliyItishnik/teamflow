$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$environmentPath = Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $environmentPath)) {
    Copy-Item -LiteralPath (Join-Path $projectRoot '.env.example') -Destination $environmentPath
}
$content = [IO.File]::ReadAllText($environmentPath)
$keyLine = [regex]::Match($content, '(?m)^JWT_SECRET=([^\r\n]*)')
$jwtSecret = $keyLine.Groups[1].Value.Trim().Trim('"').Trim("'")
if ([string]::IsNullOrWhiteSpace($jwtSecret)) {
    $randomBytes = New-Object byte[] 32
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($randomBytes) } finally { $random.Dispose() }
    $jwtSecret = [Convert]::ToBase64String($randomBytes)
    if ($keyLine.Success) {
        $content = [regex]::Replace($content, '(?m)^JWT_SECRET=[^\r\n]*', 'JWT_SECRET=' + $jwtSecret)
    } else {
        $content = $content.TrimEnd() + "`nJWT_SECRET=" + $jwtSecret + "`n"
    }
    [IO.File]::WriteAllText($environmentPath, $content, [Text.UTF8Encoding]::new($false))
}
$env:JWT_SECRET = $jwtSecret
Write-Output 'JWT key is ready in .env and in this PowerShell session. The key value is not displayed.'
