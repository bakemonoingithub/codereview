# 检查新库是否可被新路线安全使用（只读）。
#
# 检查项：库名不是旧库、存在 agent_schema_identity 且版本为 1、不存在旧链
# flyway_schema_history、18 张业务表齐全、P32 报告迁移（report 时间列、
# model_call.report_id、迁移版本至少 4）已应用。任一项失败以退出码 1 结束。
#
# 用法：pwsh scripts/agent-db-check.ps1 -MysqlExe 'C:\mysql\bin\mysql.exe' -Database ai_code_review_agent
[CmdletBinding()]
param(
    [string]$Database = 'ai_code_review_agent',
    [string]$MysqlExe,
    [string]$User = 'root',
    [string]$Password,
    [int]$Port = 3306
)

$ErrorActionPreference = 'Stop'

if ($Database -eq 'code_review') {
    throw '目标库是旧库 code_review；新路线必须使用独立新库'
}
if (-not $MysqlExe -or -not (Test-Path $MysqlExe)) {
    Write-Error '未找到 mysql 客户端：请用 -MysqlExe 指定，或用 SQL 手工核对（见 docs/work/agent-redesign/p30/p30-2-schema.md）'
    exit 2
}

function Invoke-Sql([string]$sql) {
    $env:MYSQL_PWD = $Password
    try {
        $rows = & $MysqlExe --batch --skip-column-names --host=localhost --port=$Port --user=$User `
            --default-character-set=utf8mb4 --database=$Database --execute=$sql 2>&1
        if ($LASTEXITCODE -ne 0) { throw "查询失败：$rows" }
        return ($rows | Select-Object -First 1)
    } finally {
        Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    }
}

$problems = [System.Collections.Generic.List[string]]::new()

$legacy = Invoke-Sql "select count(*) from information_schema.tables where table_schema='$Database' and table_name='flyway_schema_history'"
if ([int]$legacy -gt 0) { $problems.Add('存在旧链 flyway_schema_history：这个库被旧迁移链占用') }

$tableCount = Invoke-Sql "select count(*) from information_schema.tables where table_schema='$Database' and table_name in ('agent_schema_identity','project','model_config','model_validation','prompt','prompt_version','review_strategy','review_task','task_target','execution_unit','model_call','material','call_material','review_result','issue','feedback','report','report_source')"
if ([int]$tableCount -ne 18) { $problems.Add("表结构不完整：期望 18 张，实际 $tableCount 张") }

$version = Invoke-Sql "select schema_version from agent_schema_identity where schema_name='ai_code_review_agent'"
if ([string]$version -ne '1') { $problems.Add("版本标记不符：期望 1，实际 '$version'") }

$reportColumns = Invoke-Sql "select count(*) from information_schema.columns where table_schema='$Database' and table_name='report' and column_name in ('started_at','finished_at')"
if ([int]$reportColumns -ne 2) { $problems.Add('报告生成时间列不完整：期望 started_at／finished_at') }

$callColumns = Invoke-Sql "select count(*) from information_schema.columns where table_schema='$Database' and table_name='model_call' and column_name='report_id'"
if ([int]$callColumns -ne 1) { $problems.Add('报告调用关联列缺失：model_call.report_id') }

$migration = Invoke-Sql "select coalesce(max(version), 0) from agent_schema_history where success=1"
if ([int]$migration -lt 4) { $problems.Add("迁移版本过旧：期望至少 4，实际 '$migration'") }

if ($problems.Count -gt 0) {
    $problems | ForEach-Object { Write-Host "[FAIL] $_" -ForegroundColor Red }
    exit 1
}
Write-Host "[OK] 库 $Database 可被新路线使用：18 张表、版本 1 标记和 P32 报告迁移齐全，未混入旧链" -ForegroundColor Green
exit 0
