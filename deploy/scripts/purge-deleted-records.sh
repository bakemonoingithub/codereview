#!/usr/bin/env bash
# 离线清理：复用部署包 .env、Docker 及数据库容器内的 MySQL 8 客户端。
# 兼容部署基线 Bash 4.2；默认只读预览，不安装任何运行依赖。
set -u
set -o pipefail
PURGE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$PURGE_DIR/common.sh"

APPLY=0; WRITERS_STOPPED=0; BEFORE=""; KINDS=all; BATCH_SIZE=20
DB_PID=""; DB_READ=""; DB_WRITE=""; VALUE=0; ROOT_COUNT=0; COMMITTED_BATCHES=0
DB_ROWS=(); DB_ROW_COUNT=0; MATERIAL_COUNT=0; PLAN_TABLES=(); PLAN_WHERE=(); BATCH_LOG=()

usage() {
  cat <<'HELP'
用法：bash scripts/purge-deleted-records.sh [选项]
  --apply                 实际物理删除；默认只读预览
  --writers-stopped       声明全部应用实例及其他数据库写入者已经停止
  --kinds TYPE             all 或 reports,reviews,resources 的逗号组合
  --before UTC            删除时间截止，例如 2026-10-01T00:00:00Z
  --batch-size N          每批根记录数，1～1000，默认 20
  --help                  显示帮助，不读取配置或连接 Docker
复用部署包 .env 中 AGENT_DB_NAME 与 MYSQL_ROOT_PASSWORD。
数据库保持运行；执行模式拒绝运行中的 codereview-app，不会自动启停服务。
HELP
}

parse_args() {
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --apply) APPLY=1; shift ;;
      --writers-stopped) WRITERS_STOPPED=1; shift ;;
      --before|--kinds|--batch-size)
        [ "$#" -ge 2 ] || die "$1 缺少值"
        case "$1" in
          --before) BEFORE="$2" ;; --kinds) KINDS="$2" ;; --batch-size) BATCH_SIZE="$2" ;;
        esac
        shift 2 ;;
      --help|-h) usage; return 2 ;;
      *) die "未知参数：$1" ;;
    esac
  done
  [[ "$BATCH_SIZE" =~ ^[1-9][0-9]{0,3}$ ]] && [ "$BATCH_SIZE" -le 1000 ] || die "batch-size 必须在 1～1000 之间"
  [[ "$KINDS" =~ ^(all|reports|reviews|resources)(,(reports|reviews|resources))*$ ]] || die "kinds 参数不合法"
  if [ -n "$BEFORE" ]; then
    [[ "$BEFORE" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || die "before 必须使用 UTC 格式 YYYY-MM-DDTHH:MM:SSZ"
    BEFORE="$(date -u -d "$BEFORE" '+%Y-%m-%d %H:%M:%S' 2>/dev/null)" || die "before 不是有效日期"
  fi
  [ "$APPLY" = 0 ] || [ "$WRITERS_STOPPED" = 1 ] || die "执行前停止全部写入者，并指定 --writers-stopped"
}

has_kind() { [[ ",$KINDS," == *,all,* || ",$KINDS," == *,$1,* ]]; }
valid_id() { [[ "$1" =~ ^[0-9A-HJKMNP-TV-Z]{26}$ ]] || die "记录 ID 不合法，停止本批清理"; }

# 单一持续 docker exec 会话。查询、根行锁及整批 DELETE 共用同一连接。
open_db() {
  coproc PURGE_CONNECTION {
    export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
    exec docker exec -i -e MYSQL_PWD codereview-mysql mysql --user=root \
      --batch --raw --skip-column-names --silent --unbuffered --skip-reconnect \
      --default-character-set=utf8mb4 --database="$AGENT_DB_NAME" 2>&1
  }
  DB_PID="$PURGE_CONNECTION_PID"
  exec {DB_READ}<&"${PURGE_CONNECTION[0]}"
  exec {DB_WRITE}>&"${PURGE_CONNECTION[1]}"
}

close_db() {
  if [ -n "$DB_WRITE" ]; then
    printf 'ROLLBACK;\nquit\n' >&"$DB_WRITE" 2>/dev/null || true
    exec {DB_WRITE}>&-
    DB_WRITE=""
  fi
  # 中断时继续排空返回，避免大型材料列表堵住管道，导致 ROLLBACK/quit 无法完成。
  local drain_pid=""
  if [ -n "$DB_READ" ]; then cat <&"$DB_READ" >/dev/null 2>&1 & drain_pid="$!"; fi
  if [ -n "$DB_PID" ]; then wait "$DB_PID" 2>/dev/null || true; DB_PID=""; fi
  if [ -n "$drain_pid" ]; then wait "$drain_pid" 2>/dev/null || true; fi
  if [ -n "$DB_READ" ]; then exec {DB_READ}<&-; DB_READ=""; fi
}

db_query() {
  local sql="$1" line marker="purge_end_${BASHPID}_${RANDOM}_${RANDOM}"
  DB_ROWS=(); DB_ROW_COUNT=0
  printf '%s;\nSELECT '\''%s'\'';\n' "$sql" "$marker" >&"$DB_WRITE" || die "MySQL 会话写入失败；未提交事务将回滚"
  while IFS= read -r -u "$DB_READ" line; do
    line="${line%$'\r'}"
    [ "$line" != "$marker" ] || return 0
    case "$line" in
      ERROR*) die "MySQL 执行失败；停止清理，当前批次未提交（错误 SQL 不回显）" ;;
      mysql:*|Warning:*) continue ;;
    esac
    DB_ROWS+=("$line"); DB_ROW_COUNT=$((DB_ROW_COUNT + 1))
  done
  die "MySQL 会话断开；当前批次未提交，已提交批次不会恢复"
}

