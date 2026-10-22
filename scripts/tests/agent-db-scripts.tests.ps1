# agent-db 脚本文本回归（不需要真实 MySQL）。
#
# 用一个假的 mysql 客户端把 stdin 捕获到文件，验证三件事：
#   1. 打印路径永远脱敏；
#   2. -Execute 缺口令或给占位值时拒绝执行，且不调用客户端；
#   3. -Execute 给真实口令时，真正下发的 SQL 含该口令且不含占位符。
#
# 用法：pwsh scripts/tests/agent-db-scripts.tests.ps1
$ErrorActionPreference = 'Stop'
$script = Join-Path $PSScriptRoot '..\agent-db-init.ps1'
$work = Join-Path ([System.IO.Path]::GetTempPath()) ('agent-db-scripts-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $work | Out-Null

$fakeMysql = Join-Path $work 'fake-mysql.cmd'
$capture = Join-Path $work 'captured.sql'
@"
@echo off
more > "$capture"
exit /b 0
"@ | Set-Content -Path $fakeMysql -Encoding ascii

$failures = 0

function Assert-True([bool]$condition, [string]$message) {
    if (-not $condition) {
        $script:failures++
        Write-Host "[FAIL] $message" -ForegroundColor Red
    } else {
        Write-Host "[ OK ] $message" -ForegroundColor Green
    }
}

try {
    $printed = & pwsh -NoProfile -File $script -DryRun
    Assert-True (($printed -join "`n") -notmatch 'S3cret') '打印路径不含真实口令'
    Assert-True (($printed -join "`n") -match '运行时密码') '打印路径展示占位符'

    Remove-Item $capture -ErrorAction SilentlyContinue
    & pwsh -NoProfile -File $script -Execute -MysqlExe $fakeMysql -AppPassword '<运行时密码>' *> $null
    Assert-True ($LASTEXITCODE -ne 0) '占位口令被拒绝'
    Assert-True (-not (Test-Path $capture)) '拒绝时没有调用 mysql 客户端'

    & pwsh -NoProfile -File $script -Execute -MysqlExe $fakeMysql *> $null
    Assert-True ($LASTEXITCODE -ne 0) '缺少口令被拒绝'
    Assert-True (-not (Test-Path $capture)) '缺口令时没有调用 mysql 客户端'

    & pwsh -NoProfile -File $script -Execute -MysqlExe $fakeMysql -AppPassword 'S3cret-pass' | Out-Null
    $issued = Get-Content $capture -Raw
    Assert-True ($issued -match "IDENTIFIED BY 'S3cret-pass'") '执行路径下发真实口令'
    Assert-True ($issued -notmatch '运行时密码') '执行路径不含占位符'

    & pwsh -NoProfile -File $script -Database code_review -DryRun *> $null
    Assert-True ($LASTEXITCODE -ne 0) '旧库名被拒绝'
} finally {
    Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
}

if ($failures -gt 0) {
    Write-Host "$failures 项失败" -ForegroundColor Red
    exit 1
}
Write-Host '全部通过' -ForegroundColor Green
exit 0
