# PAS-01 实施交接

日期：2026-11-02。PAS-01 已完成实施和基本检查；PAS-02 为 todo，独立等价验证、最终全量回归及打包检查尚未执行。下一会话先读 [验证手册](verification.md)，不得将本记录当作独立验收结果。

## 1. 改造前与候选标识

- 工作树：`H:\myProjects\aiCodeReview\code`，分支 `dev`。
- 改造前生产源码：`67d411e150deb5299bb9dd1cc11f746672ae9915`。开始时只有本会话前序计划文档未提交，未发现生产代码的既有未提交改动。
- 候选尚未提交或推送。后端候选 ID：`86fbae43fae6d467d162c576f5ba1006cd5ac0703e833390f721de254adcdfa4`。
- [candidate-manifest.json](evidence/candidate-manifest.json) 覆盖 553 份后端源码、测试、资源、构建输入和根 `.gitattributes`；候选 ID 是按路径排序后文件哈希映射的紧凑 JSON 的 SHA-256。文档和构建产物不参与后端身份计算。
- [before-manifest.json](evidence/before-manifest.json) 保存原组装类、两份原有基线和全部提示词资源的改造前 SHA-256。原有基线和全部提示词资源逐文件复核不变。

候选副本位于 `H:\myProjects\aiCodeReview\code\backend\target-pas01-handoff\candidate-source.zip`，是原提交完整仓库快照叠加本轮修改及新增源码／文档，未包含本机配置、凭据、依赖缓存或构建目录。快照 SHA-256、大小和补丁哈希见 [snapshot.json](evidence/snapshot.json)。已有跟踪文件的完整改动补丁在同目录 `candidate.patch`，新增文件由源码副本提供，不依赖未提交索引。

验证时将快照解压到新的隔离目录，用候选清单核对源码；不要覆盖主工作树。改造前副本从上述提交导出。快照是本次本机交接产物，不是可部署发行包；后续清理构建目录前需保留它，或依据源码、补丁及新增文件重建并验证清单。若快照丢失或当前源码清单不匹配，先恢复明确候选，不能直接验证不断变化的工作区。

## 2. 实现与调用对应

组件位于 `backend/src/main/java/com/codereview/agent/prompt/`；除门面外均为包内组件，由门面无参构造显式装配，不新增运行依赖或动态插件注册。

| 门面入口 | 委托 | 说明 |
| --- | --- | --- |
| assemble：四种专项用途 | SpecialistPromptStrategy → StandardPromptStrategy | 原有目标材料检查在专项策略；普通段落顺序由 Standard 负责 |
| assemble：REPORT | ReportPromptStrategy | 报告任务、规则、事实、缺失和重试的顺序保持 |
| assemble：VALIDATION | StandardPromptStrategy | 无目标材料仍可组装，保持原普通消息结构 |
| assemble：AGENT／AGENT_TOOL_CHECK | StandardPromptStrategy | 保留通用 API 原接受行为；实际业务仍走以下专用入口 |
| assembleAgent | AgentPromptStrategy | 类型化 Agent 输入、固定版本／比较、可选指导／线索、历史回放 |
| assembleToolCheck／assembleToolCheckFollowUp | ToolCheckPromptStrategy | 首轮／回放轮，工具能力仍由 OpenAiChatToolAdapter 转换 |
| systemPrompt／protocolBody | PromptSystemRenderer | 原模板选择及契约片段注入，仍消费原有权威契约 |
| correction | AgentCorrectionRenderer | 四类事件正文渲染，触发逻辑仍在执行器 |

`PromptInputStrategy` 只覆盖接收 `PromptInput` 的策略，不把 Agent 和工具验证输入改为 Object。用途映射显式列出全部枚举，新增用途未注册会在构造时失败。

`PromptSections` 承接材料格式、原行号、缺失和重试渲染、截断等；`PromptMessageFactory` 承接普通／工具两种消息封装和计量，保留原来工具定义、历史、工具名／参数及封装开销的口径。门面的有效包内辅助方法保留委托，公开版本常量及全部原公开方法签名不变。模板仍在门面构造时完整加载校验；Spring 与直接 new 使用同一路径。

生产改动仅为原门面和新增 10 个包内类型。没有修改模板、工具能力、输出契约、HTTP 请求序列化、容量算法、执行循环、数据结构、数据库、前端或 `AgentDatabase` 注释。BRC 维护索引已补充迁移后的关联位置。

