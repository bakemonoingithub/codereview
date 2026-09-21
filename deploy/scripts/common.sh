#!/usr/bin/env bash
# =============================================================================
#  公共函数库 —— 被 preflight.sh / deploy.sh / rollback.sh / backup.sh 载入
#  兼容 CentOS 7.6 自带的 bash 4.2：不使用 bash 4.3+ 的语法
# =============================================================================

set -u

PROJECT="codereview"

# ---------------------------------------------------------------------------
#  输出
# ---------------------------------------------------------------------------
if [ -t 1 ]; then
  C_RED=$'\033[31m'; C_GRN=$'\033[32m'; C_YEL=$'\033[33m'; C_CYN=$'\033[36m'; C_OFF=$'\033[0m'
else
  C_RED=""; C_GRN=""; C_YEL=""; C_CYN=""; C_OFF=""
fi
info() { printf '%s[信息]%s %s\n' "$C_CYN" "$C_OFF" "$*"; }
ok()   { printf '%s[ OK ]%s %s\n' "$C_GRN" "$C_OFF" "$*"; }
warn() { printf '%s[警告]%s %s\n' "$C_YEL" "$C_OFF" "$*"; }
err()  { printf '%s[错误]%s %s\n' "$C_RED" "$C_OFF" "$*" >&2; }
die()  { err "$*"; exit 1; }
hr()   { printf '%s\n' "------------------------------------------------------------------"; }

# ---------------------------------------------------------------------------
#  目录定位：脚本在 <部署包根>/scripts/ 下，根目录就是它的上一级
# ---------------------------------------------------------------------------
BUNDLE=""
init_bundle() {
  _sd="$(cd "$(dirname "$0")" && pwd)"
  BUNDLE="$(cd "$_sd/.." && pwd)"
  cd "$BUNDLE" || die "无法进入部署目录 $_sd/.."
  [ -f docker-compose.yml ] || die "在 $BUNDLE 下找不到 docker-compose.yml（部署包结构不对？）"
}

# ---------------------------------------------------------------------------
#  .env
# ---------------------------------------------------------------------------
load_env() {
  [ -f .env ] || die "缺少 .env（应位于 $BUNDLE/.env）。首次部署直接运行 deploy.sh，它会自动生成。"
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
}

get_env() { grep -E "^$1=" .env 2>/dev/null | head -1 | cut -d= -f2-; }

set_env() {
  _k="$1"; _v="$2"
  _tmp="$(mktemp)" || die "无法创建临时文件"
  if grep -qE "^${_k}=" .env; then
    awk -F= -v k="$_k" -v v="$_v" '$1==k {print k "=" v; next} {print}' .env > "$_tmp"
  else
    cat .env > "$_tmp"
    printf '%s=%s\n' "$_k" "$_v" >> "$_tmp"
  fi
  cat "$_tmp" > .env
  rm -f "$_tmp"
}

gen_password() {
  LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom 2>/dev/null | head -c 24
}

# ---------------------------------------------------------------------------
#  Docker / Compose
# ---------------------------------------------------------------------------
detect_compose() {
  if docker compose version >/dev/null 2>&1; then
    COMPOSE="docker compose"
  elif command -v docker-compose >/dev/null 2>&1; then
    COMPOSE="docker-compose"
  else
    die "既没有 'docker compose' 插件，也没有 'docker-compose' 命令"
  fi
}

# 注意：$COMPOSE 刻意不加引号，靠词分割拆成 "docker compose" 两个词
compose() { $COMPOSE -p "$PROJECT" --env-file .env -f docker-compose.yml "$@"; }

image_exists() { docker image inspect "$1" >/dev/null 2>&1; }

docker_root() { docker info --format '{{.DockerRootDir}}' 2>/dev/null; }

container_health() {
  docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$1" 2>/dev/null || echo missing
}

port_in_use() {
  ss -lnt 2>/dev/null | awk '{print $4}' | grep -qE "[:.]$1\$"
}

# 挑一个没被现有 docker 网络占用的 172.x 网段。
# 这台机器上已有 20 多个容器项目，默认地址池（172.17~172.31 共 16 个 /16）很可能不够用，
# 而写死一个网段又可能和别人重叠 —— 所以每次启动前都算一遍。
pick_free_subnet() {
  # 必须排除本项目自己的网络，否则第二次部署时会把"我们自己占的网段"也算成冲突，
  # 从而改掉 CR_SUBNET，进而让 compose 去修改一个已存在的网络（会失败）。
  _nets="$(docker network ls --format '{{.Name}}' 2>/dev/null | grep -v '^codereview-net$')"
  _used="$(docker network inspect $_nets \
             --format '{{range .IPAM.Config}}{{.Subnet}} {{end}}' 2>/dev/null \
           | tr ' ' '\n' | grep -E '^[0-9]+\.' | cut -d. -f1,2 | sort -u)"
  for _o2 in 31 30 29 28 27 26 25 24 23 22 21 20 19 18 17; do
    if ! printf '%s\n' "$_used" | grep -qx "172.$_o2"; then
      printf '172.%s.250.0/24\n' "$_o2"
      return 0
    fi
  done
  printf '\n'
}

# 磁盘可用空间（GB，整数）
free_gb() {
  df -Pk "$1" 2>/dev/null | awk 'NR==2 {printf "%d", $4/1024/1024}'
}

wait_healthy() {
  _c="$1"; _t="${2:-300}"; _i=0
  printf '等待容器 %s 变为 healthy ' "$_c"
  while [ "$_i" -lt "$_t" ]; do
    case "$(container_health "$_c")" in
      healthy) printf ' 完成（%ss）\n' "$_i"; return 0 ;;
      unhealthy|exited|dead|missing) printf ' 失败\n'; return 1 ;;
    esac
    printf '.'
    sleep 5
    _i=$((_i + 5))
  done
  printf ' 超时\n'
  return 1
}

confirm() {
  [ "${ASSUME_YES:-0}" = "1" ] && return 0
  printf '%s [y/N] ' "$1"
  read -r _ans || return 1
  case "$_ans" in y|Y|yes|YES|Yes) return 0 ;; *) return 1 ;; esac
}
