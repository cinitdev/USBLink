$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskVersion = (Get-Content -LiteralPath (Join-Path $taskRoot 'package.json') -Raw | ConvertFrom-Json).version
$taskInstaller = Join-Path $taskRoot "src-tauri/target/release/bundle/nsis/USBLink_${taskVersion}_x64-setup.exe"
$taskBinary = Join-Path $taskRoot 'src-tauri/target/release/USBLink.exe'
$taskInstallDir = [IO.Path]::GetFullPath((Join-Path $taskRoot 'test-results/installer-smoke/install space'))
if (-not $taskInstallDir.StartsWith($taskRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Test path is outside the workspace' }
if (Test-Path -LiteralPath $taskInstallDir) { throw 'Test install directory already exists; refusing to overwrite it' }
$taskUninstallKey = 'Software\Microsoft\Windows\CurrentVersion\Uninstall\USBLink'
$taskProductKey = 'Software\CinitDev\USBLink'
$taskRunKey = 'Software\Microsoft\Windows\CurrentVersion\Run'
$taskRegistry = [Microsoft.Win32.RegistryKey]::OpenBaseKey([Microsoft.Win32.RegistryHive]::CurrentUser, [Microsoft.Win32.RegistryView]::Registry64)
foreach ($taskKey in @($taskUninstallKey, $taskProductKey)) {
    $taskExisting = $taskRegistry.OpenSubKey($taskKey)
    if ($taskExisting) { $taskExisting.Dispose(); throw "An existing USBLink installation key must be preserved: $taskKey" }
}
if (Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.ProcessName -like 'USBLink*' }) { throw 'Close USBLink before running the installer test' }
$taskShortcuts = @(
    (Join-Path ([Environment]::GetFolderPath('Programs')) 'USBLink.lnk'),
    (Join-Path ([Environment]::GetFolderPath('DesktopDirectory')) 'USBLink.lnk')
)
foreach ($taskShortcut in $taskShortcuts) { if (Test-Path -LiteralPath $taskShortcut) { throw "Existing shortcut must be preserved: $taskShortcut" } }
$taskRun = $taskRegistry.OpenSubKey($taskRunKey)
$taskHadRun = $taskRun -and ($taskRun.GetValueNames() -contains 'USBLink')
if ($taskHadRun) {
    $taskOldRun = $taskRun.GetValue('USBLink', $null, [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
    $taskOldRunKind = $taskRun.GetValueKind('USBLink')
}
if ($taskRun) { $taskRun.Dispose() }
$taskExpectedHash = (Get-FileHash -LiteralPath $taskBinary -Algorithm SHA256).Hash
$taskInstalled = Join-Path $taskInstallDir 'USBLink.exe'
$taskUninstaller = Join-Path $taskInstallDir 'uninstall.exe'
$taskSentinel = Join-Path $taskInstallDir 'preserved-user-file.txt'
$taskReport = [ordered]@{ version = $taskVersion; install = $false; reinstall = $false; uninstall = $false; preservedData = $false; restoredAutoStart = $false }
try {
    # Silent installation does not launch the app unless /R is explicitly supplied.
    $taskProcess = Start-Process -FilePath $taskInstaller -ArgumentList "/S /D=$taskInstallDir" -WindowStyle Hidden -Wait -PassThru
    if ($taskProcess.ExitCode -ne 0) { throw "Installer exit code: $($taskProcess.ExitCode)" }
    if ((Get-FileHash -LiteralPath $taskInstalled -Algorithm SHA256).Hash -ne $taskExpectedHash) { throw 'Installed binary hash mismatch' }
    if (-not (Test-Path -LiteralPath $taskUninstaller)) { throw 'Uninstaller missing' }
    foreach ($taskResource in @('THIRD_PARTY_NOTICES.md', 'licenses/LICENSE-EasyTier.txt')) {
        if (-not (Test-Path -LiteralPath (Join-Path $taskInstallDir $taskResource))) { throw "Missing bundled notice: $taskResource" }
    }
    $taskEntry = $taskRegistry.OpenSubKey($taskUninstallKey)
    if (-not $taskEntry) { throw 'Windows uninstall registration missing' }
    try {
        if ($taskEntry.GetValue('DisplayVersion') -ne $taskVersion) { throw 'Registered version mismatch' }
        if ($taskEntry.GetValue('InstallLocation').Trim('"') -ne $taskInstallDir) { throw 'Registered install location mismatch' }
    } finally { $taskEntry.Dispose() }
    $taskShell = New-Object -ComObject WScript.Shell
    foreach ($taskShortcut in $taskShortcuts) {
        if (-not (Test-Path -LiteralPath $taskShortcut)) { throw "Shortcut missing: $taskShortcut" }
        if ($taskShell.CreateShortcut($taskShortcut).TargetPath -ne $taskInstalled) { throw 'Shortcut target mismatch' }
    }
    $taskReport.install = $true
    [IO.File]::WriteAllText($taskSentinel, 'Preserve user data when reinstalling or uninstalling')
    $taskProcess = Start-Process -FilePath $taskInstaller -ArgumentList "/S /D=$taskInstallDir" -WindowStyle Hidden -Wait -PassThru
    if ($taskProcess.ExitCode -ne 0) { throw 'Reinstallation failed' }
    if ((Get-FileHash -LiteralPath $taskInstalled -Algorithm SHA256).Hash -ne $taskExpectedHash) { throw 'Reinstalled binary hash mismatch' }
    if (-not (Test-Path -LiteralPath $taskSentinel)) { throw 'Reinstallation removed user data' }
    $taskReport.reinstall = $true
} finally {
    try {
        if (Test-Path -LiteralPath $taskUninstaller) {
            $taskEntry = $taskRegistry.OpenSubKey($taskUninstallKey)
            try {
                if (-not $taskEntry -or $taskEntry.GetValue('InstallLocation').Trim('"') -ne $taskInstallDir) { throw 'Refusing to uninstall an unrelated location' }
            } finally { if ($taskEntry) { $taskEntry.Dispose() } }
            $taskProcess = Start-Process -FilePath $taskUninstaller -ArgumentList '/S' -WindowStyle Hidden -Wait -PassThru
            if ($taskProcess.ExitCode -ne 0) { throw 'Test uninstallation failed' }
            if (Test-Path -LiteralPath $taskInstalled) { throw 'Uninstallation left the main binary' }
            foreach ($taskShortcut in $taskShortcuts) { if (Test-Path -LiteralPath $taskShortcut) { throw 'Uninstallation left a test shortcut' } }
            $taskEntry = $taskRegistry.OpenSubKey($taskUninstallKey)
            if ($taskEntry) { $taskEntry.Dispose(); throw 'Uninstallation left the uninstall registration' }
            $taskReport.uninstall = $true
            $taskReport.preservedData = Test-Path -LiteralPath $taskSentinel
            if (-not $taskReport.preservedData) { throw 'Uninstallation removed user data' }
            Remove-Item -LiteralPath $taskSentinel
            # Non-recursive: any unexpected file causes cleanup to stop rather than erase it.
            [IO.Directory]::Delete($taskInstallDir, $false)
            # NSIS remembers install location/language; remove only the two test-created values.
            $taskProduct = $taskRegistry.OpenSubKey($taskProductKey, $true)
            if ($taskProduct) {
                $taskProduct.DeleteValue('', $false)
                $taskProduct.DeleteValue('Installer Language', $false)
                $taskProductEmpty = $taskProduct.ValueCount -eq 0 -and $taskProduct.SubKeyCount -eq 0
                $taskProduct.Dispose()
                if ($taskProductEmpty) { $taskRegistry.DeleteSubKey($taskProductKey, $false) }
            }
            $taskManufacturer = $taskRegistry.OpenSubKey('Software\CinitDev')
            if ($taskManufacturer) {
                $taskManufacturerEmpty = $taskManufacturer.ValueCount -eq 0 -and $taskManufacturer.SubKeyCount -eq 0
                $taskManufacturer.Dispose()
                if ($taskManufacturerEmpty) { $taskRegistry.DeleteSubKey('Software\CinitDev', $false) }
            }
        }
    } finally {
        $taskRun = $taskRegistry.CreateSubKey($taskRunKey)
        if ($taskHadRun) { $taskRun.SetValue('USBLink', $taskOldRun, $taskOldRunKind) }
        else { $taskRun.DeleteValue('USBLink', $false) }
        $taskRun.Dispose()
        $taskReport.restoredAutoStart = $true
        $taskRegistry.Dispose()
        $taskReport | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskRoot 'test-results/installer-smoke/report.json') -Encoding UTF8
    }
}
$taskReport | ConvertTo-Json
