#!/usr/bin/env bash
# =============================================================================
#  数据库备份（保留 7 天）
#    bash scripts/backup.sh
#
#  建议加入 root 的定时任务（每天 02:00）：
#    0 2 * * * cd /opt/codereview && bash scripts/backup.sh >> backups/backup.log 2>&1
#
#  备份内容：兼容库与 Agent 新库的 mysqldump（gzip）+ 当时的 .env 副本
#  注意：.env 里含数据库密码，丢了它就连不上已有的 data/mysql，所以一并留存。
# =============================================================================
set -u
set -o pipefail
umask 077
. "$(cd "$(dirname "$0")" && pwd)/common.sh"

BACKUP_INSTALL=""; BACKUP_OUTPUT=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --install|--output-dir)
      [ "$#" -ge 2 ] && [ -n "$2" ] || die "$1 缺少路径"
      case "$1" in --install) BACKUP_INSTALL="$2" ;; --output-dir) BACKUP_OUTPUT="$2" ;; esac
      shift 2 ;;
    --help|-h)
      printf '%s\n' '用法：bash scripts/backup.sh [--install 原部署目录] [--output-dir 独立备份目录]' \
        '默认使用脚本所属部署目录的 backups/，保留 7 天；指定独立目录时不执行过期清理。'
      exit 0 ;;
    *) die "未知参数：$1" ;;
  esac
done
if [ -n "$BACKUP_INSTALL" ]; then
  BUNDLE="$(cd -- "$BACKUP_INSTALL" && pwd -P)" || die "原部署目录不存在"
  cd -- "$BUNDLE" || die "无法进入原部署目录"
  [ -f docker-compose.yml ] || die "原部署目录缺少 docker-compose.yml"
else
  init_bundle
fi
load_env

[ -n "${MYSQL_ROOT_PASSWORD:-}" ] || die ".env 里没有 MYSQL_ROOT_PASSWORD，无法备份"

STATE="$(container_health codereview-mysql)"
if [ "$STATE" != "healthy" ]; then
  die "codereview-mysql 当前状态为 $STATE，不是 healthy，跳过备份（避免备份出损坏文件）"
fi

if [ -n "$BACKUP_OUTPUT" ]; then
  case "$BACKUP_OUTPUT" in /*) ;; *) die "--output-dir 必须使用绝对路径" ;; esac
  BACKUP_DIR="$BACKUP_OUTPUT"
else
  BACKUP_DIR="$BUNDLE/backups"
fi
mkdir -p -- "$BACKUP_DIR" || die "无法创建备份目录"
TS="$(date +%Y%m%d-%H%M%S)"
DBS="${MYSQL_DATABASE:-code_review} ${AGENT_DB_NAME:-ai_code_review_agent}"
for DB in $DBS; do
  valid_sql_identifier "$DB" || die "数据库名包含不支持的字符，无法安全备份：$DB"
  EXISTS=$(docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" codereview-mysql \
    mysql -uroot --batch --skip-column-names -e \
    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$DB'") \
    || die "无法确认数据库 $DB 是否存在"
  if [ "$EXISTS" != "1" ]; then
    [ "$DB" != "${AGENT_DB_NAME:-ai_code_review_agent}" ] || die "业务数据库 $DB 不存在，停止备份"
    info "兼容库 $DB 不存在，跳过"
    continue
  fi
  OUT="$BACKUP_DIR/${DB}-${TS}.sql.gz"
  # 用 MYSQL_PWD 传密码，避免密码出现在 ps 输出里。
  if ! docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" codereview-mysql \
         mysqldump -uroot --single-transaction --quick --routines --triggers --events \
         --default-character-set=utf8mb4 "$DB" | gzip > "$OUT"; then
    rm -f "$OUT"
    die "数据库 $DB 的 mysqldump 执行失败"
  fi
  [ -s "$OUT" ] || { rm -f "$OUT"; die "备份文件为空：$OUT"; }
  ok "备份完成：$OUT（$(du -h "$OUT" | awk '{print $1}')）"
done

# .env 一并留存（含数据库密码）
cp -p .env "$BACKUP_DIR/env-${TS}.txt" || die "无法备份 .env"
chmod 600 "$BACKUP_DIR/env-${TS}.txt" || die "无法保护 .env 备份权限"
info "已同时留存 .env 副本：$BACKUP_DIR/env-${TS}.txt"

# 升级封存目录独立保留，不参与日常 7 天清理。
[ -z "$BACKUP_OUTPUT" ] || exit 0

# 只清理 backups/ 下超过 7 天的本项目备份（不碰任何其它目录）
DEL=$(find backups -maxdepth 1 \( -name "${MYSQL_DATABASE:-code_review}-*.sql.gz" -o -name "${AGENT_DB_NAME:-ai_code_review_agent}-*.sql.gz" \) -mtime +7 -print -delete 2>/dev/null | wc -l)
DEL2=$(find backups -maxdepth 1 -name 'env-*.txt' -mtime +7 -print -delete 2>/dev/null | wc -l)
info "已清理超过 7 天的备份：$((DEL + DEL2)) 个"
