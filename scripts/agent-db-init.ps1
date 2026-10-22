# 创建新库（aiCodeReview 新路线）并准备独立账号。
#
# 只生成并可选执行建库/建账号 SQL，不做迁移：表结构由应用启动时的
# classpath:db/agent-migration 负责。旧库（code_review）不会被连接或修改。
#
# 安全约定：打印的 SQL 永远脱敏；-Execute 必须用 -AppPassword 提供真实应用口令，
# 缺省或占位值一律拒绝，口令经 stdin 交给 mysql，不出现在命令行参数里。
#
# 用法：
#   pwsh scripts/agent-db-init.ps1 -DryRun
#   pwsh scripts/agent-db-init.ps1 -Execute -AppPassword '***' -MysqlExe 'C:\mysql\bin\mysql.exe' -AdminUser root -AdminPassword '***'
[CmdletBinding()]
param(
    [string]$Database = 'ai_code_review_agent',
    [string]$AppUser = 'codereview_agent',
    [string]$AppHost = 'localhost',
    [int]$Port = 3306,
    [string]$MysqlExe,
    [string]$AdminUser = 'root',
    [string]$AdminPassword,
    [string]$AppPassword,
    [switch]$Execute,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

if ($Database -eq 'code_review') {
    throw '拒绝初始化：code_review 是旧库，新链必须使用独立新库'
}
if ($Database -notmatch '^[A-Za-z0-9_]+$' -or $AppUser -notmatch '^[A-Za-z0-9_]+$') {
    throw '库名与账号只允许字母、数字和下划线'
}

function Test-PlaceholderPassword([string]$password) {
    if ([string]::IsNullOrWhiteSpace($password)) { return $true }
    $trimmed = $password.Trim()
    return $trimmed -match '^<.*>$' -or $trimmed -eq '运行时密码' -or $trimmed -eq 'password'
}

function New-Sql([string]$password) {
    $escaped = $password.Replace("'", "''")
    return @"
CREATE DATABASE IF NOT EXISTS ``$Database``
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS '$AppUser'@'$AppHost' IDENTIFIED BY '$escaped';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP
  ON ``$Database``.* TO '$AppUser'@'$AppHost';
FLUSH PRIVILEGES;
"@
}

Write-Host "目标库：$Database（新库，独立于旧库 code_review）"
Write-Host '以下 SQL 已脱敏，可直接交给 DBA 手工执行：'
Write-Host (New-Sql '<运行时密码>')

if ($DryRun -or -not $Execute) {
    Write-Host '未加 -Execute，只输出 SQL；要直接执行请同时提供 -AppPassword。'
    exit 0
}

if (Test-PlaceholderPassword $AppPassword) {
    throw '拒绝执行：-Execute 必须用 -AppPassword 提供真实应用口令，不能用占位值'
}
if (-not $MysqlExe -or -not (Test-Path $MysqlExe)) {
    throw '缺少可用的 mysql 客户端：请用 -MysqlExe 指定路径，或手工执行上面的 SQL'
}

$env:MYSQL_PWD = $AdminPassword
try {
    New-Sql $AppPassword | & $MysqlExe --host=localhost --port=$Port --user=$AdminUser `
        --default-character-set=utf8mb4
    if ($LASTEXITCODE -ne 0) { throw "建库失败，mysql 退出码 $LASTEXITCODE" }
} finally {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
}

Write-Host '建库完成。下一步：配置 agent.datasource.* 后启动应用，由 Flyway 建立表结构。'
