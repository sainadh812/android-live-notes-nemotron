param([string]$Destination = (Join-Path $PSScriptRoot "..\resources\windows-x64\speaker-worker"))
$ErrorActionPreference = "Stop"
Push-Location $PSScriptRoot
try {
    python -m pip install --disable-pip-version-check -r requirements.txt
    if ($LASTEXITCODE -ne 0) { throw "Speaker worker dependencies failed to install" }
    python -m unittest discover -s tests -v
    if ($LASTEXITCODE -ne 0) { throw "Speaker worker tests failed" }
    python -m PyInstaller --noconfirm --clean --onedir --console --name speaker-worker `
        --collect-all sherpa_onnx --copy-metadata sherpa-onnx --copy-metadata numpy `
        --add-data "models.json;." --add-data "licenses;licenses" --add-data "THIRD_PARTY.md;." worker.py
    if ($LASTEXITCODE -ne 0) { throw "Speaker worker packaging failed" }
    & ".\dist\speaker-worker\speaker-worker.exe" --self-test
    if ($LASTEXITCODE -ne 0) { throw "Packaged speaker worker self-test failed" }
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    Copy-Item -Path ".\dist\speaker-worker\*" -Destination $Destination -Recurse -Force
} finally { Pop-Location }
