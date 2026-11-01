# PAR-05 回归、使用验证与交付

日期：2026-11-01。状态：done。范围：对 PAR-03／PAR-04 重构后的模板资源、组装边界和工具描述执行工程回归、打包检查及一次真实使用小样本；本轮不新增业务协议、执行控制、页面、数据库结构或部署方式改动，启动时的既有 Flyway 迁移单独记录。源码基线为 dev 的 `835122d`。

## 1. 环境与对象

- 工程环境：Windows、Java 17.0.20.1、Maven Wrapper，隔离构建目录 `backend/target-par05`。
- 全量回归命令：在 `backend` 下运行 `./mvnw.cmd '-Dbackend.buildDirectory=target-par05' test`；打包命令为同一构建目录加 `'-DskipTests' package`。
- 真实运行使用该构建产出的 `backend-0.0.1-SNAPSHOT.jar`，监听 `8081`；启动时按既有 Flyway 流程将本机库从 v9 升级到 v10（record deletion），验证结束后已停止进程并确认端口释放。
- 业务样本：项目 `codereview`（`01M5VNTMAMXP5EFT7RZCFV7XZ9`），分支 dev，固定 SHA `9b5b393652c76fbfd27a4ccab90ffdcb165c8659`；模型 `deepseek`／`deepseek-flash`（`01M5VNQDTC9JEMTXQ9YYFGWZEM`，修订 0）。
- 目标文件：`backend/src/main/java/com/codereview/agent/prompt/PromptFact.java`。三类专项、代码 Agent 与报告均围绕该文件的小样本执行，不扩大为业务验收。
- 结构化证据：[PAR-05 真实使用证据](evidence/par-05-real-usage.json)。证据不含凭据、认证信息或完整模型正文。

## 2. 工程回归

| 范围 | 套件 | 结果 |
| --- | --- | --- |
| 后端全量 | 134 个套件 | 994 项，失败 0、异常 0、跳过 11 |
| 与本次重构直接相关 | 48 个套件 | 355 项，失败 0、异常 0、跳过 2 |

跳过的 11 项均为既有开关或真实环境探针：`AgentCancellationLiveTest` 2 项、`AiGatewayProbeTest` 2 项、`GiteaClientLiveTest` 5 项、`JavaParserProbeTest` 1 项、`StructureLlmProbeTest` 1 项；不记为通过。相关范围中的 2 项跳过即上述 live 取消探针，其余 353 项通过。

| 验证面 | 采用的检查 | 结果 |
| --- | --- | --- |
| 问题文件／差异、设计模式、耦合 | `PromptAssemblyServiceTest`、`SpecialistPromptBuilderTest`、`SpecialistBoundaryRegressionTest` | 通过 |
| 完整文件与分片 | `SpecialistSplitPlannerTest`、`SpecialistBoundaryRegressionTest` | 通过 |
| 格式纠错与容量 | `PromptAssemblyServiceTest`、`ModelInvocationServiceTest`、`ModelCapacityServiceTest`、`PromptMeteringSampleTest` | 通过 |
| Agent 首轮与工具往返 | `PromptAssemblyAgentTest`、`AgentTaskWorkerTest` | 通过 |
| Agent 四类纠正与非法批次 | `AgentTaskWorkerTest`、`PromptCorrectionTest` | 通过 |
| 取消、迟到响应、硬限制 | `AgentTaskWorkerTest`、`TaskDeadlineSweeperTest`、`AgentResultPublisherTest` | 通过；真实取消探针保持跳过 |
| 报告无规则／专用规则／COMMON 规则 | `PromptAssemblyServiceTest`、`ReportGenerationServiceTest`、`ReportFactsBuilderTest` | 通过 |
| 报告来源引用与执行控制 | `ReportExecutionWorkerTest`、`ReportCitationValidatorTest` | 通过 |
| 连通验证与工具协议两轮 | `AgentModelServiceTest`、`AgentToolLoopValidatorTest`、`ModelToolProtocolTest` | 通过 |
| 工具描述与参数约束 | `ToolDefinitionBaselineTest`、`AgentToolSchemaTest`、`ToolArgumentParserTest` | 通过 |
| 迁移前请求基线 | `PromptRefactorBaselineTest`（20 组固定请求） | 通过，未重新捕获 |

本轮全量一次通过，未重复 PAR-04 记录中的一次性 Windows 临时目录清理错误。日志内的异常堆栈来自既有负向用例，不计为失败。

## 3. 打包与资源加载

- 产物 `BOOT-INF/classes/prompts/` 含清单与 30 份模板，共 31 个条目；逐文件字节与 `backend/src/main/resources/prompts` 一致，清单声明 30 份且无缺失、无未声明条目、无重复标识。
- 模板类别数量：内置指令 8、输出协议 8、协议封套 1、系统提示词 4、Agent 用户段 3、格式重试 2、纠正事件 4。
- 真实服务由打包 jar 启动，因此以下检查同时证明资源是从发布产物加载，而不是开发机源目录：ISSUE_FILE、PATTERN、COUPLING、AGENT、REPORT 五个用途的实际 `system` 正文与清单模板渲染结果逐字一致。
- 实际请求中未出现模板标识、清单路径或未替换的 `{{...}}`；Agent 请求携带 `tree`、`find_files`、`search_code`、`read_code`、`read_diff`、`submit_result` 六个工具，名称与冻结定义一致。

## 4. 真实使用验证

