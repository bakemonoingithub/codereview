#!/usr/bin/env bash
# 无 Docker／数据库自测。docker 命令由本文件生成的 Bash 替身替代。
set -u
set -o pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPT="$ROOT/deploy/scripts/purge-deleted-records.sh"
PASS=0; FAIL=0
TMP_BASE="$(cd "${TMPDIR:-/tmp}" && pwd)"
TMP="$(mktemp -d "$TMP_BASE/codereview-purge-test.XXXXXX")"
cleanup() {
  case "$TMP" in "$TMP_BASE"/codereview-purge-test.*) [ -d "$TMP" ] && rm -rf -- "$TMP" ;; esac
}
trap cleanup EXIT

run() {
  if ( "$@" ); then printf 'OK   %s\n' "$1"; PASS=$((PASS+1));
  else printf 'FAIL %s\n' "$1"; FAIL=$((FAIL+1)); fi
}

help_without_dependencies() {
  bash "$SCRIPT" --help > "$TMP/help"
  grep -q -- '--writers-stopped' "$TMP/help"
}
reject_bad_arguments() {
  local args
  for args in '--batch-size 0' '--batch-size 1001' '--batch-size x' '--before 2026-10-01' '--before 2026-02-30T00:00:00Z' '--kinds bad' '--apply' '--database code_review'; do
    if bash "$SCRIPT" $args > /dev/null 2>&1; then return 1; fi
  done
}
task_cascade_plan() {
  . "$SCRIPT"
  make_plan review_task 01J9Z6Q3W4X5Y6Z7A8B9C0D1F1
  [ "${PLAN_TABLES[0]}" = feedback ] || return 1
  [ "${PLAN_TABLES[11]}" = review_task ] || return 1
  [ "${PLAN_TABLES[3]}" = call_material ] || return 1
  [ "${PLAN_TABLES[6]}" = execution_unit ] || return 1
  local table
  for table in "${PLAN_TABLES[@]}"; do
    case "$table" in report|report_source|material) return 1 ;; esac
  done
}
report_does_not_delete_reviews() {
  . "$SCRIPT"
  make_plan report 01J9Z6Q3W4X5Y6Z7A8B9C0D1F1
  [ "${#PLAN_TABLES[@]}" = 4 ] && [ "${PLAN_TABLES[3]}" = report ]
}
blocks_invalid_report_snapshot() {
  . "$SCRIPT"
  scalar() { VALUE=1; }
  task_blocker 01J9Z6Q3W4X5Y6Z7A8B9C0D1F1
  [ "$BLOCKER" = '报告来源快照不完整或格式不受支持' ]
}
keeps_referenced_resource() {
  . "$SCRIPT"
  scalar() { VALUE=1; }
  resource_blocker project 01J9Z6Q3W4X5Y6Z7A8B9C0D1F1
  [ "$BLOCKER" = '仍有保留记录引用' ]
}
time_cutoff_keeps_legacy_resources() {
  . "$SCRIPT"
  BEFORE='2026-10-01 00:00:00'
  scalar() { VALUE=2; }
  db_query() { return 1; }
  process_table project > "$TMP/cutoff"
  grep -q '旧资源没有删除时间' "$TMP/cutoff"
}

# 用于会话和完整编排检查的 Docker 替身，只返回字符串，不解析或执行 SQL。
mkdir -p "$TMP/bin" "$TMP/bundle/scripts"
cp "$ROOT/deploy/scripts/common.sh" "$SCRIPT" "$TMP/bundle/scripts/"
printf 'services: {}\n' > "$TMP/bundle/docker-compose.yml"
printf 'AGENT_DB_NAME=ai_code_review_agent\nMYSQL_ROOT_PASSWORD=mock-secret\n' > "$TMP/bundle/.env"
cat > "$TMP/bin/docker" <<'MOCK'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$MOCK_LOG"
if [ "$1" = inspect ]; then
  if [[ "$*" == *codereview-mysql* ]]; then echo healthy;
  elif [ "${MOCK_APP_RUNNING:-0}" = 1 ]; then echo true; else echo false; fi
  exit 0
