#!/usr/bin/env bash
# =============================================================================
#  回滚到上一版本
#    bash scripts/rollback.sh --tag <明确旧版本> --schema-compatible
#    仅用于已确认旧程序兼容当前数据库和配置的情况，不恢复数据库或 YAML。
# =============================================================================
set -u
. "$(cd "$(dirname "$0")" && pwd)/common.sh"

ASSUME_YES=0
SCHEMA_COMPATIBLE=0
ROLLBACK_TAG=previous
while [ $# -gt 0 ]; do
  case "$1" in
    --yes|-y)  ASSUME_YES=1; shift ;;
    --schema-compatible) SCHEMA_COMPATIBLE=1; shift ;;
    --tag) ROLLBACK_TAG="${2:-}"; [ -n "$ROLLBACK_TAG" ] || die "--tag 缺少版本"; shift 2 ;;
    -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
    *)         die "未知参数：$1" ;;
  esac
done

hr
info "「基于AI的智能代码分析工具」回滚脚本"
hr

init_bundle
load_env
detect_compose

[ "$SCHEMA_COMPATIBLE" = "1" ] || die "此脚本只回退镜像。请先确认旧程序兼容当前数据库和 YAML，再显式加 --schema-compatible；数据库不兼容时按手册恢复备份。"

image_exists "codereview-app:$ROLLBACK_TAG" || die "本机不存在回退镜像 codereview-app:$ROLLBACK_TAG"

info "当前版本：codereview-app:$(get_env APP_TAG)"
info "将回滚到：codereview-app:$ROLLBACK_TAG（不恢复数据库或 YAML）"

[ "$(container_health codereview-mysql)" = "healthy" ] || die "数据库未就绪，停止镜像回退并按手册处理"
info "回滚前先备份数据库"
bash scripts/backup.sh || die "备份失败，停止回滚"

confirm "确认回滚？" || die "已取消"

set_env APP_TAG "$ROLLBACK_TAG"
compose up -d app || die "回滚启动失败，请人工介入"

if wait_healthy codereview-app 300; then
  ok "已回滚到 codereview-app:$ROLLBACK_TAG"
  IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
  info "访问地址： http://${IP:-<本机IP>}:${APP_PORT:-18080}"

else
  die "回滚后仍未健康 —— 请人工介入：$COMPOSE -p $PROJECT logs --tail=200 app"
fi
