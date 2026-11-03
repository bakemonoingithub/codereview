# LGC-01 改造前基准

日期：2026-11-03（Asia/Taipei）。LGC-01 已完成，LGC-02–04 尚未启动。本步只建立清单和检查基准，没有删除或修改生产、测试及构建配置。

## 1. 源码与工作区

- 分支：`dev`；完整提交：`9dc1ff7dd12292f2515890e11dd9ad22f97f30d9`。启动时 `git status --porcelain=v1` 为空；规划时记录的 `frontend/components.d.ts` 修改已包含在当前提交，不再是未提交内容。
- 使用 `git archive HEAD` 固定输入，隔离目录为 `H:/myProjects/aiCodeReview/artifacts/lgc01-20261103/before`，后端输出为同级 `backend-build`。没有切换分支，也没有复制忽略的本机配置。
- [源码清单](evidence/source-manifest.json)记录归档 SHA-256、各文件 Git 内容及检出内容 SHA-256；清单内文件与工作区除 CRLF／LF 检出差异外全部一致。归档原文件为隔离目录上级 `source.tar`。原生 Windows tar 未能展开的三份中文名历史附件随后从同一归档补齐，未参与工程检查。
- 启动和收尾均只读核对 5173、8080、8081，未发现监听端口；此结论只覆盖这三个常用端口。未启停现有业务服务。
- 前端测试缓存、构建输出及生成声明均位于隔离副本或独立依赖副本，不写入共享前端目录。

## 2. 固定范围与测试归属

处置依据见 [逐文件清单](inventory.md)，完整消费者、验证项目、源码哈希和测试数量见 [机器清单](evidence/file-inventory.json)。它是后续实施依据，本步所有候选仍在原位。

| 范围 | 后续拟删除 | 保留边界 |
| --- | --- | --- |
| 后端生产 Java | 96 份 | 现行 agent/git、启动类及共同异常、过滤、差异、追踪和公共配置 |
| 后端专属测试 | 38 个套件，221 项 | 迁移契约 1 套件、4 项保留；其余当前与共用测试保留 |
| 前端源码 | 26 份 | 现行路由可达组件、工具及 `api/v2.ts` 消费者保留 |
| 前端专属测试 | 23 个文件，226 项 | 50 个当前／共用测试文件保留 |
| 资源、历史迁移与独立工具 | 0 份 | 7 份旧 SQL、当前迁移链、提示词、迁移工具及运维脚本保留 |

后续必要调整包括旧项目详情路由、生成组件声明、前端直接依赖及锁文件，以及 v2 异常处理器的过时注释。`echarts`、`splitpanes` 仅在最后消费者删除后移除。数量不作为验收目标；基准实际测试归属见 [责任汇总](evidence/test-ownership-summary.json)。此前 PAS-02 的额外差分测试不在当前提交中，因此本次实际为 142 套件、1025 项，不沿用其 143／1026 数量。

## 3. 工具链与执行方式

Windows 11 amd64；Azul Java 17.0.20.1；Maven Wrapper / Maven 3.9.9；Node 24.9.0；pnpm 11.7.0。Maven 环境见 [版本记录](evidence/maven-version.log)。未增加运行依赖或升级仓库依赖。

测试子进程移除 `DEEPSEEK_API_KEY`、`GITEA_TEST_BASE_URL`、`JAVA_TOOL_OPTIONS`、`JDK_JAVA_OPTIONS`、`MAVEN_OPTS`、`MAVEN_ARGS`、`SPRING_APPLICATION_JSON`、`SPRING_CONFIG_LOCATION`、`SPRING_CONFIG_ADDITIONAL_LOCATION`，显式传空 `deepseek.api-key`；不传 `probe.root`、`agent.live.cancel` 或基线捕获开关。扫描测试开关后，仅运行默认替身／本地测试。未连接现有数据库、远端仓库或真实模型。

从隔离副本的 `backend` 执行：

```powershell
$env:JAVA_HOME = 'C:/Users/ysx/.jdks/azul-17.0.20.1'
$env:Path = "$env:JAVA_HOME/bin;$env:Path"
./mvnw.cmd -q '-Ddeepseek.api-key=' '-Dbackend.buildDirectory=H:/myProjects/aiCodeReview/artifacts/lgc01-20261103/backend-build' test
```

