param([switch]$SkipTests, [switch]$ExperimentalUsb, [string]$AndroidSdk = $env:ANDROID_HOME)
$ErrorActionPreference = 'Stop'
$MobileRoot = Split-Path $PSScriptRoot -Parent
$RepoRoot = Split-Path $MobileRoot -Parent
if (-not $AndroidSdk) { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$AndroidJar = Join-Path $AndroidSdk 'platforms/android-35/android.jar'
$D8 = Join-Path $AndroidSdk 'build-tools/35.0.0/d8.bat'
if (-not (Test-Path $AndroidJar) -or -not (Test-Path $D8)) { throw 'Install Android SDK platform 35 and build-tools 35.0.0, or supply -AndroidSdk.' }
$BuildRoot = Join-Path $MobileRoot $(if ($ExperimentalUsb) { 'build/usb-module' } else { 'build' })
$Classes = Join-Path $BuildRoot 'classes'
$TestClasses = Join-Path $BuildRoot 'test-classes'
$Dex = Join-Path $BuildRoot 'dex'
$Staging = Join-Path $BuildRoot 'module'
# Only generated directories under mobile/build are replaced.
foreach ($directory in @($Classes, $TestClasses, $Dex, $Staging)) {
  $FullTarget = [IO.Path]::GetFullPath($directory)
  if (-not $FullTarget.StartsWith([IO.Path]::GetFullPath($BuildRoot) + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid build output path.' }
  if (Test-Path -LiteralPath $FullTarget) { Remove-Item -LiteralPath $FullTarget -Recurse -Force }
  New-Item -ItemType Directory -Path $FullTarget -Force | Out-Null
}
function Invoke-Checked([string]$Program, [string[]]$Arguments) {
  $Lines = & $Program @Arguments 2>&1
  $Result = $LASTEXITCODE
  $Lines | ForEach-Object { Write-Output $_ }
  if ($Result -ne 0 -or ($Lines -join "`n") -match '(?m)(^|\s)(error:|Exception in thread|java\.nio\.file\.AccessDeniedException)') { throw "Build command failed: $Program" }
}
$Sources = @(Get-ChildItem (Join-Path $MobileRoot 'daemon/src') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
if ($ExperimentalUsb) {
  $Sources = @($Sources | Where-Object { [IO.Path]::GetFileName($_) -ne 'TransportFactory.java' })
  $Sources += @(Get-ChildItem (Join-Path $MobileRoot 'experimental/usbip/src'), (Join-Path $MobileRoot 'experimental/usbip/module') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
}
if (-not $SkipTests) {
  $HostSources = @($Sources | Where-Object { [IO.Path]::GetFileName($_) -notin @('Main.java', 'AndroidPlatform.java') })
  $Tests = @(Get-ChildItem (Join-Path $MobileRoot 'daemon/test') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
  if ($ExperimentalUsb) { $Tests += @(Get-ChildItem (Join-Path $MobileRoot 'experimental/usbip/test'), (Join-Path $MobileRoot 'experimental/usbip/module-test') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }) }
  Invoke-Checked 'javac' (@('--release', '11', '-encoding', 'UTF-8', '-d', $TestClasses) + $HostSources + $Tests)
  Invoke-Checked 'java' @('-cp', $TestClasses, 'io.usblink.mobile.TestMain')
  Invoke-Checked 'java' @('-cp', $TestClasses, 'io.usblink.mobile.MeshTestMain')
  Invoke-Checked 'java' @('-cp', $TestClasses, 'io.usblink.mobile.DeviceNamesTestMain')
  Invoke-Checked 'java' @('-cp', $TestClasses, 'io.usblink.mobile.PresenceTestMain')
  Invoke-Checked 'java' @('-cp', $TestClasses, 'io.usblink.mobile.TcpAdbTestMain')
  Invoke-Checked 'java' @('-cp', $TestClasses, 'io.usblink.mobile.SharingStartupTestMain')
  if ($ExperimentalUsb) {
    Invoke-Checked 'java' @('-cp', $TestClasses, 'io.usblink.mobile.usbip.ProtocolTest')
    Invoke-Checked 'java' @('-cp', $TestClasses, 'io.usblink.mobile.UsbModuleTest')
  }
  $WebTests = @(Get-ChildItem (Join-Path $MobileRoot 'webui/tests') -Filter '*.test.mjs' | ForEach-Object { $_.FullName })
  Invoke-Checked 'node' (@('--test') + $WebTests)
}
Invoke-Checked 'javac' (@('--release', '11', '-encoding', 'UTF-8', '-classpath', $AndroidJar, '-d', $Classes) + $Sources)
$ClassJar = Join-Path $BuildRoot 'classes.jar'
Invoke-Checked 'jar' @('--create', '--file', $ClassJar, '-C', $Classes, '.')
Invoke-Checked $D8 @('--release', '--min-api', '31', '--lib', $AndroidJar, '--output', $Dex, $ClassJar)
$DexJar = Join-Path $BuildRoot 'usblink-daemon.jar'
Invoke-Checked 'jar' @('--create', '--file', $DexJar, '-C', $Dex, '.')
Copy-Item (Join-Path $MobileRoot 'ksu/*') $Staging -Recurse -Force
if ($ExperimentalUsb) {
  Copy-Item (Join-Path $MobileRoot 'experimental/usbip/module.prop') (Join-Path $Staging 'module.prop')
  Copy-Item (Join-Path $MobileRoot 'experimental/usbip/MODULE.md') (Join-Path $Staging 'EXPERIMENTAL.md')
}
New-Item -ItemType Directory (Join-Path $Staging 'lib') -Force | Out-Null
Copy-Item $DexJar (Join-Path $Staging 'lib/usblink-daemon.jar')
$Manifest = Get-Content (Join-Path $MobileRoot 'vendor/easytier/manifest.json') -Raw | ConvertFrom-Json
foreach ($Asset in $Manifest.files) {
  $File = Join-Path $MobileRoot ('vendor/easytier/bin/' + $Asset.name)
  if (-not (Test-Path $File)) { throw 'EasyTier assets are missing. Run mobile/scripts/fetch-easytier.ps1 first.' }
  if ((Get-FileHash $File -Algorithm SHA256).Hash -ne $Asset.sha256) { throw "EasyTier asset hash mismatch: $($Asset.name)" }
  Copy-Item $File (Join-Path $Staging ('bin/' + $Asset.name))
}
New-Item -ItemType Directory (Join-Path $Staging 'licenses') -Force | Out-Null
Copy-Item (Join-Path $MobileRoot 'vendor/easytier/LICENSE*') (Join-Path $Staging 'licenses')
Copy-Item (Join-Path $MobileRoot 'vendor/easytier/NOTICE.md') (Join-Path $Staging 'licenses/EasyTier-NOTICE.md')
Copy-Item (Join-Path $MobileRoot 'vendor/easytier/manifest.json') (Join-Path $Staging 'licenses/EasyTier-manifest.json')
Copy-Item (Join-Path $MobileRoot 'README.md') (Join-Path $Staging 'README.md')
if ($ExperimentalUsb) { Copy-Item (Join-Path $MobileRoot 'experimental/usbip/MODULE.md') (Join-Path $Staging 'README.md') }
Invoke-Checked 'node' @((Join-Path $RepoRoot 'node_modules/vite/bin/vite.js'), 'build', '--config', (Join-Path $MobileRoot 'webui/vite.config.mjs'))
if (-not (Test-Path (Join-Path $MobileRoot 'webui/dist/index.html'))) { throw 'WebUI build is missing.' }
New-Item -ItemType Directory (Join-Path $Staging 'webroot') -Force | Out-Null
Copy-Item (Join-Path $MobileRoot 'webui/dist/*') (Join-Path $Staging 'webroot') -Recurse -Force
# Android init/manager scripts must use LF, regardless of Windows checkout settings.
$Utf8NoBom = New-Object Text.UTF8Encoding($false)
Get-ChildItem $Staging -Recurse -File | Where-Object { $_.Extension -in @('.sh', '.prop') -or $_.Name -eq 'usblinkctl' } | ForEach-Object {
  [IO.File]::WriteAllText($_.FullName, ([IO.File]::ReadAllText($_.FullName) -replace "`r`n", "`n"), $Utf8NoBom)
}
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$ReleaseRoot = Join-Path $RepoRoot 'release'
New-Item -ItemType Directory $ReleaseRoot -Force | Out-Null
$ModuleVersion = [regex]::Match([IO.File]::ReadAllText((Join-Path $Staging 'module.prop')), '(?m)^version=(\d+\.\d+\.\d+)\r?$').Groups[1].Value
if ($ModuleVersion -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid module version.' }
$Suffix = if ($ExperimentalUsb) { '-usb-adb-experimental' } else { '' }
$ZipPath = Join-Path $ReleaseRoot ("USBLink-Mobile-$ModuleVersion$Suffix-arm64.zip")
$OutStream = [IO.File]::Open($ZipPath, [IO.FileMode]::Create)
$Archive = New-Object IO.Compression.ZipArchive($OutStream, [IO.Compression.ZipArchiveMode]::Create, $false)
try {
  Get-ChildItem $Staging -Recurse -File | Sort-Object FullName | ForEach-Object {
    $Relative = $_.FullName.Substring($Staging.Length + 1).Replace('\', '/')
    $Entry = $Archive.CreateEntry($Relative, [IO.Compression.CompressionLevel]::Optimal)
    $Entry.LastWriteTime = [DateTimeOffset]'2026-10-05T00:00:00Z'
    $InputFile = [IO.File]::OpenRead($_.FullName)
    $OutputFile = $Entry.Open()
    try { $InputFile.CopyTo($OutputFile) } finally { $InputFile.Dispose(); $OutputFile.Dispose() }
  }
} finally { $Archive.Dispose(); $OutStream.Dispose() }
Get-Item $ZipPath | Select-Object FullName, Length
$Checksum = (Get-FileHash $ZipPath -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText($ZipPath + '.sha256', $Checksum + '  ' + [IO.Path]::GetFileName($ZipPath) + "`n", $Utf8NoBom)
Write-Output "SHA256: $Checksum"
