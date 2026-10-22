# Requires PowerShell 7. Plan is read-only; Apply requires a previously saved run.
param(
    [ValidateSet('plan', 'apply', 'verify', 'self-test')][string]$Mode = 'plan',
    [string]$SourceConfig,
    [string]$TargetConfig,
    [string]$RunDirectory
)
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'Run this script with PowerShell 7 (pwsh).' }
$repoRoot = Split-Path -Parent $PSScriptRoot
$config = Import-PowerShellDataFile (Join-Path $PSScriptRoot 'dev.config.psd1')
$javaHome = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) { $env:JAVA_HOME } else {
    $config.JavaHomeCandidates | Where-Object { Test-Path -LiteralPath (Join-Path $_ 'bin/java.exe') } | Select-Object -First 1
}
if (-not $javaHome) { throw 'JDK 17 or later is required.' }
$repository = Join-Path $env:USERPROFILE '.m2/repository'
$libraries = @(
    'org/yaml/snakeyaml/2.2/snakeyaml-2.2.jar',
    'com/mysql/mysql-connector-j/9.1.0/mysql-connector-j-9.1.0.jar',
    'com/fasterxml/jackson/core/jackson-databind/2.17.2/jackson-databind-2.17.2.jar',
    'com/fasterxml/jackson/core/jackson-core/2.17.2/jackson-core-2.17.2.jar',
    'com/fasterxml/jackson/core/jackson-annotations/2.17.2/jackson-annotations-2.17.2.jar'
) | ForEach-Object {
    $path = Join-Path $repository $_
    if (-not (Test-Path -LiteralPath $path)) { throw "Required local dependency missing: $path" }
    $path
}
$javaArguments = @('-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-cp', ($libraries -join [IO.Path]::PathSeparator), (Join-Path $PSScriptRoot 'migration/LegacyConfigMigration.java'), $Mode)
if ($Mode -ne 'self-test') {
    if (-not $SourceConfig -or -not $TargetConfig) { throw 'Provide explicit SourceConfig and TargetConfig paths.' }
    if (-not $RunDirectory) {
        if ($Mode -ne 'plan') { throw 'Apply and Verify require the existing RunDirectory.' }
        $RunDirectory = Join-Path $repoRoot ('backend/.local-backups/config-migration/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    }
    $javaArguments += @((Resolve-Path -LiteralPath $SourceConfig).ProviderPath, (Resolve-Path -LiteralPath $TargetConfig).ProviderPath, [IO.Path]::GetFullPath($RunDirectory))
    Write-Host "Migration artifacts: $RunDirectory"
}
& (Join-Path $javaHome 'bin/java.exe') @javaArguments
if ($LASTEXITCODE -ne 0) { throw "Migration $Mode failed; see sanitized stage diagnostics above." }
