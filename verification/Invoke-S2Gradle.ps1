param([Parameter(Mandatory=$true)][string]$Name, [string[]]$GradleArguments = @('build'))
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$recordRoot = Join-Path $projectRoot '.verification\s2-20261106'
New-Item -ItemType Directory -Path $recordRoot -Force | Out-Null
if ($Name -notmatch '^[a-z0-9-]+$') { throw 'Invalid run name' }
foreach ($argument in $GradleArguments) { if ($argument -notmatch '^[a-zA-Z0-9_.*:=/-]+$') { throw 'Unsupported Gradle argument' } }
$logPath = Join-Path $recordRoot "$Name.log"
$errorPath = Join-Path $recordRoot "$Name-errors.log"
$recordPath = Join-Path $recordRoot "$Name-processes.json"
if (Test-Path -LiteralPath $logPath) { throw 'Run log already exists' }
$trackedIds = [Collections.Generic.HashSet[int]]::new()
$ownedJava = @{}
$buildExit = 1
$cleanupOkay = $false
$buildProcess = $null
$mappedDrive = $null
$remaining = @()
$startedAt = [DateTime]::UtcNow
$originalFailure = $null
$cleanupError = $null
try {
    # Verify process visibility before starting any Gradle/Java process.
    $null = Get-CimInstance Win32_Process -Filter "ProcessId = $PID"
    if (@([IO.DriveInfo]::GetDrives().Name).Contains('H:\')) { throw 'Temporary drive H: is already in use.' }
    & subst.exe H: $projectRoot
    if ($LASTEXITCODE -ne 0) { throw 'Could not create temporary project drive.' }
    $mappedDrive = 'H:'
    $env:GRADLE_USER_HOME = 'H:\.gradle-home'
    $env:TEMP = 'H:\.verification\tmp'
    $env:TMP = $env:TEMP
    New-Item -ItemType Directory -Path $env:TEMP -Force | Out-Null
    $command = '.\gradlew.bat ' + ($GradleArguments -join ' ') + ' --no-daemon'
    $buildProcess = Start-Process -FilePath $env:ComSpec -ArgumentList '/d', '/c', $command -WorkingDirectory 'H:\' -WindowStyle Hidden -RedirectStandardOutput $logPath -RedirectStandardError $errorPath -PassThru
    [void]$trackedIds.Add($buildProcess.Id)
    Write-Output "GRADLE_STARTED NAME=$Name PID=$($buildProcess.Id) LOG=$logPath"
    do {
        $snapshot = @(Get-CimInstance Win32_Process)
        do {
            $added = $false
            foreach ($process in $snapshot) {
                if ($trackedIds.Contains([int]$process.ParentProcessId) -and -not $trackedIds.Contains([int]$process.ProcessId)) {
                    [void]$trackedIds.Add([int]$process.ProcessId)
                    $added = $true
                }
            }
        } while ($added)
        foreach ($process in $snapshot) {
            if ($trackedIds.Contains([int]$process.ProcessId) -and $process.Name -in @('java.exe', 'javaw.exe')) {
                $ownedJava[[int]$process.ProcessId] = @{ ProcessId=[int]$process.ProcessId; ParentProcessId=[int]$process.ParentProcessId; CreationDate=$process.CreationDate; CommandLine=$process.CommandLine }
            }
        }
        Start-Sleep -Milliseconds 250
        $buildProcess.Refresh()
    } while (-not $buildProcess.HasExited)
    $buildProcess.WaitForExit()
    $buildExit = $buildProcess.ExitCode
    Get-Content -LiteralPath $logPath -Tail 14
    if ((Get-Item -LiteralPath $errorPath).Length -gt 0) { Get-Content -LiteralPath $errorPath -Tail 4 }
} catch {
    $originalFailure = $_
} finally {
    try {
    if ($null -ne $buildProcess -and -not $buildProcess.HasExited) { $buildProcess.WaitForExit(); $buildExit=$buildProcess.ExitCode }
    if ($null -eq $buildProcess) {
        $cleanupOkay = $true # No build process was launched.
    } else {
    $deadline = [DateTime]::UtcNow.AddSeconds(40)
    do {
        $javaNow = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'")
        $remaining = @($javaNow | Where-Object { $identity=$ownedJava[[int]$_.ProcessId]; $null -ne $identity -and $identity.CreationDate -eq $_.CreationDate })
        if ($remaining.Count -eq 0) { break }
        Start-Sleep -Seconds 1
    } while ([DateTime]::UtcNow -lt $deadline)
    $cleanupOkay = $remaining.Count -eq 0
    }
    if ($cleanupOkay -and $null -ne $mappedDrive) {
        & subst.exe $mappedDrive /d
        if ($LASTEXITCODE -ne 0) { throw 'Could not remove temporary project drive.' }
    }
    } catch {
        $cleanupError = $_.Exception.Message
        $cleanupOkay = $false
    }
    @{ Name=$Name; Arguments=$GradleArguments; StartedAt=$startedAt; FinishedAt=[DateTime]::UtcNow; ExitCode=$buildExit; Launched=($null -ne $buildProcess); OriginalFailure=([string]$originalFailure); CleanupError=$cleanupError; OwnedJava=@($ownedJava.Values); RemainingJava=@($remaining | Select-Object ProcessId,ParentProcessId,CreationDate,CommandLine); CleanupOkay=$cleanupOkay } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $recordPath -Encoding utf8
    Write-Output "GRADLE_EXIT=$buildExit TRACKED_JAVA=$($ownedJava.Count) REMAINING_JAVA=$($remaining.Count) CLEANUP_OKAY=$cleanupOkay"
}
if ($null -ne $originalFailure) { Write-Error $originalFailure -ErrorAction Continue; exit 1 }
if ($buildExit -ne 0) { exit $buildExit }
if ($GradleArguments -contains 'runS0Server') {
    $eulaPath = Join-Path $projectRoot '.verification/s0-20261106/runtime/development-server/eula.txt'
    if ((Get-Content -LiteralPath $eulaPath -Raw) -match '(?m)^eula=false\s*$') {
        & (Join-Path $PSScriptRoot 'Check-S0ServerLog.ps1') -LogPath $logPath -ResultPath (Join-Path $recordRoot "$Name-runtime.json")
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
}
if (-not $cleanupOkay) { exit 2 }
