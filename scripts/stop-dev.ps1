<#
.SYNOPSIS
  停止当前仓库 start-dev.ps1 启动的前后端；-CheckOnly 仅显示匹配的进程。
.EXAMPLE
  .\stop-dev.ps1
#>
param([switch]$CheckOnly)
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) {
    $pwsh = Get-Command pwsh -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    $pwshPath = if ($pwsh) { $pwsh.Source } else {
        @(
            (Join-Path $env:ProgramFiles 'PowerShell/7/pwsh.exe')
            (Join-Path $env:LOCALAPPDATA 'Microsoft/PowerShell/7/pwsh.exe')
            (Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/native/powershell/pwsh.exe')
        ) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    }
    if (-not $pwshPath) { throw 'PowerShell 7 was not found. Install it or add pwsh.exe to PATH, then retry.' }
    $launchArguments = @('-NoLogo', '-NoProfile', '-File', $PSCommandPath)
    if ($CheckOnly) { $launchArguments += '-CheckOnly' }
    & $pwshPath @launchArguments
    exit $LASTEXITCODE
}
if (-not $IsWindows) { throw 'This launcher requires Windows.' }
$repoRoot = Split-Path -Parent $PSScriptRoot
$cacheRoot = Join-Path $repoRoot 'frontend/.dev-runtime'
$backendLocation = "Set-Location -LiteralPath '" + (Join-Path $repoRoot 'backend').Replace("'", "''") + "'"
$snapshots = @(Get-CimInstance Win32_Process)
$targets = @{}
$runDirectories = @{}

# Match the encoded backend command used by start-dev, including older runs
# that predate processes.json. Never select services by port alone.
foreach ($snapshot in $snapshots) {
    if ($snapshot.Name -ne 'pwsh.exe' -or $snapshot.CommandLine -notmatch '-EncodedCommand\s+([A-Za-z0-9+/=]+)') { continue }
    try { $command = [System.Text.Encoding]::Unicode.GetString([Convert]::FromBase64String($Matches[1])) } catch { continue }
    if (-not $command.Contains($backendLocation) -or -not $command.Contains('spring-boot:run')) { continue }
    if ($command -notmatch '-Dbackend\.buildDirectory=target/runtime-local-(\d{8}-\d{6}-[a-f0-9]{8})') { continue }
    $runId = $Matches[1]
    $targets[[int]$snapshot.ProcessId] = $snapshot
    $runDirectories[$runId] = Join-Path $cacheRoot $runId
    $frontendConfig = Join-Path $cacheRoot "$runId/vite.config.mts"
    foreach ($frontend in $snapshots) {
        if ($frontend.Name -eq 'node.exe' -and $frontend.CommandLine -and $frontend.CommandLine.Contains('node_modules/vite/bin/vite.js') -and $frontend.CommandLine.Contains($frontendConfig)) {
            $targets[[int]$frontend.ProcessId] = $frontend
        }
    }
}

# The start time prevents a stale PID record from selecting a reused PID.
if (Test-Path -LiteralPath $cacheRoot) {
    foreach ($directory in Get-ChildItem -LiteralPath $cacheRoot -Directory) {
        $recordPath = Join-Path $directory.FullName 'processes.json'
        if (-not (Test-Path -LiteralPath $recordPath)) { continue }
        try { $record = Get-Content -LiteralPath $recordPath -Raw | ConvertFrom-Json -ErrorAction Stop } catch { continue }
        if ($record.repoRoot -ne $repoRoot) { continue }
        foreach ($role in @('backend', 'frontend')) {
            $entry = $record.$role
            if ($null -eq $entry) { continue }
            foreach ($snapshot in $snapshots) {
                if ($snapshot.ProcessId -ne $entry.id) { continue }
                try { $startedAt = ([DateTimeOffset]$entry.startedAt).UtcDateTime } catch { continue }
                $expectedName = if ($role -eq 'backend') { 'pwsh.exe' } else { 'node.exe' }
                if ($snapshot.Name -eq $expectedName -and [Math]::Abs(($snapshot.CreationDate.ToUniversalTime() - $startedAt).TotalMilliseconds) -lt 1) {
                    $targets[[int]$snapshot.ProcessId] = $snapshot
                    $runDirectories[$directory.Name] = $directory.FullName
                }
            }
        }
    }
}
if ($targets.Count -eq 0) { Write-Host 'No development services started by this repository were found.'; return }
foreach ($snapshot in $targets.Values) { Write-Host "Matched $($snapshot.Name), PID $($snapshot.ProcessId)" }
if ($CheckOnly) { Write-Host 'Check only; no services stopped.'; return }
foreach ($directory in $runDirectories.Values) {
    if (Test-Path -LiteralPath $directory) {
        [System.IO.File]::WriteAllText((Join-Path $directory 'stop.requested'), '', [System.Text.UTF8Encoding]::new($false))
    }
}
foreach ($snapshot in $targets.Values) {
    $process = Get-Process -Id $snapshot.ProcessId -ErrorAction SilentlyContinue
    if ($null -eq $process) { continue }
    try {
        if ($process.HasExited) { continue }
        try { $actualStartTime = $process.StartTime.ToUniversalTime() } catch {
            if ($process.HasExited) { continue }
            throw
        }
        if ([Math]::Abs(($actualStartTime - $snapshot.CreationDate.ToUniversalTime()).TotalMilliseconds) -ge 1) {
            throw "PID $($snapshot.ProcessId) was reused; refusing to stop it."
        }
        if (-not $process.HasExited) {
            try { $process.Kill($true) } catch [System.InvalidOperationException] {
                if (-not $process.HasExited) { throw }
            }
            if (-not $process.WaitForExit(10000)) { throw "Timed out stopping PID $($snapshot.ProcessId)." }
        }
    } finally { $process.Dispose() }
}
Write-Host 'Development services stopped.'
