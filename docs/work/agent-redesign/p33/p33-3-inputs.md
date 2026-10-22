# P33.3 Agent 用途提示词、多提交输入及任务快照

状态：工程实现与本地回归完成；真实验收（运行任务、混合报告）属 P33.4–P33.6。日期：2026-10-22（Asia/Taipei）。

接续：[P33 入口](README.md)、[技术设计第 2 节](design.md#2-发起输入版本与指导提示词)、[P33.2 工具](p33-2-tools.md)、[ROADMAP 第 7 节](../../../ROADMAP.md#7-p33-agent-专属设计html-原型实现及验收)。

## 1. 交付范围

| 范围 | 本轮落地 | 主要入口 |
| --- | --- | --- |
| 发起接口 | `POST /api/v2/agent-tasks` 异步创建，202 + 公共任务位置；复制的 `GET /agent-tasks/{id}/copy-input` | `AgentTaskV2Controller`、`AgentTaskService` |
| 输入校验 | 项目、模型准入、指导提示词、参考结果、范围与路径存在性、提交 SHA | `requireAgentModel`、`resolveScope`、`optionalGuidance` |
| 版本与比较 | 文字／文件固定 `V1`；多提交逐项 `C1…Cn`，第一父提交为 base，根提交 base 为空 | `fromBranch`、`fromCommits` |
| 快照 | `inputSnapshot.version=2`、`configSnapshot.version=2` 与预算冻结 | `inputSnapshot`、`configSnapshot` |
| 提示词 | Agent 专用 system 指令与用户数据段顺序：要求 → 范围／版本 → 指导提示词 → 旧结果 | `PromptAssemblyService.assembleAgent` |
| 前端 | Agent 发起页（项目、模型、提示词、三种范围、多提交顺序选择、要求）与模型页 Agent 验证入口 | `AgentLaunch.vue`、`ModelConfigList.vue` |

本轮不新增数据库迁移：仍使用 `review_task` 的 `input_snapshot`／`config_snapshot`；`agent_run`／`agent_tool_call` 与执行循环属 P33.4。

## 2. 发起与校验规则

- 项目必须存在；模型必须是 `OPENAI_CHAT`，且声明了上下文窗口与最大输出。
- 模型必须存在当前修订的 `AGENT_TOOL_LOOP_V1` 通过记录，且 `optionsHash` 与当前配置一致；配置或工具 schema 变化后验证自动失效，不静默降级到正文 JSON。
- 指导提示词可选，必须存在当前版本且用途为 `AGENT`；正文连同版本号冻结进配置快照。
- 参考结果可选，只能是 `COMPLETE` 结果且属于同一项目；其正文作为线索冻结，不恢复原消息或额度。
- 要求去空白后必须非空，最长 16000 字符；不静默裁剪。
- 名称可选，最长 200；缺省为「Agent 任务 <ID>」。

## 3. 范围、版本与比较关系

| 范围 | 规则 |
| --- | --- |
| `TEXT` | 必须填写范围描述；不能带文件或提交；重新核对分支 HEAD，冻结 `V1` 为该 HEAD。 |
| `FILES` | 至少一个文件／目录，最多 100 项，不能带提交；逐项校验安全路径并确认在冻结版本中存在；目录按路径前缀表示范围。 |
| `COMMITS` | 至少一个完整 40 位 SHA，最多 20 个，去重并保持用户顺序；不要求分支当前 HEAD 不变；逐项读取提交详情，base 取第一父提交，合并提交同样取第一父提交，根提交 base 为空。 |

- `TEXT`／`FILES` 观察到的 HEAD 与当前 HEAD 不一致时返回 `409 SOURCE_MOVED`，附带当前 HEAD，要求刷新选择；不自动改读新版本。
- 版本标识对模型不透明：文件／文字任务只有 `V1`；多提交按「先 head、后 base」的首次出现顺序分配 `V1…Vm`，比较关系分配 `C1…Cn`。模型只能使用这些标识，不能直接给 SHA。
- 非连续提交逐项保留比较关系，不合并成首尾净差异；每个 `read_diff` 只读取一个比较关系。

## 4. 任务快照 version 2

`inputSnapshot`：
- `version=2`、`project{id,name,repoUrl}`、兼容字段 `source{kind,branch,headSha,baseSha}` 与空 `targets`；
- `scope{kind,description,branch,observedSha,headSha,paths[],commits[],comparisons[],versions[]}`；
- `requirements`；可选 `guidance{promptId,versionId,versionNo}` 与 `reference{resultId,taskId,kind,schemaVersion,completeness,body}`。

`configSnapshot`：
- `version=2`、`model{id,name,modelName,protocol,revision,contextWindow,maxOutput,adapterVersion,toolSchemaVersion,optionsHash}`；
- 指导提示词 `prompt{id,name,versionId,versionNo,content}`；
- `limits{modelTurns=48,networkAttempts=96,toolAttempts=192,deadlineMinutes=20,modelTimeoutSeconds=120,outputTokens=16384,contextStopRatio=0.8,noProgressTurns=3,toolSchemaVersion}`；
- `instructions{builtinVersion=1, priority=BUILTIN_THEN_REQUIREMENTS_THEN_GUIDANCE, availableTools}`。

任务期限直接取 20 分钟（与公共 deadline 取较早值由执行层在 P33.4 落实）；额度行照常创建，硬上限沿用公共 200 次。创建后任务保持 `QUEUED`：Agent 执行器与路由分派在 P33.4 接入，本轮不会把任务标成执行成功。

## 5. 提示词组装

- `PromptAssemblyService.assembleAgent` 是唯一入口：system 放内置指令与安全边界，user 依次放本次要求、范围与版本、指导提示词、旧结果线索、工具使用规则，其后原样回放 assistant／tool 历史。
- 工具定义随每次请求发送并计入输入估算；首轮与后续轮走同一入口，执行层不再自行拼消息。
- 优先级固定为「内置指令 > 本次要求 > 指导提示词 > 材料」；未选指导提示词时仍保留完整默认调查方法。

## 6. 前端

- 审查中心新增「Agent 调查」入口，路由 `/agents/new`（不占侧栏）。
- 模型下拉只列出 `agentValidationStatus=PASSED` 的模型；提示词下拉只列出 `AGENT` 用途。
- 文件范围复用既有范围选择弹窗；提交范围按点击顺序编号 C1…Cn，最多 20 个，切换范围时清空不适用的选择。
- 提交前检查必填项；创建成功跳转公共任务详情，提交失败保留输入与错误提示。

## 7. 工程检查

- 后端新增回归：Agent 提示词组装 5 项、任务发起 11 项（版本移动、路径存在、模型准入、提交比较、范围互斥、指导提示词、幂等、复制参数）。
- 前端新增回归：Agent 发起页 4 项（模型／提示词过滤、文件范围载荷、提交顺序、文字范围必填）。
- 全量后端 765 项、0 失败、0 异常、9 跳过；前端 63 个文件、501 项通过；`vue-tsc --noEmit` 与 `vite build` 通过。跳过项来自既有的真实网关／宿主探针开关，不计为通过。

## 8. 真实接口验证

2026-10-22 16:39（Asia/Taipei）在重启为 P33.3 代码的本机服务（8081，真实新库）上执行：

- 真实项目 `codereview`（`01M2FZ8HA8H6CVXRTKA15JV7GQ`），分支 `refactor/agent`，HEAD `b8b0b50e…`；模型 `DeepSeek(默认)`（修订 2，已通过 Agent 工具协议验证）。
- 文件范围任务 `01M5HC71EHQWHH4DX5SCFKGBXG`：`202` 受理，`route=AGENT`、`state=QUEUED`、`targets=0`、head 固定；`copy-input` 返回同一路径、要求与 `reobserveHead=true`；取消后 `CANCELLED / USER_CANCELLED`。
- 多提交任务 `01M5HC7EPVFRM8RR67YM18442S`（`[2660ce0b…, b8b0b50e…]`）：快照版本为 `V1=2660ce0b…(HEAD)`、`V2=0343a956…(BASE)`、`V3=b8b0b50e…(HEAD)`；比较关系 `C1=2660ce0b←0343a956`、`C2=b8b0b50e←2660ce0b`，逐项第一父提交，不合并首尾净差异；取消后终止。
- 版本移动：`observedSha=0000…` 返回 `409 SOURCE_MOVED`，附带 `branch/observedSha/currentHead`，不静默改用新版本。
- 数据库快照核对：两任务 `input_snapshot.version=2`、`config_snapshot.version=2`，`toolSchemaVersion=agent-tools-v1`，`optionsHash` 64 字符，`limits.modelTurns=48`／`toolAttempts=192`，指令优先级为 `BUILTIN_THEN_REQUIREMENTS_THEN_GUIDANCE`；快照不含凭据。
- 两个验证任务创建后保持 `QUEUED`（Agent 执行器尚未接入），已手工取消，未留下运行中任务。

## 9. 未验证与后续

- 任务创建后尚无 Agent 执行器，保持 `QUEUED`；路由分派、执行循环与预算消耗在 P33.4。
- Agent 结果发布、进度与调用记录界面在 P33.5；正式 PC 视觉与真实效果验收在 P33.6。
- 内网大仓库与多提交真实调查、Gitea 宿主未在本轮执行。