| 路线 | 任务／报告 | 结果 | 核对要点 |
| --- | --- | --- | --- |
| 问题文件审查 | `01M6AQZDJ818NTHMNBQHF0TKFB` | SUCCEEDED | 结果 `01M6AQZEXXEGX7TDANNE7J4QYA`，ISSUE，COMPLETE，问题 0 |
| 设计模式 | `01M6AQZNSBXHK3ZNA8M7F0S5QB` | SUCCEEDED | 结果 `01M6AQZQQ52YRRAWY3G4RFWD35`，PATTERN，COMPLETE，候选 1 |
| 耦合度 | `01M6AQZP8FG7CSEJ53YSSN18EW` | SUCCEEDED | 结果 `01M6AQZQRP29H6PJEB0T86D209`，COUPLING，COMPLETE，覆盖率 0.0 被保留 |
| 代码 Agent | `01M6AQZZES1S7DMY3DSYH2W636` | SUCCEEDED | 2 轮、1 次工具尝试、2 次网络尝试；`read_code` 与 `submit_result` 各一次 OK；ANSWERED，1 引用、1 未解决 |
| 混合来源报告 | `01M6AR0ZWAE1KBXP59A43J7708` | SUCCEEDED | 4 个来源，ISSUE／PATTERN／COUPLING／AGENT 各 1；正文含 `[S1]`–`[S4]`，下载与正文一致 |
| 连通验证 | 模型 `01M5VNQDTC9JEMTXQ9YYFGWZEM` | PASSED | scope=CONNECTIVITY；返回最小 JSON |
| 工具协议验证 | 同一模型 | PASSED | scope=AGENT_TOOL_LOOP_V1；第 1 轮 `read_code`，第 2 轮返回 41 字符 |

实际模型调用与用量：

| 调用 | 输入 token | 输出 token | 合计 |
| --- | --- | --- | --- |
| ISSUE_FILE | 877 | 70 | 947 |
| PATTERN | 799 | 217 | 1016 |
| COUPLING | 959 | 147 | 1106 |
| Agent 第 1 轮 | 3598 | 108 | 3706 |
| Agent 第 2 轮 | 4202 | 938 | 5140 |
| REPORT | 2342 | 5630 | 7972 |
| 合计 | 12777 | 7110 | 19887 |

报告输出含 3913 个 reasoning token；用量包含缓存命中输入。未发生失败重跑或额外总结调用。

已核对：

1. 三类专项均返回合法 JSON：问题审查的 `issues` 为空，模式候选证据落在实际材料行 9–18，耦合结果保留“覆盖率 0.0 不等于零依赖”的限制。
2. Agent 首轮携带固定文件指引与工具定义，工具返回材料为 1–19 行完整文件；随后显式提交，引用 E1 覆盖 1–19 行且 SHA 与固定版本一致。未触发纠正事件。
3. 报告冻结四类来源，生成正文 2895 字符；来源引用标签共 61 处，来源顺序与 `[S1]`–`[S4]` 对应；下载文件 5975 字节，内容与正文逐字相同。
4. 两类模型验证均针对当前修订 0 通过，模型视图的 `validationStatus` 与 `agentValidationStatus` 均为 PASSED。
5. 五个用途的实际 `system` 正文与打包模板逐字一致；模板标识和占位符未进入模型正文；Agent 工具定义计数与冻结定义一致。
6. 本轮未修改模型参数或凭据；新建任务与报告名称带 “PAR-05” 标识，任务、报告及验证记录均保留在现有库中，未执行清理。

## 5. 未验证与边界

- 真实使用只覆盖一个项目、一个模型、一个 19 行小文件；不替代多文件、大仓库、超限拆分、并发、取消竞争或长任务验收。
- 未真实触发 Agent 四类纠正、重复无进展、材料不足提交、非法批次、取消与硬限制；这些仍以工程检查为据，不记作真实通过。
- 报告真实样本为无通用规则、四类混合来源；专用 REPORT 规则与 COMMON 规则的真实报告未另跑，规则选择与冻结由现有服务测试覆盖。
- 未执行页面视觉、浏览器交互、服务重启后的在途任务恢复、另一台机器的 classpath 加载或新工作区检出复核。
- 模型输出具有随机性；本样本成功不承诺后续请求逐字或逐条结论一致，也不把模型结论当作人工确认的事实。
- 本轮启动了本机后端并写入现有库的任务、报告和验证记录；启动时既有 Flyway 迁移将库从 v9 升至 v10（record deletion），未执行手工 SQL、迁移回退、数据清理或部署。真实环境与目标内网仍按部署流程另行验证。

## 6. 发布与回退

- 运行时发布单元仍是应用 jar 与 `backend/src/main/resources/prompts` 模板、工具定义、纠正模板及冻结基线；不能只替换模板或只回退代码，避免正文、schema 与校验版本混搭。
- PAR-05 本身只新增验证记录与文档，不新增运行时代码、数据库迁移、热更新、在线模板管理或插件执行能力。
- 若本次发布需要回退，整体回到 PAR-04 的 `835122d` 或其对应构建产物；已写入的业务任务、报告与验证记录按现有数据保留规则处理，不用当前模板重建历史请求。本机库已由既有迁移升至 v10，回退代码前须确认目标版本与 v10 兼容，不能以回退 jar 自动降级 schema。
- 模板资源已进入构建产物，不依赖开发机绝对路径；未新增宿主机或镜像运行依赖。

## 7. 状态

工程矩阵、打包资源检查与要求的最低真实使用验证均已完成，PAR-05 标记 done；PAR-01–PAR-05 全部完成，当前无本工作包 active。未验证项按上文保留，不因本记录自动扩展为插件框架、数据库迁移或正式部署授权。状态同步见[重构计划](README.md)、[ROADMAP](../../ROADMAP.md)与[STATE](../../STATE.md)。
