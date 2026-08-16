param(
    [string]$Staging,
    [string]$Zip,
    [string]$Serial = 'debbff75'
)

$ErrorActionPreference = 'Stop'
if ((& adb -s $Serial get-state 2>$null) -ne 'device') { throw "ADB device is unavailable: $Serial" }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$temporary = $null
if ($Zip) {
    if (-not (Test-Path $Zip)) { throw "ZIP not found: $Zip" }
    $archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path $Zip))
    try {
        $rawNames = @($archive.Entries | ForEach-Object { $_.FullName })
        if ($rawNames | Where-Object { $_.Contains('\') }) { throw 'ZIP contains Windows path separators' }
        $names = @($rawNames)
        if ($names -notcontains 'module.prop') { throw 'ZIP root does not contain module.prop' }
        if ($names | Where-Object { $_ -match '^module/' -or $_ -match '(^|/)\.\.(/|$)' }) { throw 'ZIP contains a parent directory prefix' }
    } finally { $archive.Dispose() }
    $temporary = Join-Path ([IO.Path]::GetTempPath()) ("vmq-verify-" + [guid]::NewGuid())
    [IO.Compression.ZipFile]::ExtractToDirectory((Resolve-Path $Zip), $temporary)
    $Staging = $temporary
}
if (-not $Staging) { throw 'Specify -Staging or -Zip' }
$root = (Resolve-Path $Staging).Path
$required = @('module.prop','customize.sh','service.sh','action.sh','uninstall.sh','sepolicy.rule','bin/vmqd','bin/vmqctl','dex/vmq-listener.dex','defaults/config.json','fixtures/notifications.json','webroot/index.html')
foreach ($relative in $required) {
    if (-not (Test-Path (Join-Path $root $relative))) { throw "Missing module artifact: $relative" }
}
$webIndex = Get-Content -Raw -Encoding utf8 (Join-Path $root 'webroot/index.html')
$webAssets = Join-Path $root 'webroot/assets'
$hashedJavaScript = @(Get-ChildItem $webAssets -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -match '^index-[A-Za-z0-9_-]+\.js$' })
$hashedStyles = @(Get-ChildItem $webAssets -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -match '^index-[A-Za-z0-9_-]+\.css$' })
if (-not $hashedJavaScript -or -not $hashedStyles) { throw 'WebUI hashed JavaScript/CSS assets are missing' }
if ($webIndex -notmatch '\./assets/index-[A-Za-z0-9_-]+\.js' -or $webIndex -notmatch '\./assets/index-[A-Za-z0-9_-]+\.css') {
    throw 'WebUI entry does not use relative hashed asset paths'
}
$expectedMetadata = @(
    'id=vmq_ksu','name=V免签通知监听','version=v1.0.0','versionCode=2026081601',
    'author=LeoChen','description=V免签通知监听服务已停止运行'
)
$metadata = Get-Content -Encoding utf8 (Join-Path $root 'module.prop')
foreach ($line in $expectedMetadata) { if ($metadata -notcontains $line) { throw "Missing metadata: $line" } }
$scripts = Get-ChildItem $root -Recurse -File | Where-Object { $_.Extension -eq '.sh' -or $_.Name -eq 'vmqctl' -or $_.Name -eq 'update-binary' }
foreach ($script in $scripts) {
    $bytes = [IO.File]::ReadAllBytes($script.FullName)
    for ($index = 0; $index -lt $bytes.Length - 1; $index++) {
        if ($bytes[$index] -eq 13 -and $bytes[$index + 1] -eq 10) { throw "CRLF script: $($script.FullName)" }
    }
    $remote = "/data/local/tmp/vmq-verify-$([guid]::NewGuid().ToString('N')).sh"
    & adb -s $Serial push $script.FullName $remote | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "adb push failed for $($script.Name)" }
    & adb -s $Serial shell su -c "sh -n '$remote'; rm -f '$remote'"
    if ($LASTEXITCODE -ne 0) { throw "Android sh syntax failed: $($script.Name)" }
}
$readelf = "$env:LOCALAPPDATA/Android/Sdk/ndk/27.3.13750724/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe"
$header = & $readelf -h (Join-Path $root 'bin/vmqd') 2>&1 | Out-String
if ($LASTEXITCODE -ne 0 -or $header -notmatch 'AArch64' -or $header -notmatch 'DYN') { throw 'vmqd is not an arm64 PIE executable' }
$dex = [IO.File]::ReadAllBytes((Join-Path $root 'dex/vmq-listener.dex'))
if ($dex.Length -lt 8 -or [Text.Encoding]::ASCII.GetString($dex, 0, 4) -ne "dex`n") { throw 'listener DEX is invalid' }
$policy = Get-Content -Raw -Encoding utf8 (Join-Path $root 'sepolicy.rule')
if ($policy -match 'permissive|\*') { throw 'sepolicy contains a permissive or wildcard rule' }
$policyRemote = "/data/local/tmp/vmq-policy-$([guid]::NewGuid().ToString('N')).rule"
$checkerRemote = "/data/local/tmp/vmq-policy-check-$([guid]::NewGuid().ToString('N')).sh"
$checkerLocal = Join-Path ([IO.Path]::GetTempPath()) ("vmq-policy-check-" + [guid]::NewGuid() + '.sh')
$checker = @'
#!/system/bin/sh
set -e
while IFS= read -r line; do
  normalized=$(printf '%s' "$line" | tr ':' ' ')
  [ -z "$normalized" ] || ksud sepolicy check "$normalized"
done <"$1"
'@
[IO.File]::WriteAllText($checkerLocal, $checker.Replace("`r`n", "`n") + "`n", (New-Object Text.UTF8Encoding($false)))
& adb -s $Serial push (Join-Path $root 'sepolicy.rule') $policyRemote | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'failed to push sepolicy.rule for device validation' }
& adb -s $Serial push $checkerLocal $checkerRemote | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'failed to push policy checker' }
& adb -s $Serial shell su -c "sh '$checkerRemote' '$policyRemote'"
$policyExit = $LASTEXITCODE
& adb -s $Serial shell su -c "rm -f '$checkerRemote' '$policyRemote'" | Out-Null
Remove-Item -LiteralPath $checkerLocal -Force
if ($policyExit -ne 0) { throw 'KernelSU rejected sepolicy.rule' }
if ($temporary) { Remove-Item -LiteralPath $temporary -Recurse -Force }
Write-Output 'PASS: module metadata, scripts, arm64 PIE, DEX, policy, WebUI, and ZIP layout'
