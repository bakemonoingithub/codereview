#!/usr/bin/env bash
# =============================================================================
#  一键部署 / 升级
#
#    bash scripts/deploy.sh                      自动识别 images/ 里的镜像包
#    bash scripts/deploy.sh --tag 20261022-a1b2c3d   指定版本
#    bash scripts/deploy.sh --yes                不交互确认（用于自动化）
#
#  流程：初始化工作区 → 体检 → 装载镜像 → 记录上一版本 → 备份数据库
#        → 启动 → 等健康 → 失败自动回滚
# =============================================================================
set -u
. "$(cd "$(dirname "$0")" && pwd)/common.sh"

TAG=""
ASSUME_YES=0
while [ $# -gt 0 ]; do
  case "$1" in
    --tag)     TAG="${2:-}"; shift 2 ;;
    --yes|-y)  ASSUME_YES=1; shift ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *)         die "未知参数：$1（可用：--tag <版本> / --yes）" ;;
  esac
done

GEN_PWD=0

hr
info "「基于AI的智能代码分析工具」部署脚本"
hr

init_bundle
info "部署包目录：$BUNDLE"

# ── 步骤 1：工作区初始化 ────────────────────────────────────────────────────
mkdir -p data/mysql data/git-mirrors backups config

# Gitea 本地镜像目录必须能被容器内的 uid 1000 写入
if chown -R 1000:1000 data/git-mirrors 2>/dev/null; then
  chmod 755 data/git-mirrors 2>/dev/null || true
else
  warn "无法把 data/git-mirrors 的属主改为 1000（当前用户不是 root？），将按 777 处理"
  chmod 777 data/git-mirrors 2>/dev/null || true
fi

if [ ! -f .env ]; then
  info "未找到 .env —— 按 .env.example 生成，并自动创建数据库密码"
  cp .env.example .env || die "无法从 .env.example 生成 .env"
  set_env MYSQL_ROOT_PASSWORD "$(gen_password)"
  set_env MYSQL_PASSWORD "$(gen_password)"
  GEN_PWD=1
fi

# 兼容已有部署包生成的 .env：补齐 Agent 新库参数，不覆盖已有配置。
_agent_db_name="$(get_env AGENT_DB_NAME)"; [ -n "$_agent_db_name" ] || _agent_db_name=ai_code_review_agent
_agent_db_user="$(get_env AGENT_DB_USER)"; [ -n "$_agent_db_user" ] || _agent_db_user=codereview_agent
_agent_db_pass="$(get_env AGENT_DB_PASSWORD)"
set_env AGENT_DB_NAME "$_agent_db_name"
set_env AGENT_DB_USER "$_agent_db_user"
if [ -z "$_agent_db_pass" ]; then
  set_env AGENT_DB_PASSWORD "$(gen_password)"
  GEN_PWD=1
fi
chmod 600 .env 2>/dev/null || true
load_env
detect_compose

valid_sql_identifier "$AGENT_DB_NAME" || die "AGENT_DB_NAME 只允许字母、数字和下划线"
valid_sql_identifier "$AGENT_DB_USER" || die "AGENT_DB_USER 只允许字母、数字和下划线"
[ "$AGENT_DB_NAME" = "ai_code_review_agent" ] || die "AGENT_DB_NAME 必须为 ai_code_review_agent"
[ "$AGENT_DB_NAME" != "${MYSQL_DATABASE:-code_review}" ] || die "Agent 新库不能与兼容库使用同一个 schema"
[ -n "$AGENT_DB_PASSWORD" ] || die "AGENT_DB_PASSWORD 不能为空"
printf '%s' "$AGENT_DB_PASSWORD" | grep -qE '^[A-Za-z0-9]+$' \
  || die "AGENT_DB_PASSWORD 只允许字母和数字，以确保配置模板可安全渲染"

