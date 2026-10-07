param([switch]$Offline)
$ErrorActionPreference = 'Stop'
$MobileRoot = Split-Path $PSScriptRoot -Parent
$Manifest = Get-Content (Join-Path $MobileRoot 'vendor/easytier/manifest.json') -Raw | ConvertFrom-Json
$DownloadDir = Join-Path $MobileRoot 'vendor/downloads'
$BinDir = Join-Path $MobileRoot 'vendor/easytier/bin'
New-Item -ItemType Directory $DownloadDir, $BinDir -Force | Out-Null
$ZipPath = Join-Path $DownloadDir $Manifest.archive
if (-not (Test-Path $ZipPath)) {
  if ($Offline) { throw 'Official EasyTier archive is missing from mobile/vendor/downloads.' }
  Invoke-WebRequest -Uri $Manifest.url -OutFile ($ZipPath + '.partial')
  Move-Item -LiteralPath ($ZipPath + '.partial') -Destination $ZipPath
}
if ((Get-Item $ZipPath).Length -ne $Manifest.archiveSize -or (Get-FileHash $ZipPath -Algorithm SHA256).Hash -ne $Manifest.archiveSha256) {
  throw 'EasyTier archive verification failed; no binaries were installed.'
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$Archive = [IO.Compression.ZipFile]::OpenRead($ZipPath)
try {
  foreach ($Asset in $Manifest.files) {
    $Entry = $Archive.GetEntry($Asset.name)
    if (-not $Entry -or $Entry.Length -ne $Asset.size) { throw "Invalid EasyTier archive entry: $($Asset.name)" }
    $Destination = Join-Path $BinDir $Asset.name
    $Temporary = $Destination + '.partial'
    $InputStream = $Entry.Open()
    $OutputStream = [IO.File]::Open($Temporary, [IO.FileMode]::Create)
    try { $InputStream.CopyTo($OutputStream) } finally { $InputStream.Dispose(); $OutputStream.Dispose() }
    if ((Get-FileHash $Temporary -Algorithm SHA256).Hash -ne $Asset.sha256) { throw "EasyTier binary verification failed: $($Asset.name)" }
    Move-Item -LiteralPath $Temporary -Destination $Destination -Force
    Write-Output "Verified official $($Asset.name) $($Manifest.version)"
  }
} finally { $Archive.Dispose() }
