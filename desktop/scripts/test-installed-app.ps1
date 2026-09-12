$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$Project = (Resolve-Path "$PSScriptRoot/..").Path
$Evidence = Join-Path $Project "build/install-evidence"
New-Item -ItemType Directory -Force $Evidence | Out-Null
foreach ($Marker in @("startup-ok.txt", "startup-error.txt", "installed-app.png", "installed-native-checks.txt", "installed-worker-self-test.log")) {
    Remove-Item (Join-Path $Evidence $Marker) -Force -ErrorAction SilentlyContinue
}
$Installer = @(Get-ChildItem "$Project/build/release" -Filter *.msi)
if ($Installer.Count -ne 1) { throw "Expected one MSI installer" }
$Log = Join-Path $Evidence "install.log"
$Install = Start-Process msiexec.exe -ArgumentList @('/i', "`"$($Installer[0].FullName)`"", '/qn', '/norestart', '/L*v', "`"$Log`"") -Wait -PassThru
if ($Install.ExitCode -notin @(0, 3010)) { throw "Installer failed with exit code $($Install.ExitCode); inspect install.log" }
$Candidates = @(Get-ChildItem $env:LOCALAPPDATA -Filter LiveMeetingNotes.exe -Recurse -ErrorAction SilentlyContinue)
if ($Candidates.Count -ne 1) { throw "Expected one installed launcher, found $($Candidates.Count)" }
$App = $Candidates[0].FullName
$Process = Start-Process $App -ArgumentList @('--smoke-test', "`"$Evidence`"") -WorkingDirectory $Evidence -PassThru
if (-not $Process.WaitForExit(150000)) { Stop-Process -Id $Process.Id -Force; throw "Installed app did not complete startup check" }
if ($Process.ExitCode -ne 0 -or -not (Test-Path "$Evidence/startup-ok.txt") -or (Test-Path "$Evidence/startup-error.txt")) {
    throw "Installed app startup failed; inspect evidence"
}
if (-not (Test-Path "$Evidence/installed-app.png")) { throw "Installed app screenshot is missing" }
if (-not (Test-Path "$Evidence/installed-native-checks.txt")) { throw "Installed native resource checks are missing" }
$NativeChecks = Get-Content "$Evidence/installed-native-checks.txt" -Raw
if ($NativeChecks -notmatch 'native_dll_load=ok' -or $NativeChecks -notmatch 'speaker_worker_self_test=ok' -or $NativeChecks -notmatch 'model_notices=ok' -or $NativeChecks -notmatch 'desktop_https=ok') {
    throw "Installed speech libraries or speaker worker checks did not pass"
}
Write-Host "Installed Windows app launched, opened its database, loaded its speech DLLs, verified its speaker worker, rendered UI, and closed successfully."
