#!/usr/bin/env bash
# 自测：只验证纯逻辑（set_env / get_env / gen_password / 配置渲染），不依赖 docker
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"   # -> code/
TMP="$(mktemp -d)"
FAIL=0
say() { printf '%s\n' "$*"; }
chk() { # chk <描述> <实际> <期望>
  if [ "$2" = "$3" ]; then say "  OK   $1"; else say "  FAIL $1"; say "        实际=[$2]"; say "        期望=[$3]"; FAIL=$((FAIL+1)); fi
}

say "== 1. .env 读写 =="
cd "$TMP"
printf 'A=1\nB=\nJAVA_OPTS=-Xms1g -Xmx4g\n' > .env
. "$ROOT/deploy/scripts/common.sh"

chk "get_env A" "$(get_env A)" "1"
chk "get_env 空值" "$(get_env B)" ""
chk "get_env 带空格的值" "$(get_env JAVA_OPTS)" "-Xms1g -Xmx4g"
chk "get_env 不存在的键" "$(get_env NOPE)" ""

set_env B "hello"
chk "set_env 替换空值" "$(get_env B)" "hello"

set_env NEWVAL "x/y:z-1"
chk "set_env 追加新键（含 / 和 :）" "$(get_env NEWVAL)" "x/y:z-1"

set_env JAVA_OPTS "-Xms1g -Xmx4g -Dfoo=bar"
chk "set_env 覆盖含空格与 = 的值" "$(get_env JAVA_OPTS)" "-Xms1g -Xmx4g -Dfoo=bar"

chk "set_env 幂等（重复设置不新增行）" "$(grep -c '^NEWVAL=' .env)" "1"
chk ".env 总行数未膨胀" "$(wc -l < .env | tr -d ' ')" "4"

say "== 2. 随机密码 =="
P1="$(gen_password)"; P2="$(gen_password)"
chk "密码长度 24" "${#P1}" "24"
chk "两次生成不重复" "$([ "$P1" != "$P2" ] && echo yes)" "yes"
chk "密码只含字母数字" "$(printf '%s' "$P1" | grep -cE '^[A-Za-z0-9]+$')" "1"

say "== 3. 配置模板渲染（复刻 deploy.sh 的 sed）=="
mkdir -p config
cp "$ROOT/deploy/config/application.yml.example" config/application.yml
sed -i \
  -e "s|__DB_USER__|codereview|g" \
  -e "s|__DB_PASSWORD__|AbC123xyz789|g" \
  -e "s|__DB_NAME__|code_review|g" \
  -e "s|__AI_BASE_URL__|http://10.1.2.3:8000/v1|g" \
  -e "s|__AI_MODEL__|deepseek-chat|g" \
  config/application.yml

chk "模板占位符已全部替换" "$(grep -c '__DB_\|__AI_' config/application.yml)" "0"
chk "数据库主机改为 mysql 服务名" "$(grep -c 'jdbc:mysql://mysql:3306/code_review' config/application.yml)" "1"
chk "数据库密码已写入" "$(grep -c 'password: AbC123xyz789' config/application.yml)" "1"
chk "AI 网关地址已写入" "$(grep -c 'base-url: http://10.1.2.3:8000/v1' config/application.yml)" "1"
chk "Gitea 镜像目录指向持久卷" "$(grep -c 'work-dir: ${GITEA_MIRROR_DIR:/app/data/git-mirrors}' config/application.yml)" "1"
chk "自带 yml 占位符未被误伤" "$(grep -c '\${DEEPSEEK_API_KEY:}' config/application.yml)" "1"
chk "YAML 可解析" "$(python -c "import yaml,io; yaml.safe_load(io.open('config/application.yml',encoding='utf-8')); print('y')" 2>/dev/null || echo n)" "y"

say "== 4. 网段挑选（用 stub 假装是本机那台跑着一堆容器的机器）=="
# 目标语义：跳过别的项目占用的 /16，落在一个空闲的 172.x 上；
#           本项目自己的网络必须被排除，这样第二次部署会选回同一个网段（幂等，不会去改已有网络）。
stub_docker() { # stub_docker <ls输出的网络名列表> <网络名:网段 列表...>
  _ls="$1"; shift
  _MAP="$*"
  docker() {
    if [ "$1" = "network" ] && [ "$2" = "ls" ]; then
      printf '%s\n' "$_ls"; return 0
    fi
    if [ "$1" = "network" ] && [ "$2" = "inspect" ]; then
      shift 2
      for _n in "$@"; do
        for _p in $_MAP; do
          case "$_p" in
            "$_n":*) printf '%s \n' "${_p#*:}" ;;
          esac
        done
      done
      return 0
    fi
    return 0
  }
}

# 场景 A：首次部署（还没有我们的网络），172.17 被占 -> 应挑 172.31
stub_docker 'bridge
host
none
dify-113_default' 'bridge:172.17.0.0/16' 'dify-113_default:172.20.0.0/16'
chk "场景A 首次部署挑最大空闲网段" "$(pick_free_subnet)" "172.31.250.0/24"

# 场景 B：已有我们的网络在 172.29，别的项目占 172.31/172.30 -> 应选回 172.29（幂等）
stub_docker 'bridge
codereview-net
other-a
other-b' 'bridge:172.17.0.0/16' 'other-a:172.31.0.0/16' 'other-b:172.30.0.0/16' 'codereview-net:172.29.250.0/24'
chk "场景B 二次部署选回原网段（幂等，不改已有网络）" "$(pick_free_subnet)" "172.29.250.0/24"

# 场景 C：还没有我们的网络，且 172.31/172.30/172.29 都被占 -> 应挑 172.28
stub_docker 'bridge
other-a
other-b
other-c' 'bridge:172.17.0.0/16' 'other-a:172.31.0.0/16' 'other-b:172.30.0.0/16' 'other-c:172.29.0.0/16'
chk "场景C 连续占用时顺延" "$(pick_free_subnet)" "172.28.250.0/24"

say "== 5. 其余工具函数 =="
chk "free_gb 有输出" "$(free_gb / | grep -cE '^[0-9]+$')" "1"
chk "port_in_use 对高位端口判定" "$(port_in_use 65000 && echo busy || echo free)" "free"

cd /
rm -rf "$TMP"
say ""
if [ "$FAIL" -eq 0 ]; then say "全部自测通过"; else say "有 $FAIL 项失败"; fi
exit "$FAIL"
