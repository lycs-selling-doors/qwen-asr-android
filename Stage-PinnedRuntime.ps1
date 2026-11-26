param(
    [string]$Archive,
    [string]$WorkDir,
    [ValidateSet('snapdragon', 'cpu', 'all')]
    [string]$Variant = 'snapdragon'
)

$ErrorActionPreference = 'Stop'
$tag = 'b12470'

function Stage-OneVariant {
    param([string]$TargetVariant, [string]$ExplicitArchive, [string]$ExplicitWorkDir)

    if ($TargetVariant -eq 'snapdragon') {
        $name = "llama-$tag-bin-android-arm64-snapdragon.tar.gz"
        $expected = 'ea5c5432bace3383e987229611649a3934656af5226b269615c418857818d510'
        $work = if ($ExplicitWorkDir) { $ExplicitWorkDir } else { Join-Path $PSScriptRoot 'build/llama-b12470' }
        $checksumFile = Join-Path $PSScriptRoot 'Runtime-SHA256.txt'
    } else {
        $name = "llama-$tag-bin-android-arm64.tar.gz"
        $expected = '8e13bdc6cbd736afc1622b315c296dbddfffc4d13891e3c91d32c4d30a50529d'
        $work = if ($ExplicitWorkDir) { $ExplicitWorkDir } else { Join-Path $PSScriptRoot 'build/llama-b12470-cpu' }
        $checksumFile = Join-Path $PSScriptRoot 'Runtime-CPU-SHA256.txt'
    }

    New-Item -ItemType Directory -Path $work -Force | Out-Null
    $archivePath = $ExplicitArchive
    if (-not $archivePath) {
        $archivePath = Join-Path $work $name
        if (-not (Test-Path -LiteralPath $archivePath)) {
            $partial = "$archivePath.partial"
            $url = "https://github.com/ggml-org/llama.cpp/releases/download/$tag/$name"
            Invoke-WebRequest -Uri $url -OutFile $partial -TimeoutSec 240
            if ((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash -ne $expected) {
                throw "Downloaded $name SHA-256 differs from the pinned digest."
            }
            Move-Item -LiteralPath $partial -Destination $archivePath -Force
        }
    }
    if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash -ne $expected) {
        throw "$name SHA-256 differs from the pinned digest."
    }

    tar -xf $archivePath -C $work
    if ($LASTEXITCODE -ne 0) { throw "Could not extract $name." }
    $runtime = Join-Path $work "llama-$tag"

    if ($TargetVariant -eq 'snapdragon') {
        if (-not (Test-Path (Join-Path $runtime 'bin/llama-server'))) { throw 'Unexpected Snapdragon runtime archive layout.' }
        & (Join-Path $PSScriptRoot 'Stage-Hexagon.ps1') -LocalRuntime $runtime
    } else {
        if (-not (Test-Path (Join-Path $runtime 'llama-server'))) { throw 'Unexpected Android ARM64 CPU runtime archive layout.' }
        $jniDir = Join-Path $PSScriptRoot 'app/runtime-cpu/jniLibs/arm64-v8a'
        $assetsDir = Join-Path $PSScriptRoot 'app/runtime-cpu/assets'
        New-Item -ItemType Directory -Force -Path $jniDir, $assetsDir | Out-Null
        Copy-Item -LiteralPath (Join-Path $runtime 'llama-server') -Destination (Join-Path $jniDir 'libqwen_llama_server.so') -Force
        Copy-Item -LiteralPath (Join-Path $runtime 'LICENSE') -Destination (Join-Path $assetsDir 'llama.cpp-LICENSE.txt') -Force
        $coreLibs = @(
            'libllama-server-impl.so', 'libllama-common.so', 'libmtmd.so',
            'libllama.so', 'libggml.so', 'libggml-base.so'
        )
        $cpuPlugins = Get-ChildItem -LiteralPath $runtime -File -Filter 'libggml-cpu-android_*.so' |
            Sort-Object Name | Select-Object -ExpandProperty Name
        if (-not $cpuPlugins) { throw 'No Android ARM64 CPU backend libraries found.' }
        foreach ($libName in ($coreLibs + $cpuPlugins)) {
            Copy-Item -LiteralPath (Join-Path $runtime $libName) -Destination (Join-Path $jniDir $libName) -Force
        }
    }

    $checksums = Get-Content -LiteralPath $checksumFile
    foreach ($entry in $checksums) {
        if ([string]::IsNullOrWhiteSpace($entry)) { continue }
        $digest, $relative = $entry -split ' ', 2
        $actual = Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $relative) -Algorithm SHA256
        if ($actual.Hash -ne $digest) { throw "Staged runtime file differs from its pinned digest: $relative" }
    }
    Write-Output "Android arm64 ($TargetVariant) runtime verified: $tag (dde9de82cbb9b1050ae75c95ab9a9f955340647f)."
}

if ($Variant -eq 'all') {
    Stage-OneVariant -TargetVariant 'snapdragon' -ExplicitArchive '' -ExplicitWorkDir ''
    Stage-OneVariant -TargetVariant 'cpu' -ExplicitArchive '' -ExplicitWorkDir ''
} else {
    Stage-OneVariant -TargetVariant $Variant -ExplicitArchive $Archive -ExplicitWorkDir $WorkDir
}
