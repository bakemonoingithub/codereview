# 本机开发服务启停

2026-10-21 按负责人要求，将其他分支的开发启停脚本迁入 `refactor/agent`。本记录说明使用方法、迁移差异与验证结果；不改变 P31 的阶段状态。

## 使用方法

在 `H:\myProjects\aiCodeReview\agent` 打开 PowerShell，先检查依赖与端口，再启动：

```powershell
pwsh -NoProfile -File .\scripts\start-dev.ps1 -CheckOnly
pwsh -NoProfile -File .\scripts\start-dev.ps1
```

启动后保留终端，等待前后端均就绪，再打开 <http://127.0.0.1:5173/>。后端使用 `8081`，前端 `/api` 请求转发到该端口。按 `Ctrl+C` 停止本次启动的服务，或从另一个终端执行：

```powershell
pwsh -NoProfile -File .\scripts\stop-dev.ps1 -CheckOnly
pwsh -NoProfile -File .\scripts\stop-dev.ps1
```

启动等待默认最多 180 秒，可通过 `-StartupTimeoutSeconds 300` 调整。超时或任一服务提前退出时，脚本报错并停止本次启动的进程树。端口已占用时直接拒绝启动，不自动终止占用者。

## 配置与运行文件

- 需要 Windows、PowerShell 7、Node.js、已安装的前端依赖、Maven 与 JDK。Windows PowerShell 5.1 调用脚本时会尝试切换到 PowerShell 7。
- Maven 优先从 `PATH` 查找，Java 优先使用 `JAVA_HOME`。本机候选路径在 [dev.config.psd1](../../../scripts/dev.config.psd1) 中维护。
- 后端配置通过本机 `application.yml` 或 Spring Boot 支持的环境配置提供，参考 [application.example.yml](../../../backend/src/main/resources/application.example.yml) 中的 `agent.datasource.*` 说明。必须配置隔离的 Agent 数据库；未配置时 `/api/v2/health` 不注册，脚本不会判为就绪。
- `-CheckOnly` 只检查工具、前端依赖文件与端口，不验证数据库连接、账号或迁移。正常启动会执行应用既有的数据库检查与迁移流程。本次迁移没有创建配置文件、复制凭据或执行数据库操作。
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

未执行真实服务启动、数据库迁移或浏览器联调；预检和替身检查不代表真实联调通过。会话前已有的 `frontend/components.d.ts` 改动保留，不纳入本次提交。
