param([Parameter(Mandatory=$true)][string]$LogPath, [Parameter(Mandatory=$true)][string]$ResultPath)
$ErrorActionPreference = 'Stop'
$text = Get-Content -LiteralPath $LogPath -Raw
$failure = $text -match 'MixinApplyError|MixinTransformerError|InvalidInjectionException|Could not execute entrypoint|Exception in thread'
$eulaGate = $text -match 'You (need|must) to agree to the EULA|You need to agree to the EULA'
$passed = $eulaGate -and -not $failure
$reason = if ($failure) { 'Runtime exception occurred before expected EULA stop' } elseif (-not $eulaGate) { 'Expected EULA gate was not reached' } else { 'Reached EULA gate; full server startup is still unverified' }
$result = [pscustomobject]@{ Passed=$passed; ExpectedGate='EULA'; FatalRuntimeError=$failure; EulaGateReached=$eulaGate; Reason=$reason }
$result | ConvertTo-Json | Set-Content -LiteralPath $ResultPath -Encoding utf8
Write-Output ($result | ConvertTo-Json -Compress)
if (-not $passed) { exit 3 }
