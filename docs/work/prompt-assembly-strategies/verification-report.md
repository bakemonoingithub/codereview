# PAS-02 独立验证报告

日期：2026-11-02（Asia/Taipei）。本轮只验证提示词组装及重构等价性，不评价模型业务结论。负责人允许必要时启动后端，但本轮使用固定输入和本地替身直接核对完整组装输出与请求，无需调用真实模型。

## 1. 验证对象与隔离

- 改造前：`67d411e150deb5299bb9dd1cc11f746672ae9915`，通过 `git archive` 导出。
- 候选：`86fbae43fae6d467d162c576f5ba1006cd5ac0703e833390f721de254adcdfa4`，未提交；交接快照和补丁 SHA-256 均通过复核，见 [源码核对](evidence/pas02-source-check.json)。工作区与隔离副本各 553 份清单文件逐项一致。
- 隔离根目录：`H:\myProjects\aiCodeReview\code\backend\target-pas02`；`before` 和 `after` 分别构建，不切换或覆盖主工作树。完整日志、差分输出及 Surefire 报告保留在该目录。
- 工具链：Windows 11 amd64、Azul Java 17.0.20.1、Maven Wrapper / Maven 3.9.9，见 [环境与变更范围](evidence/pas02-environment.json)。
- 每个测试子进程移除 `DEEPSEEK_API_KEY`、`GITEA_TEST_BASE_URL`、`JAVA_TOOL_OPTIONS`、`JDK_JAVA_OPTIONS`、`MAVEN_OPTS`，传空 `deepseek.api-key`；不启用 `probe.root`。最终全量不传 `agent.live.cancel`，真实取消探针默认跳过。
- 不开启任何基线捕获开关。原有基线和新增 34 条边界期望均未刷新；将 PAS-01 边界测试及原期望复制到 before，只验证其旧实现来源。

## 2. 检查结果

| ID | 结果 | 证据与范围 |
| --- | --- | --- |
| V01 | 通过 | 工作区／快照清单一致；除门面、10 个新增包内类型及必要测试外，无生产改动；模板、工具能力、输出契约、执行循环和请求序列化未改 |
| V02 | 通过 | 门面全部公开方法和有效包内辅助方法保留；八种用途显式路由；直接构造与最小 Spring 装配输出一致；构造时加载模板 |
| V03 | 通过 | before／after 的 `PromptRefactorBaselineTest` 均通过，20 组完整组装、最终 HTTP JSON 和容量字段保持原期望 |
| V04 | 通过 | 工具基线、Schema 及四类纠正消息检查均通过；原工具 JSON／字节数／指纹和纠正正文不变 |
| V05 | 通过 | 普通／工具消息字节与估算口径分别保留；固定请求、补充差分、容量及拆分规划检查通过 |
| V06 | 通过 | 专项、Agent、报告、验证及传输调用方的定向回归通过；仅作为迁移未影响调用方的证据，不评价实际审查结论 |
| V07 | 通过 | 最终全量 143 套件、1026 项，失败／异常为 0，11 项真实探针跳过，见第 4 节 |
| V08 | 通过 | package 退出 0；jar 内清单及全部提示词资源共 31 份与 before／after 源文件逐字节一致，候选 553 份文件最终复核不变 |

## 3. 补充差分与边界

独立采集测试仅使用原门面 API，同一源码分别放入 before／after 的隔离副本。测试源码见 [Pas02DifferentialTest.java](evidence/Pas02DifferentialTest.java)；完整结果分别保留为两个副本的 `backend/pas02-differential.json`，不是替换既有期望的新基线。

279 组完整输出共 3408981 字节，逐字节一致，SHA-256 为 `91f2bdb77456b7ef21acbd20400494743ea13fbbc7e513d804fb8b4481704ba7`，见 [差分摘要与命令](evidence/pas02-differential.json)。比较包含正文、角色、消息／工具顺序、null、快捷字段、工具选择和字节／估算等全部序列化字段；没有排序或归一化输出以消除差异。

| ID | 结果 | 覆盖来源 |
| --- | --- | --- |
| D01 | 通过 | 八种用途 system／protocol／空目标的原 34 条边界；20 组请求；279 组补充样本 |
| D02 | 通过 | 每种用途规则、名称、说明、报告字段、缺失说明分别为 null／空／空白／有值；目标缺失的原错误码与消息一致；材料和事实顺序保留 |
| D03 | 通过 | 文件说明 3999／4000／4001、重试 1999／2000／2001、诊断 499／500／501、缺失说明 999／1000／1001；补充完整输出与原摘要共同核对 |
| D04 | 通过 | 原实现捕获并独立复跑的描述 1999／2000／2001、指导 15999／16000／16001、旧线索 19999／20000／20001 |
| D05 | 通过 | 空内容、无末尾换行、有末尾换行、CRLF、非 1 起始行、缺失 endLine、中文、emoji 和原样占位符 |
| D06 | 通过 | TEXT／FILES／COMMITS，空／多路径，空／多比较、根提交与第一父文本；既有 Agent 测试补充空版本 |
| D07 | 通过 | null／空／多轮历史，assistant 正文为空／null、多工具调用及不同 ID；组装前后历史序列化一致 |
| D08 | 通过 | 默认／自定义读取与树深限额，只读／含提交工具集合，工具验证首轮／回放轮 |
| D09 | 通过 | CompositionTest 的交错、重复与 4 线程 24 项并发组装及历史不变断言 |
| D10 | 通过 | 规则／材料／工具返回中的占位符原样保留；RegistryTest 的资源缺失、重复、变量检查及 CompositionTest 的门面构造时缺失清单失败 |

