# PAR-04 组装边界与工具描述收敛

日期：2026-11-01。状态：done。范围：把 Agent 四类纠正文案从执行器移到组装层，把工具描述拆成中立能力定义与模型协议适配；不改提示词措辞、工具 schema 字段、执行控制、页面、数据库或部署方式。回归与真实使用见 PAR-05。

## 1. 改动

### 1.1 纠正事件

- 新增 `prompt/AgentCorrection`（sealed）：`ModelOutputInvalid(diagnostic)`、`MissingToolCall`、`InvalidToolBatch(maxToolsPerTurn)`、`RepeatedToolCalls`。事件只描述发生了什么，不含模型指令。
- 新增 `resources/prompts/correction/` 四份模板（清单 `kind=CORRECTION`，变量分别为 `diagnostic`、无、`maxToolsPerTurn`、无）。正文逐字取自改造前的内联文案，四份都不带末尾换行，与文本块处理后的实际字符串一致。
- `PromptAssemblyService.correction(event)` 负责选择正文；`AgentTaskWorker` 只产生事件。触发条件、`protocolErrors`／`repeats` 计数、插入位置与 `user` 角色保持原样。
- 非法批次只生成一次 `batchError`：同一段文字同时用于逐工具 envelope 的 `TOOL_BATCH_INVALID` 诊断与随后的纠正消息，不再各写一份。
- 变量替换只扫描模板一次；诊断文本中的 `{{...}}` 原样保留，不会被二次展开。

### 1.2 能力定义与协议适配

- 新增 `tools/capability`：
  - `ToolParameter`（`Text`／`IntegerValue`／`Array`／`Object`，`Text` 带 `Role` 语义：版本、比较关系、路径、glob、查询、游标）。
  - `ToolCapability`：名称、用途说明、参数声明顺序与必填项。
  - `AgentToolCapabilities`：五类只读工具与 `submit_result` 的中立定义，含参数说明、枚举、上限与提交示例；数值来自 `ToolLimits` 与冻结的 `ToolLimitProperties`。
- 新增 `tools/protocol/OpenAiChatToolAdapter`：把能力定义转成当前 OpenAI Chat `tools` JSON。属性顺序沿用改造前序列化顺序，因为工具定义会随请求发送并计入容量估算。协议字段（`type=function`、`function.parameters`）只出现在适配层。
- `AgentToolSchema` 收敛为兼容入口，只保留 `version()`、`definitions()`、`agentTools()`，实现改为「能力定义 + 适配器」；schema 版本指纹算法未变。
- `ToolArgumentParser` 改为读取同一份能力定义：参数名、必填项、类型、`min`／`max`、默认值、`maxItems` 与数组项 `pattern` 均来自 `ToolCapability`。错误码、提示文案、未知字段拒绝和校验先后顺序（版本与比较关系在读取该参数后立即解析）保持原样。
- `PromptAssemblyService.assembleAgent`／`assembleToolCheck`／`assembleToolCheckFollowUp` 改为接收 `List<ToolCapability>`，工具 JSON 在组装层内由适配器生成；保留按任务注入能力集合与指导内容的入口，未接入插件发现、授权、MCP 或插件执行。`AgentTaskWorker` 与 `AgentToolLoopValidator` 传入各自冻结限额下的能力集合。

## 2. 等价性证据

- 新增冻结基线 `backend/src/test/resources/tool-baseline/tools.json`：在改造前实现上以一次性捕获测试导出 `definitions()`、`agentTools()`、两者序列化字节数（6529／9850）、默认与自定义限额下的 schema 版本指纹；捕获测试已删除，且有覆盖保护。正式检查 `ToolDefinitionBaselineTest` 只读比较，不重新捕获。
- 适配器一致性检查同时断言：每个工具的 JSON 属性顺序等于能力定义声明顺序，`required` 等于能力定义的必填项，`additionalProperties=false`，且不输出空说明。
- PAR-02 固定请求基线 20 组仍逐字一致（system／user 正文、消息角色与顺序、工具定义、完整请求体与容量估算），基线文件未重捕获。
- 新增 `PromptCorrectionTest`：以改造前文案字面量断言四类纠正正文、批次数值替换、诊断原样插入，并检查清单变量声明与缺变量失败。
- `AgentTaskWorkerTest` 新增 4 项：只有正文时的纠正、模型输出非法时的纠正、非法批次 envelope 诊断与纠正消息同文、重复调用只补一次纠正；原有插入顺序、计数与终止阈值断言未改。
- `ToolArgumentParserTest` 新增边界一致性检查：描述中写出的上限就是解析器实际上限（`depth`、`limit`、`extensions`、`query`、`path`、`glob`、`cursor`、版本标识长度），避免 schema 与校验各自维护一套约束。
- 定向复验：23 个相关套件 220 项，失败 0、异常 0、跳过 0（提示词组装与基线、纠正事件、工具描述、参数解析、Agent 与专项执行、报告、工具协议、容量及模板检查）。日志中的异常堆栈为既有负向用例输出。
- 后端全量：991 项，失败 0、异常 0，跳过 11（既有真实网关／宿主探针）。首次全量出现 1 次 `GitMirrorTest` 临时目录清理错误（Windows 占用 git pack），该用例单独复跑通过，第二次及最终全量均通过；不记为代码回归，也不据此宣称全量稳定。
- 打包检查：`mvn -DskipTests package` 产物内 `BOOT-INF/classes/prompts/correction/` 含四份模板（169／170／92／154 字节，与源文件一致），`manifest.json` 为 5961 字节；新增正文随应用发布，不依赖开发机路径。

## 3. 未验证与边界

- 未启动或重启服务、未调用真实模型、未连接数据库；真实使用验证留给 PAR-05。
- 未在另一台机器验证 classpath 模板、工具基线与容量数字；本机为 Windows + Java 17。
- 工具描述与纠正正文仍为中文单语；没有新增工具、插件来源或工具说明的在线管理。
- `submit_result` 的参数校验仍由 `AgentSubmission` 执行，本轮只统一了它的描述来源，没有把提交校验并入工具参数解析器。
- 纠正事件的触发条件与次数由执行器测试覆盖，未在真实模型下逐一复现四类事件。

## 4. 回退

代码、`backend/src/main/resources/prompts`（含新增 `correction` 模板与清单条目）以及工具冻结基线属于同一发布单元；回退应整体回到 PAR-03 提交，不单独替换模板或工具定义，避免正文、schema 与校验版本混搭。本轮未新增热更新、数据库迁移或在线版本管理，也未改变既有业务契约与其他工作包状态。
