# P33.2 统一只读工具、受限检索与模型协议适配

状态：工程实现、全量回归与真实模型双轮工具验证已完成；P33.2 的未验证边界（大仓库、Gitea 检索、并发）记入第 7 节。日期：2026-10-22（Asia/Taipei）。

接续：[P33 入口](README.md)、[技术设计](design.md#4-统一只读工具)、[预算与接口对照实验](budget-calibration.md)、[ROADMAP 第 7 节](../../../ROADMAP.md#7-p33-agent-专属设计html-原型实现及验收)。本文件记录本轮实现、检查结果与未验证边界，不替代 P33.6 的真实 Agent 验收。

## 1. 交付范围

| 范围 | 本轮落地 | 主要入口 |
| --- | --- | --- |
| 工具协议 | 五类只读工具的 JSON Schema、严格参数解析、通用 envelope、查询绑定游标 | `AgentToolSchema`、`ToolArgumentParser`、`ToolEnvelope`、`ToolCursor` |
| 固定版本读取 | `tree`、`find_files`、`read_code`、`read_diff` 复用 `RepositoryReadService` 的固定 SHA 读取与缓存 | `ReadOnlyToolService`、`ReadCodeTool`、`ReadDiffTool` |
| 受限检索 | 共享 bare mirror 上的 `git grep`：参数数组、查询经临时文件、字面量、原始行号、超时与有界输出 | `GitMirror`、`GitGrepRunner`、`SearchCodeTool` |
| 材料 | 工具返回的片段按固定版本、侧、行范围与内容哈希去重保存到 `material` | `ToolMaterialStore` |
| 模型协议 | assistant 工具调用、tool 消息、请求 `tools`／`tool_choice`、空正文工具响应、工具定义计量 | `PromptMessage`、`AssembledPrompt`、`ModelInvocationService` |
| 准入 | `POST /api/v2/models/{id}/validate-agent` 双轮真实调用，记录 `scope=AGENT_TOOL_LOOP_V1` 的验证 | `AgentToolLoopValidator`、`AgentModelService` |

迁移：本轮不新增表或列，复用 `material` 与 `model_validation`；`agent_tool_call`、`agent_run` 留到 P33.4/P33.5。

## 2. 工具与限额

- 工具名：`tree`、`find_files`、`search_code`、`read_code`、`read_diff`；`submit_result` 不在只读集合内，由 P33.4 单独处理。
- schema 版本 `agent-tools-v1`；模型准入、搜索缓存键和验证记录都带该版本。
- 参数只接受 JSON 对象、无尾随内容、未知字段直接拒绝；整数只接受整数类型且必须在范围内，不做夹取。
- 版本参数只接受任务作用域中的 `V1…Vm`，比较只接受 `C1…Cn`；模型不能直接给 SHA。
- 单次 envelope 32 KiB、单条材料 24 KiB；`read_code` 一次最多 400 行、默认 200 行；`tree` 深度 2／最大 3、默认 100／最大 200 项；`find_files` 与 `search_code` 默认 50／最大 100 条；`read_diff` 一次 20 个变更项或 10 个 hunk。
- 完整性：`OK + COMPLETE + 空命中` 才能说明没有匹配；截断、超时、不可读一律保留为非 `COMPLETE`。
- 游标只对同一工具、同一版本、同一路径／查询／后缀与同一 schema 有效；换查询复用旧游标返回 `CURSOR_MISMATCH`。

## 3. 共享镜像与受限检索

- 从 `GiteaGitMirror` 提炼出 `GitMirror`：同一 cloneUrl 只有一份 bare mirror，固定对象存在时不逐次 fetch；读锁用于读取，写锁用于克隆／拉取。
- 凭据只经 JGit `CredentialsProvider` 使用，不进入镜像地址、目录名、命令行或诊断正文。
- `git grep` 固定 `-F -n -I -z`；查询写入 UTF-8 临时文件后经 `-f` 传入，用户输入不进入命令行（同时避开 Windows 上 ProcessBuilder 对引号的转义差异）；路径加 `--literal-pathspecs`，不会被解释为选项或 pathspec 魔法。
- 退出码 1 是"没有匹配"，其他非零是失败；超时或输出超过 2 MiB 时终止进程并标记截断，不把半截输出当完整结果。
- 搜索缓存按项目修订、SHA、完整查询、路径、后缀与 schema 隔离，权重上限 32 MiB、5 分钟；失败与超时不入缓存。搜索槽位全局 2 个，等待加执行上限 10 秒，镜像准备另计且受任务期限约束。
- 已知限制：Windows 默认 `MAX_PATH` 下，过深的镜像目录会让 git CLI 读不到 pack 文件；部署时应给 `GITEA_MIRROR_DIR` 使用较短路径，或在支持长路径的环境运行。

## 4. 模型协议适配

- 消息角色扩展为 `system`／`user`／`assistant`／`tool`：assistant 可同时带正文与工具调用，tool 消息必须带 `tool_call_id`。
- 请求体在组装结果带工具定义时追加 `tools` 与 `tool_choice=auto`；工具定义字节计入输入估算，容量判断覆盖完整序列化请求。
- 工具响应允许正文为空；同一响应内工具调用 id 重复、缺少 id／name、`tool_calls` 不是数组均判为 `MODEL_OUTPUT_INVALID`；参数原文交工具层严格校验。
- 验证用途新增 `AGENT_TOOL_CHECK`，与连通验证一样对 DeepSeek 关闭思考模式。
- `validate-agent` 用本地 fixture 完成两轮真实调用：第 1 轮要求 `read_code`，第 2 轮回放工具结果并要求最终文本。记录绑定 `modelConfigId + revision + adapterVersion + toolSchemaVersion + optionsHash`；模型视图新增 `agentValidationStatus`，不与连通性状态混用。

## 5. 工程检查

- 新增回归：工具 schema 4 项、参数解析 11 项、游标 5 项、glob 5 项、hunk 解析 4 项、读取 7 项、tree／find 8 项、diff 6 项、真实 grep 5 项、真实镜像检索 5 项、协议适配 5 项、双轮准入 6 项、共享镜像 1 项。
- 真实 grep 与镜像检索回归依赖本机 `git`，缺失时按 JUnit assumption 跳过；跳过不计为通过。
- 全量后端回归：749 项、0 失败、0 异常、9 跳过；跳过来自既有的真实网关／宿主探针开关，不计为通过。项目既有专项、报告、Gitea 与模型调用回归在本轮修改后仍通过。

## 6. 真实模型双轮验证

2026-10-22 16:19（Asia/Taipei）在重启后的本机服务（8081，新库 `ai_code_review_agent`）对数据库中的 DeepSeek 配置 `01M23AKQZRG40082QFECKMC36N`（`deepseek-flash`，修订 2）执行 `POST /api/v2/models/{id}/validate-agent`：

- 结果 `PASSED`，`scope=AGENT_TOOL_LOOP_V1`，耗时 4 秒；详情为「第 1 轮调用 read_code，第 2 轮返回 40 字符」。
- 两次 `model_call` 均 `SUCCEEDED`、`attempt=1`：第 1 轮请求 5706 字节、输入 1733／输出 91 token，响应含 `tool_calls`；第 2 轮请求 6764 字节（含工具历史）、输入 2092／输出 21 token。
- 两次请求都含 `tools` 与 `tool_choice=auto`；optionsHash `df70758452ee1e3636bc84f4ee11d840ba80934fb1162d29bc9a2bbcca7a4b21`；adapterVersion `openai-chat-v1`、toolSchemaVersion `agent-tools-v1`。
- 模型视图 `agentValidationStatus` 由 `NONE` 变为 `PASSED`，连通性状态单独保留。

这次验证只证明原生工具调用、工具结果回放与最终文本在真实网关可用，不证明检索质量、Agent 结论正确性或大仓库承载。

## 7. 未验证与后续

- 内网大仓库、Gitea 宿主真实检索、2 路并发搜索、冷镜像 120 秒准备以及超时／取消竞争仍需在目标环境实测。
- 工具结果尚未接入执行循环与界面；材料「已取得未发送」、调用记录、进度和 Markdown 结果由 P33.4／P33.5 实现。
- 报告选材对 Agent 结果的适配仍按设计第 6 节在 P33.6 联调。
