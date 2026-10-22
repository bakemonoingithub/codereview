param([Parameter(Mandatory=$true)][ValidateSet('deepseek')][string]$ModelMatch)
$ErrorActionPreference = 'Stop'
# Explicit design-only probe: SELECT model configuration, then at most 4 real requests per matched model.
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$config = Import-PowerShellDataFile (Join-Path $root 'scripts/dev.config.psd1')
$probeJavaHome = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    $env:JAVA_HOME
} else { $config.JavaHomeCandidates | Where-Object { Test-Path -LiteralPath (Join-Path $_ 'bin/java.exe') } | Select-Object -First 1 }
if (-not $probeJavaHome) { throw 'A local JDK 17+ is required.' }
$libraries = @(
    'org/yaml/snakeyaml/2.2/snakeyaml-2.2.jar',
    'com/mysql/mysql-connector-j/9.1.0/mysql-connector-j-9.1.0.jar',
    'com/fasterxml/jackson/core/jackson-databind/2.17.2/jackson-databind-2.17.2.jar',
    'com/fasterxml/jackson/core/jackson-core/2.17.2/jackson-core-2.17.2.jar',
    'com/fasterxml/jackson/core/jackson-annotations/2.17.2/jackson-annotations-2.17.2.jar'
) | ForEach-Object {
    $library = Join-Path "$env:USERPROFILE/.m2/repository" $_
    if (-not (Test-Path -LiteralPath $library)) { throw 'A required local probe library is missing.' }
    $library
}
& (Join-Path $probeJavaHome 'bin/java.exe') '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' '-cp' ($libraries -join [IO.Path]::PathSeparator) (Join-Path $PSScriptRoot 'ProtocolProbe.java') $root $ModelMatch plain
if ($LASTEXITCODE -ne 0) { throw 'Protocol probe failed; see sanitized stage diagnostics.' }
