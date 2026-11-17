param(
    [string]$LocalRuntime,
    [string]$DeviceRuntime,
    [string]$Serial
)

$ErrorActionPreference = 'Stop'
if ([bool]$LocalRuntime -eq [bool]$DeviceRuntime) {
    throw 'Set exactly one of -LocalRuntime or -DeviceRuntime to the Snapdragon llama.cpp package directory.'
}

$androidLibraries = Join-Path $PSScriptRoot 'app/runtime/jniLibs/arm64-v8a'
$dspLibraries = Join-Path $PSScriptRoot 'app/runtime/assets/htp'
New-Item -ItemType Directory -Force -Path $androidLibraries, $dspLibraries | Out-Null
$adb = Join-Path (Split-Path -Parent $PSScriptRoot) 'tools/android-sdk/platform-tools/adb.exe'
$device = if ($Serial) { @('-s', $Serial) } else { @('-d') }

function Copy-RuntimeFile($RelativePath, $Destination) {
    if ($LocalRuntime) {
        Copy-Item -LiteralPath (Join-Path $LocalRuntime $RelativePath) -Destination $Destination -Force
    } else {
        & $adb @device pull "$($DeviceRuntime.TrimEnd('/'))/$RelativePath" $Destination
        if ($LASTEXITCODE -ne 0) { throw "Could not pull $RelativePath from $Serial." }
    }
}

Copy-RuntimeFile 'bin/llama-server' (Join-Path $androidLibraries 'libqwen_llama_server.so')
Copy-RuntimeFile 'LICENSE' (Join-Path (Split-Path -Parent $dspLibraries) 'llama.cpp-LICENSE.txt')
$hostLibraries = @(
    'libllama-server-impl.so', 'libllama-common.so', 'libmtmd.so', 'libllama.so',
    'libggml.so', 'libggml-base.so', 'libggml-cpu.so', 'libggml-rpc.so',
    'libggml-opencl.so', 'libggml-hexagon.so'
)
foreach ($name in $hostLibraries) {
    Copy-RuntimeFile "lib/$name" (Join-Path $androidLibraries $name)
}
if ($LocalRuntime) {
    $skels = Get-ChildItem -LiteralPath (Join-Path $LocalRuntime 'lib') -File -Filter 'libggml-htp-v*.so' |
        Select-Object -ExpandProperty Name
} else {
    if ($DeviceRuntime -notmatch '^/[a-zA-Z0-9_./-]+$') { throw 'Unsupported remote runtime path.' }
    $skels = & $adb @device shell "cd '$DeviceRuntime/lib' && ls -1 libggml-htp-v*.so"
    if ($LASTEXITCODE -ne 0) { throw 'No HTP libraries found on the device.' }
}
if (-not $skels) { throw 'No HTP libraries found.' }
foreach ($name in $skels) {
    if ($name -notmatch '^libggml-htp-v[0-9]+\.so$') { throw "Unexpected HTP library name: $name" }
    Copy-RuntimeFile "lib/$name" (Join-Path $dspLibraries $name)
}
