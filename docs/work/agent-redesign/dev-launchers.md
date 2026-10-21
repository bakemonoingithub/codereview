# 本机开发服务启停

2026-10-21 按负责人要求，将其他分支的开发启停脚本迁入 `refactor/agent`。本记录说明使用方法、迁移差异与验证结果；不改变 P31 的阶段状态。

## 使用方法

在 `H:\myProjects\aiCodeReview\agent` 打开 PowerShell，先检查依赖与端口，再启动：

```powershell
pwsh -NoProfile -File .\scripts\start-dev.ps1 -CheckOnly
pwsh -NoProfile -File .\scripts\start-dev.ps1
```

首次使用前须准备实际后端配置。如果未使用 Spring Boot 默认配置文件或环境变量，可以将 [backend-dev.example.properties](../../../scripts/backend-dev.example.properties) 复制到 `backend/application-local.properties`，填写真实库地址与账号，并通过本机环境变量提供示例引用的密码。该本机文件已忽略。随后显式指定配置：

```powershell
pwsh -NoProfile -File .\scripts\start-dev.ps1 -BackendConfig .\backend\application-local.properties -CheckOnly
pwsh -NoProfile -File .\scripts\start-dev.ps1 -BackendConfig .\backend\application-local.properties
```

从 `scripts` 目录执行时，配置路径相应使用 `..\backend\application-local.properties`。`-BackendConfig` 按调用终端的当前目录解析，支持包含空格的路径。

启动后保留终端，等待前后端均就绪，再打开 <http://127.0.0.1:5173/>。后端使用 `8081`，前端 `/api` 请求转发到该端口。按 `Ctrl+C` 停止本次启动的服务，或从另一个终端执行：

```powershell
pwsh -NoProfile -File .\scripts\stop-dev.ps1 -CheckOnly
pwsh -NoProfile -File .\scripts\stop-dev.ps1
```

启动等待默认最多 180 秒，可通过 `-StartupTimeoutSeconds 300` 调整。超时或任一服务提前退出时，脚本报错并停止本次启动的进程树。端口已占用时直接拒绝启动，不自动终止占用者。

## 配置与运行文件

- 需要 Windows、PowerShell 7、Node.js、已安装的前端依赖、Maven 与 JDK。Windows PowerShell 5.1 调用脚本时会尝试切换到 PowerShell 7。
- Maven 优先从 `PATH` 查找，Java 优先使用 `JAVA_HOME`。本机候选路径在 [dev.config.psd1](../../../scripts/dev.config.psd1) 中维护。
- 后端配置通过本机 `application.yml`、`-BackendConfig` 或 Spring Boot 支持的环境配置提供。当前应用仍装配旧链组件，必须配置 `spring.datasource.*`；同时需要配置隔离的 `agent.datasource.*`。仅有旧库配置不能启用新接口。字段参考 [application.example.yml](../../../backend/src/main/resources/application.example.yml)。
- `-CheckOnly` 检查工具、前端依赖文件、配置来源与端口。配置来源检查只确认默认文件、显式文件或配置环境变量存在，不解析配置有效值，不验证数据库连接、账号或迁移。正常启动会执行应用既有的数据库检查与迁移流程。本次迁移及故障修复没有创建实际配置文件、复制凭据或执行数据库操作。
- 每次启动的配置、日志和进程记录放在 `frontend/.dev-runtime/<runId>/`，已加入忽略规则。后端编译输出使用 `backend/target/runtime-local-<runId>/`，避免与常规测试目录混用。
- 停止脚本按当前工作树路径、运行标识和进程启动时间识别服务，不按端口终止进程。脚本不自动安装依赖。

## 来源与适配

检查了本地分支和远端跟踪分支，仅 `refactor/review` 包含本次所需的开发启停脚本。来源固定为提交 `160b8064faea129842379eab9bdf95d480cceb62` 的 `scripts/start-dev.ps1`、`scripts/stop-dev.ps1`，通过 Git 对象读取，未使用旧工作树未提交内容。

