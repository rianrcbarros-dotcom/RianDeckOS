$ErrorActionPreference = 'Stop'
$file = Join-Path $PSScriptRoot 'out\agent\studio\editor.key'
if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw 'Inicie o Rian Deck Agent antes de abrir o Studio.' }
$key = (Get-Content -LiteralPath $file -Raw).Trim()
if ($key -notmatch '^[a-fA-F0-9]{64}$') { throw 'Chave local inválida.' }
Start-Process "http://127.0.0.1:48627/editor#$key"
