$ErrorActionPreference = 'Stop'

$remoteDex = '/data/local/tmp/vmq-ksu-smoke.dex'
$remoteLog = '/data/local/tmp/vmq-ksu-smoke.log'
$remotePid = '/data/local/tmp/vmq-ksu-smoke.pid'

function Wait-ForListenerRegistration {
    param(
        [Parameter(Mandatory = $true)] [scriptblock] $LogReader,
        [Parameter(Mandatory = $true)] [scriptblock] $ProcessAlive,
        [int] $TimeoutMilliseconds = 5000,
        [int] $PollMilliseconds = 250
    )

    $deadline = (Get-Date).AddMilliseconds($TimeoutMilliseconds)
    do {
        $listenerOutput = & $LogReader
        if ($listenerOutput -match 'VMQ_REGISTER_FAILED') {
            throw "app_process listener registration failed: $listenerOutput"
        }
        if ([bool]($listenerOutput -match 'VMQ_REGISTERED component=android/com.vmq.ksu.Listener')) {
            return $listenerOutput
        }
        if (-not (& $ProcessAlive)) {
            throw "app_process listener exited before registration completed: $listenerOutput"
        }
        Start-Sleep -Milliseconds $PollMilliseconds
    } while ((Get-Date) -lt $deadline)

    throw 'app_process listener registration did not complete'
}

function Invoke-DeviceRegistrationSmoke {
    adb push "$PSScriptRoot/../module/dex/vmq-listener.dex" $remoteDex
    if ($LASTEXITCODE -ne 0) {
        throw "adb push failed with exit code $LASTEXITCODE"
    }

    try {
        adb shell su -c "rm -f $remoteLog $remotePid; CLASSPATH=$remoteDex app_process /system/bin com.vmq.ksu.Main --smoke >$remoteLog 2>&1 & echo `$! >$remotePid"
        Start-Sleep -Seconds 2
        $listenerOutput = Wait-ForListenerRegistration `
            -LogReader { adb shell su -c "cat $remoteLog" } `
            -ProcessAlive {
                $listenerPid = adb shell su -c "cat $remotePid"
                $alive = adb shell su -c "kill -0 $listenerPid 2>/dev/null && echo alive || true"
                return [bool]($alive -match 'alive')
            }
        $dump = adb shell su -c 'dumpsys notification --noredact'
        if (-not [bool]($dump -match 'android/com.vmq.ksu.Listener')) {
            throw 'notification manager does not contain the live Vmq system listener'
        }
        $avc = adb shell su -c "dmesg | grep -F 'com.vmq.ksu.Listener' || true"
        if ($avc -match 'avc: denied') {
            throw 'kernel log contains an AVC denial for com.vmq.ksu.Listener'
        }
        Write-Output 'PASS: VMQ_REGISTERED marker and notification listener entry observed'
    } finally {
        adb shell su -c "test -f $remotePid && kill `$(cat $remotePid); rm -f $remotePid"
    }
}

if ($MyInvocation.InvocationName -ne '.') {
    Invoke-DeviceRegistrationSmoke
}
