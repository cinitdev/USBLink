param(
    [Parameter(Mandatory)][ValidatePattern('^[0-9.]+$')][string]$HostAddress,
    [Parameter(Mandatory)][ValidatePattern('^\d+-\d+(?:\.\d+)*$')][string]$BusId,
    [switch]$Reproduce,
    [ValidateRange(1,60)][int]$Seconds = 25
)
$ErrorActionPreference = 'Stop'
$taskUsbip = Join-Path $env:ProgramFiles 'USBip/usbip.exe'
$taskRoot = Join-Path $PSScriptRoot '../test-results'
New-Item -ItemType Directory -Path $taskRoot -Force | Out-Null
$taskTrace = Join-Path $taskRoot ('attach-trace-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.jsonl')
$taskStart = [DateTime]::UtcNow
function Record([string]$Kind, [object]$Data) {
    @{elapsedMs=[int]([DateTime]::UtcNow-$taskStart).TotalMilliseconds; kind=$Kind; data=$Data} | ConvertTo-Json -Depth 6 -Compress | Add-Content -LiteralPath $taskTrace -Encoding utf8
}
function Run-Usbip([string[]]$Arguments) {
    $taskPsi = [Diagnostics.ProcessStartInfo]::new($taskUsbip)
    $taskPsi.UseShellExecute=$false
    $taskPsi.CreateNoWindow=$true
    $taskPsi.RedirectStandardOutput=$true
    $taskPsi.RedirectStandardError=$true
    foreach ($argument in $Arguments) { [void]$taskPsi.ArgumentList.Add($argument) }
    $taskProcess=[Diagnostics.Process]::Start($taskPsi)
    $taskOut=$taskProcess.StandardOutput.ReadToEndAsync()
    $taskErr=$taskProcess.StandardError.ReadToEndAsync()
    if (-not $taskProcess.WaitForExit(30000)) {
        $taskProcess.Kill()
        Record 'timeout' $Arguments
        throw 'Component did not finish; no further command was submitted'
    }
    $taskResult=@{exitCode=$taskProcess.ExitCode;stdout=$taskOut.GetAwaiter().GetResult();stderr=$taskErr.GetAwaiter().GetResult()}
    $taskProcess.Dispose()
    Record ($Arguments -join ' ') $taskResult
    return $taskResult
}

$taskVersion=Run-Usbip @('--version')
if ($taskVersion.exitCode -ne 0 -or [version]$taskVersion.stdout.Trim() -lt [version]'0.9.8.0') { throw 'Client version has not passed the safety check' }
$taskImage=(Get-ItemProperty -LiteralPath 'HKLM:/SYSTEM/CurrentControlSet/Services/usbip2_ude').ImagePath
$taskImage=$taskImage -replace '^\\SystemRoot\\',($env:SystemRoot+'\')
$taskInf=Get-Content -LiteralPath (Join-Path ([IO.Path]::GetDirectoryName($taskImage)) 'usbip2_ude.inf') -Raw
$taskDriver=[regex]::Match($taskInf,'(?im)^\s*DriverVer\s*=\s*[^,]+,\s*([0-9.]+)\s*$')
if (-not $taskDriver.Success -or [version]$taskDriver.Groups[1].Value -le [version]'1.45.29.368') { throw 'Installed UDE driver has not passed the safety check' }
Record 'driverVersion' $taskDriver.Groups[1].Value
$taskBaseline=Run-Usbip @('port')
if ($taskBaseline.exitCode -ne 0) { throw 'Cannot read existing mounts' }
if ($Reproduce) {
    $taskPattern='(?ms)^Port\s+(\d+):(?:(?!^Port\s).)*?-> usbip://' + [regex]::Escape($HostAddress) + ':3240/' + [regex]::Escape($BusId) + '\s*$'
    $taskMatches=[regex]::Matches($taskBaseline.stdout,$taskPattern)
    if ($taskMatches.Count -gt 1) { throw 'Ambiguous device; no change made' }
    if ($taskMatches.Count -eq 1) {
        $taskPort=$taskMatches[0].Groups[1].Value
        $taskDetached=Run-Usbip @('detach','-p',$taskPort)
        if ($taskDetached.exitCode -ne 0) { throw 'Detach failed; no attach submitted' }
        $taskReleased=$false
        for ($taskIndex=0;$taskIndex -lt 20;$taskIndex++) {
            $taskSnapshot=Run-Usbip @('port')
            if ($taskSnapshot.exitCode -ne 0) { throw 'Unknown mount state; no attach submitted' }
            if (-not [regex]::IsMatch($taskSnapshot.stdout,$taskPattern)) { $taskReleased=$true; break }
            Start-Sleep -Milliseconds 250
        }
        if (-not $taskReleased) { throw 'Old mount remains; no attach submitted' }
    }
    # Exactly one requested diagnostic attach. Never retry, reconnect, bind, or unbind.
    $taskAttached=Run-Usbip @('attach','--once','--terse','-r',$HostAddress,'-b',$BusId)
    Write-Output ('Single attach exit code: ' + $taskAttached.exitCode + '; stdout: ' + $taskAttached.stdout.Trim() + '; stderr: ' + $taskAttached.stderr.Trim())
}
$taskDeadline=[DateTime]::UtcNow.AddSeconds($Seconds)
do {
    $taskSnapshot=Run-Usbip @('port')
    Start-Sleep -Milliseconds 250
} while ([DateTime]::UtcNow -lt $taskDeadline)
Write-Output ('Trace: ' + [IO.Path]::GetFullPath($taskTrace))
