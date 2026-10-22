# STATE - 专项审查与 Agent 分析

跨会话接续入口；工作树为 `H:\myProjects\aiCodeReview\agent`，分支为 `refactor/agent`。

## 当前状态

当前: P33.4（active）

2026-10-22 负责人要求开始 P33 剩余开发。P33.2、P33.3 已于当日交付并经真实接口验证；P33.4 设为 active，P33.5–P33.6 保持 todo，P33 父阶段 active。

P33.1 已完成：技术设计、原型与真实项目预算实验按 D-91 确认；默认预算（48 轮／96 次网络尝试／192 次工具／20 分钟／16384 输出）以[预算实验](work/agent-redesign/p33/budget-calibration.md)为据。材料不足结果可选材、只使用 DeepSeek 已由 D-90 确认。

P29–P32 已完成并经负责人确认：共用设计与专项原型、公共基础与新库、三类专项、反馈／统计／报告、专项验收；P31.9 补齐真实仓库与 PC 视觉，P32.6 补齐真实模型报告流程。未执行的并发／重启／输入超限与部署演练按各阶段文档保留，不记为通过。

## P33.3 交付

- 发起：`POST /api/v2/agent-tasks`（202）、`GET /agent-tasks/{id}/copy-input`；模型必须通过当前修订的 `AGENT_TOOL_LOOP_V1` 且 optionsHash 一致；指导提示词只收 `AGENT` 用途并冻结正文；参考结果只收同项目完整结果。
- 范围：`TEXT`／`FILES` 重新核对 HEAD（移动返回 `SOURCE_MOVED`），文件逐项校验存在性；`COMMITS` 去重保序、逐项第一父提交、根提交 base 为空，版本分配 `V1…Vm`、比较 `C1…Cn`。
- 快照：`inputSnapshot`／`configSnapshot` version 2，冻结范围、版本、要求、指导提示词、参考结果、模型准入与首版预算。
- 组装：`PromptAssemblyService.assembleAgent` 统一生成 system／user／历史消息，优先级「内置 > 本次要求 > 指导提示词 > 材料」。
- 前端：`/agents/new` 发起页（三种范围、多提交顺序选择、AGENT 提示词过滤）与模型页「Agent 验证」入口。
- 详见 [P33.3 记录](work/agent-redesign/p33/p33-3-inputs.md)。

## P33.2 交付

- 工具：`tree`、`find_files`、`search_code`、`read_code`、`read_diff`；严格 JSON Schema 参数、32 KiB envelope、24 KiB 单材料、查询绑定游标；材料按固定版本与行范围去重落库。
- 检索：从 `GiteaGitMirror` 提炼共享 `GitMirror`；`git grep` 用参数数组、查询经临时文件、`-F -n -I -z`、超时与 2 MiB 有界输出，退出码 1 只表示无匹配。
- 协议：assistant 工具调用、tool 消息、请求 `tools`／`tool_choice=auto`、空正文工具响应；工具定义计入输入估算；`AGENT_TOOL_CHECK` 对 DeepSeek 关闭思考模式。
- 准入：`POST /api/v2/models/{id}/validate-agent` 记录 `scope=AGENT_TOOL_LOOP_V1`，绑定修订、适配器、工具 schema 与选项哈希；模型视图新增 `agentValidationStatus`。
- 详见 [P33.2 记录](work/agent-redesign/p33/p33-2-tools.md)。

## 验证与未验证

- 2026-10-22 全量后端 765 项、0 失败、0 异常、9 跳过（既有真实网关／宿主探针开关，不计为通过）；前端 63 个文件、501 项通过，`vue-tsc --noEmit` 与 `vite build` 通过。
- 真实 `deepseek-flash`（修订 2）`validate-agent`：`PASSED`，4 秒；工具调用与工具历史回放均成功。
- P33.3 真实接口：文件范围与多提交任务各建一个，快照 version 2、比较关系与版本分配正确，`copy-input` 可用，取消成功；`SOURCE_MOVED` 返回 409 与当前 HEAD；快照不含凭据。两个验证任务已取消。
- P33.4 起待实现：执行循环、`submit_result`、预算与容量终止、路由分派；当前 Agent 任务创建后保持 `QUEUED`。
- P33.2 未验证：内网大仓库与 Gitea 宿主真实检索、2 路并发搜索、冷镜像 120 秒、超时／取消竞争；Windows 默认 `MAX_PATH` 下镜像目录过深会让 git 读不到 pack，部署需短镜像目录。
- P31.9／P32 备忘项在部署前补测：GitHub 429／超时、真实多单元拆分、`CONTEXT_LIMIT`、专项输出截断；P32 并发双页面、服务重启、真实输入超限与封存／切换／回退演练。
- 项目 `currentBranch=main` 与远端实际分支不一致，待负责人决定；本次未改配置。

