# Resolve configuration sources without reading or printing credentials.
function Get-DevBackendArguments([string]$BackendRoot, [string]$ConfigPath) {
    $arguments = @('--server.port=8081')
    if ($ConfigPath) {
        $resolved = (Resolve-Path -LiteralPath $ConfigPath -ErrorAction Stop).ProviderPath
        if (-not (Test-Path -LiteralPath $resolved -PathType Leaf) -or [IO.Path]::GetExtension($resolved) -notin '.properties', '.yml', '.yaml') {
            throw 'BackendConfig must point to a .properties, .yml or .yaml file.'
        }
        $arguments += '--spring.config.additional-location=' + ([Uri]::new($resolved)).AbsoluteUri
        return $arguments
    }

    # Spring Boot resolves these sources itself. This check only confirms a source
    # exists; it does not parse configuration, connect to a database or run migrations.
    if ($env:SPRING_CONFIG_LOCATION -or $env:SPRING_CONFIG_ADDITIONAL_LOCATION -or $env:SPRING_APPLICATION_JSON) { return $arguments }
    $directories = @($BackendRoot, (Join-Path $BackendRoot 'config'), (Join-Path $BackendRoot 'src/main/resources'))
    $configDirectory = Join-Path $BackendRoot 'config'
    if (Test-Path -LiteralPath $configDirectory -PathType Container) {
        $directories += @(Get-ChildItem -LiteralPath $configDirectory -Directory | Select-Object -ExpandProperty FullName)
    }
    $names = if ($env:SPRING_CONFIG_NAME) { $env:SPRING_CONFIG_NAME -split ',' } else { @('application') }
    $profiles = if ($env:SPRING_PROFILES_ACTIVE) { $env:SPRING_PROFILES_ACTIVE -split ',' } else { @('default') }
    foreach ($directory in $directories) {
        foreach ($name in $names) {
            $bases = @($name.Trim()) + @($profiles | ForEach-Object { $name.Trim() + '-' + $_.Trim() })
            foreach ($base in $bases) {
                foreach ($extension in @('yml', 'yaml', 'properties')) {
                    if (Test-Path -LiteralPath (Join-Path $directory "$base.$extension") -PathType Leaf) { return $arguments }
                }
            }
        }
    }
    if ($env:SPRING_DATASOURCE_URL -and $env:AGENT_DATASOURCE_URL) { return $arguments }
    throw 'No backend configuration source found. Configure BOTH spring.datasource.* (required by the existing application) and agent.datasource.* (isolated Agent database). Copy scripts/backend-dev.example.properties to an ignored local file, fill in the actual settings, then run start-dev.ps1 -BackendConfig <file>. Alternatively provide both SPRING_DATASOURCE_URL and AGENT_DATASOURCE_URL with their credentials. No services were started.'
}
