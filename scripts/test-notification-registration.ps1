$ErrorActionPreference = 'Stop'

$moduleRoot = Split-Path -Parent $PSScriptRoot
$sdk = "$env:LOCALAPPDATA/Android/Sdk"
$androidJar = "$sdk/platforms/android-35/android.jar"
$classes = Join-Path $moduleRoot 'build/java/classes'
$testClasses = Join-Path $moduleRoot 'build/java/test-classes'
$testSources = Get-ChildItem -Path (Join-Path $moduleRoot 'java/tests') -Recurse -Filter '*.java' |
    Select-Object -ExpandProperty FullName

& "$PSScriptRoot/build-java.ps1"
if ($LASTEXITCODE -ne 0) {
    throw "DEX build failed with exit code $LASTEXITCODE"
}
Remove-Item -Recurse -Force $testClasses -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $testClasses | Out-Null
& javac --release 8 -classpath "$androidJar;$classes" -d $testClasses $testSources
if ($LASTEXITCODE -ne 0) {
    throw "javac test compilation failed with exit code $LASTEXITCODE"
}
& java -cp "$classes;$testClasses" com.vmq.ksu.ListenerConnectionTest
if ($LASTEXITCODE -ne 0) {
    throw "ListenerConnectionTest failed with exit code $LASTEXITCODE"
}

. "$PSScriptRoot/device-registration-smoke.ps1"
$script:delayedReads = 0
$markerOutput = Wait-ForListenerRegistration -TimeoutMilliseconds 500 -PollMilliseconds 25 `
    -LogReader {
        $script:delayedReads++
        if ($script:delayedReads -ge 4) {
            'VMQ_REGISTERED component=android/com.vmq.ksu.Listener'
        }
    } `
    -ProcessAlive { $true }
if (-not [bool]($markerOutput -match 'VMQ_REGISTERED component=android/com.vmq.ksu.Listener')) {
    throw 'delayed marker was not returned'
}
if ($script:delayedReads -lt 4) {
    throw 'registration poller did not wait for delayed marker'
}

$timer = [Diagnostics.Stopwatch]::StartNew()
$timedOut = $false
try {
    Wait-ForListenerRegistration -TimeoutMilliseconds 150 -PollMilliseconds 25 `
        -LogReader { '' } `
        -ProcessAlive { $true } | Out-Null
} catch {
    if ($_.Exception.Message -match 'app_process listener registration did not complete') {
        $timedOut = $true
    } else {
        throw
    }
}
$timer.Stop()
if (-not $timedOut -or $timer.ElapsedMilliseconds -lt 125) {
    throw "registration poller did not honor the full wait window: $($timer.ElapsedMilliseconds)ms"
}
Write-Output 'PASS: listener callback handshake and smoke polling wait window'
