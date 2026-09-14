param([ValidateSet('Prepare','Baseline','Hold','Release','AssertBaseline','AssertUpdated','AssertKilled','SilentBlocked','Reinstall','Cleanup')][string]$Action = 'Prepare', [switch]$LockOnly)
$ErrorActionPreference = 'Stop'
$taskProject = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskProduct = 'USBLinkInstallerQA'
$taskVersion = (Get-Content (Join-Path $taskProject 'package.json') -Raw | ConvertFrom-Json).version
$taskRoot = [IO.Path]::GetFullPath((Join-Path $taskProject "test-results/installer-flow-$taskVersion"))
$taskInstall = Join-Path $taskRoot 'install space'
$taskProfile = Join-Path $env:LOCALAPPDATA $taskProduct
$taskUninstallKey = "Software\Microsoft\Windows\CurrentVersion\Uninstall\$taskProduct"
$taskProductKey = "Software\CinitDev\$taskProduct"
$taskRunKey = 'Software\Microsoft\Windows\CurrentVersion\Run'
$taskRegistry = [Microsoft.Win32.RegistryKey]::OpenBaseKey([Microsoft.Win32.RegistryHive]::CurrentUser, [Microsoft.Win32.RegistryView]::Registry64)
$taskCompiler = Join-Path $env:LOCALAPPDATA 'tauri/NSIS/makensis.exe'
$taskGenerated = Join-Path $taskProject 'src-tauri/target/release/nsis/x64'
$taskExe = Join-Path $taskInstall "$taskProduct.exe"
$taskSentinel = Join-Path $taskInstall 'preserved-user-file.txt'
$taskManifest = Join-Path $taskRoot 'fixture.json'