scalar() {
  db_query "$1"
  [ "$DB_ROW_COUNT" = 1 ] || die "MySQL 计数返回异常"
  VALUE="${DB_ROWS[0]}"
  [[ "$VALUE" =~ ^[0-9]+$ ]] || die "MySQL 计数格式异常"
}

check_schema() {
  local tables="'agent_schema_identity','agent_schema_history','project','model_config','model_validation','prompt','prompt_version','review_strategy','review_task','task_target','execution_unit','task_quota','model_call','material','call_material','review_result','issue','review_result_item','feedback','report','report_source','agent_run','agent_tool_call'"
  scalar "SELECT VERSION() LIKE '8.%'"; [ "$VALUE" = 1 ] || die "脚本仅支持 MySQL 8"
  scalar "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"
  [ "$VALUE" = 23 ] || die "目标表数量与脚本不匹配"
  scalar "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ($tables) AND engine='InnoDB'"
  [ "$VALUE" = 23 ] || die "目标表集合或引擎不受支持"
  scalar "SELECT COUNT(*) FROM agent_schema_identity WHERE schema_name='ai_code_review_agent' AND schema_version='1'"
  [ "$VALUE" = 1 ] || die "目标不是受支持的新路线数据库"
  scalar "SELECT COUNT(*) FROM agent_schema_identity"; [ "$VALUE" = 1 ] || die "库身份标记异常"
  scalar "SELECT COUNT(*) FROM agent_schema_history WHERE version='10' AND success=1"
  [ "$VALUE" = 1 ] || die "请先按正常升级流程应用 V10；脚本不执行迁移"
  scalar "SELECT COUNT(*) FROM agent_schema_history WHERE success=0 OR CAST(version AS UNSIGNED)>10"
  [ "$VALUE" = 0 ] || die "迁移失败或版本超过脚本支持范围"
  scalar "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN ('review_task','report') AND column_name IN ('is_deleted','deleted_at')"
  [ "$VALUE" = 4 ] || die "删除字段不完整"
  scalar "SELECT COUNT(*) FROM model_call WHERE task_id IS NOT NULL AND report_id IS NOT NULL"
  [ "$VALUE" = 0 ] || die "发现双重归属调用，须先核对"
}

check_idle() {
  scalar "SELECT (SELECT COUNT(*) FROM review_task WHERE state NOT IN ('SUCCEEDED','PARTIAL','FAILED','CANCELLED')) + (SELECT COUNT(*) FROM report WHERE state IN ('QUEUED','RUNNING')) + (SELECT COUNT(*) FROM model_call WHERE state NOT IN ('SUCCEEDED','FAILED')) + (SELECT COUNT(*) FROM agent_tool_call WHERE finished_at IS NULL)"
  [ "$VALUE" = 0 ] || die "存在在途审查、报告、模型或工具调用，拒绝物理删除"
}

