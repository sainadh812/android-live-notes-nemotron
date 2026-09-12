$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$Project = (Resolve-Path "$PSScriptRoot/..").Path
$Evidence = Join-Path $Project "build/install-evidence"
New-Item -ItemType Directory -Force $Evidence | Out-Null
$Installer = @(Get-ChildItem "$Project/build/release" -Filter *.msi)
if ($Installer.Count -ne 1) { throw "Expected one MSI installer" }
$Log = Join-Path $Evidence "install.log"
$Install = Start-Process msiexec.exe -ArgumentList @('/i', "`"$($Installer[0].FullName)`"", '/qn', '/norestart', '/L*v', "`"$Log`"") -Wait -PassThru
if ($Install.ExitCode -notin @(0, 3010)) { throw "Installer failed with exit code $($Install.ExitCode); inspect install.log" }
$Candidates = @(Get-ChildItem $env:LOCALAPPDATA -Filter LiveMeetingNotes.exe -Recurse -ErrorAction SilentlyContinue)
if ($Candidates.Count -lt 1) { throw "Installed launcher was not found" }
$App = $Candidates[0].FullName
$Process = Start-Process $App -ArgumentList @('--smoke-test', "`"$Evidence`"") -PassThru
if (-not $Process.WaitForExit(90000)) { Stop-Process -Id $Process.Id -Force; throw "Installed app did not complete startup check" }
if ($Process.ExitCode -ne 0 -or -not (Test-Path "$Evidence/startup-ok.txt") -or (Test-Path "$Evidence/startup-error.txt")) {
    throw "Installed app startup failed; inspect evidence"
}
if (-not (Test-Path "$Evidence/installed-app.png")) { throw "Installed app screenshot is missing" }
Write-Host "Installed Windows app launched, opened its database, rendered UI, and closed successfully."
