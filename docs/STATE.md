# STATE - 专项审查与 Agent 分析

跨会话接续入口；当前工作树为 `H:\myProjects\aiCodeReview\code`，主线分支为 `dev`。此前 `agent/` 开发路径已随 `agent` 分支合并至本分支，不再是独立 Git 工作树。

## 当前状态

2026-10-26 CFG 审查修复：提交差异选材与文件树统一使用运行后缀白名单，空白 `expected-database` 在业务组件创建前拒绝；11 个后端套件、132 项通过。详见[实施记录第 6 节](work/agent-redesign/runtime-configuration/implementation-20261026.md#6-审查修复2026-10-26)。

当前：CFG-02–05 配置规范、后端参数外置、Agent 冻结预算及前端运行配置已完成，并通过定向工程检查。后端 533 项、前端 40 项及隔离构建通过；既有计量断言、类型错误和旧菜单断言已在改造前基线复现，不记为通过。详见[运行配置实施记录](work/agent-redesign/runtime-configuration/implementation-20261026.md)及[配置规范](work/agent-redesign/runtime-configuration/specification.md)。CFG-06／07 保持 todo，未操作真实数据库或启停现有服务；UPI-06 待验收状态不变。

UPI 状态：UPI-06 报告通用规则框架实现及定向工程检查已完成，等待隔离库、真实报告和负责人 PC 正式页面视觉验收。UPI-05 已于 2026-10-25 确认完成。

2026-10-25 负责人确认 UPI-05 PC 正式页面视觉验收完成，UPI-05 标记 done。三类专项、Agent 与四来源报告小样本均成功，共 6 次真实模型调用、10611 输入／3507 输出 token；版本冻结、优先级、复制、引用、下载及旧策略／错用途／缺版本拒绝均核对。详见[业务测试记录](work/agent-redesign/prompt-input-unification/business-smoke-20261025.md)。未执行边界仍按未验证记录，不记作通过；验收总记录见[UPI-05 记录](work/agent-redesign/prompt-input-unification/validation-20261025.md)。

2026-10-25 UPI-06 原型与接口字段稿获负责人确认后完成框架实现：报告生成可选 REPORT 通用规则及固定版本，规则正文与本次补充要求随入队快照冻结，详情显示实际冻结配置；规则置于补充要求字段上方。报告来源冻结与混合来源逻辑未改。工程检查与未执行验收边界见[UPI-06 实施记录](work/agent-redesign/prompt-input-unification/upi-06-implementation-20261025.md)。当前等待负责人 PC 正式页面视觉验收、隔离库 V8 初始化及真实混合来源报告验收，不提前标记 done；后端服务保持停止。

2026-10-25 UPI 审核修复：已修复模型在途引用删除保护、规则首次加载失败重试和规则列表分页，详见[修复记录](work/agent-redesign/prompt-input-unification/review-fixes-20261025.md)。未执行边界沿用验收记录并保持未验证状态。

2026-10-25 负责人确认[专项发起原型](work/agent-redesign/prompt-input-unification/prototype.html)及[接口字段稿](work/agent-redesign/prompt-input-unification/interface.md)，UPI-02 done。UPI-03 后端和 UPI-04 前端改造及定向工程检查已完成。UPI-05 已完成隔离空库 V1–V7 初始化、专项接口联调、真实调用和五类业务小样本；负责人确认 PC 正式页面视觉后，UPI-05 标记 done。未执行矩阵项继续作为未验证边界记录。负责人现已明确启动 UPI-06，业务依据及实施方案见[契约第 9 节](work/agent-redesign/business-contract.md#9-提示词输入统一与策略退出2026-10-25)、[UPI 工作包](work/agent-redesign/prompt-input-unification/README.md)、[前端方案](work/agent-redesign/prompt-input-unification/frontend.md)和[后端方案](work/agent-redesign/prompt-input-unification/backend.md)。P29–P33 保持 done。

2026-10-22 负责人要求开始 P33 剩余开发。P33 全部子任务已于当日交付：P33.6 完成专项／Agent 混合报告联调，真实报告同时引用两类来源并保留材料不足限制。当前无 active，等待负责人分诊。

P33.1 已完成：技术设计、原型与真实项目预算实验按 D-91 确认；默认预算（48 轮／96 次网络尝试／192 次工具／20 分钟／16384 输出）以[预算实验](work/agent-redesign/p33/budget-calibration.md)为据。材料不足结果可选材、只使用 DeepSeek 已由 D-90 确认。

P29–P32 已完成并经负责人确认：共用设计与专项原型、公共基础与新库、三类专项、反馈／统计／报告、专项验收；P31.9 补齐真实仓库与 PC 视觉，P32.6 补齐真实模型报告流程。未执行的并发／重启／输入超限与部署演练按各阶段文档保留，不记为通过。

## P33.6 交付

- 候选：`answerStatus`／`unresolvedCount` 进入报告候选，材料不足结果可选但明确标注。
- 冻结：Agent 正文按结构化 JSON 冻结（兼容旧纯文本），固定版本取自任务输入；材料键兼容 `id`／`materialId`。
- 事实与指令：Agent 状态、未解决事项、引用证据进入事实包；报告指令要求不得把「未取得」写成「不存在」。
- 来源阅读：报告来源抽屉展示 Agent 状态／Markdown／未解决／引用；候选点击进入 Agent 详情。
- 真实混合报告 `01M5HF6V…`：2 个来源、8664 字符、`[S1]`／`[S2]`、下载 14614 字节；两轮失败样本（材料键、纯文本冻结）保留。详见 [P33.6 记录](work/agent-redesign/p33/p33-6-acceptance.md)。

## P33.5 交付

- 接口：`GET /agent-tasks/{id}/progress`、`/tool-calls`（页码，默认 20／上限 100）、`/tool-calls/{callId}`、`/result`；材料正文复用公共任务材料端点。
- 进度取 `agent_run` 与冻结限额；调用列表不放大正文，详情返回参数与 envelope；结果只读 `review_result(kind=AGENT)`，未发布时 404。
- 前端：`/reviews/{id}` 先按路线分流，Agent 详情含结果／调用记录／进度、来源材料阅读、Markdown 下载与取消；运行中 2 秒轮询，终态停止。
- 真实读取：成功任务 9 条调用（含 2 次提交尝试）、17 引用、3 未解决；失败任务结果返回 404，无伪造空结果。详见 [P33.5 记录](work/agent-redesign/p33/p33-5-progress.md)。

## P33.4 交付

- 循环：`AgentTaskWorker` 按任务路线接入调度，模型调用只读工具、材料回填下一轮、显式 `submit_result` 结束；单轮最多 4 个工具、任务内串行。
- 限额：48 轮、96 次网络尝试、192 次工具、20 分钟、单次 120 秒、输出 16384、上下文 80%、连续 3 轮无进展；命中限制不追加总结调用。
- 提交：结构／引用／行号校验，只允许实际 `SENT` 的材料；结果与任务终态同一事务发布，`ANSWERED`／`INSUFFICIENT_EVIDENCE` 分别对应无／有停止原因。
- 存储：`V6__agent_run.sql` 新增 `agent_run`、`agent_tool_call`、`model_call.turn_no`；发送材料写入 `call_material(SENT)`。
- 真实失败样本：未暴露 `submit_result` 时 14 轮后 `PROTOCOL_INVALID`；补齐工具定义后同一问题成功。详见 [P33.4 记录](work/agent-redesign/p33/p33-4-execution.md)。

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

- 2026-10-22 全量后端 789 项、0 失败、0 异常、9 跳过（既有真实网关／宿主探针开关，不计为通过）；前端 64 个文件、506 项通过，`vue-tsc --noEmit` 与 `vite build` 通过。
- 真实 `deepseek-flash`（修订 2）`validate-agent`：`PASSED`，4 秒；工具调用与工具历史回放均成功。
- P33.3 真实接口：文件范围与多提交任务各建一个，快照 version 2、比较关系与版本分配正确，`copy-input` 可用，取消成功；`SOURCE_MOVED` 返回 409 与当前 HEAD；快照不含凭据。两个验证任务已取消。
- 真实混合报告：专项 + Agent 来源冻结、生成、引用、下载与来源抽屉数据均通过；失败样本保留。
- P33.5 真实读取：成功任务进度 `SUCCEEDED`（5 轮／7 工具／5 网络，限额 48），调用 9 条（末条 `submit_result:OK`），结果 `ANSWERED` 17 引用／3 未解决／2913 字符，引用材料 39–43 行可读；失败任务进度 `PARTIAL / PROTOCOL_INVALID`、结果 404。
- 真实 Agent 任务：失败样本 `01M5HD85…` 14 轮后 `PROTOCOL_INVALID`（`submit_result` 未暴露）；成功样本 `01M5HDEK…` 5 轮、7 次工具、6 次模型调用、7269 输出 token，`ANSWERED`、17 引用、3 未解决、23 材料，含一次提交纠错。
- P33.2 未验证：内网大仓库与 Gitea 宿主真实检索、2 路并发搜索、冷镜像 120 秒、超时／取消竞争；Windows 默认 `MAX_PATH` 下镜像目录过深会让 git 读不到 pack，部署需短镜像目录。
- P31.9／P32 备忘项在部署前补测：GitHub 429／超时、真实多单元拆分、`CONTEXT_LIMIT`、专项输出截断；P32 并发双页面、服务重启、真实输入超限与封存／切换／回退演练。
- 项目 `currentBranch=main` 与远端实际分支不一致，待负责人决定；本次未改配置。

## 接续依据

- [P33 入口](work/agent-redesign/p33/README.md)、[技术设计](work/agent-redesign/p33/design.md)、[预算实验](work/agent-redesign/p33/budget-calibration.md)、[P33.2 记录](work/agent-redesign/p33/p33-2-tools.md)、[P33.3 记录](work/agent-redesign/p33/p33-3-inputs.md)、[调研参考](work/agent-redesign/p33/minimal-agent-research.md)。
- [业务契约](work/agent-redesign/business-contract.md)、[工作区规则](../AGENTS.md)、[ROADMAP](ROADMAP.md)。
- 公共实现：[统一调用](work/agent-redesign/p30/p30-5-assembly-invocation.md)、[任务控制](work/agent-redesign/p30/p30-6-task-control.md)、[P31 材料与拆分](work/agent-redesign/p31/README.md)、[P32 反馈与报告](work/agent-redesign/p32/README.md)。
- [本机开发服务启停](work/agent-redesign/dev-launchers.md)；真实库 `ai_code_review_agent`，服务当前运行在 8081。

## 下一步（2026-10-22 P33 交付时的历史安排）

1. 当前无 active：负责人可分诊部署前补测（PC 视觉、并发双页面、服务重启收敛、真实上限触达、内网大仓库／Gitea、封存与回退演练）或新工作。
2. P33.5 做进度／调用记录／结果与来源阅读，P33.6 做验收与混合报告联调。
3. P33.2 的检索边界与 Windows 长路径限制在目标环境实测后回填记录。
4. 部署前补测 P31.9／P32 备忘项；push、合并与真实联调由负责人执行。

## 工作区保留

- P29–P32 各阶段均已本地提交，未 push；最近提交 `352b97b`（P32.4–P32.7）。
- P33.2：`1ebd1b8`（工具与协议）、`36d57d6`（共享镜像 Bean 装配），未 push。
- P33.3：`d3f273d`（发起输入、多提交快照、提示词组装与发起页），未 push。
- P33.4：`dcdfe27`（执行循环、预算、提交与结果发布、V6 迁移），未 push。
- P33.5：`f94064a`（进度／调用／结果读取与 Agent 详情页），未 push。
- P33.6：`5558fde`（Agent 结果进入候选、冻结、事实、报告指令与来源阅读），未 push。
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

## 2026-10-23 单库启动调整

负责人要求当前分支废弃旧库依赖，只使用 ai_code_review_agent。实际仓库为 H:\myProjects\aiCodeReview\code，分支 dev；原 agent 工作树入口已失效。

应用只扫描 Agent 与共用 Git 组件，保留 Web/Jackson 配置；不注册旧控制器、服务和 Mapper，排除旧数据源与 Flyway 自动配置。唯一运行数据源为 agent.datasource，缺少配置直接启动失败。旧库及历史源码保留，不删除数据；本机配置、配置样例与启动检查同步调整。

实际启动仅出现 agent-db 连接池，ai_code_review_agent 的 6 个迁移校验通过，无待执行迁移；通过 5173 代理调用健康检查和项目列表成功。启动脚本回归 32 项通过，后端单库启动与迁移防护相关测试 14 项通过；旧 /api/projects 返回 404。页面视觉及完整业务流程未在本次重跑。

## 2026-10-23 Agent 指定文件提示词修复

已补齐冻结 scope.paths 到模型提示词的传递，明确优先直接读取指定目标；19 项相关测试通过。真实任务 01M5K73RVKSC04RDQYSQBG032J 前 3 轮直接读取全部 5 个目标文件，17 轮后 SUCCEEDED / ANSWERED，29 条引用范围核对通过。版本按当前远端 HEAD 重新固定，不能作为原任务同版本性能对比。详见[修复与重跑记录](work/agent-redesign/p33/file-scope-prompt-fix.md)。

## 2026-10-23 Agent 测试计划手册

已按负责人要求编制 [Agent 分析测试计划手册](work/agent-redesign/p33/agent-test-plan.md)，固定编号 AGT-001 至 AGT-034，覆盖范围输入、真实调查、提示词与参考结果、工具协议、限额、取消重启、报告、界面和质量核对。包含实际输入、步骤、预期、证据与运行记录模板；本次仅编制计划，34 个用例均未在本轮执行，不变更业务阶段状态。

## 2026-10-23 AGT 第一批六项测试

已按负责人指示执行 AGT-002、004、007、013、017、019，任务／报告／受控测试名称含用例编号。目录、多提交、材料不足及混合报告真实流程成功；模型等待取消以真实任务与迟到响应回归补证，发布先后顺序以真实数据库和取消 API 的受控集成测试验证。保留 AGT-007 过度断言措辞、AGT-002 目录指引措辞及界面未验的限制；详见[执行记录](work/agent-redesign/p33/agt-batch-20261023.md)。

## 2026-10-23 AGT-008 至 AGT-012 测试

已执行指导提示词、冻结、参考结果、分页和零命中五项；复用 AGT-008／009 旧版任务，共 5 个实际任务。四个任务成功，新版对照任务因引用纠错耗尽为 PARTIAL／PROTOCOL_INVALID；旧版 17 次请求均为 V1，新版 18 次均为 V2，冻结范围验证通过。专用提示词已恢复原正文；保留新版纠错失败和 AGT-011 来源措辞观察。详见[执行记录](work/agent-redesign/p33/agt-prompts-20261023.md)。

## 2026-10-23 AGT-003 与 AGT-005 提交分析测试

已执行两个编号真实任务，均 SUCCEEDED／ANSWERED：单提交比较核对 5 个文件，合并提交按第一父核对 44 项清单；59 条引用均有本次已发送材料且范围合法，未串入第二父版本。模型风险结论与合并任务未逐项读取的范围保留为验证限制。详见[执行记录](work/agent-redesign/p33/agt-commits-20261023.md)。

## 2026-10-23 BIZ-RPT-002 三类专项业务报告

已执行问题、耦合度、设计模式各 3 文件审查。首轮 8/9 完整，执行器耦合度因 severity 额外字段失败；保留原记录，补测该文件成功。9 份 COMPLETE 来源、同一 SHA、4 个任务生成报告 01M5KRC97552ZX5QNHR69QN2TY，SUCCEEDED，三类各 3 份、S1–S9 和下载一致性核对通过。19 条问题为待复核模型发现，不能作为已确认缺陷；详见[业务报告执行记录](work/agent-redesign/p33/biz-rpt-002-20261023.md)。
## 2026-10-23 长代码与调用 JSON 展示修复

实际调用正文保留 JSON 缩进与语法高亮，默认自动换行，长字符串可在窗口内阅读；支持切换为横向滚动，切换不改写保存内容。代码材料、报告来源、范围预览和 Agent 固定版本预览补齐滚动容器，保留原始行号与范围高亮。

相关 6 个组件／页面测试文件原有 24 项通过；新增长 JSON 显示切换与内容转义回归后，调用阅读组件 4 项通过，前端类型检查通过。真实 BIZ-RPT-002 调用请求约 14 万字符，浏览器确认默认换行时正文滚动宽度等于可视宽度、关闭后可横向滚动；代码材料保持不换行。此次仅修复展示，不重新发起模型分析，不变更业务阶段状态。截图见 [JSON 换行效果](work/agent-redesign/p33/evidence/call-json-wrap-20261023.png)。
## 2026-10-23 报告实际调用阅读修复

修复报告页把分段正文接口误当完整调用详情读取的问题。新增完整详情端点并同步前端，原始请求、返回、用量及状态已在 BIZ-RPT-002 报告页验证；正文与原接口一致，无需重新生成报告。前端相关 28 项、后端相关 23 项及类型检查通过。详见[报告执行记录末节](work/agent-redesign/p33/biz-rpt-002-20261023.md#2026-10-23-报告实际调用阅读修复)。

## 2026-10-24 专项提交选择器改进

专项提交选择已补齐独立弹窗、历史分页、已加载内容筛选、完整 SHA 定位、虚拟列表、失败同页重试与关闭／换源的迟到响应保护。4 个相关前端测试文件、46 项通过，类型检查与隔离生产构建通过。视觉验收由负责人执行，真实宿主历史完整性未在本轮验收；详见[改进记录](work/agent-redesign/commit-picker-20261024.md)。不变更业务阶段状态。

## 2026-10-25 提示词版本选择

按负责人确认的方案补齐审查策略与 Agent 发起时的具体提示词版本选择、正文预览、专项发起展示、版本归属校验、历史参数复制及任务冻结。旧策略保留空版本关联行为；业务规则见[契约第 6.1.4 节](work/agent-redesign/business-contract.md#614-提示词版本选择补充2026-10-25)。工程验证和运行边界见[改动记录](work/agent-redesign/prompt-version-selection-20261025.md)。本轮未在实际数据库执行 V7、未重启当前服务、未发起真实模型任务。负责人已完成正式页面 PC 视觉确认，并明确授权提交。此项不重新开启 P29–P33 阶段。

## 2026-10-25 报告审查结果选择弹窗

按负责人要求将生成报告表单中的结果选择改为独立弹窗，支持筛选、分页、跨筛选草稿、确认回填、取消保留和结果预览。业务规则见[契约第 6.4.3 节](work/agent-redesign/business-contract.md#643-报告审查结果选择弹窗2026-10-25)，工程验证及运行边界见[改动记录](work/agent-redesign/report-result-picker-20261025.md)。负责人明确视觉测试由本人执行，本轮不记为视觉通过；未修改后端、操作数据库或生成新报告，不重新开启 P29–P33 阶段。