# JSON 验证在容器内 MySQL 执行，不依赖宿主机 JSON 解析工具。
task_blocker() {
  local id="$1" doc="IF(JSON_VALID(s.source_snapshot),s.source_snapshot,JSON_OBJECT())"
  local results="SELECT id FROM review_result WHERE task_id='$id'"
  BLOCKER=""
  scalar "SELECT COUNT(*) FROM report_source s WHERE s.result_id IN ($results) AND (
    NOT JSON_VALID(s.source_snapshot)
    OR COALESCE(JSON_UNQUOTE(JSON_EXTRACT($doc,'$.resultId')),'')<>s.result_id
    OR COALESCE(JSON_UNQUOTE(JSON_EXTRACT($doc,'$.snapshotVersion')),'1') NOT IN ('1','2')
    OR COALESCE(JSON_TYPE(JSON_EXTRACT($doc,'$.result.body')),'NULL') NOT IN ('OBJECT','STRING')
    OR COALESCE(JSON_UNQUOTE(JSON_EXTRACT($doc,'$.result.body')),'') IN ('','null')
    OR (JSON_TYPE(JSON_EXTRACT($doc,'$.result.body'))='OBJECT' AND JSON_LENGTH(JSON_EXTRACT($doc,'$.result.body'))=0)
    OR COALESCE(JSON_TYPE(JSON_EXTRACT($doc,'$.result.provenance')),'NULL')<>'OBJECT'
    OR COALESCE(JSON_TYPE(JSON_EXTRACT($doc,'$.result.provenance.materials')),'NULL')<>'ARRAY'
    OR COALESCE(JSON_TYPE(JSON_EXTRACT($doc,'$.materials')),'NULL')<>'ARRAY'
    OR COALESCE(JSON_UNQUOTE(JSON_EXTRACT($doc,'$.taskId')),JSON_UNQUOTE(JSON_EXTRACT($doc,'$.task.id')),'')<>'$id'
    OR EXISTS (SELECT 1 FROM JSON_TABLE($doc,'$.materials[*]' COLUMNS(
         mid VARCHAR(128) PATH '$.id' NULL ON ERROR, body LONGTEXT PATH '$.content' NULL ON ERROR)) m
         WHERE m.mid IS NULL OR m.mid NOT REGEXP '^[0-9A-HJKMNP-TV-Z]{26}$' OR m.body IS NULL)
    OR EXISTS (SELECT 1 FROM JSON_TABLE($doc,'$.result.provenance.materials[*]' COLUMNS(
         mid VARCHAR(128) PATH '$.id' NULL ON ERROR, legacy VARCHAR(128) PATH '$.materialId' NULL ON ERROR)) r
         WHERE COALESCE(r.mid,r.legacy) IS NULL OR NOT EXISTS (
           SELECT 1 FROM JSON_TABLE($doc,'$.materials[*]' COLUMNS(mid VARCHAR(128) PATH '$.id' NULL ON ERROR)) m
           WHERE m.mid=COALESCE(r.mid,r.legacy))))"
  if [ "$VALUE" != 0 ]; then BLOCKER="报告来源快照不完整或格式不受支持"; return; fi
  scalar "SELECT COUNT(*) FROM review_task WHERE id<>'$id' AND input_snapshot IS NOT NULL AND (
    JSON_TYPE(input_snapshot)<>'OBJECT' OR (JSON_UNQUOTE(JSON_EXTRACT(input_snapshot,'$.reference.resultId')) IN ($results)
    AND (COALESCE(JSON_TYPE(JSON_EXTRACT(input_snapshot,'$.reference.body')),'NULL') NOT IN ('OBJECT','STRING')
     OR COALESCE(JSON_UNQUOTE(JSON_EXTRACT(input_snapshot,'$.reference.body')),'') IN ('','null')
     OR (JSON_TYPE(JSON_EXTRACT(input_snapshot,'$.reference.body'))='OBJECT' AND JSON_LENGTH(JSON_EXTRACT(input_snapshot,'$.reference.body'))=0))))"
  [ "$VALUE" = 0 ] || BLOCKER="其他任务输入或参考正文不完整"
}

