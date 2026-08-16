param(
    [switch]$RunJvmTests
)

$ErrorActionPreference = 'Stop'

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "$env:LOCALAPPDATA/Android/Sdk" }
$androidJar = "$sdk/platforms/android-35/android.jar"
$d8 = "$sdk/build-tools/35.0.1/d8.bat"
$moduleRoot = Split-Path -Parent $PSScriptRoot
$sourceRoot = Join-Path $moduleRoot 'java/src'
$buildRoot = Join-Path $moduleRoot 'build/java'
$classes = Join-Path $buildRoot 'classes'
$testClasses = Join-Path $buildRoot 'test-classes'
$temporaryJar = Join-Path $buildRoot 'vmq-listener.jar'
$dexDirectory = Join-Path $moduleRoot 'module/dex'

if (-not (Test-Path $androidJar)) {
    throw "Android API 35 platform jar not found: $androidJar"
}
if (-not (Test-Path $d8)) {
    throw "Android build-tools 35.0.1 D8 not found: $d8"
}

Remove-Item -Recurse -Force $buildRoot -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $classes, $testClasses, $dexDirectory | Out-Null
$sources = Get-ChildItem -Path $sourceRoot -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName
if ($sources.Count -eq 0) {
    throw "No Java sources found under $sourceRoot"
}

& javac -encoding UTF-8 --release 8 -classpath $androidJar -d $classes $sources
if ($LASTEXITCODE -ne 0) {
    throw "javac failed with exit code $LASTEXITCODE"
}

if ($RunJvmTests) {
    $testRoot = Join-Path $moduleRoot 'java/tests'
    $testSources = Get-ChildItem -Path $testRoot -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName
    if ($testSources.Count -eq 0) {
        throw "No Java tests found under $testRoot"
    }

    & javac -encoding UTF-8 --release 8 -classpath "$androidJar;$classes" -d $testClasses $testSources
    if ($LASTEXITCODE -ne 0) {
        throw "test javac failed with exit code $LASTEXITCODE"
    }

    & java -cp "$testClasses;$classes" com.vmq.ksu.TestMain
    if ($LASTEXITCODE -ne 0) {
        throw "JVM tests failed with exit code $LASTEXITCODE"
    }
}

& jar --create --file $temporaryJar -C $classes .
if ($LASTEXITCODE -ne 0) {
    throw "jar failed with exit code $LASTEXITCODE"
}

Remove-Item (Join-Path $dexDirectory 'classes.dex') -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $dexDirectory 'vmq-listener.dex') -Force -ErrorAction SilentlyContinue
& $d8 --min-api 35 --output $dexDirectory $temporaryJar
if ($LASTEXITCODE -ne 0) {
    throw "D8 failed with exit code $LASTEXITCODE"
}

Rename-Item (Join-Path $dexDirectory 'classes.dex') 'vmq-listener.dex'
Write-Output "Built $dexDirectory/vmq-listener.dex"
