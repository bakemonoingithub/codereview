#!/usr/bin/env bash
# =============================================================================
#  环境体检脚本（只读 / 无副作用）
#
#  用途：为「基于AI的智能代码分析工具」在内网 CentOS 7.6 服务器上的 Docker 部署，
#        一次性收集全部决策所需的环境信息。跑完把报告带回来即可，无需二次进内网。
#
#  安全声明（请运维放心执行）：
#    - 只“读取”系统与 Docker 状态，不修改任何系统配置
#    - 不 pull / 不 run / 不 load / 不 build / 不 prune / 不删除任何镜像、容器、卷
#    - 不改防火墙、不重启任何服务
#    - 唯一写入：把报告文件写到第 1 个参数指定的目录（默认 /tmp）
#
#  用法：
#    bash env-check.sh                 # 报告写到 /tmp
#    bash env-check.sh /mnt/usb        # 报告直接写到 U 盘（推荐，省一步拷贝）
#    bash env-check.sh /mnt/usb 2>&1 | tee /tmp/last.txt   # 想同时留一份也行
#
#  跑完后要带回来的东西：终端里显示的“报告文件”路径指向的那个 txt 文件
# =============================================================================

OUTDIR="${1:-/tmp}"
if [ ! -d "$OUTDIR" ] || [ ! -w "$OUTDIR" ]; then
  echo "提示：目录 '$OUTDIR' 不存在或不可写，报告将改写到 /tmp"
  OUTDIR=/tmp
fi
REPORT="$OUTDIR/env-report-$(hostname 2>/dev/null || echo host)-$(date +%Y%m%d-%H%M%S).txt"

have() { command -v "$1" >/dev/null 2>&1; }
h()    { echo; echo "===================== $* ====================="; }

# 端口连通性探测：TCP 通不通 + HTTP 状态码
probe() {
  _host="$1"; _port="$2"; _label="$3"; _scheme="${4:-http}"
  _tcp="不通"
  if timeout 2 bash -c "exec 3<>/dev/tcp/${_host}/${_port}" 2>/dev/null; then
    _tcp="可连通"
  fi
  _http="-"
  if have curl; then
    _http=$(timeout 5 curl -sS -o /dev/null -w '%{http_code}' "${_scheme}://${_host}:${_port}/" 2>/dev/null)
    [ -z "$_http" ] && _http="-"
  fi
  printf '  %-30s %s:%s  TCP=%-6s HTTP=%s\n' "$_label" "$_host" "$_port" "$_tcp" "$_http"
}

