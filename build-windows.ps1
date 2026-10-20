$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$root = $PSScriptRoot.Replace('\','/')
New-Item -ItemType Directory -Force dist,release | Out-Null
Invoke-WebRequest 'https://github.com/aria2/aria2/releases/download/release-1.37.0/aria2-1.37.0-win-64bit-build1.zip' -OutFile aria2.zip
if ((Get-FileHash aria2.zip -Algorithm SHA256).Hash -ne '67D015301EEF0B612191212D564C5BB0A14B5B9C4796B76454276A4D28D9B288') { throw 'Engine archive checksum mismatch' }
Expand-Archive aria2.zip aria-upstream -Force
$engineFolder = Get-ChildItem aria-upstream -Directory | Select-Object -First 1
Copy-Item $engineFolder.FullName dist/Engine -Recurse -Force
$csc = "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
& $csc /nologo /codepage:65001 /optimize+ /target:winexe /out:dist/AWRDownloadNext.exe /r:System.Windows.Forms.dll /r:System.Drawing.dll /r:System.Web.Extensions.dll /r:System.Net.Http.dll /r:System.Security.dll Core.cs UI.cs
if ($LASTEXITCODE -ne 0) { throw 'UI compile failed' }
$utf8 = [System.Text.UTF8Encoding]::new($false)
$test = [IO.File]::ReadAllText("$PSScriptRoot/Tests.cs").Replace('/vercel/awr',$root).Replace('/usr/bin/aria2c',"$root/dist/Engine/aria2c.exe")
[IO.File]::WriteAllText("$PSScriptRoot/Tests.Windows.cs",$test,$utf8)
$fixture = [IO.File]::ReadAllText("$PSScriptRoot/fixture.py").Replace('/vercel/awr',$root)
[IO.File]::WriteAllText("$PSScriptRoot/fixture.windows.py",$fixture,$utf8)
& $csc /nologo /codepage:65001 /optimize+ /out:Tests.exe /r:System.Web.Extensions.dll /r:System.Net.Http.dll /r:System.Security.dll Core.cs Tests.Windows.cs
if ($LASTEXITCODE -ne 0) { throw 'Tests compile failed' }
$server = Start-Process python -ArgumentList 'fixture.windows.py' -PassThru -RedirectStandardError fixture-error.log
try {
  for ($i=0;$i -lt 30;$i++) {
    if (Test-Path test-payload.sha256) { break }
    Start-Sleep -Milliseconds 100
  }
  & ./Tests.exe | Tee-Object release/WINDOWS-TEST-REPORT.txt
  if ($LASTEXITCODE -ne 0) { throw 'Integration tests failed' }
  $env:AWR_TEST_ENGINE = "$root/dist/Engine/aria2c.exe"
  $env:AWR_TEST_DATA = "$root/ui-state"
  $env:AWR_TEST_SCREENSHOT = "$root/ui-windows.png"
  $ui = Start-Process "$PSScriptRoot/dist/AWRDownloadNext.exe" -ArgumentList '--smoke' -PassThru
  if (-not $ui.WaitForExit(40000)) { $ui.Kill(); throw 'Windows UI smoke test timed out' }
  if ($ui.ExitCode -ne 0 -or -not (Test-Path ui-windows.png)) { throw 'Windows UI smoke test failed' }
  'PASS: Actual Windows UI startup, aria2 RPC connection, rendering and clean shutdown.' | Add-Content release/WINDOWS-TEST-REPORT.txt
} finally { if (-not $server.HasExited) { Stop-Process -Id $server.Id -Force } }
Copy-Item README.md dist/README.md
Compress-Archive dist/* release/AWR-Download-Next-Windows-Build.zip -Force