fi
[ "$1" = exec ] || exit 1
printf 'session-start\n' >> "$MOCK_LOG"
while IFS= read -r line; do
  printf '%s\n' "$line" >> "$MOCK_SQL"
  if [[ "$line" == SELECT\ \'purge_end_* ]]; then
    value="${line#SELECT \'}"; printf '%s\n' "${value%\';}"
  elif [[ "$line" == quit* ]]; then
    printf 'session-end\n' >> "$MOCK_LOG"; exit 0
  elif [[ "$line" == SELECT\ 41* ]]; then
    echo 41
  elif [[ "$line" == SELECT\ 42* ]]; then
    echo 42
  elif [[ "$line" == *"SELECT VERSION()"* ]]; then
    echo 1
  elif [[ "$line" == *"SELECT COUNT(*) FROM information_schema.tables"* ]]; then
    echo 23
  elif [[ "$line" == *"SELECT COUNT(*) FROM information_schema.columns"* ]]; then
    echo 4
  elif [[ "$line" == *"SELECT COUNT(*) FROM agent_schema_identity"* ]]; then
    echo 1
  elif [[ "$line" == *"version='10' AND success=1"* ]]; then
    echo "${MOCK_V10:-1}"
  elif [[ "$line" == *"SELECT COALESCE(GET_LOCK"* ]]; then
    echo "${MOCK_LOCK:-1}"
  elif [[ "$line" == *"SELECT COUNT(*) FROM model_call WHERE task_id IS NOT NULL"* || "$line" == *"success=0 OR"* ]]; then
    echo 0
  elif [[ "$line" == "SELECT (SELECT COUNT(*) FROM review_task WHERE state"* ]]; then
    echo "${MOCK_ACTIVE:-0}"
  elif [[ "$line" == SELECT\ id\ FROM\ report\ WHERE* && "${MOCK_ROOT:-0}" = 1 && "$line" != *"AND id>"* ]]; then
    echo 01J9Z6Q3W4X5Y6Z7A8B9C0D1F1
  elif [[ "$line" == SELECT\ id\ FROM* ]]; then
    :
  elif [[ "$line" == *"SELECT ROW_COUNT()"* ]]; then
    if [ "${MOCK_DELETE_ERROR:-0}" = 1 ]; then
      echo 'ERROR 9999: simulated SQL failure'; exit 1
    fi
    echo 1
  elif [[ "$line" == "SELECT COALESCE(SUM("* ]]; then
    echo 42
  elif [[ "$line" == "SELECT COUNT(*) FROM "* ]]; then
    echo 1
  fi
done
MOCK
chmod +x "$TMP/bin/docker"

prepare_mock() {
  export PATH="$TMP/bin:$PATH" MOCK_LOG="$TMP/docker.log" MOCK_SQL="$TMP/sql.log"
  export MOCK_APP_RUNNING=0 MOCK_V10=1 MOCK_LOCK=1 MOCK_ACTIVE=0 MOCK_ROOT=0 MOCK_DELETE_ERROR=0
  : > "$MOCK_LOG"; : > "$MOCK_SQL"
}
single_session_protocol() {
  prepare_mock
  . "$SCRIPT"
  MYSQL_ROOT_PASSWORD=mock-secret; AGENT_DB_NAME=ai_code_review_agent
  open_db
  scalar 'SELECT 41'; [ "$VALUE" = 41 ] || return 1
  scalar 'SELECT 42'; [ "$VALUE" = 42 ] || return 1
  close_db
  [ "$(grep -c '^session-start$' "$MOCK_LOG")" = 1 ] || return 1
  ! grep -q mock-secret "$MOCK_LOG"
}
preview_has_no_delete_or_commit() {
  prepare_mock; export MOCK_ROOT=1
  bash "$TMP/bundle/scripts/purge-deleted-records.sh" --kinds reports > "$TMP/preview" || return 1
  grep -q '只读预览完成' "$TMP/preview" || return 1
  ! grep -qE '^(DELETE|START TRANSACTION|COMMIT)' "$MOCK_SQL"
}
apply_uses_one_transaction_session() {
  prepare_mock; export MOCK_ROOT=1
  bash "$TMP/bundle/scripts/purge-deleted-records.sh" --kinds reports --apply --writers-stopped > "$TMP/apply" || return 1
  grep -q '已提交第 1 批' "$TMP/apply" || return 1
  grep -q '^COMMIT;' "$MOCK_SQL" || return 1
  grep -q '^DELETE FROM report_source' "$MOCK_SQL" || return 1
  [ "$(grep -c '^session-start$' "$MOCK_LOG")" = 1 ]
}
mysql_error_prevents_commit() {
  prepare_mock; export MOCK_ROOT=1 MOCK_DELETE_ERROR=1
  if bash "$TMP/bundle/scripts/purge-deleted-records.sh" --kinds reports --apply --writers-stopped > "$TMP/error" 2>&1; then return 1; fi
  ! grep -q '^COMMIT;' "$MOCK_SQL"
}
running_app_is_rejected() {
  prepare_mock; export MOCK_APP_RUNNING=1
  if bash "$TMP/bundle/scripts/purge-deleted-records.sh" --apply --writers-stopped > "$TMP/running" 2>&1; then return 1; fi
  ! grep -q session-start "$MOCK_LOG"
}
active_task_is_rejected() {
  prepare_mock; export MOCK_ACTIVE=1
  if bash "$TMP/bundle/scripts/purge-deleted-records.sh" --apply --writers-stopped > "$TMP/active" 2>&1; then return 1; fi
  ! grep -q '^DELETE' "$MOCK_SQL"
}
missing_v10_is_rejected() {
  prepare_mock; export MOCK_V10=0
  if bash "$TMP/bundle/scripts/purge-deleted-records.sh" > "$TMP/version" 2>&1; then return 1; fi
  ! grep -q '^DELETE' "$MOCK_SQL"
}
concurrent_purge_is_rejected() {
  prepare_mock; export MOCK_LOCK=0
  if bash "$TMP/bundle/scripts/purge-deleted-records.sh" --apply --writers-stopped > "$TMP/lock" 2>&1; then return 1; fi
  ! grep -q '^DELETE' "$MOCK_SQL"
}

run help_without_dependencies
run reject_bad_arguments
run task_cascade_plan
run report_does_not_delete_reviews
run blocks_invalid_report_snapshot
run keeps_referenced_resource
run time_cutoff_keeps_legacy_resources
run single_session_protocol
run preview_has_no_delete_or_commit
run apply_uses_one_transaction_session
run mysql_error_prevents_commit
run running_app_is_rejected
run active_task_is_rejected
run missing_v10_is_rejected
run concurrent_purge_is_rejected
printf '通过 %s 项，失败 %s 项；全部使用 Bash 替身，未连接 Docker 或数据库。\n' "$PASS" "$FAIL"
[ "$FAIL" = 0 ]
