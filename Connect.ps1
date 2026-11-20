param(
    [Parameter(Mandatory)][string]$Serial,
    [string]$Adb = 'C:\RianDeck\platform-tools\adb.exe',
    [switch]$Install,
    [switch]$Open
)

$ErrorActionPreference = 'Stop'
if ((& $Adb -s $Serial get-state) -ne 'device') {
    throw 'O aparelho especificado precisa estar conectado e autorizado.'
}

if ($Install) {
    $apk = Join-Path $PSScriptRoot 'out\RianDeckOS.apk'
    & $Adb -s $Serial install -r $apk
    if ($LASTEXITCODE -ne 0) { throw 'Falha na instalação do APK.' }
}

& $Adb -s $Serial reverse tcp:48627 tcp:48627
if ($LASTEXITCODE -ne 0) { throw 'Falha ao abrir o túnel USB.' }
& $Adb -s $Serial reverse --list
if ($Open) {
    & $Adb -s $Serial shell am start -n 'br.com.rian.deck/.DeckActivity'
    if ($LASTEXITCODE -ne 0) { throw 'Falha na abertura do Deck.' }
}