本机默认 `java` 不在 PATH；默认 pnpm 启动器指向缺失的 12.8.1 文件。先尝试 pnpm 11.28.5 和 11.7.0 离线安装，分别遇到缓存元数据及 tarball 缺失。两次均未成功，日志保留在隔离副本根目录 `frontend-install*.log`。随后使用 `robocopy /E /XJ` 复制现有 `frontend/node_modules` 的普通文件，再在副本内重建 591 个依赖链接；链接目标全部指向独立副本。没有修改现有依赖目录，也没有更新锁文件。

pnpm 默认的执行前依赖安装会再调用损坏的全局启动器，初次三个前端命令因此未进入实际检查。最终使用 `--config.verify-deps-before-run=false` 关闭该自动安装步骤，仍执行仓库原有测试、类型检查和构建脚本，未关闭质量检查。从隔离副本的 `frontend` 执行：

```powershell
$pnpm = 'I:/uv_cache/node/corepack/v1/pnpm/11.7.0/bin/pnpm.cjs'
node $pnpm --config.verify-deps-before-run=false test --reporter=json --outputFile=../frontend-test.json
node $pnpm --config.verify-deps-before-run=false type-check
node $pnpm --config.verify-deps-before-run=false build
```

## 4. 结果与既有问题

| 检查 | 结果 | 实际范围 | 退出码 |
| --- | --- | --- | --- |
| 后端全量 | 通过 | 142 套件、1025 项，失败 0、异常 0、跳过 11 | 0 |
| 前端全量 | 通过 | 73 个文件、658 项，失败 0、跳过 0 | 0 |
| 前端类型检查 | 既有失败 | 两处诊断，见下表 | 2 |
| 前端生产构建 | 通过 | Vite 完成；有大 chunk 提示 | 0 |

统计及退出码见 [检查汇总](evidence/checks.json)。后端逐套件及跳过方法见 [后端结果](evidence/backend-tests.json)，前端逐文件结果见 [前端结果](evidence/frontend-tests.json)。Vitest JSON 的 `numTotalTestSuites=197` 包含 describe 分组；此处文件数按 `testResults` 的 73 项统计，不混用两个口径。

11 项跳过为：AgentCancellationLiveTest 2 项、AiGatewayProbeTest 2 项、GiteaClientLiveTest 5 项，JavaParserProbeTest 与 StructureLlmProbeTest 各 1 项；分别缺少显式真实取消开关、模型凭据、远端仓库变量和真实源码目录。它们不记为真实环境通过。

| 编号 | 改造前诊断 | 后续比较要求 |
| --- | --- | --- |
| LGC-B01 | `ProjectDetail.vue(561,58)`，TS2769：可选提交字段不能赋给必填 `CommitInfo` | 删除旧页后应自然消失；不通过改类型或期望消除基准 |
| LGC-B02 | `ProjectList.vue(117,1)`，TS6133：`PageNotice` 导入未使用 | 当前页面保留，默认作为既有问题登记；不得新增诊断或扩大排除范围 |

完整诊断见 [类型检查日志](evidence/frontend-type-final.log)，构建提示见 [构建日志](evidence/frontend-build-final.log)。完整后端日志为隔离副本根目录 `backend-test.log`，Surefire XML 位于上级 `backend-build/surefire-reports`。这些失败均来自未改造提交，不刷新任何测试期望。

## 5. 接续与限制

LGC-01 完成；下一步是 LGC-02，仅在负责人要求后开始。使用本步清单先关闭旧详情路由，再处理前端独占依赖；保留既有类型诊断的 before 证据。后端删除仍归 LGC-03，最终全量及打包归 LGC-04。

本轮没有执行数据库迁移／清理、业务服务启动、模型调用、Postman 修改、正式页面视觉验收、部署、提交或发布。后端打包及删除后装配保护验证尚未执行，不能用本次基准通过替代。真实模型调用和 token 消耗均为 0。
