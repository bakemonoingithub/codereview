<#
.SYNOPSIS
  启动本机开发服务：前端 5173，后端 8081；Ctrl+C 停止本次启动的进程树。
.EXAMPLE
  pwsh scripts/start-dev.ps1
.EXAMPLE
  pwsh scripts/start-dev.ps1 -CheckOnly
#>
param(
    [switch]$CheckOnly,
    [ValidateRange(10, 3600)][int]$StartupTimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) {
    # Keep this bootstrap compatible with Windows PowerShell 5.1.
    $pwsh = Get-Command pwsh -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    $pwshPath = if ($pwsh) { $pwsh.Source } else {
        @(
            (Join-Path $env:ProgramFiles 'PowerShell/7/pwsh.exe')
            (Join-Path $env:LOCALAPPDATA 'Microsoft/PowerShell/7/pwsh.exe')
            (Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/native/powershell/pwsh.exe')
        ) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    }
    if (-not $pwshPath) { throw 'PowerShell 7 was not found. Install it or add pwsh.exe to PATH, then retry.' }
    Write-Host 'Switching to PowerShell 7...'
    $launchArguments = @('-NoLogo', '-NoProfile', '-File', $PSCommandPath)
    if ($CheckOnly) { $launchArguments += '-CheckOnly' }
    $launchArguments += @('-StartupTimeoutSeconds', [string]$StartupTimeoutSeconds)
    & $pwshPath @launchArguments
    exit $LASTEXITCODE
}
if (-not $IsWindows) { throw 'This launcher requires Windows.' }
$repoRoot = Split-Path -Parent $PSScriptRoot
$frontendRoot = Join-Path $repoRoot 'frontend'
$backendRoot = Join-Path $repoRoot 'backend'
$config = Import-PowerShellDataFile (Join-Path $PSScriptRoot 'dev.config.psd1')
$nodePath = (Get-Command node -CommandType Application -ErrorAction Stop).Source
$maven = Get-Command mvn -CommandType Application -ErrorAction SilentlyContinue
$mavenPath = if ($maven) { $maven.Source } else {
    $config.MavenCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
}
$javaHome = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    $env:JAVA_HOME
} else {
    $config.JavaHomeCandidates | Where-Object { Test-Path -LiteralPath (Join-Path $_ 'bin/java.exe') } | Select-Object -First 1
}
if (-not $mavenPath) { throw 'Maven unavailable; check scripts/dev.config.psd1.' }
if (-not $javaHome) { throw 'Java unavailable; check JAVA_HOME or scripts/dev.config.psd1.' }
foreach ($relativePath in @('node_modules/vite/bin/vite.js', 'vite.config.ts')) {
    if (-not (Test-Path -LiteralPath (Join-Path $frontendRoot $relativePath))) {
        throw "Missing frontend dependency or configuration: $relativePath"
    }
}
$listeners = @(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object { $_.LocalPort -in 5173, 8081 })
if ($listeners.Count -gt 0) {
    $details = ($listeners | ForEach-Object { "port $($_.LocalPort), PID $($_.OwningProcess)" } | Sort-Object -Unique) -join '; '
    throw "Development ports already in use: $details. Stop the existing services before retrying."
}
Write-Host "Java: $javaHome"
Write-Host "Maven: $mavenPath"
Write-Host 'Ports 5173 and 8081 are available.'
Write-Host 'Database configuration is not validated by -CheckOnly; startup requires the isolated agent database configuration.'
if ($CheckOnly) { Write-Host 'Preflight passed; no services started or files written.'; return }

$runId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$runtimeRelative = ".dev-runtime/$runId"
$runtimeRoot = Join-Path $frontendRoot $runtimeRelative
$null = New-Item -ItemType Directory -Path $runtimeRoot -Force
$viteConfig = @'
import base from '../../vite.config.ts'
export default {
  ...base,
  server: {
    ...base.server,
    host: '127.0.0.1', port: 5173, strictPort: true,
    proxy: { ...base.server?.proxy, '/api': { target: 'http://127.0.0.1:8081', changeOrigin: true } }
  }
}
'@
$runtimeConfig = Join-Path $runtimeRoot 'vite.config.mts'
[System.IO.File]::WriteAllText($runtimeConfig, ($viteConfig.Replace("`r`n", "`n") + "`n"), [System.Text.UTF8Encoding]::new($false))

