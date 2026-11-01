# PAR-02 调用清单与冻结基线

## 1. 来源与边界

生产源码基线：df48cac550fff9983b7be89276c5ce4eb729eaaa。此次未修改生产 Java、数据库或运行配置。固定输入为人工构造的中文、多行、边界长度及工具历史样本，不包含真实凭证或业务数据。

新增测试：backend/src/test/java/com/codereview/agent/prompt/PromptRefactorBaselineTest.java。冻结结果：backend/src/test/resources/prompt-baseline/requests.json。路径均相对仓库根目录。文件包含 20 组组装结果、完整请求及容量估算：六种非 Agent 用途各有最小／丰富输入，三种 Agent 范围各有首轮／工具往返，以及工具验证两轮。丰富输入覆盖可选规则、辅助材料、缺失说明、冻结事实、4001 字文件说明及 2001 字错误片段。部分组合用于组装边界验证，不表示生产业务会发出所有组合。

基线从改造前实现一次性捕获后，正常测试只读比较。捕获开关拒绝覆盖已有文件；后续不得删除文件后重捕获以消除差异。JSON 对象键顺序不影响比较，数组及文本逐项精确比较。请求通过当前 requestBody 方法生成，不另写一份序列化实现；该方法若迁移，调整测试调用接缝，保留冻结期望。

## 2. 运行入口清单

以下路径以 backend/src/main/java/com/codereview/agent 为根。

| 场景 | 调用及组装路径 | 关键约束 |
| --- | --- | --- |
| 问题文件／差异、模式、耦合 | execution/SpecialistTaskWorker → specialist/protocol/SpecialistPromptBuilder → prompt/PromptAssemblyService.assemble → invocation/ModelInvocationService.invoke | 目标、辅助、事实、文件说明及格式重试；专项类型映射由 builder 决定 |
| 专项容量预估与分片 | specialist/capacity/SpecialistSplitPlanner → 同一 builder 和 assemble | 不是另一条网络入口，必须保持选材与切分一致 |
| 代码 Agent | execution/AgentTaskWorker → assembleAgent → invoke | 固定范围、多轮历史、工具定义、显式提交及纠错 |
| 报告 | report/ReportExecutionWorker → report/ReportFactsBuilder → assemble(REPORT) → invoke | 冻结事实、来源及用户规则，不重新读取来源替换历史 |
| 连通验证 | service/AgentModelService → ModelInvocationService.validate → assemble(VALIDATION) → invoke | 最小 JSON，验证输出预算及供应商参数 |
| 工具验证 | service/AgentModelService → tools/AgentToolLoopValidator → assembleToolCheck / assembleToolCheckFollowUp → invoke | 两轮工具协议与固定材料 |

公共 ModelInvocationService.requestBody 负责最终消息及工具协议序列化，ModelCapacityService 管容量，ModelTransport 管传输。旧 com.codereview.service、llm、analyzer 中的执行实现不在当前应用扫描范围内，不纳入本轮迁移。

## 3. 提示片段与工具信息清单

| 来源 | 内容 | 后续归属 |
| --- | --- | --- |
| PromptPurpose | 八种用途的内置任务指令 | 场景模板 |
| SpecialistProtocol | 专项 JSON、报告 Markdown、Agent 提交、两类验证协议说明 | 协议关联模板，校验代码保留 |
| PromptAssemblyService.systemPrompt / agentSystem | 身份、优先级、安全边界、调查方法 | 公共及场景规则 |
| assemble / append 系列 | 规则、文件说明、范围、目标、辅助、事实、缺失、格式纠错段 | 固定文案可抽取，材料编排与截断留在程序 |
| assembleAgent | 指定文件指引、版本与比较、指导、旧线索、工具使用 | 代码场景及指导内容 |
| TOOL_CHECK_USER | 工具验证步骤 | 验证模板 |
| AgentTaskWorker | 输出不可用；正文无工具；非法批次；重复无新增材料 | 类型化纠正事件 |
| AgentToolSchema | 工具名称、用途、参数说明、Schema、提交示例 | 能力定义与模型协议适配 |
| ToolArgumentParser、ToolFailure、ToolEnvelope、工具执行及提交校验 | 参数错误、工具状态、完整性、分页及纠错诊断 | 结构化执行反馈；不能简单整体改成系统指令 |

Agent 四类纠错分别在 MODEL_OUTPUT_INVALID、无 toolCalls、超过单轮工具限制／混合 submit_result、首次重复且无新增材料时产生。保留原有 protocolErrors、repeats、终止阈值及逐工具返回顺序。工具版本当前由限额配置参与计算，不能假定已覆盖全部说明正文。

## 4. 关键流程基线

复用以下既有测试而非复制执行器：PromptAssemblyServiceTest、PromptAssemblyAgentTest、SpecialistPromptBuilderTest、AgentToolSchemaTest、ModelCapacityServiceTest、SpecialistSplitPlannerTest、AgentTaskWorkerTest、SpecialistTaskWorkerTest、ReportExecutionWorkerTest、AgentToolLoopValidatorTest、ModelInvocationServiceTest、SpecialistResultValidatorTest、ReportCitationValidatorTest。

覆盖容量边界、完整文件／分片、辅助裁剪、单行超限、工具往返、非法批次逐条返回、协议纠错耗尽、重复无进展、取消及迟到响应、冻结预算、提交校验、报告引用和公共调用。新增固定 JSON 提供全文请求与计量基线；既有流程断言提供执行控制基线。所有运行来源固定为上述提交加本次新增测试。

## 5. 复验方法与限制

使用 Java 17，在 backend 目录运行 Maven Wrapper；隔离构建目录为 target-par02。定向命令：

```powershell
$env:JAVA_HOME='C:/Users/ysx/.jdks/azul-17.0.20.1'
./mvnw.cmd -q '-Dtest=PromptRefactorBaselineTest,PromptAssemblyServiceTest,PromptAssemblyAgentTest,SpecialistPromptBuilderTest,AgentToolSchemaTest,ModelCapacityServiceTest,SpecialistSplitPlannerTest,AgentTaskWorkerTest,SpecialistTaskWorkerTest,ReportExecutionWorkerTest,AgentToolLoopValidatorTest,ModelInvocationServiceTest,SpecialistResultValidatorTest,ReportCitationValidatorTest' '-Dbackend.buildDirectory=target-par02' test
```

其他机器设置其实际 Java 17 路径，不依赖上述本机目录。首次使用默认旧 Java 编译失败，切换已有 Java 17 后重新检查；该失败是工具链不匹配，不属于生产源码缺陷。正常复验不传 par.captureBaseline。

本次不运行真实模型、不连接数据库、不启停服务。冻结请求不穷举所有业务输入；COMMON 用途准入、历史数据库读取及真实混合报告仍需后续按计划验证。具体回归结果见下方执行记录。

## 6. 执行记录

2026-11-01：Java 17 定向复验 14 个套件、130 项，失败 0、异常 0、跳过 0。包括不带捕获开关的固定基线比较。此范围内无既有失败；未运行后端全量或真实环境验收。