这些输入不穷举全部组合或任意非法对象。没有为重构新增业务校验。

## 4. 执行统计与命令修订

原命令与完整统计见 [首次检查](evidence/pas02-checks.json)，复验见 [修订命令检查](evidence/pas02-rechecks.json)。各行使用独立构建目录，测试报告位于对应 `backend/target-pas02-<label>/surefire-reports`，日志位于隔离根目录 `<label>.log`。

| 执行 | suites | tests | failures | errors | skipped | 退出码 |
| --- | --- | --- | --- | --- | --- | --- |
| before-core | 23 | 194 | 0 | 0 | 0 | 0 |
| after-core | 24 | 197 | 0 | 0 | 0 | 0 |
| before／after 补充差分，各自 | 1 | 1 | 0 | 0 | 0 | 0 |
| after-full 首次 | 143 | 1026 | 8 | 1 | 11 | 1 |
| before-config-reproduce | 2 | 21 | 8 | 1 | 0 | 1 |
| before-config-clean | 2 | 21 | 0 | 0 | 0 | 0 |
| after-full-clean | 143 | 1026 | 0 | 0 | 11 | 0 |
| after-package（跳过测试，仅验证打包） | 0 | 0 | 0 | 0 | 0 | 0 |

11 项跳过分别为 AgentCancellationLiveTest 的 2 项真实取消探针、AiGatewayProbeTest 的 2 项真实网关探针、GiteaClientLiveTest 的 5 项远端仓库探针，以及 JavaParserProbeTest／StructureLlmProbeTest 各 1 项指定真实项目探针；逐项方法名见复验 JSON。它们不参与本次固定输入下的组装等价结论，未记为真实环境通过。

首次全量严格沿用手册的 `-Dagent.live.cancel=false`，导致 RuntimeConfigurationTest 的 7 项失败／1 项异常及 AgentOnlyStartupTest 的 1 项失败。原因是该测试开关成为 `agent.*` 系统配置，被严格配置绑定拒绝；before 使用相同配置复现全部 9 项，移除参数后 21 项均通过。这是验证命令与配置校验的冲突，不是本次职责拆分的生产差异。未放宽测试断言或修改业务配置校验；已修订验证手册的后续命令。

全量中的独立补充采集测试只存在于隔离副本；其源码单独存证，不改变交接候选的生产、测试或资源清单文件。最终清单复核与打包逐字节比较用于确认本轮未改变候选。

打包身份、资源逐文件哈希及原基线核对见 [打包检查](evidence/pas02-package-check.json)。两份原基线的候选文件均与改造前工作区冻结哈希完全一致：requests.json 为 `c8a05aab6b4a76c5cc97dccafc700eeeaf70da4a9b48647de77c5b4df73cdee4`，tools.json 为 `2d32ae40159a13b5a90a9a83c4e6333c60f75cdc4c4c8388ff1834f21d942f89`。首次打包核对脚本对这两份测试 JSON 使用原始字节直接比较时发现 Git archive 导出为 LF、原工作区冻结文件保留 CRLF；定位为导出换行差异，分别保留导出和候选哈希，并确认只在 CRLF／LF 上不同。没有改写文件或更新期望。该换行处理只用于测试基线文件的来源核对，模型正文、组装输出及 31 份打包提示词仍逐字节比较。

## 5. 结构审查与结论边界

门面使用不可变的用途映射，构造时显式装配策略并加载原模板注册表。专项前置检查仍在专项策略；VALIDATION 走普通组装；报告和 Agent 保留原段落顺序。公共封装分别保留普通消息只计正文、工具消息另计工具定义及历史调用的旧口径，不混用算法。输入仍为 PromptInput／AgentPromptInput 和类型化工具能力，无动态插件框架或运行依赖。

V01–V08 及 D01–D10 均有通过依据；生产行为差异为 0，最终全量与打包通过，PAS-02 done，PAS 工作包关闭。结论绑定上述未提交候选，适用于本轮检查的输入和流程，不宣称任意输入或真实环境完全等价。

未修改生产代码、原基线或提示词资源，未提交或推送。未启动业务服务、未连接现有数据库、未调用真实模型、未使用 computer use，真实模型 token 消耗为 0；未进行页面、部署或审查语义质量验收。
