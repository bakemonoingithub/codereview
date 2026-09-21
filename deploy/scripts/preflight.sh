#!/usr/bin/env bash
# =============================================================================
#  前置体检（只读，不修改任何东西）
#  任何一条 [错误] 都会阻止后续部署。
#  一般不用单独运行 —— deploy.sh 会自动调用它；想先看看环境再决定时可以单跑。
# =============================================================================
set -u
. "$(cd "$(dirname "$0")" && pwd)/common.sh"

PASSES=0
FAILS=0
pass() { ok "$1";   PASSES=$((PASSES + 1)); }
fail() { err "$1";  FAILS=$((FAILS + 1)); }

hr
info "前置体检开始（只读）"
hr

init_bundle
info "部署包目录：$BUNDLE"

# ── 0. 部署目录所在文件系统 ─────────────────────────────────────────────────
# MySQL 容器要在 ./data/mysql 上 chown/chmod 自己的数据目录。Windows 共享挂载
# （WSL 的 /mnt/c、/mnt/h，或 cifs/ntfs/vboxsf）不支持这些操作，MySQL 会直接崩溃，
# 而报错发生在容器日志里、不看日志很难猜。这里提前拦住，把话说明白。
_FSTYPE="$(df -PT "$BUNDLE" 2>/dev/null | awk 'NR==2 {print $2}')"
case "$_FSTYPE" in
  drvfs|9p|cifs|ntfs|ntfs3|vboxsf)
    fail "部署目录位于 Windows/网络共享文件系统（$_FSTYPE）上，MySQL 无法在那里初始化数据目录。
       请把整个部署包移到 Linux 原生目录再执行，例如：
           mv \"$BUNDLE\" ~/ && cd ~/$(basename "$BUNDLE")" ;;
  fuse.*)
    warn "部署目录所在文件系统是 $_FSTYPE（FUSE），历史上出现过 MySQL 数据目录权限问题，请留意容器日志" ;;
  *)
    case "$BUNDLE" in
      /mnt/[a-zA-Z]/*)
        fail "部署目录在 Windows 盘挂载点下（$BUNDLE），MySQL 无法初始化数据目录。
       请移到 Linux 原生目录再执行，例如： mv \"$BUNDLE\" ~/ && cd ~/$(basename "$BUNDLE")" ;;
      *) pass "部署目录在 Linux 原生文件系统上（${_FSTYPE:-未知}）" ;;
    esac ;;
esac

# ── 1. Docker ───────────────────────────────────────────────────────────────
if docker info >/dev/null 2>&1; then
  pass "Docker 守护进程可访问"
else
  fail "Docker 守护进程不可访问（当前用户可能不在 docker 组，或 dockerd 未运行）"
fi

DV="$(docker version --format '{{.Server.Version}}' 2>/dev/null || echo '')"
if [ -n "$DV" ]; then
  info "Docker 服务端版本：$DV"
  case "$DV" in
    1[0-9].*|2[0-9].*) : ;;
    *) warn "Docker 版本较新，若镜像装载报 'unsupported manifest media type'，说明镜像包构建时没关 provenance" ;;
  esac
fi

if docker compose version >/dev/null 2>&1; then
  info "Compose 调用方式：docker compose（插件）"
elif command -v docker-compose >/dev/null 2>&1; then
  info "Compose 调用方式：docker-compose（独立二进制）"
else
  fail "既没有 docker compose 插件，也没有 docker-compose 命令"
fi

# ── 2. 资源 ─────────────────────────────────────────────────────────────────
D_FREE="$(free_gb "$BUNDLE")"
if [ -n "$D_FREE" ] && [ "$D_FREE" -ge 5 ] 2>/dev/null; then
  pass "部署目录所在分区可用空间 ${D_FREE}G（>=5G）"
else
  fail "部署目录所在分区可用空间仅 ${D_FREE:-?}G，至少需要 5G（镜像装载 + 数据卷 + 备份）"
fi

DROOT="$(docker_root)"
if [ -n "$DROOT" ]; then
  R_FREE="$(free_gb "$DROOT")"
  if [ -n "$R_FREE" ] && [ "$R_FREE" -ge 8 ] 2>/dev/null; then
    pass "Docker 数据盘 $DROOT 可用空间 ${R_FREE}G（>=8G）"
  else
    fail "Docker 数据盘 $DROOT 可用空间仅 ${R_FREE:-?}G，至少需要 8G"
  fi
  info "Docker 存储驱动：$(docker info --format '{{.Driver}}' 2>/dev/null)，根目录：$DROOT"
fi

MEM_AVAIL_MB="$(awk '/^MemAvailable:/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null || echo 0)"
if [ "${MEM_AVAIL_MB:-0}" -ge 6144 ] 2>/dev/null; then
  pass "当前可用内存 $((MEM_AVAIL_MB / 1024))G（>=6G）"
else
  fail "当前可用内存仅 $((MEM_AVAIL_MB / 1024))G，低于本方案要求的 6G（应用 4G + 数据库 2G）"
fi

# ── 3. 依赖 .env 的检查 ─────────────────────────────────────────────────────
if [ -f .env ]; then
  load_env

  for k in MYSQL_ROOT_PASSWORD MYSQL_PASSWORD APP_PORT; do
    v="$(get_env "$k")"
    if [ -n "$v" ]; then
      pass ".env 已设置 $k"
    else
      fail ".env 缺少必填项 $k"
    fi
  done

  PORT="${APP_PORT:-18080}"
  if port_in_use "$PORT"; then
    OWNER="$(docker ps --format '{{.Names}} {{.Ports}}' 2>/dev/null | grep ":$PORT->" | head -1 | awk '{print $1}')"
    if [ "$OWNER" = "codereview-app" ]; then
      info "端口 $PORT 由本次要部署的 codereview-app 占用（升级场景，正常）"
    else
      fail "端口 $PORT 已被占用${OWNER:+（容器 $OWNER）}。请在 .env 里把 APP_PORT 改成空闲端口后重试"
    fi
  else
    pass "端口 $PORT 空闲"
  fi

  if [ -f config/application.yml ]; then
    if grep -q '__DB_' config/application.yml; then
      fail "config/application.yml 里还有未替换的占位符（__DB_ 开头），说明它是模板而不是渲染后的文件"
    else
      pass "config/application.yml 已存在"
    fi
    if grep -q 'CHANGE-ME' config/application.yml; then
      warn "config/application.yml 里的 AI 网关地址还是占位符，大模型调用会失败（页面与分析器不受影响）"
    fi
  else
    fail "缺少 config/application.yml（首次部署时 deploy.sh 会自动生成，单独跑体检可忽略这条）"
  fi
else
  warn "未找到 .env，跳过与 .env 相关的检查（由 deploy.sh 调用时会先自动生成）"
fi

# ── 4. 明确告知不会做什么 ───────────────────────────────────────────────────
hr
info "本套脚本不会执行：docker (system|container|volume|image) prune、systemctl restart docker"
info "也不会修改防火墙；请勿在部署期间执行 firewall-cmd --reload（会破坏 20.10 的容器网络隔离）"
hr

if [ "$FAILS" -gt 0 ]; then
  err "体检未通过：$FAILS 项错误，$PASSES 项通过。请先处理上面的 [错误] 再部署。"
  exit 1
fi
ok "体检通过：$PASSES 项检查全部通过"
exit 0
