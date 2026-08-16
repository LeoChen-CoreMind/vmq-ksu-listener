param(
    [Parameter(Mandatory = $true)][string]$Tag
)

$ErrorActionPreference = 'Stop'
$moduleRoot = Split-Path -Parent $PSScriptRoot
$prop = Join-Path $moduleRoot 'module/module.prop'
if (-not (Test-Path -LiteralPath $prop)) { throw "module.prop not found: $prop" }
$versionLine = Get-Content -Encoding utf8 $prop | Where-Object { $_ -match '^version=' } | Select-Object -First 1
if (-not $versionLine) { throw 'module.prop does not define version=' }
$version = $versionLine.Substring('version='.Length)
$expected = if ($version.StartsWith('v')) { $version } else { "v$version" }
if ($Tag -ne $expected) { throw "Release tag $Tag does not match module version $expected" }
Write-Output "PASS: release tag $Tag matches module version $version"
