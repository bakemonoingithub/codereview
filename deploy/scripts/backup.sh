#!/usr/bin/env bash
# =============================================================================
#  数据库备份（保留 7 天）
#    bash scripts/backup.sh
#
#  建议加入 root 的定时任务（每天 02:00）：
#    0 2 * * * cd /opt/codereview && bash scripts/backup.sh >> backups/backup.log 2>&1
#
#  备份内容：code_review 库的 mysqldump（gzip）+ 当时的 .env 副本
#  注意：.env 里含数据库密码，丢了它就连不上已有的 data/mysql，所以一并留存。
# =============================================================================
set -u
set -o pipefail
. "$(cd "$(dirname "$0")" && pwd)/common.sh"

init_bundle
load_env

[ -n "${MYSQL_ROOT_PASSWORD:-}" ] || die ".env 里没有 MYSQL_ROOT_PASSWORD，无法备份"

STATE="$(container_health codereview-mysql)"
if [ "$STATE" != "healthy" ]; then
  die "codereview-mysql 当前状态为 $STATE，不是 healthy，跳过备份（避免备份出损坏文件）"
fi

mkdir -p backups
TS="$(date +%Y%m%d-%H%M%S)"
DB="${MYSQL_DATABASE:-code_review}"
OUT="backups/${DB}-${TS}.sql.gz"

# 用 MYSQL_PWD 传密码，避免密码出现在 ps 输出里
if ! docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" codereview-mysql \
       mysqldump -uroot --single-transaction --quick --routines --triggers --events \
       --default-character-set=utf8mb4 "$DB" | gzip > "$OUT"; then
  rm -f "$OUT"
  die "mysqldump 执行失败"
fi

[ -s "$OUT" ] || { rm -f "$OUT"; die "备份文件为空：$OUT"; }
ok "备份完成：$OUT（$(du -h "$OUT" | awk '{print $1}')）"

# .env 一并留存（含数据库密码）
if cp -p .env "backups/env-${TS}.txt" 2>/dev/null; then
  chmod 600 "backups/env-${TS}.txt" 2>/dev/null || true
  info "已同时留存 .env 副本：backups/env-${TS}.txt"
fi

# 只清理 backups/ 下超过 7 天的本项目备份（不碰任何其它目录）
DEL=$(find backups -maxdepth 1 -name "${DB}-*.sql.gz" -mtime +7 -print -delete 2>/dev/null | wc -l)
DEL2=$(find backups -maxdepth 1 -name 'env-*.txt' -mtime +7 -print -delete 2>/dev/null | wc -l)
info "已清理超过 7 天的备份：$((DEL + DEL2)) 个"
