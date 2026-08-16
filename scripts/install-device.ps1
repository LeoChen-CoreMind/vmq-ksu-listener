param(
    [switch]$Install,
    [switch]$Verify,
    [switch]$Lifecycle,
    [switch]$RebootVerify,
    [switch]$CapturePaymentBaseline,
    [switch]$VerifyPayment,
    [switch]$Rollback,
    [string]$Serial = 'debbff75',
    [string]$PaymentEvidence
)

$ErrorActionPreference = 'Stop'
$moduleRoot = Split-Path -Parent $PSScriptRoot
$zip = Join-Path $moduleRoot 'dist/vmq-ksu-v1.0.0-arm64-api35.zip'
$modulePath = '/data/adb/modules/vmq_ksu'
$control = "$modulePath/bin/vmqctl"
$oldListener = 'com.shinian.pay/com.shinian.pay.service.PayNotificationListenerService'
if (-not $PaymentEvidence) { $PaymentEvidence = Join-Path $moduleRoot 'build/payment-baseline.json' }

function Invoke-Adb([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments) {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & adb -s $Serial @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($exitCode -ne 0) { throw "adb failed: $($Arguments -join ' ')`n$($output -join "`n")" }
    return $output
}

function Invoke-Root([string]$Command) {
    return Invoke-Adb shell su -c $Command
}

function Assert-Preflight {
    if ($Serial -ne 'debbff75') { throw "Expected serial debbff75; received $Serial" }
    if ((Invoke-Adb get-state | Select-Object -Last 1).Trim() -ne 'device') { throw 'ADB state is not device' }
    if ((Invoke-Root 'id') -notmatch 'uid=0\(root\)') { throw 'su does not provide UID 0' }
    if ((Invoke-Adb shell getprop ro.build.version.sdk | Select-Object -Last 1).Trim() -ne '35') { throw 'Android API 35 is required' }
    if ((Invoke-Adb shell getprop ro.product.cpu.abi | Select-Object -Last 1).Trim() -ne 'arm64-v8a') { throw 'arm64-v8a is required' }
    if ((Invoke-Root 'ksud -V') -notmatch 'ksud 3\.2\.5') { throw 'KernelSU ksud 3.2.5 is required' }
}

function Get-ControlJson([string]$Command) {
    $line = (Invoke-Root "$control $Command" | Select-Object -Last 1).Trim()
    try { return $line | ConvertFrom-Json } catch { throw "Invalid vmqctl JSON: $line" }
}

function Get-LogCount([string]$Pattern) {
    $value = (Invoke-Root "grep -F '$Pattern' /data/adb/vmq_ksu/vmq.log 2>/dev/null | wc -l" | Select-Object -Last 1).Trim()
    return [int]$value
}

function Assert-LogRedaction {
    $local = Join-Path ([IO.Path]::GetTempPath()) ("vmq-log-check-" + [guid]::NewGuid() + '.sh')
    $remote = "/data/local/tmp/vmq-log-check-$([guid]::NewGuid().ToString('N')).sh"
    $script = @'
#!/system/bin/sh
set -eu
ROOT=/data/adb/vmq_ksu
CONTROL=/data/adb/modules/vmq_ksu/bin/vmqctl
OUTPUT=$($CONTROL logs)
printf '%s' "$OUTPUT" | grep -Eq '"lineCount":[0-9]+'
printf '%s' "$OUTPUT" | grep -Eq '"truncated":(true|false)'
KEY=$(sed -n 's/.*"key":"\([^"]*\)".*/\1/p' "$ROOT/config.json")
if [ -n "$KEY" ] && printf '%s' "$OUTPUT" | grep -F "$KEY" >/dev/null; then
  exit 3
fi
'@
    [IO.File]::WriteAllText($local, $script.Replace("`r`n", "`n") + "`n", (New-Object Text.UTF8Encoding($false)))
    try {
        Invoke-Adb push $local $remote | Out-Null
        Invoke-Root "sh '$remote'" | Out-Null
    } finally {
        Invoke-Root "rm -f '$remote'" | Out-Null
        Remove-Item -LiteralPath $local -Force -ErrorAction SilentlyContinue
    }
}

function Wait-ForRunning([int64]$AfterHeartbeat = 0, [int]$TimeoutSeconds = 70) {
    $attempts = [Math]::Ceiling($TimeoutSeconds / 2)
    for ($attempt = 0; $attempt -lt $attempts; $attempt++) {
        $status = Get-ControlJson 'status'
        if ($status.ok -and
                [int64]$status.data.supervisor.pid -gt 0 -and
                [int64]$status.data.supervisor.listenerPid -gt 0 -and
                $status.data.listener.running -and
                $status.data.listener.lastHeartbeatSuccess -and
                [int64]$status.data.listener.lastHeartbeatAt -gt $AfterHeartbeat -and
                $status.data.listener.lastNotificationPollSuccess -and
                [int64]$status.data.listener.lastNotificationPollAt -gt 0) {
            return $status
        }
        Start-Sleep -Seconds 2
    }
    throw "Module did not reach a healthy running state within $TimeoutSeconds seconds"
}

function Wait-ForListenerReplacement(
        [int64]$SupervisorPid,
        [int64]$ListenerPid,
        [int]$RestartCount,
        [int64]$AfterHeartbeat,
        [int]$TimeoutSeconds = 70) {
    $attempts = [Math]::Ceiling($TimeoutSeconds / 2)
    for ($attempt = 0; $attempt -lt $attempts; $attempt++) {
        $status = Get-ControlJson 'status'
        if ([int64]$status.data.supervisor.pid -eq $SupervisorPid -and
                [int64]$status.data.supervisor.listenerPid -gt 0 -and
                [int64]$status.data.supervisor.listenerPid -ne $ListenerPid -and
                [int]$status.data.supervisor.restartCount -gt $RestartCount -and
                $status.data.listener.running -and
                $status.data.listener.lastHeartbeatSuccess -and
                [int64]$status.data.listener.lastHeartbeatAt -gt $AfterHeartbeat -and
                $status.data.listener.lastNotificationPollSuccess -and
                [int64]$status.data.listener.lastNotificationPollAt -gt 0) {
            return $status
        }
        Start-Sleep -Seconds 2
    }
    throw "Listener was not replaced within $TimeoutSeconds seconds"
}

Assert-Preflight
if ($Install) {
    if (-not (Test-Path $zip)) { throw "Release ZIP is missing: $zip" }
    $hashFile = "$zip.sha256"
    if (-not (Test-Path $hashFile)) { throw "Release checksum is missing: $hashFile" }
    $expectedHash = ((Get-Content -Raw -Encoding UTF8 $hashFile).Trim() -split '\s+')[0].ToLowerInvariant()
    $actualHash = (Get-FileHash -Algorithm SHA256 $zip).Hash.ToLowerInvariant()
    if ($actualHash -ne $expectedHash) { throw "Release checksum mismatch: expected $expectedHash actual $actualHash" }
    $record = Join-Path $moduleRoot 'build/device-enabled-notification-listeners.txt'
    New-Item -ItemType Directory -Force (Split-Path -Parent $record) | Out-Null
    $listeners = (Invoke-Adb shell settings get secure enabled_notification_listeners | Select-Object -Last 1).Trim()
    [IO.File]::WriteAllText($record, "$listeners`n", (New-Object Text.UTF8Encoding($false)))
    Invoke-Adb push $zip /data/local/tmp/vmq-ksu.zip | Out-Null
    $remoteHash = ((Invoke-Root "sha256sum /data/local/tmp/vmq-ksu.zip" | Select-Object -Last 1).Trim() -split '\s+')[0].ToLowerInvariant()
    if ($remoteHash -ne $expectedHash) { throw "Uploaded ZIP checksum mismatch: expected $expectedHash actual $remoteHash" }
    Invoke-Root '/data/adb/ksu/bin/ksud module install /data/local/tmp/vmq-ksu.zip'
    Write-Output "PASS: installed vmq_ksu; previous listeners recorded at $record"
}

if ($Verify) {
    if ((Invoke-Root "test -d '$modulePath' && echo present" | Select-Object -Last 1).Trim() -ne 'present') { throw 'Installed module directory is missing' }
    $status = $null
    for ($attempt = 0; $attempt -lt 13; $attempt++) {
        $status = Get-ControlJson 'status'
        if ($status.ok -and $status.data.listener.lastHeartbeatSuccess -and
                $status.data.listener.lastNotificationPollSuccess) { break }
        if ($attempt -lt 12) { Start-Sleep -Seconds 5 }
    }
    if (-not $status.ok) { throw "vmqctl status failed: $($status.error)" }
    $supervisorPid = [int64]$status.data.supervisor.pid
    $listenerPid = [int64]$status.data.supervisor.listenerPid
    if ($supervisorPid -le 0 -or $listenerPid -le 0) { throw "Service PIDs are not live: supervisor=$supervisorPid listener=$listenerPid" }
    Invoke-Root "kill -0 $supervisorPid; kill -0 $listenerPid" | Out-Null
    $dumpText = (Invoke-Root 'dumpsys notification --noredact') -join "`n"
    $liveListeners = [regex]::Match($dumpText, '(?s)Live notification listeners \(\d+\):(.*?)Snoozed notification listeners').Groups[1].Value
    if ($liveListeners -notmatch 'android/com\.vmq\.ksu\.Listener') { throw 'Binder notification listener is not live' }
    if ($liveListeners -match [regex]::Escape($oldListener)) { throw 'Old APK notification listener is still live' }
    $packageDump = (Invoke-Root 'dumpsys package com.shinian.pay') -join "`n"
    $userZero = [regex]::Match($packageDump, '(?s)User 0:(.*?)(?:\n\s*User \d+:|\nQueries:)')
    if (-not $userZero.Success -or
            $userZero.Groups[1].Value -notmatch '(?s)disabledComponents:.*com\.shinian\.pay\.service\.PayNotificationListenerService') {
        throw 'Old APK notification listener component is not disabled'
    }
    if (-not $status.data.listener.lastHeartbeatSuccess) { throw 'Latest heartbeat is not successful' }
    if (-not $status.data.listener.lastNotificationPollSuccess -or
            [int64]$status.data.listener.lastNotificationPollAt -le 0 -or
            [int]$status.data.listener.activeProviderNotifications -lt 0) {
        throw "Notification polling is not successful: $($status.data.listener.lastNotificationPollMessage)"
    }
    $listenerHealth = Get-ControlJson 'test-listener'
    if (-not $listenerHealth.ok -or -not $listenerHealth.data.healthy) {
        throw "Listener health check failed: $($listenerHealth.data.message)"
    }
    if ([int]$status.data.listener.pending -ne 0) { throw 'Unexpected pending payment events remain' }
    if ([int]$status.data.listener.failed -ne 0) { throw 'Unexpected failed payment events remain' }
    if (-not (Invoke-Root "test -f '$modulePath/webroot/index.html' && echo present" | Select-Object -Last 1).Trim().Equals('present')) { throw 'KernelSU WebUI entry is missing' }
    $logs = Get-ControlJson 'logs'
    $logProperties = @($logs.data.PSObject.Properties.Name)
    if (-not $logs.ok -or $logProperties -notcontains 'lineCount' -or $logProperties -notcontains 'truncated' -or [int]$logs.data.lineCount -gt 200) {
        throw 'Bounded log response is missing lineCount/truncated metadata or exceeds 200 lines'
    }
    Assert-LogRedaction
    $acceptedBefore = Get-LogCount '已接收支付'
    $deliveredBefore = Get-LogCount '支付回调成功'
    $parser = Get-ControlJson 'test-parser'
    if (-not $parser.ok -or [int]$parser.data.accepted -ne 7 -or [int]$parser.data.rejected -ne 6) {
        throw "Parser fixtures failed: accepted=$($parser.data.accepted) rejected=$($parser.data.rejected)"
    }
    $afterParser = Get-ControlJson 'status'
    $parserMutated = [int]$afterParser.data.listener.pending -ne [int]$status.data.listener.pending -or
        [int]$afterParser.data.listener.failed -ne [int]$status.data.listener.failed -or
        (Get-LogCount '已接收支付') -ne $acceptedBefore -or
        (Get-LogCount '支付回调成功') -ne $deliveredBefore
    if ($parserMutated) {
        throw 'Parser self-test mutated the delivery queue or payment logs'
    }
    $runtimeFiles = @('config.json','desired_state','state.json','vmq.log','queue.jsonl','dedup.json')
    foreach ($runtimeFile in $runtimeFiles) {
        $mode = Invoke-Root "stat -c %a '/data/adb/vmq_ksu/$runtimeFile' 2>/dev/null || true" | Select-Object -Last 1
        $mode = if ($null -eq $mode) { '' } else { $mode.Trim() }
        if ($mode -and $mode -ne '600') { throw "Insecure runtime file mode: $runtimeFile=$mode" }
    }
    Write-Output "PASS: module running; supervisor=$supervisorPid listener=$listenerPid heartbeat=success notification-poll=success listener-health=healthy logs=bounded-redacted queue=empty failed=0 parser=7/6 old-listener=disabled files=0600"
}

if ($Lifecycle) {
    $initial = Wait-ForRunning
    Invoke-Root "$control stop" | Out-Null
    $stopped = $false
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $status = Get-ControlJson 'status'
        if ([int64]$status.data.supervisor.listenerPid -le 0 -and -not $status.data.listener.running) {
            $stopped = $true
            break
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $stopped) { throw 'Listener did not stop cleanly' }

    Invoke-Root "$control start" | Out-Null
    $started = Wait-ForRunning ([int64]$initial.data.listener.lastHeartbeatAt)

    $supervisorPid = [int64]$started.data.supervisor.pid
    $listenerPid = [int64]$started.data.supervisor.listenerPid
    $restartCount = [int]$started.data.supervisor.restartCount
    $heartbeatAt = [int64]$started.data.listener.lastHeartbeatAt
    Invoke-Root "$control restart" | Out-Null
    $restarted = Wait-ForListenerReplacement $supervisorPid $listenerPid $restartCount $heartbeatAt

    $listenerPid = [int64]$restarted.data.supervisor.listenerPid
    $restartCount = [int]$restarted.data.supervisor.restartCount
    $heartbeatAt = [int64]$restarted.data.listener.lastHeartbeatAt
    Invoke-Root "kill -KILL $listenerPid" | Out-Null
    $recovered = Wait-ForListenerReplacement $supervisorPid $listenerPid $restartCount $heartbeatAt

    $previousHeartbeat = [int64]$recovered.data.listener.lastHeartbeatAt
    $dozeHeartbeats = 0
    Invoke-Adb shell input keyevent 223 | Out-Null
    try {
        for ($attempt = 0; $attempt -lt 26; $attempt++) {
            Start-Sleep -Seconds 5
            $status = Get-ControlJson 'status'
            $currentHeartbeat = [int64]$status.data.listener.lastHeartbeatAt
            if ($status.data.listener.lastHeartbeatSuccess -and $currentHeartbeat -gt $previousHeartbeat) {
                $dozeHeartbeats++
                $previousHeartbeat = $currentHeartbeat
                if ($dozeHeartbeats -ge 2) { break }
            }
        }
    } finally {
        Invoke-Adb shell input keyevent 224 | Out-Null
    }
    if ($dozeHeartbeats -lt 2) { throw 'Fewer than two successful heartbeats occurred while dozing' }
    Write-Output "PASS: stop/start/restart, child crash recovery, and two doze heartbeats"
}

if ($RebootVerify) {
    $beforeReboot = Wait-ForRunning
    $beforeHeartbeat = [int64]$beforeReboot.data.listener.lastHeartbeatAt
    Invoke-Adb reboot | Out-Null
    Invoke-Adb wait-for-device | Out-Null
    $booted = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        $bootValue = Invoke-Adb shell getprop sys.boot_completed | Select-Object -Last 1
        if ($null -ne $bootValue -and $bootValue.Trim() -eq '1') {
            $booted = $true
            break
        }
        Start-Sleep -Seconds 2
    }
    if (-not $booted) { throw 'Android did not complete boot within 120 seconds' }
    $afterReboot = Wait-ForRunning $beforeHeartbeat 90
    Write-Output "PASS: reboot recovery supervisor=$($afterReboot.data.supervisor.pid) listener=$($afterReboot.data.supervisor.listenerPid) heartbeat=success"
}

if ($CapturePaymentBaseline) {
    $status = Wait-ForRunning
    $evidenceDirectory = Split-Path -Parent $PaymentEvidence
    if ($evidenceDirectory) { New-Item -ItemType Directory -Force $evidenceDirectory | Out-Null }
    $evidence = [ordered]@{
        capturedAt = (Get-Date).ToUniversalTime().ToString('o')
        accepted = Get-LogCount '已接收支付'
        delivered = Get-LogCount '支付回调成功'
        lastAcceptedEvent = $status.data.listener.lastAcceptedEvent
        lastDeliveryEvent = $status.data.listener.lastDeliveryEvent
    }
    [IO.File]::WriteAllText($PaymentEvidence, ($evidence | ConvertTo-Json -Compress) + "`n", (New-Object Text.UTF8Encoding($false)))
    Write-Output "PASS: payment baseline captured at $PaymentEvidence"
}

if ($VerifyPayment) {
    if (-not (Test-Path $PaymentEvidence)) { throw "Payment baseline is missing: $PaymentEvidence" }
    $baseline = Get-Content -Raw -Encoding UTF8 $PaymentEvidence | ConvertFrom-Json
    $status = Wait-ForRunning
    $accepted = Get-LogCount '已接收支付'
    $delivered = Get-LogCount '支付回调成功'
    $eventId = [string]$status.data.listener.lastAcceptedEvent
    $deliveryEventId = [string]$status.data.listener.lastDeliveryEvent
    if ($accepted -ne [int]$baseline.accepted + 1 -or $delivered -ne [int]$baseline.delivered + 1) {
        throw "Payment counts are not exactly once: accepted $($baseline.accepted)->$accepted delivered $($baseline.delivered)->$delivered"
    }
    if (-not $eventId -or $eventId -ne $deliveryEventId -or $eventId -eq [string]$baseline.lastAcceptedEvent) {
        throw 'Accepted and delivered event IDs do not identify one new payment'
    }
    if (-not $status.data.listener.lastDeliverySuccess -or
            [int]$status.data.listener.pending -ne 0 -or [int]$status.data.listener.failed -ne 0) {
        throw 'Payment delivery is not successful or the queue is not empty'
    }
    Write-Output "PASS: 支付已准确接收并投递一次 event=$eventId pending=0 failed=0"
}

if ($Rollback) {
    Invoke-Root "$control stop" | Out-Null
    Invoke-Root '/data/adb/ksu/bin/ksud module uninstall vmq_ksu' | Out-Null
    Invoke-Root "pm enable --user 0 $oldListener" | Out-Null
    Write-Output 'PASS: vmq_ksu marked for uninstall and original listener re-enabled; reboot required'
}

if (-not ($Install -or $Verify -or $Lifecycle -or $RebootVerify -or $CapturePaymentBaseline -or $VerifyPayment -or $Rollback)) {
    throw 'Specify -Install, -Verify, -Lifecycle, -RebootVerify, -CapturePaymentBaseline, -VerifyPayment, or -Rollback'
}