{
  echo "########################################################################"
  echo "# 环境体检报告（只读）"
  echo "# 报告文件: $REPORT"
  echo "# 生成时间: $(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "# 请把这个文件带回（终端里的内容与它完全一致）"
  echo "########################################################################"

  # ---------------------------------------------------------------------------
  h "[01] 执行者身份 / 时间 / 负载"
  # ---------------------------------------------------------------------------
  echo "\$ id";                 id 2>&1
  echo "\$ whoami";             whoami 2>&1
  echo "\$ date";               date 2>&1
  echo "\$ uptime";             uptime 2>&1
  if have timedatectl; then echo "\$ timedatectl"; timedatectl 2>&1; fi

  # ---------------------------------------------------------------------------
  h "[02] 系统 / 内核 / CPU / 安全模块"
  # ---------------------------------------------------------------------------
  echo "\$ uname -a";                      uname -a 2>&1
  echo "\$ cat /etc/redhat-release";       cat /etc/redhat-release 2>&1
  echo "\$ cat /etc/os-release";           cat /etc/os-release 2>&1
  echo "\$ getenforce";                    getenforce 2>&1 || echo "  (无 getenforce)"
  if have sestatus; then echo "\$ sestatus"; sestatus 2>&1 | head -10; fi
  echo "\$ nproc";                         nproc 2>&1
  if have lscpu; then echo "\$ lscpu (片段)"; lscpu 2>&1 | head -18; fi
  echo "\$ locale";                        locale 2>&1
  echo "\$ ulimit -n";                     ulimit -n 2>&1
  echo "\$ cat /proc/sys/vm/max_map_count"; cat /proc/sys/vm/max_map_count 2>&1

  # ---------------------------------------------------------------------------
  h "[03] 内存 / 交换分区"
  # ---------------------------------------------------------------------------
  echo "\$ free -m";        free -m 2>&1
  echo "\$ swapon --show";  swapon --show 2>&1 || echo "  (无交换分区)"

  # ---------------------------------------------------------------------------
  h "[04] 磁盘 / inode / 挂载（重点：docker 数据盘 /data）"
  # ---------------------------------------------------------------------------
  echo "\$ df -hT";         df -hT 2>&1
  echo "\$ df -i";          df -i 2>&1
  if have lsblk; then echo "\$ lsblk"; lsblk -o NAME,SIZE,FSTYPE,MOUNTPOINT 2>&1; fi
  if have findmnt; then
    echo "\$ findmnt -T /data/var/lib/docker"; findmnt -T /data/var/lib/docker 2>&1
    echo "\$ findmnt -T /opt";                 findmnt -T /opt 2>&1
  fi
  echo "\$ xfs_info /data/var/lib/docker"; xfs_info /data/var/lib/docker 2>&1 || echo "  (无 xfs_info 或该路径不是 xfs)"
  echo "\$ xfs_info /data";                xfs_info /data 2>&1 || echo "  (无 xfs_info 或该路径不是 xfs)"
  echo "--- 部署目录候选取舍 ---"
  for d in /opt /data /home /var /; do
    df -h "$d" 2>/dev/null | tail -1 | sed "s|^|  $d  ->  |"
  done
  ls -ld /opt/codereview /data/codereview 2>/dev/null || echo "  /opt/codereview 与 /data/codereview 均不存在（首次部署，正常）"

  # ---------------------------------------------------------------------------
  h "[05] Docker 版本与守护进程配置"
  # ---------------------------------------------------------------------------
  if have docker; then
    echo "\$ docker version";              docker version 2>&1
    echo "\$ docker info";                 docker info 2>&1
    echo "\$ systemctl is-enabled docker"; systemctl is-enabled docker 2>&1
    echo "\$ systemctl is-active docker";  systemctl is-active docker 2>&1
    echo "\$ systemctl cat docker";        systemctl cat docker 2>&1
    echo "\$ cat /etc/docker/daemon.json"; cat /etc/docker/daemon.json 2>&1 || echo "  (无该文件)"
    echo "\$ ls /etc/systemd/system/docker.service.d/"; ls -l /etc/systemd/system/docker.service.d/ 2>&1 || echo "  (无该目录)"
    if [ -d /etc/systemd/system/docker.service.d ]; then
      for f in /etc/systemd/system/docker.service.d/*.conf; do
        [ -f "$f" ] && { echo "--- $f ---"; cat "$f" 2>&1; }
      done
    fi
  else
    echo "  docker 命令不存在！"
  fi

  # ---------------------------------------------------------------------------
  h "[06] Compose 调用形式（决定部署脚本怎么写）"
  # ---------------------------------------------------------------------------
  echo "\$ docker compose version";  docker compose version 2>&1 || echo "  (插件形式不可用)"
  echo "\$ docker-compose --version"; docker-compose --version 2>&1 || echo "  (独立二进制不可用)"
  echo "\$ which docker-compose";    which docker-compose 2>&1 || echo "  (不在 PATH)"
  echo "\$ docker-compose 文件类型"; file "$(command -v docker-compose 2>/dev/null)" 2>&1 || true

  # ---------------------------------------------------------------------------
  h "[07] Docker 空间占用"
  # ---------------------------------------------------------------------------
  if have docker; then
    echo "\$ docker system df";    docker system df 2>&1
    echo "\$ docker system df -v (前 40 行)"; docker system df -v 2>&1 | head -40
  fi

  # ---------------------------------------------------------------------------
  h "[08] Docker 网络 / 卷 / 已有 Compose 项目（避免命名与网段冲突）"
  # ---------------------------------------------------------------------------
  if have docker; then
    echo "\$ docker network ls"; docker network ls 2>&1
    echo "--- 各网络网段 ---"
    for n in $(docker network ls --format '{{.Name}}' 2>/dev/null); do
      printf '  %-28s ' "$n"
      docker network inspect "$n" --format '{{range .IPAM.Config}}subnet={{.Subnet}} gw={{.Gateway}} {{end}}' 2>/dev/null
      echo
    done
    echo "--- 卷列表（前 40）---"
    docker volume ls 2>/dev/null | head -40
    echo "--- 已存在的 compose 项目名 ---"
    docker ps -a --format '{{.Label "com.docker.compose.project"}}' 2>/dev/null | sort -u
    echo "--- 是否已存在 codereview 相关资源 ---"
    echo -n "  容器: "; docker ps -a --format '{{.Names}}' 2>/dev/null | grep -i codereview || echo "无"
    echo -n "  网络: "; docker network ls --format '{{.Name}}' 2>/dev/null | grep -i codereview || echo "无"
    echo -n "  卷:   "; docker volume ls --format '{{.Name}}' 2>/dev/null | grep -i codereview || echo "无"
  fi

  # ---------------------------------------------------------------------------
  h "[09] 现有容器与端口占用（评估共存影响）"
  # ---------------------------------------------------------------------------
  if have docker; then
    echo "\$ docker ps -a"
    docker ps -a --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}' 2>&1
  fi
  echo "\$ ss -lntp"; ss -lntp 2>&1
  echo "\$ hostname -I"; hostname -I 2>&1
  echo "\$ ip -4 addr show"; ip -4 addr show 2>&1
  echo "\$ ip route"; ip route 2>&1

  # ---------------------------------------------------------------------------
  h "[10] 候选端口占用检查（应用要用一个空闲端口）"
  # ---------------------------------------------------------------------------
  for p in 18080 18081 18082 9080 9081 8081 8001 8888 8080; do
    if ss -lnt 2>/dev/null | awk '{print $4}' | grep -qE "[:.]${p}\$"; then
      echo "  端口 $p : 已占用"
    else
      echo "  端口 $p : 空闲"
    fi
  done

  # ---------------------------------------------------------------------------
  h "[11] 防火墙 / 内核转发（决定端口怎么开）"
  # ---------------------------------------------------------------------------
  echo "\$ firewall-cmd --state";        firewall-cmd --state 2>&1 || echo "  (firewalld 未运行或未安装)"
  echo "\$ systemctl is-active firewalld"; systemctl is-active firewalld 2>&1
  echo "\$ iptables --version";          iptables --version 2>&1
  echo "\$ iptables -S (前 30 条)";      iptables -S 2>&1 | head -30
  echo "\$ cat /proc/sys/net/ipv4/ip_forward"; cat /proc/sys/net/ipv4/ip_forward 2>&1
  echo "\$ lsmod | grep -E 'br_netfilter|overlay|xt_conntrack'"; lsmod 2>/dev/null | grep -E 'br_netfilter|overlay|xt_conntrack' 2>&1 || echo "  (无匹配)"

  # ---------------------------------------------------------------------------
  h "[12] 宿主机上的 mysqld（确认归属，我们不会碰它）"
  # ---------------------------------------------------------------------------
  echo "\$ systemctl status mysqld"; systemctl status mysqld --no-pager 2>&1 | head -8
  echo "\$ pgrep -a mysqld";         pgrep -a mysqld 2>&1 || echo "  (无 mysqld 进程)"
  echo "\$ ss -lntp | grep 3306";    ss -lntp 2>&1 | grep 3306 || echo "  (3306 未监听)"
  echo "\$ mysql --version";         mysql --version 2>&1 || echo "  (无 mysql 客户端)"

  # ---------------------------------------------------------------------------
  h "[13] 网络可达性探测（内网服务 + 仓库 + 代理）"
  # ---------------------------------------------------------------------------
  echo "--- 内网已知服务 ---"
  probe 192.104.224.172 80   "Gitea（已知内网地址）" http
  probe 192.104.224.172 443  "Gitea HTTPS"          https
  probe 192.104.224.172 5000 "可能的镜像仓库 registry" http
  probe 192.104.224.172 8081 "可能的 Nexus"          http
  probe 192.104.224.172 8082 "可能的 Nexus 备用"     http
  echo "--- 外网 / 公网仓库（确认是否真的不通）---"
  probe 114.114.114.114 53 "DNS(114)" ""
  probe registry.cn-chengdu.aliyuncs.com 443 "阿里云镜像仓库" https
  probe registry-1.docker.io 443 "Docker Hub" https
  if have curl; then
    echo "\$ curl Gitea 版本接口"
    timeout 6 curl -sS "http://192.104.224.172/gitea/api/v1/version" 2>&1 | head -3
    echo
  fi
  echo "--- 代理环境变量 ---"
  echo "\$ env | grep -i proxy"; env 2>/dev/null | grep -i proxy || echo "  (无)"
  echo "\$ grep -i proxy /etc/environment /etc/profile"; grep -i proxy /etc/environment /etc/profile 2>/dev/null || echo "  (无)"
  echo "\$ grep -i proxy /etc/sysconfig/docker"; grep -i proxy /etc/sysconfig/docker 2>/dev/null || echo "  (无)"
  echo "\$ docker info | grep -i proxy"; docker info 2>/dev/null | grep -i proxy || echo "  (无)"
  echo "\$ /etc/resolv.conf"; cat /etc/resolv.conf 2>&1
  echo "\$ /etc/hosts"; cat /etc/hosts 2>&1

  # ---------------------------------------------------------------------------
  h "[14] 工具链可用性（部署脚本会用到的命令）"
  # ---------------------------------------------------------------------------
  echo "\$ bash --version"; bash --version 2>&1 | head -1
  for t in tar gzip gunzip unzip zip sha256sum curl wget python python3 git jq file dos2unix nc lsof timeout tee sed awk mount; do
    p=$(command -v "$t" 2>/dev/null)
    if [ -n "$p" ]; then printf '  %-10s %s\n' "$t" "$p"; else printf '  %-10s %s\n' "$t" "缺失"; fi
  done

  # ---------------------------------------------------------------------------
  h "[15] U 盘 / 可移动介质与挂载情况"
  # ---------------------------------------------------------------------------
  echo "\$ mount | grep -Ei 'media|/mnt|vfat|ntfs|exfat|iso9660'"
  mount 2>/dev/null | grep -Ei 'media|/mnt|vfat|ntfs|exfat|iso9660' || echo "  (当前没看到挂载的可移动介质)"
  echo "\$ ls -l /media /mnt"; ls -l /media /mnt 2>&1
  echo "\$ df -hT (可移动介质)"; df -hT 2>/dev/null | grep -Ei 'vfat|ntfs|exfat|iso9660' || echo "  (无)"

  # ---------------------------------------------------------------------------
  h "[16] 其它运行中的服务 / 定时任务 / Docker 日志尾部"
  # ---------------------------------------------------------------------------
  echo "\$ systemctl list-units --type=service --state=running (前 40 行)"
  systemctl list-units --type=service --state=running --no-pager 2>&1 | head -40
  echo "\$ crontab -l"; crontab -l 2>&1 || echo "  (当前用户无 crontab)"
  echo "\$ journalctl -u docker -n 20"; journalctl -u docker -n 20 --no-pager 2>&1 || echo "  (无权限或不可用)"

  echo
  echo "########################################################################"
  echo "# 报告结束。"
  echo "# 报告文件位置: $REPORT"
  echo "# 请把该文件带回（或用终端输出复制粘贴）。"
  echo "########################################################################"
} 2>&1 | tee "$REPORT"