# EncodedCommand preserves literal paths, including spaces and single quotes.
function Quote-Literal([string]$Value) { return "'" + $Value.Replace("'", "''") + "'" }
$backendCommand = @(
    '$ErrorActionPreference = ''Stop'''
    ('$env:JAVA_HOME = ' + (Quote-Literal $javaHome))
    ('Set-Location -LiteralPath ' + (Quote-Literal $backendRoot))
    ('& ' + (Quote-Literal $mavenPath) + ' ' + (Quote-Literal "-Dbackend.buildDirectory=target/runtime-local-$runId") + ' spring-boot:run ''-Dspring-boot.run.arguments=--server.port=8081''')
    'exit $LASTEXITCODE'
) -join "`n"
$encodedCommand = [Convert]::ToBase64String([System.Text.Encoding]::Unicode.GetBytes($backendCommand))
function Test-ServiceReady([string]$Uri) {
    try {
        $response = Invoke-RestMethod -Uri $Uri -TimeoutSec 2 -NoProxy -ErrorAction Stop
        return $response.database -eq 'agent' -and -not [string]::IsNullOrWhiteSpace([string]$response.schemaVersion)
    } catch { return $false }
}
$backendProcess = $null
$frontendProcess = $null
try {
    $backendProcess = Start-Process -FilePath (Join-Path $PSHOME 'pwsh.exe') -WindowStyle Hidden -PassThru `
        -ArgumentList @('-NoLogo', '-NoProfile', '-NonInteractive', '-EncodedCommand', $encodedCommand) `
        -WorkingDirectory $backendRoot -RedirectStandardOutput (Join-Path $runtimeRoot 'backend.log') `
        -RedirectStandardError (Join-Path $runtimeRoot 'backend-error.log')
    $frontendProcess = Start-Process -FilePath $nodePath -WindowStyle Hidden -PassThru `
        -ArgumentList @('node_modules/vite/bin/vite.js', '--config', ('"' + $runtimeConfig + '"')) `
        -WorkingDirectory $frontendRoot -RedirectStandardOutput (Join-Path $runtimeRoot 'frontend.log') `
        -RedirectStandardError (Join-Path $runtimeRoot 'frontend-error.log')
    $processRecord = @{
        repoRoot = $repoRoot
        backend = @{ id = $backendProcess.Id; startedAt = $backendProcess.StartTime.ToUniversalTime().ToString('o') }
        frontend = @{ id = $frontendProcess.Id; startedAt = $frontendProcess.StartTime.ToUniversalTime().ToString('o') }
    } | ConvertTo-Json -Depth 3
    [System.IO.File]::WriteAllText((Join-Path $runtimeRoot 'processes.json'), ($processRecord + "`n"), [System.Text.UTF8Encoding]::new($false))
    Write-Host "Starting backend (PID $($backendProcess.Id)) and frontend (PID $($frontendProcess.Id))..."
    Write-Host 'Waiting for backend compilation and startup; the frontend may become available first.'
    Write-Host "Startup progress and errors: $runtimeRoot"
    Write-Host 'Keep this terminal open. Press Ctrl+C or run scripts/stop-dev.ps1 to stop both services.'
    $backendReady = $false
    $proxyReady = $false
    $startupWatch = [System.Diagnostics.Stopwatch]::StartNew()
    $nextProbe = 0
    $nextStatus = 10
    while (-not $backendProcess.HasExited -and -not $frontendProcess.HasExited) {
        if (Test-Path -LiteralPath (Join-Path $runtimeRoot 'stop.requested')) { break }
        $elapsed = [int]$startupWatch.Elapsed.TotalSeconds
        if (-not $proxyReady -and $elapsed -ge $StartupTimeoutSeconds) {
            throw "Startup timed out after ${StartupTimeoutSeconds}s; check configuration and logs in $runtimeRoot"
        }
        if (-not $proxyReady -and $elapsed -ge $nextProbe) {
            if (-not $backendReady -and (Test-ServiceReady 'http://127.0.0.1:8081/api/v2/health')) {
                $backendReady = $true
                Write-Host 'Backend ready: http://127.0.0.1:8081 (database and migrations checked).'
            }
            if ($backendReady -and (Test-ServiceReady 'http://127.0.0.1:5173/api/v2/health')) {
                $proxyReady = $true
                Write-Host "Both services are ready ($([int]$startupWatch.Elapsed.TotalSeconds)s). Open http://127.0.0.1:5173/"
            }
            $nextProbe = $startupWatch.Elapsed.TotalSeconds + 2
        }
        if (-not $proxyReady -and $elapsed -ge $nextStatus) {
            $waitingFor = if ($backendReady) { 'frontend agent API proxy' } else { 'backend compilation/startup and agent database readiness' }
            Write-Host "Waiting for $waitingFor (${elapsed}s); startup logs: $runtimeRoot"
            $nextStatus = $elapsed + 10
        }
        Start-Sleep -Milliseconds 500
    }
    if (Test-Path -LiteralPath (Join-Path $runtimeRoot 'stop.requested')) {
        Write-Host 'Stop requested; stopping both development services.'
    } else {
        $ended = if ($backendProcess.HasExited) { $backendProcess } else { $frontendProcess }
        throw "Service process $($ended.Id) exited (code $($ended.ExitCode)). Check logs in $runtimeRoot"
    }
} finally {
    foreach ($process in @($frontendProcess, $backendProcess)) {
        if ($null -ne $process) {
            if (-not $process.HasExited) {
                try { $process.Kill($true) } catch [System.InvalidOperationException] {
                    if (-not $process.HasExited) { throw }
                }
                $process.WaitForExit()
            }
            $process.Dispose()
        }
    }
}
