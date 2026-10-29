#!/usr/bin/env bash
# 从新包入口升级原部署；Bash 4.2，不新增宿主机或容器运行依赖。
set -euo pipefail
umask 077
UPDATE_SOURCE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
. "$UPDATE_SOURCE/scripts/common.sh"
NEW=""; INSTALL=""; TAG=""; CHECK_ONLY=0; EXPORT_IMAGE=0
LOCKED=0; MAINTENANCE=0; SNAPSHOT=""
usage() {
  cat <<'HELP'
用法：bash <新包>/scripts/update.sh --new <新包目录> --install <原部署目录> --tag <版本> [选项]
  --check-only         只读检查，不导入镜像、停服务、备份或复制文件；新镜像须已在本机
  --export-old-image   将旧镜像额外导出到升级封存目录（默认仅在本机保留）
  --help               显示帮助
必须从 --new 指向的新包运行本脚本。路径须为绝对路径，且不能相同或互相包含。
先暂停新任务提交；存在未完成任务时退出，不自动取消任务。
备份或部署失败均停止；数据库可能已迁移，不自动回退。
HELP
}
while [ "$#" -gt 0 ]; do
  case "$1" in
    --new|--install|--tag)
      [ "$#" -ge 2 ] && [ -n "$2" ] || die "$1 缺少值"
      case "$1" in --new) NEW="$2" ;; --install) INSTALL="$2" ;; --tag) TAG="$2" ;; esac
      shift 2 ;;
    --check-only) CHECK_ONLY=1; shift ;;
    --export-old-image) EXPORT_IMAGE=1; shift ;;
    --help|-h) usage; exit 0 ;;
    *) die "未知参数：$1" ;;
  esac
done
[ -n "$NEW" ] && [ -n "$INSTALL" ] && [ -n "$TAG" ] || die "必须指定 --new、--install 和 --tag"
[[ "$TAG" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]] || die "镜像版本格式无效"
for path in "$NEW" "$INSTALL"; do
  case "$path" in /*) ;; *) die "部署路径必须为绝对路径" ;; esac
done
NEW="$(cd -- "$NEW" && pwd -P)" || die "新包目录不存在"
INSTALL="$(cd -- "$INSTALL" && pwd -P)" || die "原部署目录不存在"
case "$NEW/" in "$INSTALL/"*) die "新包不能位于原部署目录内或与其相同" ;; esac
case "$INSTALL/" in "$NEW/"*) die "原部署目录不能位于新包内" ;; esac
[ "$NEW" = "$UPDATE_SOURCE" ] || die "请运行 --new 指向的新包中的 scripts/update.sh"

cleanup() {
  local result=$?
  trap - EXIT
  if [ "$result" -ne 0 ] && [ "$MAINTENANCE" = 1 ]; then
    docker stop codereview-app >/dev/null 2>&1 || warn "应用停止失败，请人工核对容器状态"
    err "升级未完成，已尝试停止应用；不自动回退。封存目录：${SNAPSHOT:-尚未创建}"
  fi
  if [ "$LOCKED" = 1 ]; then rmdir -- "$INSTALL/.update.lock" || true; fi
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# 只支持既有标准绑定挂载；拒绝覆盖符号链接指向的外部配置或交付文件。
for root in "$NEW" "$INSTALL"; do
  for entry in .env docker-compose.yml config scripts images data backups upgrade-snapshots docs; do
    [ ! -L "$root/$entry" ] || die "$entry 不允许为符号链接"
  done
  for dir in config scripts images docs; do
    if [ "$dir" = docs ] && [ ! -e "$root/$dir" ]; then continue; fi
    [ -d "$root/$dir" ] || die "缺少 $dir 目录：$root"
    [ -z "$(find "$root/$dir" -type l -print -quit)" ] || die "$dir 中存在符号链接，需人工处理"
  done
done
for file in .env config/application.yml docker-compose.yml; do
  [ -f "$INSTALL/$file" ] || die "原部署缺少 $file"
done
for file in docker-compose.yml config/application.yml.example 系统运维手册.md 系统部署手册.md 00-先读我.md \
  scripts/deploy.sh scripts/preflight.sh scripts/backup.sh scripts/common.sh; do
  [ -f "$NEW/$file" ] || die "新包缺少 $file"
  [ ! -L "$INSTALL/$file" ] || die "正式文件 $file 不允许为符号链接"
done
[ -d "$INSTALL/data/mysql" ] && [ ! -L "$INSTALL/data/mysql" ] || die "原 MySQL 数据目录不存在或为符号链接"
[ ! -e "$INSTALL/.update.lock" ] || die "存在升级锁 .update.lock；确认无其他升级后再人工处理"
ARCHIVE="$NEW/images/codereview-app-$TAG.tar.gz"
[ -f "$ARCHIVE" ] || die "新包缺少指定版本镜像：codereview-app-$TAG.tar.gz"
[ -f "$NEW/images/SHA256SUMS.txt" ] || die "新包缺少镜像校验清单"
(cd -- "$NEW/images" && sha256sum -c SHA256SUMS.txt) || die "新包镜像校验失败"

# 数据库容器配置保持原样；数据库结构由应用的增量迁移更新。
mysql_section() {
  awk '/^  mysql: *$/ {emit=1} emit && /^  [A-Za-z0-9_-]+: *$/ && $0 !~ /^  mysql:/ {exit} emit {sub(/#.*/, ""); gsub(/^[ \t]+|[ \t]+$/, ""); if(length) print}' "$1"
}
[ -n "$(mysql_section "$INSTALL/docker-compose.yml")" ] || die "原 Compose 缺少标准 mysql 服务"
[ "$(mysql_section "$NEW/docker-compose.yml")" = "$(mysql_section "$INSTALL/docker-compose.yml")" ] \
  || die "MySQL 容器配置发生变化，需人工评估；自动升级仅处理业务库增量迁移"
