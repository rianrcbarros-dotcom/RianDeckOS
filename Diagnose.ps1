param(
    [Parameter(Mandatory)][string]$Serial,
    [string]$Adb = 'C:\RianDeck\platform-tools\adb.exe'
)

$ErrorActionPreference = 'Stop'
& $Adb version
& $Adb devices -l
if ((& $Adb -s $Serial get-state) -eq 'device') {
    & $Adb -s $Serial shell getprop ro.boot.hardware.sku
    & $Adb -s $Serial shell getprop ro.build.display.id
    & $Adb -s $Serial shell pm path br.com.rian.deck
    & $Adb -s $Serial reverse --list
}

$keyPath = Join-Path $PSScriptRoot 'out\agent\pairing.key'
if (!(Test-Path -LiteralPath $keyPath -PathType Leaf)) { throw 'Chave local ausente. Compile o projeto.' }
$token = [IO.File]::ReadAllText($keyPath).Trim()
Invoke-RestMethod -Uri 'http://127.0.0.1:48627/v1/status' -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 3