resource_blocker() {
  local table="$1" id="$2" direct=0
  BLOCKER=""
  case "$table" in
    project) scalar "SELECT COUNT(*) FROM review_task WHERE project_id='$id'"; direct="$VALUE" ;;
    prompt) scalar "SELECT COUNT(*) FROM review_strategy WHERE prompt_id='$id'"; direct="$VALUE" ;;
    model_config) scalar "SELECT COUNT(*) FROM review_strategy WHERE model_config_id='$id'"; direct="$VALUE" ;;
  esac
  if [ "$direct" != 0 ]; then BLOCKER="仍有保留记录引用"; return; fi
  scalar "SELECT (SELECT COUNT(*) FROM review_task WHERE LOCATE('$id',CAST(input_snapshot AS CHAR))>0 OR LOCATE('$id',CAST(config_snapshot AS CHAR))>0) + (SELECT COUNT(*) FROM report WHERE LOCATE('$id',CAST(model_snapshot AS CHAR))>0 OR LOCATE('$id',instruction_snapshot)>0 OR LOCATE('$id',frozen_input)>0) + (SELECT COUNT(*) FROM report_source WHERE LOCATE('$id',source_snapshot)>0)"
  if [ "$VALUE" != 0 ]; then BLOCKER="仍被历史快照引用，保守保留"; return; fi
  if [ "$table" = project ]; then
    scalar "SELECT COUNT(*) FROM material WHERE project_id='$id'"
    [ "$VALUE" = 0 ] || BLOCKER="仍有保留源码材料"
  fi
}

add_plan() { PLAN_TABLES+=("$1"); PLAN_WHERE+=("$2"); }
make_plan() {
  local table="$1" id="$2" results calls targets
  PLAN_TABLES=(); PLAN_WHERE=()
  if [ "$table" = review_task ]; then
    results="SELECT id FROM review_result WHERE task_id='$id'"
    calls="SELECT id FROM model_call WHERE task_id='$id'"
    targets="SELECT id FROM task_target WHERE task_id='$id'"
    add_plan feedback "(subject_kind='ISSUE' AND subject_id IN (SELECT id FROM issue WHERE result_id IN ($results))) OR (subject_kind='RESULT' AND subject_id IN ($results)) OR (subject_kind IN ('PATTERN','COUPLING') AND subject_id IN (SELECT id FROM review_result_item WHERE result_id IN ($results)))"
    add_plan issue "result_id IN ($results)"
    add_plan review_result_item "result_id IN ($results)"
    add_plan call_material "call_id IN ($calls)"
    add_plan agent_tool_call "task_id='$id'"
    add_plan agent_run "task_id='$id'"
    add_plan execution_unit "target_id IN ($targets)"
    add_plan review_result "task_id='$id'"
    add_plan model_call "task_id='$id'"
    add_plan task_target "task_id='$id'"
    add_plan task_quota "task_id='$id'"
  elif [ "$table" = report ]; then
    add_plan call_material "call_id IN (SELECT id FROM model_call WHERE report_id='$id')"
    add_plan model_call "report_id='$id'"
    add_plan report_source "report_id='$id'"
  elif [ "$table" = prompt ]; then
    add_plan prompt_version "prompt_id='$id'"
  elif [ "$table" = model_config ]; then
    add_plan model_validation "model_config_id='$id'"
  fi
  add_plan "$table" "id='$id' AND is_deleted=1"
}

collect_materials() {
  local id="$1"
  MATERIALS=(); MATERIAL_COUNT=0
  scalar "SELECT (SELECT COUNT(*) FROM review_result WHERE task_id='$id' AND COALESCE(JSON_TYPE(provenance),'NULL')<>'OBJECT') + (SELECT COUNT(*) FROM agent_tool_call WHERE task_id='$id' AND material_ids IS NOT NULL AND JSON_TYPE(material_ids)<>'ARRAY')"
  [ "$VALUE" = 0 ] || return 0
  db_query "SELECT m.id FROM material m WHERE EXISTS(SELECT 1 FROM call_material cm JOIN model_call c ON c.id=cm.call_id WHERE c.task_id='$id' AND cm.material_id=m.id) OR EXISTS(SELECT 1 FROM agent_tool_call t WHERE t.task_id='$id' AND LOCATE(m.id,CAST(t.material_ids AS CHAR))>0) OR EXISTS(SELECT 1 FROM review_result r WHERE r.task_id='$id' AND LOCATE(m.id,CAST(r.provenance AS CHAR))>0) ORDER BY m.id"
  MATERIAL_COUNT="$DB_ROW_COUNT"
  MATERIALS=(${DB_ROWS[@]+"${DB_ROWS[@]}"})
}

