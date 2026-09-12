param(
    [string]$EngineSource = "",
    [string]$BuildDirectory = "$PSScriptRoot/build",
    [string]$Destination = "$PSScriptRoot/../resources/windows-x64/native"
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$PinnedCommit = "63a44d9239d610b3908e8a66b384924cd4a77217"
function Run-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed with exit code $LASTEXITCODE" }
}
New-Item -ItemType Directory -Force -Path $BuildDirectory | Out-Null
$BuildDirectory = (Resolve-Path $BuildDirectory).Path
if (-not $EngineSource) {
    $EngineSource = Join-Path $BuildDirectory "transcribe-source"
    if (-not (Test-Path "$EngineSource/.git")) {
        Run-Checked git @("clone", "--no-checkout", "https://github.com/handy-computer/transcribe.cpp.git", $EngineSource)
        Run-Checked git @("-C", $EngineSource, "checkout", "--detach", $PinnedCommit)
    }
}
$EngineSource = (Resolve-Path $EngineSource).Path
$ActualCommit = (& git -C $EngineSource rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $ActualCommit -ne $PinnedCommit) { throw "Unexpected transcribe.cpp commit: $ActualCommit" }
if (& git -C $EngineSource status --porcelain) { throw "Engine checkout must be clean" }
$EngineBuild = Join-Path $BuildDirectory "engine"
Run-Checked cmake @("-S", $EngineSource, "-B", $EngineBuild, "-A", "x64",
    "-DTRANSCRIBE_BUILD_SHARED=ON", "-DTRANSCRIBE_BUILD_TESTS=OFF", "-DTRANSCRIBE_BUILD_EXAMPLES=OFF",
    "-DTRANSCRIBE_BUILD_TOOLS=OFF", "-DTRANSCRIBE_USE_SYSTEM_BLAS=OFF", "-DTRANSCRIBE_USE_OPENMP=OFF",
    "-DTRANSCRIBE_X86_CONSERVATIVE=ON", "-DTRANSCRIBE_METAL=OFF", "-DTRANSCRIBE_VULKAN=OFF",
    "-DTRANSCRIBE_CUDA=OFF", "-DTRANSCRIBE_HIP=OFF", "-DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded",
    "-DCMAKE_CXX_FLAGS=/utf-8")
Run-Checked cmake @("--build", $EngineBuild, "--config", "Release", "--target", "transcribe", "--parallel", "4")
$ImportLibrary = @(Get-ChildItem $EngineBuild -Filter transcribe.lib -Recurse)
if ($ImportLibrary.Count -ne 1) { throw "Expected one transcribe.lib, found $($ImportLibrary.Count)" }
$BridgeBuild = Join-Path $BuildDirectory "bridge"
Run-Checked cmake @("-S", $PSScriptRoot, "-B", $BridgeBuild, "-A", "x64",
    "-DTRANSCRIBE_SOURCE_DIR=$EngineSource", "-DTRANSCRIBE_LIBRARY=$($ImportLibrary[0].FullName)",
    "-DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded")
Run-Checked cmake @("--build", $BridgeBuild, "--config", "Release", "--parallel", "4")
New-Item -ItemType Directory -Force -Path $Destination | Out-Null
foreach ($Name in @("ggml-base.dll", "ggml-cpu.dll", "ggml.dll", "transcribe.dll")) {
    $Libraries = @(Get-ChildItem $EngineBuild -Filter $Name -Recurse)
    if ($Libraries.Count -ne 1) { throw "Expected one $Name, found $($Libraries.Count)" }
    Copy-Item $Libraries[0].FullName (Join-Path $Destination $Name) -Force
}
Copy-Item "$BridgeBuild/Release/livenotes_jni.dll" (Join-Path $Destination "livenotes_jni.dll") -Force
Copy-Item "$EngineSource/LICENSE" (Join-Path $Destination "transcribe-LICENSE.txt") -Force
Copy-Item "$EngineSource/ggml/LICENSE" (Join-Path $Destination "ggml-LICENSE.txt") -Force
Get-ChildItem $Destination -Filter *.dll | Get-FileHash -Algorithm SHA256 | Format-Table
Write-Host "Windows x64 CPU native libraries staged at $Destination"