# ── 步骤 2：渲染运行期配置（只做一次，之后升级不再覆盖）────────────────────
if [ ! -f config/application.yml ]; then
  info "生成 config/application.yml（配置兼容库、Agent 新库与 AI 网关）"
  cp config/application.yml.example config/application.yml || die "无法生成 config/application.yml"

  _db_user="$(get_env MYSQL_USER)";      [ -n "$_db_user" ]  || _db_user=codereview
  _db_pass="$(get_env MYSQL_PASSWORD)"
  _db_name="$(get_env MYSQL_DATABASE)";  [ -n "$_db_name" ]  || _db_name=code_review
  _ai_base="$(get_env AI_BASE_URL)";     [ -n "$_ai_base" ]  || _ai_base=http://CHANGE-ME-内网AI网关地址
  _ai_model="$(get_env AI_MODEL)";       [ -n "$_ai_model" ] || _ai_model=deepseek-chat

  sed -i \
    -e "s|__DB_USER__|${_db_user}|g" \
    -e "s|__DB_PASSWORD__|${_db_pass}|g" \
    -e "s|__DB_NAME__|${_db_name}|g" \
    -e "s|__AGENT_DB_USER__|${AGENT_DB_USER}|g" \
    -e "s|__AGENT_DB_PASSWORD__|${AGENT_DB_PASSWORD}|g" \
    -e "s|__AGENT_DB_NAME__|${AGENT_DB_NAME}|g" \
    -e "s|__AI_BASE_URL__|${_ai_base}|g" \
    -e "s|__AI_MODEL__|${_ai_model}|g" \
    config/application.yml

  # 容器里以 uid 1000 运行，必须能读到这个文件；优先收窄属主而不是放开权限
  if chown 1000:1000 config/application.yml 2>/dev/null; then
    chmod 600 config/application.yml
  else
    warn "无法把 config/application.yml 的属主改为 1000，改为 644（容器需要读取）"
    chmod 644 config/application.yml
  fi
  ok "已生成 config/application.yml"
else
  # 旧部署包的配置没有 agent.datasource。只补这段新库配置，不覆盖现有运行参数。
  if ! awk '/^agent:[[:space:]]*$/ { in_agent=1; next } /^[^[:space:]#]/ { in_agent=0 } in_agent && /^  datasource:[[:space:]]*$/ { found=1 } END { exit !found }' config/application.yml; then
    if grep -qE '^agent:[[:space:]]*$' config/application.yml; then
      die "config/application.yml 已有 agent 配置但没有 agent.datasource，请手工合并模板中的 Agent 数据库段"
    fi
    info "现有 config/application.yml 缺少 Agent 新库配置 —— 补入配置模板"
    _agent_tmp="$(mktemp)" || die "无法创建 Agent 配置临时文件"
    sed -n '/^agent:[[:space:]]*$/,$p' config/application.yml.example \
      | sed -e "s|__AGENT_DB_USER__|${AGENT_DB_USER}|g" \
            -e "s|__AGENT_DB_PASSWORD__|${AGENT_DB_PASSWORD}|g" \
            -e "s|__AGENT_DB_NAME__|${AGENT_DB_NAME}|g" > "$_agent_tmp"
    [ -s "$_agent_tmp" ] || { rm -f "$_agent_tmp"; die "模板中缺少 agent.datasource 配置段"; }
    printf '\n' >> config/application.yml
    cat "$_agent_tmp" >> config/application.yml
    rm -f "$_agent_tmp"
    if chown 1000:1000 config/application.yml 2>/dev/null; then
      chmod 600 config/application.yml
    else
      chmod 644 config/application.yml 2>/dev/null || true
    fi
  else
    info "config/application.yml 已含 agent.datasource —— 保持现有配置"
  fi
fi

# ── 步骤 3：前置体检 ────────────────────────────────────────────────────────
hr
if ! bash scripts/preflight.sh; then
  die "前置体检未通过，已中止（未做任何改动）"
fi
hr

