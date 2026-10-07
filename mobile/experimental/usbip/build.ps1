param([string]$AndroidSdk = "$env:LOCALAPPDATA/Android/Sdk")
$ErrorActionPreference = 'Stop'
$MobileRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$Output = Join-Path $MobileRoot 'build/usbip-probe'
$Classes = Join-Path $Output 'classes'
$Dex = Join-Path $Output 'dex'
$PhoneClasses = Join-Path $Output 'phone-classes'
foreach ($Generated in @($Classes,$Dex,$PhoneClasses)) {
    $Resolved = [IO.Path]::GetFullPath($Generated)
    if (-not $Resolved.StartsWith([IO.Path]::GetFullPath($Output) + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe output path' }
    if (Test-Path -LiteralPath $Resolved) { Remove-Item -LiteralPath $Resolved -Recurse -Force }
}
New-Item -ItemType Directory -Force $Classes,$Dex | Out-Null
$AndroidJar = Join-Path $AndroidSdk 'platforms/android-35/android.jar'
function Checked([string]$Program, [string[]]$Arguments) {
    $Lines = & $Program @Arguments 2>&1
    $Code = $LASTEXITCODE
    $Lines | ForEach-Object { Write-Output $_ }
    if ($Code -ne 0 -or ($Lines -join "`n") -match 'error:|Exception in thread|java\.nio\.file\.AccessDeniedException') { throw "$Program failed ($Code)" }
}
$Core = @(Get-ChildItem (Join-Path $PSScriptRoot 'src') -Recurse -Filter '*.java' | ForEach-Object FullName)
$Tests = @(Get-ChildItem (Join-Path $PSScriptRoot 'test') -Recurse -Filter '*.java' | ForEach-Object FullName)
Checked 'javac' (@('--release','11','-encoding','UTF-8','-d',$Classes) + $Core + $Tests)
Checked 'java' @('-cp',$Classes,'io.usblink.mobile.usbip.ProtocolTest')
$Android = @(Get-ChildItem (Join-Path $PSScriptRoot 'android') -Recurse -Filter '*.java' | ForEach-Object FullName)
$Existing = @(Get-ChildItem (Join-Path $MobileRoot 'daemon/src') -Recurse -Filter '*.java' | ForEach-Object FullName)
New-Item -ItemType Directory -Force $PhoneClasses | Out-Null
Checked 'javac' (@('--release','11','-encoding','UTF-8','-classpath',$AndroidJar,'-d',$PhoneClasses) + $Core + $Android + $Existing)
$ClassJar = Join-Path $Output 'probe-classes.jar'
Checked 'jar' @('--create','--file',$ClassJar,'-C',$PhoneClasses,'.')
Checked (Join-Path $AndroidSdk 'build-tools/35.0.0/d8.bat') @('--release','--min-api','31','--lib',$AndroidJar,'--output',$Dex,$ClassJar)
$Jar = Join-Path $Output 'usblink-usb-probe.jar'
Checked 'jar' @('--create','--file',$Jar,'-C',$Dex,'.')
Get-FileHash $Jar -Algorithm SHA256
