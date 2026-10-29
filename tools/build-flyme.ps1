param([switch]$StandardMediaOnly, [string]$SigningKeystore)
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
Push-Location $projectDir
try {
    $buildArgs = @(':app:assembleFlymeGecko')
    if ($SigningKeystore) {
        if (-not (Test-Path -LiteralPath $SigningKeystore -PathType Leaf)) { throw 'Signing keystore file not found.' }
        $keystorePath = (Resolve-Path -LiteralPath $SigningKeystore).ProviderPath
        $buildArgs += "-PFLYME_SIGNING_KEYSTORE_FILE=$keystorePath"
    }
    if ($StandardMediaOnly) { $buildArgs += '-Pflyme.nativeMedia=false' }
    & .\gradlew.bat @buildArgs
    if ($LASTEXITCODE -ne 0) { throw 'Flyme Gecko build failed.' }
    New-Item -ItemType Directory -Force -Path dist | Out-Null
    Copy-Item -LiteralPath 'app/build/outputs/apk/flymeGecko/app-flymeGecko.apk' -Destination 'dist/AMATV-FlymeAuto-Gecko-arm64.apk'
    Write-Output (Join-Path $projectDir 'dist/AMATV-FlymeAuto-Gecko-arm64.apk')
} finally { Pop-Location }