## 3. 新增检查及原实现来源

新增 `PromptAssemblyBoundaryBaselineTest` 和 `src/test/resources/prompt-baseline/pas01-boundaries.json`。在改动生产类前，使用原提交的生产实现和同一份新测试，通过一次性 `pas.captureBoundaryBaseline=true` 捕获；之后候选运行没有捕获参数。该参数拒绝覆盖已存在文件，不得删文件重录。

共 34 个输出条目：3 个专项长度边界、3 个 Agent 长段边界、八种用途各自 system／protocol／空目标行为共 24 项，以及四类纠正事件。组装结果采用 Jackson 序列化后 SHA-256，异常保存原错误码和消息。覆盖 CRLF、中文、非 ASCII 字符、模板占位符原文、非 1 起始行、缺失 endLine、材料诊断／缺失说明超长、多个历史工具调用、根提交比较等。捕获文件的 JSON 排版换行统一为 LF，已核对解析后的 34 个期望值不变；没有重新捕获输出。摘要不替代原有完整请求基线；失败时应对相同输入在 before 输出完整结构定位，不能只报告摘要不同。

验证会话可把该测试与原始期望文件复制到 before 隔离副本，不传捕获参数复跑，以独立确认期望来源。新增测试不依赖新策略类型，在原门面上可执行。原有两个基线未更新。

另新增 `PromptAssemblyCompositionTest`：无数据库的最小 Spring 上下文与直接构造对照、交错并发组装与历史不被改写、清单缺失在构造时失败。没有为每个新类新增镜像测试。

验证手册 D01–D10 仍需下一会话检查。当前新增快照覆盖其中部分边界，未声称已穷举可选输入、全部临界组合或正式独立验证。

## 4. 本会话检查结果

环境：Windows PowerShell；Java 为 `C:/Users/ysx/.jdks/azul-17.0.20.1`，使用仓库 backend Maven Wrapper。每次测试在独立 shell 子进程移除 `DEEPSEEK_API_KEY`、`GITEA_TEST_BASE_URL`、`JAVA_TOOL_OPTIONS`、`JDK_JAVA_OPTIONS`、`MAVEN_OPTS`，显式传空 `deepseek.api-key`、`agent.live.cancel=false`，不启用 probe.root。未输出任何凭据。

| 执行范围 | 构建目录（backend 下） | 套件 | tests | failures | errors | skipped | 退出码 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 原实现基线及边界捕获，两次执行的报告合计 | target-pas01-before | 4 | 10 | 0 | 0 | 0 | 0 |
| 候选组装、原有基线、新边界及容量拆分 | target-pas01-after | 11 | 66 | 0 | 0 | 0 | 0 |
| 候选装配、主要调用方和契约关联 | target-pas01-callers | 9 | 93 | 0 | 0 | 0 | 0 |

候选两批共 20 个不同套件、159 项；详细套件名及数量见 [implementation-checks.json](evidence/implementation-checks.json)，命令见 [commands.md](evidence/commands.md)。原始报告在对应 `surefire-reports/`，快照不包含构建产物，验证会话需重新运行并保存自己的报告。

首次尝试在仓库根目录调用 `backend/mvnw.cmd -f backend/pom.xml`，Wrapper 因根目录没有 `.mvn/wrapper/maven-wrapper.properties` 而失败，尚未执行测试；改到 backend 目录后上述命令通过。该失败属于调用目录错误，非源码缺陷。测试中的预期故障分支会输出 ERROR 日志和模拟存储失败堆栈，实际通过结论来自本轮 Surefire XML 与退出码。

文档链接、UTF-8 无 BOM／LF、`git diff --check` 和候选清单另作交付检查。不运行后端全量和打包，不连接现有库、不启动业务服务、不调用真实模型、不部署。

## 5. 下一会话的工作

PAS-02 保持 todo。按 [verification.md](verification.md) 先核对候选身份，再执行独立原有基线、新增边界来源复核及补充差分、构造／初始化、计量和调用方检查、最终后端全量及资源打包。定向命令应加入 `PromptAssemblyBoundaryBaselineTest` 和 `PromptAssemblyCompositionTest`；两个捕获开关均不能开启。

发现生产差异先记录输入、字段和 before／after，再交回实施会话修复。修复后重新生成候选清单及副本，最终结论绑定新版本，不直接覆盖旧证据。只有 PAS-02 关闭条件满足后才关闭本工作包。
