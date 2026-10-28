param([ValidateSet('arm64-v8a', 'x86_64')][string]$Abi = 'arm64-v8a')
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
Push-Location $projectDir
try {
    if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 17 before building.' }
    $previewWork = Join-Path $projectDir 'work/ui-preview'
    New-Item -ItemType Directory -Force -Path $previewWork | Out-Null
    $initScript = Join-Path $previewWork 'preview.init.gradle'
    $authAars = @(Get-ChildItem -LiteralPath 'app/libs' -Filter 'musickitauth*.aar')
    if ($authAars.Count -eq 0) {
        $source = Join-Path $previewWork 'TokenProvider.java'
        $classes = Join-Path $previewWork 'classes'
        New-Item -ItemType Directory -Force -Path $classes | Out-Null
        Set-Content -LiteralPath $source -Encoding ascii -Value 'package com.apple.android.sdk.authentication; public interface TokenProvider { String getDeveloperToken(); String getUserToken(); }'
        & (Join-Path $env:JAVA_HOME 'bin/javac.exe') -d $classes $source
        if ($LASTEXITCODE -ne 0) { throw 'Preview compile-only interface generation failed.' }
        & (Join-Path $env:JAVA_HOME 'bin/jar.exe') --create --file (Join-Path $previewWork 'token-provider.jar') -C $classes .
        if ($LASTEXITCODE -ne 0) { throw 'Preview compile-only archive generation failed.' }
        Set-Content -LiteralPath $initScript -Encoding ascii -Value 'gradle.projectsEvaluated { def app = rootProject.findProject(":app"); if (app != null) app.dependencies.add("compileOnly", rootProject.files("work/ui-preview/token-provider.jar")) }'
    } else {
        Set-Content -LiteralPath $initScript -Encoding ascii -Value '// Official authentication SDK is available; no preview interface needed.'
    }
    & .\gradlew.bat -I $initScript '-Pflyme.uiVerification=true' "-Pflyme.previewAbi=$Abi" :app:assembleFlymeGecko --max-workers=2
    if ($LASTEXITCODE -ne 0) { throw 'UI preview build failed.' }
    New-Item -ItemType Directory -Force -Path dist | Out-Null
    $output = Join-Path $projectDir "dist/AM-FlymeAuto-UI-Preview-$Abi.apk"
    Copy-Item -LiteralPath 'app/build/outputs/apk/flymeGecko/app-flymeGecko.apk' -Destination $output
    Write-Output "UI PREVIEW ONLY: no MusicKit playback or live sign-in is validated by this package."
    Write-Output $output
} finally { Pop-Location }
