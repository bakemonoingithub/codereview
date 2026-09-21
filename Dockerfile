# syntax=docker/dockerfile:1
# =============================================================================
#  「基于AI的智能代码分析工具」单镜像构建
#
#  形态：前端(Vue3 + Vite) 构建产物 -> 打进后端 JAR 的 static/ -> JDK17 运行
#        对外只有一个容器、一个端口（容器内 8080），不需要 Nginx、不需要 CORS。
#
#  构建上下文必须是仓库根目录（本文件所在目录），因为 vite.config.ts 的
#  outDir 指向 ../backend/src/main/resources/static —— 保留仓库相对结构最省事。
#
#  本地/CI 构建命令（目标机是 Docker 20.10.24，必须按下面这样构建，否则 load 会报
#  "unsupported manifest media type"）：
#    docker buildx build \
#      --platform linux/amd64 --provenance=false --sbom=false \
#      --output type=docker \
#      -t codereview-app:<版本> .
# =============================================================================

ARG NODE_IMAGE=node:22-bookworm-slim
ARG MAVEN_IMAGE=maven:3.9.9-eclipse-temurin-17
# 运行时基础镜像刻意钉在 jammy（Ubuntu 22.04 LTS）：
# 容器用户态最终跑在目标机的 3.10 内核上，22.04 的 glibc 与内核兼容性经过大量验证；
# 而默认的 eclipse-temurin:17-jre 标签已经切到很新的 Ubuntu（26.04），没必要为它冒风险。
ARG RUNTIME_IMAGE=eclipse-temurin:17-jre-jammy

# -----------------------------------------------------------------------------
# 阶段 1 / 3：构建前端
# -----------------------------------------------------------------------------
FROM ${NODE_IMAGE} AS frontend

# 与开发机实测版本一致（pnpm --version = 10.18.2），锁住以保证 lockfile 行为可复现
ARG PNPM_VERSION=10.18.2
RUN npm install -g pnpm@${PNPM_VERSION} \
 && pnpm --version

WORKDIR /repo

# 先只拷依赖清单：源码改动不会触发重新安装依赖（利用层缓存）
COPY frontend/package.json \
     frontend/pnpm-lock.yaml \
     frontend/pnpm-workspace.yaml \
     frontend/.npmrc \
     /repo/frontend/
RUN cd /repo/frontend && pnpm install --frozen-lockfile

COPY frontend/ /repo/frontend/

# vite.config.ts 的 outDir 是 ../backend/src/main/resources/static：
# 该目录被 .dockerignore 排除（不带本机残留产物），所以先建出来再 build。
RUN mkdir -p /repo/backend/src/main/resources/static \
 && cd /repo/frontend && pnpm build \
 && test -f /repo/backend/src/main/resources/static/index.html \
 && echo "前端产物已生成：$(ls /repo/backend/src/main/resources/static | tr '\n' ' ')"

# -----------------------------------------------------------------------------
# 阶段 2 / 3：Maven 打包（JAR 内含阶段 1 的前端产物）
# -----------------------------------------------------------------------------
FROM ${MAVEN_IMAGE} AS backend

WORKDIR /repo/backend

# 注意：本机磁盘上的 src/main/resources/application.yml（含明文密码）已被
# .dockerignore 排除，不会进构建上下文，因此 JAR 里不会带任何数据库口令。
# 运行期配置由外部挂载的 /app/config/application.yml 提供。
COPY backend/ /repo/backend/
COPY --from=frontend /repo/backend/src/main/resources/static /repo/backend/src/main/resources/static

# maven.test.skip=true：连测试代码一起跳过编译（单测已在 CI 独立 job 里跑过）
RUN mvn -B -Dmaven.test.skip=true package \
 && cp target/backend-*.jar target/app.jar \
 && ls -lh target/app.jar

# -----------------------------------------------------------------------------
# 阶段 3 / 3：运行镜像
# -----------------------------------------------------------------------------
FROM ${RUNTIME_IMAGE} AS runtime

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8

# curl 仅供容器内 healthcheck 使用（temurin 基础镜像不带 curl）
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl tzdata \
 && ln -snf /usr/share/zoneinfo/$TZ /etc/localtime \
 && echo "$TZ" > /etc/timezone \
 && rm -rf /var/lib/apt/lists/*

# 非 root 运行；uid/gid 固定 1000，部署脚本会把 data/git-mirrors 的属主改成 1000。
# 注意：Ubuntu 基础镜像自带的 `ubuntu` 用户就占着 1000，所以这里必须写成幂等的
#       （直接 groupadd -g 1000 会报 "GID '1000' already exists"）。
RUN set -eux; \
    if ! getent group 1000 >/dev/null; then groupadd -g 1000 appuser; fi; \
    if ! getent passwd 1000 >/dev/null; then useradd -u 1000 -g 1000 -m -s /bin/bash appuser; fi; \
    id 1000

WORKDIR /app
COPY --from=backend /repo/backend/target/app.jar /app/app.jar
RUN mkdir -p /app/config /app/data/git-mirrors \
 && chown -R 1000:1000 /app

USER 1000:1000
EXPOSE 8080

# 保守的 JVM 默认值：目标机是共享机器，绝不能让 JVM 按物理内存比例开堆（62G 会开出十几 G）。
# 部署时由 docker-compose.yml 的 JAVA_TOOL_OPTIONS 覆盖，此处只是兜底。
ENV JAVA_TOOL_OPTIONS="-Xms1g -Xmx4g -XX:+UseG1GC -XX:-UseTransparentHugePages -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