app_mounts() {
  awk '/^  app: *$/ {app=1; next} app && /^  [A-Za-z0-9_-]+: *$/ {exit} app && /^    volumes: *$/ {emit=1; next} emit && /^    [A-Za-z0-9_-]+:/ {exit} emit {sub(/#.*/, ""); gsub(/^[ \t]+|[ \t]+$/, ""); if(length) print}' "$1"
}
[ -n "$(app_mounts "$INSTALL/docker-compose.yml")" ] || die "原 Compose 缺少应用挂载"
[ "$(app_mounts "$NEW/docker-compose.yml")" = "$(app_mounts "$INSTALL/docker-compose.yml")" ] || die "应用挂载发生变化，需人工处理"
cd -- "$INSTALL"
BUNDLE="$INSTALL"
load_env || die "读取原部署配置失败"
[ "$AGENT_DB_NAME" = ai_code_review_agent ] || die "仅支持当前新路线 ai_code_review_agent"
[ -n "$MYSQL_ROOT_PASSWORD" ] || die "缺少数据库 root 凭据"
detect_compose
docker info >/dev/null 2>&1 || die "Docker 不可访问"
check_mounts() {
  local mount
  mount="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/var/lib/mysql"}}{{.Type}}:{{.Source}}{{end}}{{end}}' codereview-mysql)"
  [ "$mount" = "bind:$INSTALL/data/mysql" ] || die "MySQL 实际挂载与原部署目录不一致"
  mount="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/app/config/application.yml"}}{{.Type}}:{{.Source}}{{end}}{{end}}' codereview-app)"
  [ "$mount" = "bind:$INSTALL/config/application.yml" ] || die "应用实际配置挂载与原部署目录不一致"
}
check_mounts
[ "$(container_health codereview-mysql)" = healthy ] || die "MySQL 未就绪"
bash "$NEW/scripts/preflight.sh" --install "$INSTALL" || die "新版本环境体检失败"
db_read() {
  MYSQL_PWD="$MYSQL_ROOT_PASSWORD" docker exec -e MYSQL_PWD codereview-mysql mysql -uroot \
    --batch --raw --skip-column-names --database="$AGENT_DB_NAME" -e "$1"
}
check_tasks() {
  local count
  count="$(db_read "SELECT (SELECT COUNT(*) FROM review_task WHERE state NOT IN ('SUCCEEDED','PARTIAL','FAILED','CANCELLED')) + (SELECT COUNT(*) FROM report WHERE state IN ('QUEUED','RUNNING')) + (SELECT COUNT(*) FROM model_call WHERE state NOT IN ('SUCCEEDED','FAILED')) + (SELECT COUNT(*) FROM agent_tool_call WHERE finished_at IS NULL)")"
  [ "$count" = 0 ] || die "存在未完成任务或调用，或查询失败；请先在页面处理"
}
check_tasks
[ "$(db_read "SELECT COUNT(*) FROM agent_schema_identity WHERE schema_name='ai_code_review_agent' AND schema_version='1'")" = 1 ] || die "库身份校验失败"
OLD_ID="$(docker inspect --format '{{.Image}}' codereview-app)"
OLD_REF="$(docker inspect --format '{{.Config.Image}}' codereview-app)"
docker image inspect "$OLD_ID" >/dev/null || die "旧镜像不存在，无法保留回退点"

