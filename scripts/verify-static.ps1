param(
    [string]$Staging,
    [string]$Zip
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

if ($Zip) {
    if (-not (Test-Path -LiteralPath $Zip)) { throw "ZIP not found: $Zip" }
    $archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path $Zip))
    try {
        $rawNames = @($archive.Entries | ForEach-Object { $_.FullName })
        if ($rawNames | Where-Object { $_.Contains('\') }) { throw 'ZIP contains Windows path separators' }
        if ($rawNames -notcontains 'LICENSE') { throw 'ZIP root does not contain LICENSE' }
        if ($rawNames -notcontains 'module.prop') { throw 'ZIP root does not contain module.prop' }
        if ($rawNames | Where-Object { $_ -match '^module/' -or $_ -match '(^|/)\.\.(?:/|$)' }) { throw 'ZIP contains a parent directory prefix' }
    } finally { $archive.Dispose() }
    $temporary = Join-Path ([IO.Path]::GetTempPath()) ("vmq-static-" + [guid]::NewGuid())
    [IO.Compression.ZipFile]::ExtractToDirectory((Resolve-Path $Zip), $temporary)
    try {
        & $PSCommandPath -Staging $temporary
        if ($LASTEXITCODE -ne 0) { throw 'static verification of ZIP failed' }
    } finally {
        Remove-Item -LiteralPath $temporary -Recurse -Force -ErrorAction SilentlyContinue
    }
    return
}

if (-not $Staging) { throw 'Specify -Staging or -Zip' }
$root = (Resolve-Path -LiteralPath $Staging).Path
$required = @(
    'LICENSE','module.prop','customize.sh','service.sh','action.sh','uninstall.sh',
    'sepolicy.rule','bin/vmqd','bin/vmqctl','dex/vmq-listener.dex',
    'defaults/config.json','fixtures/notifications.json','webroot/index.html'
)
foreach ($relative in $required) {
    if (-not (Test-Path -LiteralPath (Join-Path $root $relative))) { throw "Missing module artifact: $relative" }
}

$license = Get-Content -Raw -Encoding utf8 (Join-Path $root 'LICENSE')
if ($license -notmatch 'MIT License' -or $license -notmatch 'Copyright \(c\) 2026 LeoChen') {
    throw 'LICENSE is not the required MIT notice'
}
$webIndex = Get-Content -Raw -Encoding utf8 (Join-Path $root 'webroot/index.html')
$webAssets = Join-Path $root 'webroot/assets'
$hashedJavaScript = @(Get-ChildItem -LiteralPath $webAssets -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -match '^index-[A-Za-z0-9_-]+\.js$' })
$hashedStyles = @(Get-ChildItem -LiteralPath $webAssets -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -match '^index-[A-Za-z0-9_-]+\.css$' })
if (-not $hashedJavaScript -or -not $hashedStyles) { throw 'WebUI hashed JavaScript/CSS assets are missing' }
if ($webIndex -notmatch '\./assets/index-[A-Za-z0-9_-]+\.js' -or $webIndex -notmatch '\./assets/index-[A-Za-z0-9_-]+\.css') {
    throw 'WebUI entry does not use relative hashed asset paths'
}

$moduleName = -join ([char[]]@(0x0056,0x514D,0x7B7E,0x901A,0x77E5,0x76D1,0x542C))
$stoppedDescription = -join ([char[]]@(0x0056,0x514D,0x7B7E,0x901A,0x77E5,0x76D1,0x542C,0x670D,0x52A1,0x5DF2,0x505C,0x6B62,0x8FD0,0x884C))
$expectedMetadata = @(
    'id=vmq_ksu',"name=$moduleName",'version=v1.0.0','versionCode=2026081601',
    'author=LeoChen',"description=$stoppedDescription"
)
$metadata = Get-Content -Encoding utf8 (Join-Path $root 'module.prop')
foreach ($line in $expectedMetadata) { if ($metadata -notcontains $line) { throw "Missing metadata: $line" } }
$scripts = Get-ChildItem -LiteralPath $root -Recurse -File | Where-Object { $_.Extension -eq '.sh' -or $_.Name -eq 'vmqctl' -or $_.Name -eq 'update-binary' }
foreach ($script in $scripts) {
    $bytes = [IO.File]::ReadAllBytes($script.FullName)
    for ($index = 0; $index -lt $bytes.Length - 1; $index++) {
        if ($bytes[$index] -eq 13 -and $bytes[$index + 1] -eq 10) { throw "CRLF script: $($script.FullName)" }
    }
}

$ndkRoot = if ($env:ANDROID_NDK_HOME) { $env:ANDROID_NDK_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk/ndk/27.3.13750724' }
$readelf = Join-Path $ndkRoot 'toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe'
if (-not (Test-Path -LiteralPath $readelf)) { throw "NDK llvm-readelf not found: $readelf" }
$header = & $readelf -h (Join-Path $root 'bin/vmqd') 2>&1 | Out-String
if ($LASTEXITCODE -ne 0 -or $header -notmatch 'AArch64' -or $header -notmatch 'DYN') { throw 'vmqd is not an arm64 PIE executable' }
$dex = [IO.File]::ReadAllBytes((Join-Path $root 'dex/vmq-listener.dex'))
if ($dex.Length -lt 8 -or [Text.Encoding]::ASCII.GetString($dex, 0, 4) -ne "dex`n") { throw 'listener DEX is invalid' }
$policy = Get-Content -Raw -Encoding utf8 (Join-Path $root 'sepolicy.rule')
if ($policy -match 'permissive|\*') { throw 'sepolicy contains a permissive or wildcard rule' }
Write-Output 'PASS: static module metadata, scripts, arm64 PIE, DEX, policy, WebUI, LICENSE, and ZIP layout'