function Assert-WorkspacePath([string]$Path) {
    $resolved = [IO.Path]::GetFullPath($Path)
    if (-not $resolved.StartsWith($taskRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Fixture path escaped test-results/installer-flow' }
}
function Run-Installer([string]$Path, [string]$Arguments) {
    Assert-WorkspacePath $Path
    $process = Start-Process -FilePath $Path -ArgumentList $Arguments -WindowStyle Hidden -PassThru
    if (-not $process.WaitForExit(30000)) { throw 'Fixture did not finish in 30 seconds; inspect it before continuing' }
    $process.Refresh()
    return $process.ExitCode
}
function Assert-Registration([string]$ExpectedVersion) {
    $entry = $taskRegistry.OpenSubKey($taskUninstallKey)
    try {
        if (-not $entry -or $entry.GetValue('DisplayVersion') -ne $ExpectedVersion) { throw 'Fixture registered version mismatch' }
        if ($entry.GetValue('InstallLocation').Trim('"') -ne $taskInstall) { throw 'Fixture registered path mismatch' }
    } finally { if ($entry) { $entry.Dispose() } }
}
function Assert-Preserved {
    if ([IO.File]::ReadAllText($taskSentinel) -ne 'Keep this file across upgrades') { throw 'Install directory user file changed' }
    if ([IO.File]::ReadAllText((Join-Path $taskProfile 'profile-sentinel.txt')) -ne 'Dummy pairing/settings data') { throw 'Profile data changed' }
}

try {
    if ($Action -eq 'Prepare') {
        if ((Test-Path $taskRoot) -or (Test-Path $taskProfile)) { throw 'Fixture directories already exist; do not overwrite an earlier test' }
        foreach ($key in @($taskUninstallKey,$taskProductKey)) {
            $existing = $taskRegistry.OpenSubKey($key)
            if ($existing) { $existing.Dispose(); throw 'Fixture registration already exists' }
        }
        $run=$taskRegistry.OpenSubKey($taskRunKey)
        if ($run) { $hadRun=$run.GetValueNames() -contains $taskProduct;$run.Dispose();if ($hadRun) { throw 'Fixture auto-start entry already exists' } }
        $shortcuts = @((Join-Path ([Environment]::GetFolderPath('Programs')) "$taskProduct.lnk"),(Join-Path ([Environment]::GetFolderPath('DesktopDirectory')) "$taskProduct.lnk"))
        foreach ($shortcut in $shortcuts) { if (Test-Path $shortcut) { throw 'Fixture shortcut already exists' } }
        New-Item -ItemType Directory -Path $taskRoot,(Join-Path $taskRoot 'payload'),(Join-Path $taskRoot 'baseline-payload'),(Join-Path $taskRoot 'holder') | Out-Null
        $payload = Join-Path $taskRoot "payload/$taskProduct.exe"
        Copy-Item -LiteralPath (Join-Path $taskProject 'src-tauri/target/release/USBLink.exe') -Destination $payload
        $baselinePayload = Join-Path $taskRoot "baseline-payload/$taskProduct.exe"
        [IO.File]::WriteAllBytes($baselinePayload, ([IO.File]::ReadAllBytes($payload) + [Text.Encoding]::UTF8.GetBytes('fixture baseline overlay')))
        $source = [IO.File]::ReadAllText((Join-Path $taskGenerated 'installer.nsi'))
        # A fixture must never launch the real payload under a renamed filename.
        $source = $source.Replace('!define MUI_FINISHPAGE_RUN' + "`r`n",'').Replace('!define MUI_FINISHPAGE_RUN' + "`n",'')
        # Prove an update never invokes the previous uninstaller, even silently.
        $source = ('!macro NSIS_HOOK_POSTUNINSTALL' + "`n" + '  FileOpen $0 "' + (Join-Path $taskRoot 'uninstalled.signal') + '" w' + "`n" + '  FileClose $0' + "`n!macroend`n") + $source
        $source = $source.Replace('!define PRODUCTNAME "USBLink"', '!define PRODUCTNAME "USBLinkInstallerQA"').Replace('!define MAINBINARYNAME "USBLink"','!define MAINBINARYNAME "USBLinkInstallerQA"').Replace('!define BUNDLEID "dev.cinit.usblink"','!define BUNDLEID "dev.cinit.usblink.installer-qa"')
        foreach ($spec in @(@('baseline','0.2.17',$baselinePayload),@('update',$taskVersion,$payload))) {
            $name,$version,$binary = $spec
            $script = $source -replace '(?m)^!define MAINBINARYSRCPATH .*$', ('!define MAINBINARYSRCPATH "' + $binary + '"')
            $script = $script -replace '(?m)^!define VERSION .*$', ('!define VERSION "' + $version + '"')
            $script = $script -replace '(?m)^!define VERSIONWITHBUILD .*$', ('!define VERSIONWITHBUILD "' + $version + '.0"')
            $script = $script -replace '(?m)^!define OUTFILE .*$', ('!define OUTFILE "' + (Join-Path $taskRoot "$name-setup.exe") + '"')
            $script = $script.Replace('!include "utils.nsh"', ('!include "' + (Join-Path $taskGenerated 'utils.nsh') + '"')).Replace('!include "FileAssociation.nsh"', ('!include "' + (Join-Path $taskGenerated 'FileAssociation.nsh') + '"'))
            $path = Join-Path $taskRoot "$name.nsi"
            [IO.File]::WriteAllText($path,$script,[Text.UTF8Encoding]::new($true))
            & $taskCompiler /V2 $path
            if ($LASTEXITCODE -ne 0) { throw 'Fixture installer compilation failed' }
        }
        # A harmless process holds the same kind of exclusive file lock as USBLink.
        # It exits normally when Release writes a signal; it never loads USB drivers.
        $holder = @'
Unicode true
RequestExecutionLevel user
SilentInstall silent
OutFile "USBLinkInstallerQA.exe"
Section
  CreateDirectory "$LOCALAPPDATA\USBLinkInstallerQA"
  System::Call 'kernel32::CreateFileW(w "$LOCALAPPDATA\USBLinkInstallerQA\sharing-session.lock", i 0xC0000000, i 0, p 0, i 4, i 0, p 0) p.r0'
  IntCmp $0 -1 failed
  FileOpen $1 "$EXEDIR\held.signal" w
  FileClose $1
  wait:
    IfFileExists "$EXEDIR\release.signal" done
    Sleep 100
    Goto wait
  done:
    System::Call 'kernel32::CloseHandle(p r0)'
    SetErrorLevel 0
    Quit
  failed:
    SetErrorLevel 2
    Quit
SectionEnd
'@
        $holderPath = Join-Path $taskRoot 'holder/holder.nsi'
        [IO.File]::WriteAllText($holderPath,$holder)
        & $taskCompiler /V2 $holderPath
        if ($LASTEXITCODE -ne 0) { throw 'Lock holder compilation failed' }
        @{ root=$taskRoot; install=$taskInstall; profile=$taskProfile; version=$taskVersion; expectedHash=(Get-FileHash $payload).Hash; baselineHash=(Get-FileHash $baselinePayload).Hash; shortcuts=$shortcuts } | ConvertTo-Json | Set-Content $taskManifest -Encoding UTF8
        Get-Content $taskManifest
        return
    }
    if (-not (Test-Path $taskManifest)) { throw 'Prepare the isolated fixture first' }
    $fixture = Get-Content $taskManifest -Raw | ConvertFrom-Json
    switch ($Action) {
        'Baseline' {
            if (Test-Path $taskInstall) { throw 'Baseline install already exists' }
            if ((Run-Installer (Join-Path $taskRoot 'baseline-setup.exe') "/S /D=$taskInstall") -ne 0) { throw 'Baseline installation failed' }
            Assert-Registration '0.2.17'
            [IO.File]::WriteAllText($taskSentinel,'Keep this file across upgrades')
            New-Item -ItemType Directory -Path $taskProfile | Out-Null
            [IO.File]::WriteAllText((Join-Path $taskProfile 'profile-sentinel.txt'),'Dummy pairing/settings data')
            $run = $taskRegistry.CreateSubKey($taskRunKey)
            $run.SetValue($taskProduct,'"old-portable-path.exe"');$run.Dispose()
            'PASS: baseline installed in isolated directory'
        }
        'Hold' {
            foreach ($signal in @('release.signal','held.signal')) { $path=Join-Path $taskRoot "holder/$signal"; if (Test-Path $path) { Remove-Item -LiteralPath $path } }
            $path = Join-Path $taskRoot "holder/$taskProduct.exe"
            if ($LockOnly) { $copy=Join-Path $taskRoot 'holder/SessionHolder.exe';Copy-Item -LiteralPath $path -Destination $copy;$path=$copy }
            $process = Start-Process -FilePath $path -WindowStyle Hidden -PassThru
            $process.Id | Set-Content (Join-Path $taskRoot 'holder/pid.txt')
            $deadline = [DateTime]::UtcNow.AddSeconds(5)
            while (-not (Test-Path (Join-Path $taskRoot 'holder/held.signal'))) { if ($process.HasExited -or [DateTime]::UtcNow -gt $deadline) { throw 'Holder failed' };Start-Sleep -Milliseconds 100 }
            "PASS: holder $($process.Id) owns exclusive session lock"
        }
        'Release' {
            [IO.File]::WriteAllText((Join-Path $taskRoot 'holder/release.signal'),'exit')
            $process = Get-Process -Id ([int](Get-Content (Join-Path $taskRoot 'holder/pid.txt'))) -ErrorAction SilentlyContinue
            if ($process -and -not $process.WaitForExit(5000)) { throw 'Holder failed to exit normally' }
            'PASS: fixture process closed normally'
        }
        'AssertBaseline' {
            Assert-Registration '0.2.17';Assert-Preserved
            if ((Get-FileHash $taskExe).Hash -ne $fixture.baselineHash) { throw 'Cancellation modified the old executable' }
            'PASS: cancellation preserved the old installation and data'
        }
        'AssertUpdated' {
            Assert-Registration $taskVersion;Assert-Preserved
            if (Test-Path (Join-Path $taskRoot 'uninstalled.signal')) { throw 'In-place update invoked the uninstaller' }
            if ((Get-FileHash $taskExe).Hash -ne $fixture.expectedHash) { throw 'Update did not replace the executable' }
            $shell = New-Object -ComObject WScript.Shell
            foreach ($shortcut in $fixture.shortcuts) { if ($shell.CreateShortcut($shortcut).TargetPath -ne $taskExe) { throw 'Shortcut changed or missing' } }
            $run = $taskRegistry.OpenSubKey($taskRunKey)
            try { if ($run.GetValue($taskProduct) -ne ('"'+$taskExe+'"')) { throw 'Auto-start was not migrated' } } finally { $run.Dispose() }
            'PASS: updated binary, version, existing directory, profile, user file, shortcuts and auto-start'
        }
        'AssertKilled' {
            $taskHolder = Get-Process -Id ([int](Get-Content (Join-Path $taskRoot 'holder/pid.txt'))) -ErrorAction SilentlyContinue
            if ($taskHolder -and $taskHolder.ProcessName -eq $taskProduct) { throw 'Fixture process is still running' }
            $taskLockPath = Join-Path $taskProfile 'sharing-session.lock'
            $taskLock = [IO.File]::Open($taskLockPath,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
            $taskLock.Dispose()
            'PASS: user confirmation ended the fixture process and released its session lock'
        }
        'SilentBlocked' {
            $exitCode = Run-Installer (Join-Path $taskRoot 'update-setup.exe') "/S /D=$taskInstall"
            if ($exitCode -ne 1618) { throw "Expected 1618, got $exitCode" }
            if (-not (Get-Process -Id ([int](Get-Content (Join-Path $taskRoot 'holder/pid.txt'))) -ErrorAction SilentlyContinue)) { throw 'Installer killed the fixture process' }
            'PASS: unattended installer fails with 1618 and leaves running process intact'
        }
        'Reinstall' {
            if ((Run-Installer (Join-Path $taskRoot 'update-setup.exe') "/S /D=$taskInstall") -ne 0) { throw 'Same-version reinstall failed' }
            Assert-Registration $taskVersion;Assert-Preserved
            'PASS: same-version silent reinstall preserves user data'
        }
        'Cleanup' {
            if (Test-Path $taskExe) {
                Assert-Registration $taskVersion
                if ((Run-Installer (Join-Path $taskInstall 'uninstall.exe') '/S') -ne 0) { throw 'Test uninstall failed' }
            }
            # NSIS copies its uninstaller to a temporary child process; the launcher
            # can exit before the child finishes deleting files and registration.
            $deadline = [DateTime]::UtcNow.AddSeconds(15)
            do {
                $entry=$taskRegistry.OpenSubKey($taskUninstallKey)
                $stillRegistered=$null -ne $entry
                if ($entry) { $entry.Dispose() }
                $pending=(Test-Path $taskExe) -or $stillRegistered -or (Test-Path (Join-Path $taskInstall 'uninstall.exe'))
                if ($pending) { Start-Sleep -Milliseconds 100 }
            } while ($pending -and [DateTime]::UtcNow -lt $deadline)
            if ($pending) { throw 'Test uninstaller did not finish within 15 seconds' }
            if (Test-Path $taskExe) { throw 'Test binary was not removed' }
            Assert-Preserved
            $entry=$taskRegistry.OpenSubKey($taskUninstallKey);if ($entry) { $entry.Dispose();throw 'Uninstall registration remains' }
            foreach ($shortcut in $fixture.shortcuts) { if (Test-Path $shortcut) { throw 'Uninstall left a fixture shortcut' } }
            foreach ($path in @($taskSentinel,(Join-Path $taskProfile 'profile-sentinel.txt'),(Join-Path $taskProfile 'sharing-session.lock'))) { if (Test-Path $path) { Remove-Item -LiteralPath $path } }
            [IO.Directory]::Delete($taskProfile,$false)
            [IO.Directory]::Delete($taskInstall,$false)
            $product=$taskRegistry.OpenSubKey($taskProductKey,$true)
            if ($product) { $product.DeleteValue('',$false);$product.DeleteValue('Installer Language',$false);$empty=$product.ValueCount -eq 0 -and $product.SubKeyCount -eq 0;$product.Dispose();if ($empty) { $taskRegistry.DeleteSubKey($taskProductKey,$false) } }
            'PASS: test uninstalled; only fixture data removed, build artifacts retained'
        }
    }
} finally { $taskRegistry.Dispose() }
