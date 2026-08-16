param(
    [switch]$RunSelfTest
)

$ErrorActionPreference = 'Stop'
$moduleRoot = Split-Path -Parent $PSScriptRoot
$ndk = "$env:LOCALAPPDATA/Android/Sdk/ndk/27.3.13750724"
$clang = Join-Path $ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe'
$output = Join-Path $moduleRoot 'module/bin/vmqd'
$source = Join-Path $moduleRoot 'native/src/vmqd.c'
if (-not (Test-Path $clang)) { throw "NDK clang not found: $clang" }
New-Item -ItemType Directory -Force (Split-Path -Parent $output) | Out-Null
& $clang --target=aarch64-linux-android35 -fPIE -pie -O2 -Wall -Wextra -Werror $source -o $output
if ($LASTEXITCODE -ne 0) { throw "native build failed with exit code $LASTEXITCODE" }
Write-Output "Built $output"