if [ "$CHECK_ONLY" = 0 ]; then
  mkdir -- "$INSTALL/.update.lock" || die "无法取得升级锁"
  LOCKED=1
  # 仅导入本次指定的应用镜像，不导入或更换 MySQL 镜像。
  docker load -i "$ARCHIVE" || die "新镜像导入失败"
fi
image_exists "codereview-app:$TAG" || die "目标镜像不在本机；--check-only 不导入镜像，请先显式 docker load 后重试"
TARGET_ID="$(docker image inspect --format '{{.Id}}' "codereview-app:$TAG")"
# 使用实际配置和目标镜像检查新增必填项；失败发生在停应用之前。
docker run --rm --network none --memory "${APP_MEM_LIMIT:-4g}" \
  -e "JAVA_TOOL_OPTIONS=${JAVA_OPTS:--Xms1g -Xmx3g}" \
  -v "$INSTALL/config/application.yml:/app/config/application.yml:ro" \
  "codereview-app:$TAG" --check-config || die "新版本配置检查失败，请先手工合并配置"
# 在原目录上下文解析新 Compose；不输出含凭据的展开结果。
APP_TAG="$TAG" $COMPOSE -p "$PROJECT" --project-directory "$INSTALL" --env-file "$INSTALL/.env" \
  -f "$NEW/docker-compose.yml" config >/dev/null || die "新 Compose 配置检查失败"
[ "$CHECK_ONLY" = 0 ] || { ok "只读检查通过；尚未执行备份恢复演练或真实升级"; exit 0; }

