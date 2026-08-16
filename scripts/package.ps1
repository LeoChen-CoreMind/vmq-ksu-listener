$ErrorActionPreference = 'Stop'
$moduleRoot = Split-Path -Parent $PSScriptRoot
$source = Join-Path $moduleRoot 'module'
$staging = Join-Path $moduleRoot 'build/package'
$dist = Join-Path $moduleRoot 'dist'
$zip = Join-Path $dist 'vmq-ksu-v1.0.0-arm64-api35.zip'
& (Join-Path $PSScriptRoot 'build-java.ps1') -RunJvmTests
& (Join-Path $PSScriptRoot 'build-native.ps1')
$webRoot = Join-Path $moduleRoot 'web'
Push-Location $webRoot
try {
    if (Test-Path (Join-Path $webRoot 'package-lock.json')) { & npm.cmd ci } else { & npm.cmd install }
    if ($LASTEXITCODE -ne 0) { throw 'WebUI dependency install failed' }
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw 'WebUI build failed' }
} finally {
    Pop-Location
}
Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $staging, $dist | Out-Null
Copy-Item (Join-Path $source '*') $staging -Recurse -Force
New-Item -ItemType Directory -Force (Join-Path $staging 'fixtures') | Out-Null
Copy-Item (Join-Path $moduleRoot 'fixtures/notifications.json') (Join-Path $staging 'fixtures/notifications.json') -Force
$scripts = Get-ChildItem $staging -Recurse -File | Where-Object { $_.Extension -eq '.sh' -or $_.Name -eq 'vmqctl' -or $_.Name -eq 'update-binary' }
$utf8 = New-Object Text.UTF8Encoding($false)
foreach ($script in $scripts) {
    $text = [IO.File]::ReadAllText($script.FullName).Replace("`r`n", "`n").Replace("`r", "`n")
    [IO.File]::WriteAllText($script.FullName, $text, $utf8)
}
& (Join-Path $PSScriptRoot 'verify-module.ps1') -Staging $staging
if ($LASTEXITCODE -ne 0) { throw 'staging verification failed' }
Remove-Item -LiteralPath $zip -Force -ErrorAction SilentlyContinue
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$archive = [IO.Compression.ZipFile]::Open($zip, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in Get-ChildItem $staging -Recurse -File) {
        $relative = $file.FullName.Substring($staging.Length).TrimStart('\', '/').Replace('\', '/')
        $entry = $archive.CreateEntry($relative, [IO.Compression.CompressionLevel]::Optimal)
        $input = [IO.File]::OpenRead($file.FullName)
        $output = $entry.Open()
        try { $input.CopyTo($output) } finally { $output.Dispose(); $input.Dispose() }
    }
} finally {
    $archive.Dispose()
}
$hash = (Get-FileHash -Algorithm SHA256 $zip).Hash.ToLowerInvariant()
[IO.File]::WriteAllText("$zip.sha256", "$hash  $([IO.Path]::GetFileName($zip))`n", $utf8)
Write-Output "Built $zip"
Write-Output "SHA256 $hash"
