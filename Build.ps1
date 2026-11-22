param([switch]$AndroidOnly)

$ErrorActionPreference = 'Stop'

function Invoke-BuildTool($tool, [string[]]$arguments) {
    & $tool @arguments
    if ($LASTEXITCODE -ne 0) { throw "Falha de compilação em $tool (código $LASTEXITCODE)." }
}

$base = $PSScriptRoot
$tools = Join-Path $base '.toolchain'
$env:JAVA_HOME = (Get-ChildItem -LiteralPath $tools -Directory -Filter 'jdk-*' | Select-Object -First 1).FullName
$build = (Get-ChildItem -LiteralPath (Join-Path $tools 'build-tools') -Filter aapt2.exe -Recurse | Select-Object -First 1).DirectoryName
$platform = (Get-ChildItem -LiteralPath (Join-Path $tools 'platforms') -Filter android.jar -Recurse | Select-Object -First 1).FullName
if (!$env:JAVA_HOME -or !$build -or !$platform) { throw 'Instale o JDK 17, a plataforma Android 35 e Build Tools 35.0.0 em .toolchain.' }

$out = Join-Path $base 'out'
$android = Join-Path $base 'android'
$classes = Join-Path $out 'classes'
$dex = Join-Path $out 'dex'
$agent = Join-Path $out 'agent'
New-Item -ItemType Directory -Path $classes,$dex,$agent -Force | Out-Null
$keyPath = Join-Path $agent 'pairing.key'
if (!(Test-Path -LiteralPath $keyPath)) {
    $bytes = [byte[]]::new(32)
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    [IO.File]::WriteAllText($keyPath, (-join ($bytes | ForEach-Object { $_.ToString('x2') })))
}
$token = [IO.File]::ReadAllText($keyPath).Trim()
if ($token -notmatch '^[a-f0-9]{64}$') { throw 'Chave de pareamento inválida.' }
$pairing = Join-Path $out 'Pairing.java'
[IO.File]::WriteAllText($pairing, "package br.com.rian.deck; final class Pairing { static final String TOKEN = `"$token`"; }")
if (!$AndroidOnly) {
    Invoke-BuildTool dotnet @('build',(Join-Path $base 'agent'),'-c','Release','--ignore-failed-sources','-o',$agent)
}
$sources = @(Get-ChildItem -LiteralPath $android -Filter '*.java' | ForEach-Object FullName)
Invoke-BuildTool (Join-Path $env:JAVA_HOME 'bin\javac.exe') (@('--release','8','-encoding','UTF-8','-classpath',$platform,'-d',$classes,$pairing) + $sources)
$jar = Join-Path $out 'app.jar'
Invoke-BuildTool (Join-Path $env:JAVA_HOME 'bin\jar.exe') @('cf',$jar,'-C',$classes,'.')
Invoke-BuildTool (Join-Path $build 'd8.bat') @('--min-api','30','--lib',$platform,'--output',$dex,$jar)

$unsigned = Join-Path $out 'unsigned.apk'
$aligned = Join-Path $out 'aligned.apk'
$apk = Join-Path $out 'RianDeckOS.apk'
$resources = Join-Path $out 'android-res'
$drawables = Join-Path $resources 'drawable-nodpi'
$fonts = Join-Path $resources 'font'
$raw = Join-Path $resources 'raw'
New-Item -ItemType Directory -Path $drawables,$fonts,$raw -Force | Out-Null
$assetRoot = Join-Path $android 'assets'
foreach ($group in 'icons','photos') {
    Get-ChildItem -LiteralPath (Join-Path $assetRoot $group) -File | Where-Object Extension -in '.png','.jpg' | ForEach-Object {
        $resource = $group + '_' + $_.Name.ToLowerInvariant().Replace('-','_')
        Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $drawables $resource)
    }
}
foreach ($group in 'backgrounds','icons') {
    Get-ChildItem -LiteralPath (Join-Path $assetRoot "trends/$group") -File -Filter '*.png' | ForEach-Object {
        $resource = "trends_${group}_" + $_.Name.ToLowerInvariant().Replace('-','_')
        Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $drawables $resource)
    }
}
Copy-Item -LiteralPath (Join-Path $assetRoot 'fonts\Manrope.ttf') -Destination (Join-Path $fonts 'manrope.ttf')
Copy-Item -LiteralPath (Join-Path $assetRoot 'CREDITOS.txt') -Destination (Join-Path $raw 'visual_credits.txt')
Copy-Item -LiteralPath (Join-Path $assetRoot 'fonts\OFL-Manrope.txt') -Destination (Join-Path $raw 'manrope_license.txt')
Copy-Item -LiteralPath (Join-Path $assetRoot 'icons\LICENSE-Lucide.txt') -Destination (Join-Path $raw 'lucide_license.txt')
$memes = Join-Path $assetRoot 'memes'
Get-ChildItem -LiteralPath $raw -Filter 'meme_*.gif' -File | Where-Object {
    !(Test-Path -LiteralPath (Join-Path $memes $_.Name) -PathType Leaf)
} | ForEach-Object { Remove-Item -LiteralPath $_.FullName }
if (Test-Path -LiteralPath $memes -PathType Container) {
    Get-ChildItem -LiteralPath $memes -Filter 'meme_*.gif' -File | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $raw $_.Name)
    }
}
$compiled = Join-Path $out 'visual-res.zip'
$xml = Join-Path $resources 'xml'
New-Item -ItemType Directory -Path $xml -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $android 'deck_admin.xml') -Destination (Join-Path $xml 'deck_admin.xml')
Invoke-BuildTool (Join-Path $build 'aapt2.exe') @('compile','--dir',$resources,'-o',$compiled)
Invoke-BuildTool (Join-Path $build 'aapt2.exe') @('link','-I',$platform,'--manifest',(Join-Path $android 'AndroidManifest.xml'),'-R',$compiled,'-o',$unsigned)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open($unsigned, [IO.Compression.ZipArchiveMode]::Update)
try {
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,(Join-Path $dex 'classes.dex'),'classes.dex') | Out-Null
} finally { $zip.Dispose() }
Invoke-BuildTool (Join-Path $build 'zipalign.exe') @('-f','4',$unsigned,$aligned)

$keystore = Join-Path $out 'prototype.jks'
if (!(Test-Path -LiteralPath $keystore)) {
    Invoke-BuildTool (Join-Path $env:JAVA_HOME 'bin\keytool.exe') @('-genkeypair','-keystore',$keystore,'-storepass','prototype-only','-keypass','prototype-only','-alias','riandeck','-keyalg','RSA','-keysize','2048','-validity','3650','-dname','CN=Rian Deck Prototype')
}
Invoke-BuildTool (Join-Path $build 'apksigner.bat') @('sign','--ks',$keystore,'--ks-pass','pass:prototype-only','--out',$apk,$aligned)
Invoke-BuildTool (Join-Path $build 'apksigner.bat') @('verify',$apk)
Get-Item -LiteralPath $apk | Select-Object FullName,Length