# 防止检查期间新任务进入；停应用后再查一次，有在途任务则保留停止状态退出。
check_tasks
MAINTENANCE=1
compose stop app || die "停止应用失败"
check_tasks
SNAPSHOT="$INSTALL/upgrade-snapshots/$(date +%Y%m%d-%H%M%S)-$$"
mkdir -p -- "$SNAPSHOT"
chmod 700 "$SNAPSHOT"
cp -p .env docker-compose.yml "$SNAPSHOT/"
cp -p config/application.yml "$SNAPSHOT/application.yml"
cp -a scripts "$SNAPSHOT/scripts"
printf '%s\n' "$OLD_REF $OLD_ID" > "$SNAPSHOT/app-image.txt"
printf '%s\n' "codereview-app:$TAG $TARGET_ID" > "$SNAPSHOT/target-image.txt"
OLD_PIN="codereview-app:upgrade-backup-$(date +%Y%m%d-%H%M%S)-$$"
docker tag "$OLD_ID" "$OLD_PIN" || die "旧镜像保留失败"
printf '%s\n' "$OLD_PIN" > "$SNAPSHOT/old-image-tag.txt"
bash "$NEW/scripts/backup.sh" --install "$INSTALL" --output-dir "$SNAPSHOT/database" || die "数据库备份失败"
shopt -s nullglob
dumps=("$SNAPSHOT/database/$AGENT_DB_NAME-"*.sql.gz)
[ "${#dumps[@]}" -eq 1 ] || die "本次业务数据库备份缺失或不唯一"
gzip -t "$SNAPSHOT"/database/*.sql.gz || die "数据库备份压缩文件损坏"
if [ "$EXPORT_IMAGE" = 1 ]; then
  docker image save "$OLD_PIN" | gzip > "$SNAPSHOT/old-app-image.tar.gz" || die "旧镜像导出失败"
  gzip -t "$SNAPSHOT/old-app-image.tar.gz" || die "旧镜像压缩文件损坏"
fi
(cd -- "$SNAPSHOT" && sha256sum database/*.sql.gz > SHA256SUMS.txt)
if [ "$EXPORT_IMAGE" = 1 ]; then (cd -- "$SNAPSHOT" && sha256sum old-app-image.tar.gz >> SHA256SUMS.txt); fi
COUNTS_SQL="SELECT (SELECT COUNT(*) FROM project),(SELECT COUNT(*) FROM model_config),(SELECT COUNT(*) FROM prompt),(SELECT COUNT(*) FROM review_task),(SELECT COUNT(*) FROM report)"
BEFORE_COUNTS="$(db_read "$COUNTS_SQL")"
printf '%s\n' "$BEFORE_COUNTS" > "$SNAPSHOT/business-counts.tsv"

# 只更新交付文件，不复制 .env、实际 YAML、data/ 和备份；不覆盖正在运行的本脚本。
cp -a "$NEW/images/." "$INSTALL/images/"
cp -a "$NEW/scripts/." "$INSTALL/scripts/"
cp -p "$NEW/docker-compose.yml" "$INSTALL/docker-compose.yml"
cp -p "$NEW/config/application.yml.example" "$INSTALL/config/application.yml.example"
cp -p "$NEW/系统部署手册.md" "$NEW/系统运维手册.md" "$NEW/00-先读我.md" "$INSTALL/"
if [ -d "$NEW/docs" ]; then
  [ ! -L "$INSTALL/docs" ] || die "正式 docs 目录不允许为符号链接"
  mkdir -p "$INSTALL/docs"
  [ -z "$(find "$INSTALL/docs" -type l -print -quit)" ] || die "正式 docs 中存在符号链接"
  cp -a "$NEW/docs/." "$INSTALL/docs/"
fi
# deploy.sh 只导入目标镜像，禁止指定标签缺失时自动选择其他版本。
bash "$INSTALL/scripts/deploy.sh" --tag "$TAG" --yes --exact-tag || die "部署失败"
[ "$(docker inspect --format '{{.Image}}' codereview-app)" = "$TARGET_ID" ] || die "运行镜像与目标版本不一致"
check_mounts
cmp -s "$SNAPSHOT/application.yml" "$INSTALL/config/application.yml" || die "实际 YAML 意外变化"
docker exec codereview-app curl -fsS -o /dev/null http://127.0.0.1:8080/api/v2/health || die "业务健康检查失败"
[ "$(db_read 'SELECT COUNT(*) FROM agent_schema_history WHERE success=0')" = 0 ] || die "数据库迁移存在失败记录"
[ "$(db_read "$COUNTS_SQL")" = "$BEFORE_COUNTS" ] || die "关键业务表记录数发生变化，请人工核对"
MAINTENANCE=0
ok "升级完成：codereview-app:$TAG；封存目录：$SNAPSHOT"
info "请核对历史记录并完成一次审查、反馈和报告流程，再恢复使用。备份尚未经过独立恢复验证。"
