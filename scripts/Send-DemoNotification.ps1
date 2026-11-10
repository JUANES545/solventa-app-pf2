[CmdletBinding()]
param(
    [switch]$Send,
    [switch]$DryRun,
    [switch]$SelfCheck,
    [string]$Serial = $env:ANDROID_SERIAL,
    [string]$Adb = 'adb',
    [string]$EventId = [guid]::NewGuid().ToString(),
    [string]$ClaimId = '11111111-1111-4111-8111-111111111102'
)
$ErrorActionPreference = 'Stop'
$package = 'co.solventa.mobile.pf2.mock'
$component = "$package/co.solventa.mobile.mock.MockNotificationReceiver"
$action = 'co.solventa.mobile.mock.SEND_NOTIFICATION'
foreach ($value in @($EventId, $ClaimId)) {
    $parsed = [guid]::Empty
    if (-not [guid]::TryParseExact($value, 'D', [ref]$parsed) -or $parsed.ToString() -cne $value) {
        throw 'Los identificadores deben ser UUID canónicos.'
    }
}
if ($Serial -and $Serial -notmatch '^[A-Za-z0-9._:-]+$') { throw 'Serial ADB inválido.' }
$deviceArgs = @()
if ($Serial) { $deviceArgs = @('-s', $Serial) }
$broadcast = @($deviceArgs + @('shell', 'am', 'broadcast', '--receiver-foreground',
    '-n', $component, '-a', $action, '--es', 'event_id', $EventId, '--es', 'claim_id', $ClaimId))
if ($SelfCheck) {
    if ($component -cne 'co.solventa.mobile.pf2.mock/co.solventa.mobile.mock.MockNotificationReceiver' -or
        $broadcast[-1] -cne $ClaimId -or $broadcast -contains 'reverse') { throw 'Contrato demo inválido.' }
    Write-Output 'Self-check correcto; no se ha contactado el teléfono.'
    return
}
if ($DryRun -or -not $Send) {
    Write-Output ('DRY RUN: "{0}" {1}' -f $Adb, ($broadcast -join ' '))
    Write-Output 'Para enviar un aviso usa -Send. Reutiliza -EventId para comprobar deduplicación.'
    return
}
$adbCommand = Get-Command -Name $Adb -CommandType Application -ErrorAction SilentlyContinue
if (-not $adbCommand) { throw 'No se encontró ADB. Agrégalo al PATH o indica -Adb.' }
$adbPath = $adbCommand.Source
$device = & $adbPath @deviceArgs get-state 2>&1
if ($LASTEXITCODE -ne 0 -or ($device -join '').Trim() -ne 'device') { throw 'No se pudo seleccionar un dispositivo. Verifica ADB o indica -Serial.' }
$installed = & $adbPath @deviceArgs shell pm path $package 2>&1
if ($LASTEXITCODE -ne 0 -or -not ($installed -match '^package:')) { throw 'Instala Solventa Flujos antes de enviar el aviso.' }
$result = & $adbPath @broadcast 2>&1
$exit = $LASTEXITCODE
Write-Output $result
if ($exit -ne 0 -or ($result -join "`n") -notmatch 'Broadcast completed: result=0, data="Aviso (enviado|ya recibido)"') {
    throw 'Solventa no confirmó el aviso. Revisa sesión, permiso, preferencias y escenario Con información.'
}