## 接续依据

- [P33 入口](work/agent-redesign/p33/README.md)、[技术设计](work/agent-redesign/p33/design.md)、[预算实验](work/agent-redesign/p33/budget-calibration.md)、[P33.2 记录](work/agent-redesign/p33/p33-2-tools.md)、[P33.3 记录](work/agent-redesign/p33/p33-3-inputs.md)、[调研参考](work/agent-redesign/p33/minimal-agent-research.md)。
- [业务契约](work/agent-redesign/business-contract.md)、[工作区规则](../AGENTS.md)、[ROADMAP](ROADMAP.md)。
- 公共实现：[统一调用](work/agent-redesign/p30/p30-5-assembly-invocation.md)、[任务控制](work/agent-redesign/p30/p30-6-task-control.md)、[P31 材料与拆分](work/agent-redesign/p31/README.md)、[P32 反馈与报告](work/agent-redesign/p32/README.md)。
- [本机开发服务启停](work/agent-redesign/dev-launchers.md)；真实库 `ai_code_review_agent`，服务当前运行在 8081。

## 下一步

1. 开发 P33.4：`AgentTaskWorker` 执行循环、`submit_result` 与引用校验、轮次／网络／工具／期限／上下文／无进展硬限制、取消与迟到响应保护；先读设计第 3、5、7 节与 P33.3 记录。
2. P33.5 做进度／调用记录／结果与来源阅读，P33.6 做验收与混合报告联调。
3. P33.2 的检索边界与 Windows 长路径限制在目标环境实测后回填记录。
4. 部署前补测 P31.9／P32 备忘项；push、合并与真实联调由负责人执行。

## 工作区保留

- P29–P32 各阶段均已本地提交，未 push；最近提交 `352b97b`（P32.4–P32.7）。
- P33.2：`1ebd1b8`（工具与协议）、`36d57d6`（共享镜像 Bean 装配），未 push。
- P33.3：`d3f273d`（发起输入、多提交快照、提示词组装与发起页），未 push。
- `frontend/components.d.ts` 会话前已有未提交改动，不纳入本轮；`../code` 保留其原有未提交内容。

## 已知坑

- JDK 21 在 `D:\IDEA\IntelliJ IDEA 2024.3\jbr`，Maven 在 `D:\IDEA\IntelliJ IDEA 2024.3\plugins\maven\lib\maven3\bin\mvn.cmd`；`java`／`mvn` 不在 PATH，命令前需设 `JAVA_HOME`。
- 后端以 `mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081` 在第 8081 端口运行；重启前先停旧进程，日志放临时目录，不写进 `target/`。
- Spring 组件若保留多个构造器，生产装配的构造器必须标 `@Autowired`，否则启动时报 "No default constructor found"。
- Windows 下 Java `ProcessBuilder` 会把参数内的双引号转义错，用户输入不要直接进命令行；`git grep` 的查询改经临时文件 `-f` 传入。
- `frontend/node_modules` 是指向 `../code/frontend/node_modules` 的目录联接；pnpm 启动器缓存脚本缺失，前端检查通过 Node 直接调用现有依赖。
- Vitest／Vite 会改写 `frontend/components.d.ts`，结束后恢复基线。
- 实际配置在忽略的 `backend/src/main/resources/application.yml`，不得提交；新库账号与旧库分开。
- Surefire 报告会累积历史失败，统计前先 `mvn clean test`；不要重定向到 `target/` 内。
