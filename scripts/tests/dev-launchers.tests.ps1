# 启停脚本回归：使用进程和网络替身，不启动或停止真实服务。
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$startScript = Join-Path $repoRoot 'scripts/start-dev.ps1'
$stopScript = Join-Path $repoRoot 'scripts/stop-dev.ps1'
$failures = 0
$checks = 0
function Assert-True([bool]$Condition, [string]$Message) {
    $script:checks++
    if (-not $Condition) { $script:failures++; Write-Host "[FAIL] $Message" }
    else { Write-Host "[ OK ] $Message" }
}

foreach ($path in @($startScript, $stopScript, (Join-Path $repoRoot 'scripts/dev.config.psd1'))) {
    $tokens = $null; $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors)
    Assert-True ($errors.Count -eq 0) "语法有效：$(Split-Path $path -Leaf)"
}
$beforeRuntimeExists = Test-Path -LiteralPath (Join-Path $repoRoot 'frontend/.dev-runtime')

& {
    function Get-Command { param($Name, $CommandType, $ErrorAction)
        if ($Name -eq 'node' -or $Name -eq 'mvn') { return [pscustomobject]@{ Source = 'mock-executable' } }
        Microsoft.PowerShell.Core\Get-Command -Name $Name -ErrorAction $ErrorAction
    }
    function Get-NetTCPConnection { param($State, $ErrorAction) return @() }
    function Start-Process { throw '预检不允许启动进程' }
    $output = (& $startScript -CheckOnly 6>&1) -join "`n"
    Assert-True ($output -match 'Preflight passed') '空闲端口预检通过，不调用 Start-Process'
}
& {
    function Get-Command { param($Name, $CommandType, $ErrorAction)
        if ($Name -eq 'node' -or $Name -eq 'mvn') { return [pscustomobject]@{ Source = 'mock-executable' } }
        Microsoft.PowerShell.Core\Get-Command -Name $Name -ErrorAction $ErrorAction
    }
    function Get-NetTCPConnection { param($State, $ErrorAction)
        return [pscustomobject]@{ LocalPort = 5173; OwningProcess = 12345 }
    }
    function Start-Process { throw '端口冲突时不允许启动进程' }
    $refused = $false
    try { & $startScript -CheckOnly } catch { $refused = $_.Exception.Message -match 'already in use' }
    Assert-True $refused '端口冲突明确拒绝，不抢占现有服务'
}

# 只加载可信源码的就绪函数，网络访问由响应替身替代。
$ast = [System.Management.Automation.Language.Parser]::ParseFile($startScript, [ref]$null, [ref]$null)
$ready = $ast.Find({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Test-ServiceReady' }, $true)
& {
    Invoke-Expression $ready.Extent.Text
    function Invoke-RestMethod { param($Uri, $TimeoutSec, [switch]$NoProxy, $ErrorAction) return $probeResponse }
    $probeResponse = [pscustomobject]@{ status = 'UP' }
    Assert-True (-not (Test-ServiceReady 'http://mock')) '旧健康响应不能冒充 agent 就绪'
    $probeResponse = [pscustomobject]@{ database = 'agent'; schemaVersion = '1' }
    Assert-True (Test-ServiceReady 'http://mock') 'agent 库身份与版本返回后才确认就绪'
}

$runId = '20990101-010101-11111111'
$created = [DateTime]::UtcNow
function New-BackendSnapshot([string]$Root, [int]$ProcessId, [string]$Run) {
    $command = "Set-Location -LiteralPath '" + (Join-Path $Root 'backend').Replace("'", "''") + "'`n"
    $command += "& 'mvn.cmd' '-Dbackend.buildDirectory=target/runtime-local-$Run' spring-boot:run"
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    return [pscustomobject]@{ Name = 'pwsh.exe'; ProcessId = $ProcessId; CommandLine = "pwsh -EncodedCommand $encoded"; CreationDate = $created }
}
$mine = New-BackendSnapshot $repoRoot 910001 $runId
$foreign = New-BackendSnapshot (Join-Path $repoRoot 'other-worktree') 910002 '20990101-010102-22222222'
$frontendConfig = Join-Path $repoRoot "frontend/.dev-runtime/$runId/vite.config.mts"
$myFrontend = [pscustomobject]@{ Name = 'node.exe'; ProcessId = 910003; CommandLine = "node node_modules/vite/bin/vite.js --config `"$frontendConfig`""; CreationDate = $created }
$foreignFrontend = [pscustomobject]@{ Name = 'node.exe'; ProcessId = 910004; CommandLine = 'node node_modules/vite/bin/vite.js --config foreign/vite.config.mts'; CreationDate = $created }
$testSnapshots = @($mine, $foreign, $myFrontend, $foreignFrontend)
& {
    function Get-CimInstance { param($ClassName) return $testSnapshots }
    function Get-Process { throw 'CheckOnly 不允许获取可终止进程' }
    $output = (& $stopScript -CheckOnly 6>&1) -join "`n"
    Assert-True ($output -match '910001' -and $output -match '910003') '停止预检识别本工作树前后端'
    Assert-True ($output -notmatch '910002|910004') '停止预检排除其他工作树'
}
function New-FakeProcess([DateTime]$Start) {
    $process = [pscustomobject]@{ HasExited = $false; StartTime = $Start; WasKilled = $false }
    $process | Add-Member ScriptMethod Kill { param([bool]$Tree) $this.WasKilled = $true; $this.HasExited = $true }
    $process | Add-Member ScriptMethod WaitForExit { param([int]$Timeout) return $true }
    $process | Add-Member ScriptMethod Dispose { }
    return $process
}
& {
    $fakeProcess = New-FakeProcess ($created.AddSeconds(1))
    function Get-CimInstance { param($ClassName) return @($mine) }
    function Get-Process { param($Id, $ErrorAction) return $fakeProcess }
    $refused = $false
    try { & $stopScript } catch { $refused = $_.Exception.Message -match 'reused' }
    Assert-True ($refused -and -not $fakeProcess.WasKilled) 'PID 启动时间不匹配时拒绝终止'
}
& {
    $fakeProcess = New-FakeProcess $created
    function Get-CimInstance { param($ClassName) return @($mine) }
    function Get-Process { param($Id, $ErrorAction) return $fakeProcess }
    & $stopScript
    Assert-True $fakeProcess.WasKilled '身份核对通过后只终止匹配的进程替身'
}
Assert-True ((Test-Path -LiteralPath (Join-Path $repoRoot 'frontend/.dev-runtime')) -eq $beforeRuntimeExists) '预检及回归没有创建运行目录'
$runtimeConfig = Join-Path $repoRoot "frontend/.dev-runtime/$runId/vite.config.mts"
$baseConfig = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $runtimeConfig) '../../vite.config.ts'))
Assert-True ($baseConfig -eq (Join-Path $repoRoot 'frontend/vite.config.ts')) '运行配置引用当前工作树 Vite 配置'
Write-Host "$checks 项检查，$failures 项失败"
if ($failures) { exit 1 }