material_references() {
  local id="$1"
  scalar "SELECT (SELECT COUNT(*) FROM call_material WHERE material_id='$id') + (SELECT COUNT(*) FROM agent_tool_call WHERE LOCATE('$id',CAST(material_ids AS CHAR))>0 OR LOCATE('$id',envelope)>0) + (SELECT COUNT(*) FROM review_result WHERE LOCATE('$id',CAST(provenance AS CHAR))>0 OR LOCATE('$id',body)>0) + (SELECT COUNT(*) FROM report_source WHERE LOCATE('$id',source_snapshot)>0) + (SELECT COUNT(*) FROM review_task WHERE LOCATE('$id',CAST(input_snapshot AS CHAR))>0 OR LOCATE('$id',CAST(config_snapshot AS CHAR))>0)"
}

content_bytes() {
  local table="$1" condition="$2" expr
  case "$table" in
    model_call) expr="COALESCE(OCTET_LENGTH(request_body),0)+COALESCE(OCTET_LENGTH(response_body),0)" ;;
    agent_tool_call) expr="COALESCE(OCTET_LENGTH(arguments),0)+COALESCE(OCTET_LENGTH(envelope),0)" ;;
    review_result) expr="OCTET_LENGTH(body)+COALESCE(OCTET_LENGTH(provenance),0)" ;;
    review_task) expr="COALESCE(OCTET_LENGTH(input_snapshot),0)+COALESCE(OCTET_LENGTH(config_snapshot),0)" ;;
    report) expr="COALESCE(OCTET_LENGTH(body),0)+COALESCE(OCTET_LENGTH(frozen_input),0)+COALESCE(OCTET_LENGTH(instruction_snapshot),0)" ;;
    report_source) expr="OCTET_LENGTH(source_snapshot)" ;;
    prompt_version) expr="OCTET_LENGTH(content)" ;;
    *) VALUE=0; return ;;
  esac
  scalar "SELECT COALESCE(SUM($expr),0) FROM $table WHERE $condition"
}