# ── 步骤 4：装载镜像包 ──────────────────────────────────────────────────────
LOADED=0
for t in images/*.tar.gz images/*.tar; do
  [ -f "$t" ] || continue
  info "装载镜像：$t"
  docker load -i "$t" || die "镜像装载失败：$t"
  LOADED=$((LOADED + 1))
done
[ "$LOADED" -gt 0 ] && ok "共装载 $LOADED 个镜像包" || info "images/ 下没有镜像包，沿用本机已有镜像"

# ── 步骤 5：确定要部署的版本，并记录上一版本 ────────────────────────────────
if [ -n "$TAG" ] && image_exists "codereview-app:$TAG"; then
  NEW_TAG="$TAG"
else
  if [ -n "$TAG" ]; then
    warn "指定的版本 codereview-app:$TAG 在本机不存在，改为自动识别"
  fi
  # 取最新的那个镜像包（images/ 下同时存在多个版本时不猜）
  _cand="$(ls -1t images/codereview-app-*.tar.gz images/codereview-app-*.tar 2>/dev/null | head -1)"
  if [ -n "$_cand" ]; then
    _cnt="$(ls -1 images/codereview-app-*.tar.gz images/codereview-app-*.tar 2>/dev/null | wc -l | tr -d ' ')"
    [ "$_cnt" -gt 1 ] && warn "images/ 下有 $_cnt 个应用镜像包，将使用最新的：$(basename "$_cand")"
    _cand="$(basename "$_cand")"
    _cand="${_cand#codereview-app-}"
    _cand="${_cand%.tar.gz}"
    _cand="${_cand%.tar}"
  fi
  if [ -n "$_cand" ] && image_exists "codereview-app:$_cand"; then
    NEW_TAG="$_cand"
  elif image_exists "codereview-app:latest"; then
    NEW_TAG="latest"
    warn "没有找到新的镜像包，沿用本机现有的 codereview-app:latest"
  else
    die "找不到可用的应用镜像。请确认 images/ 目录下有 codereview-app-<版本>.tar.gz"
  fi
fi
info "本次部署版本：codereview-app:$NEW_TAG"

if image_exists "codereview-app:latest"; then
  if [ "$NEW_TAG" = "latest" ]; then
    warn "本次沿用 latest，没有新的回滚点（回滚将是空操作）"
  else
    docker tag codereview-app:latest codereview-app:previous \
      && ok "已记录上一版本为 codereview-app:previous（回滚用）"
  fi
fi
docker tag "codereview-app:$NEW_TAG" codereview-app:latest || die "为版本打 latest 标签失败"
set_env APP_TAG "$NEW_TAG"

# ── 步骤 6：修正 docker 网段 ────────────────────────────────────────────────
_cur_subnet="$(get_env CR_SUBNET)"
_free_subnet="$(pick_free_subnet)"
if [ -z "$_free_subnet" ]; then
  warn "172.17~172.31 网段全部被占用，交给 Docker 自动分配"
elif [ "$_cur_subnet" != "$_free_subnet" ]; then
  info "网段调整：${_cur_subnet:-（空）} -> $_free_subnet（避开本机已有容器网络）"
  set_env CR_SUBNET "$_free_subnet"
fi

# ── 步骤 7：启动数据库并确保 Agent 新库存在 ─────────────────────────────────
compose up -d mysql || die "MySQL 容器启动失败"
if ! wait_healthy codereview-mysql 180; then
  compose logs --tail=80 mysql || true
  die "MySQL 未能在 3 分钟内变为 healthy"
fi

# 密码按 SQL 字符串转义；数据库名和账号名已通过标识符白名单校验。
_agent_pass_sql="$(printf '%s' "$AGENT_DB_PASSWORD" | sed "s/'/''/g")"
_agent_sql="CREATE DATABASE IF NOT EXISTS \`$AGENT_DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS '$AGENT_DB_USER'@'%' IDENTIFIED BY '$_agent_pass_sql';
ALTER USER '$AGENT_DB_USER'@'%' IDENTIFIED BY '$_agent_pass_sql';
GRANT ALL PRIVILEGES ON \`$AGENT_DB_NAME\`.* TO '$AGENT_DB_USER'@'%';"
if ! printf '%s\n' "$_agent_sql" | docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" codereview-mysql mysql -uroot --batch; then
  die "Agent 新库或独立账号初始化失败；应用尚未启动"
fi
ok "Agent 数据库 $AGENT_DB_NAME 与独立账号已就绪"

# ── 步骤 8：升级前备份两个数据库 ────────────────────────────────────────────
if [ -n "$(container_health codereview-mysql)" ]; then
  info "备份兼容库与 Agent 新库"
  bash scripts/backup.sh || die "数据库备份失败，已停止本次应用部署"
fi

# ── 步骤 9：启动应用 ────────────────────────────────────────────────────────
hr
confirm "即将启动/更新容器：
      应用端口      ${APP_PORT:-18080}
      应用内存上限  ${APP_MEM_LIMIT:-4g}
      数据库内存上限 ${MYSQL_MEM_LIMIT:-2g}
      项目名        $PROJECT（只影响本项目的容器，不会动别人的）
是否继续？" || die "已取消，未做任何改动"
hr

if ! compose up -d app; then
  hr
  err "compose up 失败 —— 下面是相关容器日志（最后 40 行），方便直接定位："
  hr
  compose logs --tail=40 mysql 2>/dev/null || true
  hr
  compose logs --tail=40 app 2>/dev/null || true
  hr
  info "常见原因："
  info "  1) 部署目录在 Windows 盘挂载点（/mnt/x）下 -> MySQL 无法初始化数据目录，请移到 ~ 下"
  info "  2) 内存不足被 OOM -> 调小 .env 里的 JAVA_OPTS / MYSQL_BUFFER_POOL"
  info "  3) 端口被占 -> 改 .env 里的 APP_PORT"
  die "启动失败（若存在上一版本，可执行 bash scripts/rollback.sh 手工回滚）"
fi
ok "容器已提交启动"

# ── 步骤 9：等健康，失败自动回滚 ────────────────────────────────────────────
if wait_healthy codereview-app 300; then
  ok "应用已就绪"
else
  err "应用未在 5 分钟内变为 healthy"
  hr
  info "容器最近 60 行日志："
  compose logs --tail=60 codereview-app || true
  hr

  if image_exists codereview-app:previous; then
    warn "自动回滚到上一版本 codereview-app:previous"
    set_env APP_TAG previous
    compose up -d || die "回滚启动失败，请人工介入"
    if wait_healthy codereview-app 300; then
      err "本次升级失败，已回滚到上一版本。请把上面的日志发给开发排查。"
      exit 1
    else
      die "回滚后仍不健康 —— 请人工介入：$COMPOSE -p $PROJECT logs -f codereview-app"
    fi
  else
    die "没有可回滚的上一版本（首次部署）。排查日志：$COMPOSE -p $PROJECT logs --tail=200 codereview-app"
  fi
fi

# ── 结果 ────────────────────────────────────────────────────────────────────
hr
IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
ok "部署完成 —— 访问地址： http://${IP:-<本机IP>}:${APP_PORT:-18080}"
hr
info "查看状态： $COMPOSE -p $PROJECT ps"
info "查看日志： $COMPOSE -p $PROJECT logs -f codereview-app"
info "回滚：     bash scripts/rollback.sh"
info "备份目录： $BUNDLE/backups"

if [ "$GEN_PWD" = "1" ]; then
  hr
  warn "已生成数据库密码并写入 .env；请妥善保管该文件及 backups/env-*.txt。"
  info "  Agent 新库：$(get_env AGENT_DB_NAME)（账号：$(get_env AGENT_DB_USER)）"
  info "  兼容库：$(get_env MYSQL_DATABASE)（账号：$(get_env MYSQL_USER)）"
  warn ".env 与 data/mysql 是一体的：丢了 .env 就无法连接已有数据。"
fi