| 迁移项 | Agent 分支适配 |
| --- | --- |
| 工具路径配置 | 提取 Java、Maven 候选路径到 `dev.config.psd1`，不迁入旧文档管理框架 |
| 前端运行目录 | 从共享依赖缓存改到当前工作树 `.dev-runtime`；现有 `node_modules` 是目录联接，运行配置不能写入其他工作树 |
| 前端配置与停止识别 | 引用当前工作树的 Vite 配置，用绝对运行配置路径识别前端进程 |
| 就绪探测 | 改用 `/api/v2/health`，同时校验 `database=agent` 与非空 `schemaVersion`；旧 `status=UP` 不算通过 |
| 启动等待 | 新增有限等待参数，失败时回收本次启动的进程树 |
| 后端编译目录 | POM 支持 `backend.buildDirectory` 覆盖；默认仍为 `backend/target` |

## 验证记录

2026-10-21 在 `refactor/agent` 完成以下检查：

| 检查 | 结果 |
| --- | --- |
| 实机启动预检 `start-dev.ps1 -CheckOnly` | 通过；Java、Maven 与前端依赖可用，5173、8081 端口空闲；未写运行文件或启动服务 |
| 实机停止预检 `stop-dev.ps1 -CheckOnly` | 未发现当前工作树脚本启动的服务；未终止进程 |
| [启停脚本回归](../../../scripts/tests/dev-launchers.tests.ps1) | 13 项检查、0 失败；覆盖语法、空闲与占用端口、Agent 就绪响应、跨工作树识别、过期 PID 拒绝、匹配进程停止及配置路径；进程与网络使用替身 |
| Vite 实际加载生成配置 | 通过；确认前端端口、8081 API 代理及当前工作树的 `src` 别名；临时验证文件已移除，未启动服务器 |
| Maven 读取默认 `project.build.directory` | `H:\myProjects\aiCodeReview\agent\backend\target` |
| Maven 读取覆盖后的 `project.build.directory` | 传入 `-Dbackend.buildDirectory=target/runtime-local-migration-check` 后返回当前工作树对应目录 |

回归命令：

```powershell
pwsh -NoProfile -File .\scripts\tests\dev-launchers.tests.ps1
```

上述迁移验证未执行真实服务启动、数据库迁移或浏览器联调；预检和替身检查不代表真实联调通过。会话前已有的 `frontend/components.d.ts` 改动保留，不纳入本次提交。

## 启动失败补修（2026-10-21）

负责人首次启动产生的 `20261021-154247-196cf6a4/backend.log` 显示：源码编译完成后，Spring Boot 报 `DataSource: 'url' attribute is not specified`，后端退出码为 1。当前工作树没有实际应用配置，原预检只检查工具和端口，遗漏配置来源，导致较晚才暴露错误。

旧 `code` 工作树本机存在被 Git 忽略的 `backend/src/main/resources/application.yml`，配置的库名为 `code_review`，没有 `agent.datasource`。旧启动脚本依靠 Spring Boot 自动读取该文件；它不随分支迁移，且仅复制它仍无法启用 Agent 接口。本轮只核对配置来源与库名，不复制或输出凭据。

补修增加配置来源检查、`-BackendConfig` 参数及双数据源最小配置样例。Windows PowerShell 5.1 转到 PowerShell 7 时同步转发该参数；外部配置以文件 URI 传入，保留路径中的空格。无来源时在创建运行目录、编译和启动进程之前直接说明缺少的配置。

补修回归为 22 项检查、0 失败，新增覆盖缺少配置、仅有旧链 URL、两组数据源环境变量、显式路径空格、路径不存在、未激活与已激活 profile、Spring 外部配置来源。所有网络与进程仍使用替身；数据库有效性与真实服务联调仍未验证。

实机复验确认：无配置的 `-CheckOnly` 立即失败并提示双数据源配置；通过 Windows PowerShell 5.1 指定带空格的临时配置路径时，自动切换 PowerShell 7 后预检通过。该临时文件仅用于来源检查，已删除；没有启动应用或连接数据库。
