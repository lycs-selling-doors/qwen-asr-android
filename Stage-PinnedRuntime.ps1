param([string]$Archive, [string]$WorkDir = (Join-Path $PSScriptRoot 'build/llama-b12470'))

$ErrorActionPreference = 'Stop'
$tag = 'b12470'
$name = "llama-$tag-bin-android-arm64-snapdragon.tar.gz"
$expected = 'ea5c5432bace3383e987229611649a3934656af5226b269615c418857818d510'
New-Item -ItemType Directory -Path $WorkDir -Force | Out-Null
if (-not $Archive) {
    $Archive = Join-Path $WorkDir $name
    if (-not (Test-Path -LiteralPath $Archive)) {
        $partial = "$Archive.partial"
        $url = "https://github.com/ggml-org/llama.cpp/releases/download/$tag/$name"
        Invoke-WebRequest -Uri $url -OutFile $partial -TimeoutSec 180
        if ((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash -ne $expected) {
            throw 'Downloaded llama.cpp archive SHA-256 differs from the pinned digest.'
        }
        Move-Item -LiteralPath $partial -Destination $Archive -Force
    }
}
if ((Get-FileHash -LiteralPath $Archive -Algorithm SHA256).Hash -ne $expected) {
    throw 'llama.cpp archive SHA-256 differs from the pinned digest.'
}

tar -xf $Archive -C $WorkDir
if ($LASTEXITCODE -ne 0) { throw 'Could not extract the pinned llama.cpp archive.' }
$runtime = Join-Path $WorkDir "llama-$tag"
if (-not (Test-Path (Join-Path $runtime 'bin/llama-server'))) { throw 'Unexpected runtime archive layout.' }
& (Join-Path $PSScriptRoot 'Stage-Hexagon.ps1') -LocalRuntime $runtime

$checksums = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'Runtime-SHA256.txt')
foreach ($entry in $checksums) {
    $digest, $relative = $entry -split ' ', 2
    $actual = Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $relative) -Algorithm SHA256
    if ($actual.Hash -ne $digest) { throw "Staged runtime file differs from its pinned digest: $relative" }
}
Write-Output "Android arm64 runtime verified: $tag (dde9de82cbb9b1050ae75c95ab9a9f955340647f)."
