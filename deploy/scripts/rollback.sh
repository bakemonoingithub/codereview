#!/usr/bin/env bash
# =============================================================================
#  回滚到上一版本
#    bash scripts/rollback.sh
#    bash scripts/rollback.sh --yes
# =============================================================================
set -u
. "$(cd "$(dirname "$0")" && pwd)/common.sh"

ASSUME_YES=0
while [ $# -gt 0 ]; do
  case "$1" in
    --yes|-y)  ASSUME_YES=1; shift ;;
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

image_exists codereview-app:previous || die "没有可回滚的版本：codereview-app:previous 不存在（可能从未成功部署过一次，或上一版本已被删除）"

info "当前版本：codereview-app:$(get_env APP_TAG)"
info "将回滚到：codereview-app:previous"

if [ "$(container_health codereview-mysql)" = "healthy" ]; then
  info "回滚前先备份数据库"
  bash scripts/backup.sh || warn "备份失败，继续回滚"
fi

confirm "确认回滚？" || die "已取消"

set_env APP_TAG previous
compose up -d || die "回滚启动失败，请人工介入"

if wait_healthy codereview-app 300; then
  ok "已回滚到 codereview-app:previous"
  IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
  info "访问地址： http://${IP:-<本机IP>}:${APP_PORT:-18080}"
  info "如需把这次回滚固化下来："
  info "  docker tag codereview-app:previous codereview-app:latest"
  info "  sed -i 's/^APP_TAG=.*/APP_TAG=latest/' .env"
else
  die "回滚后仍未健康 —— 请人工介入：$COMPOSE -p $PROJECT logs --tail=200 codereview-app"
fi