process_table() {
  local table="$1" last="" where="is_deleted=1" suffix id i count actual bytes batch_rows root_rows root_bytes mid
  local roots=() materials=() root_kind=0 material_count=0 n_roots=0
  case "$table" in review_task|report) root_kind=1 ;; esac
  if [ -n "$BEFORE" ]; then
    if [ "$root_kind" = 0 ]; then
      scalar "SELECT COUNT(*) FROM $table WHERE is_deleted=1"
      info "$table：保留 $VALUE 条，旧资源没有删除时间"; return
    fi
    where="$where AND deleted_at IS NOT NULL AND deleted_at<'$BEFORE'"
  fi
  while :; do
    suffix=""; BATCH_LOG=(); batch_rows=0
    if [ "$APPLY" = 1 ]; then check_idle; db_query 'START TRANSACTION'; suffix=' FOR UPDATE'; fi
    db_query "SELECT id FROM $table WHERE $where ${last:+AND id>'$last'} ORDER BY id LIMIT $BATCH_SIZE$suffix"
    n_roots="$DB_ROW_COUNT"
    roots=(${DB_ROWS[@]+"${DB_ROWS[@]}"})
    if [ "$n_roots" = 0 ]; then [ "$APPLY" = 0 ] || db_query COMMIT; break; fi
    for id in "${roots[@]}"; do
      valid_id "$id"; BLOCKER=""; materials=(); material_count=0; root_rows=0; root_bytes=0
      if [ "$root_kind" = 1 ]; then
        if [ "$table" = review_task ]; then suffix="'SUCCEEDED','PARTIAL','FAILED','CANCELLED'"; else suffix="'INPUT_FROZEN','SUCCEEDED','FAILED'"; fi
        scalar "SELECT COUNT(*) FROM $table WHERE id='$id' AND is_deleted=1 AND state IN ($suffix)"
        if [ "$VALUE" != 1 ]; then BLOCKER="状态不允许删除"; elif [ "$table" = review_task ]; then task_blocker "$id"; fi
      else resource_blocker "$table" "$id"; fi
      if [ -n "$BLOCKER" ]; then BATCH_LOG+=("保留 $table $id：$BLOCKER"); continue; fi
      if [ "$table" = review_task ]; then collect_materials "$id"; material_count="$MATERIAL_COUNT"; materials=(${MATERIALS[@]+"${MATERIALS[@]}"}); fi
      for mid in ${materials[@]+"${materials[@]}"}; do valid_id "$mid"; done
      make_plan "$table" "$id"
      for ((i=0; i<${#PLAN_TABLES[@]}; i++)); do
        scalar "SELECT COUNT(*) FROM ${PLAN_TABLES[i]} WHERE ${PLAN_WHERE[i]}"; count="$VALUE"
        content_bytes "${PLAN_TABLES[i]}" "${PLAN_WHERE[i]}"; bytes="$VALUE"
        root_rows=$((root_rows + count)); root_bytes=$((root_bytes + bytes))
        BATCH_LOG+=("  ${PLAN_TABLES[i]}：$count 行，主要正文 $bytes 字节")
        if [ "$APPLY" = 1 ] && [ "$count" != 0 ]; then
          scalar "DELETE FROM ${PLAN_TABLES[i]} WHERE ${PLAN_WHERE[i]}; SELECT ROW_COUNT()"; actual="$VALUE"
          [ "$actual" = "$count" ] || die "删除数量变化；当前批次回滚"
        fi
      done
      if [ "$APPLY" = 1 ]; then
        for mid in ${materials[@]+"${materials[@]}"}; do
          material_references "$mid"
          if [ "$VALUE" = 0 ]; then
            scalar "DELETE FROM material WHERE id='$mid'; SELECT ROW_COUNT()"
            root_rows=$((root_rows + VALUE)); BATCH_LOG+=("  material：清理 $VALUE 行")
          else BATCH_LOG+=("  material：保留共享材料 $mid"); fi
        done
      fi
      batch_rows=$((batch_rows + root_rows)); ROOT_COUNT=$((ROOT_COUNT + 1))
      BATCH_LOG+=("$table $id：$root_rows 行，主要正文 $root_bytes 字节，材料候选 $material_count")
    done
    if [ "$APPLY" = 1 ]; then
      db_query COMMIT; COMMITTED_BATCHES=$((COMMITTED_BATCHES + 1))
      info "已提交第 $COMMITTED_BATCHES 批，共 $batch_rows 行"
    else info "预览 $table 本批 $batch_rows 行（共享材料将在执行时重新核对）"; fi
    printf '%s\n' "${BATCH_LOG[@]}"
    last="${roots[n_roots-1]}"
  done
}

main() {
  local result=0 running
  parse_args "$@" || result="$?"
  [ "$result" != 2 ] || return 0
  init_bundle; load_env
  command -v docker >/dev/null 2>&1 || die "找不到现有 Docker 命令"
  AGENT_DB_NAME="${AGENT_DB_NAME:-ai_code_review_agent}"
  valid_sql_identifier "$AGENT_DB_NAME" || die "数据库名称不合法"
  case "$AGENT_DB_NAME" in code_review|mysql|sys|information_schema|performance_schema) die "拒绝清理旧库或系统库" ;; esac
  [ -n "$MYSQL_ROOT_PASSWORD" ] || die ".env 缺少 MYSQL_ROOT_PASSWORD"
  [ "$(container_health codereview-mysql)" = healthy ] || die "数据库容器不是 healthy"
  if [ "$APPLY" = 1 ]; then
    running="$(docker inspect -f '{{.State.Running}}' codereview-app 2>/dev/null || true)"
    [ "$running" != true ] || die "请先按运维流程停止应用容器；脚本不自动停止服务"
  fi
  trap close_db EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  open_db
  db_query "SET SESSION innodb_lock_wait_timeout=10, lock_wait_timeout=10, wait_timeout=60, max_execution_time=60000"
  [ "$APPLY" = 1 ] || db_query 'SET SESSION TRANSACTION READ ONLY'
  check_schema
  if [ "$APPLY" = 1 ]; then
    scalar "SELECT COALESCE(GET_LOCK('codereview-offline-purge',0),0)"
    [ "$VALUE" = 1 ] || die "已有清理脚本运行"
    check_idle
  fi
  has_kind reports && process_table report
  has_kind reviews && process_table review_task
  if has_kind resources; then
    for table in review_strategy prompt model_config project; do process_table "$table"; done
  fi
  if [ "$APPLY" = 1 ]; then ok "清理遍历完成，提交 $COMMITTED_BATCHES 批；请核对保留记录";
  else ok "只读预览完成；预览没有模拟前序删除，实际依赖以执行时为准"; fi
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then main "$@"; fi
