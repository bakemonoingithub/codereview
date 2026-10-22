# P33.4 Agent 执行循环、预算与容量终止

状态：工程实现与本地回归完成；真实任务执行结果见第 6 节。日期：2026-10-22（Asia/Taipei）。

接续：[P33 入口](README.md)、[技术设计第 3、5、7 节](design.md#3-原生工具协议及模型准入)、[P33.2 工具](p33-2-tools.md)、[P33.3 输入](p33-3-inputs.md)、[ROADMAP 第 7 节](../../../ROADMAP.md#7-p33-agent-专属设计html-原型实现及验收)。

## 1. 交付范围

| 范围 | 本轮落地 | 主要入口 |
| --- | --- | --- |
| 执行器 | `AgentTaskWorker` 实现 `TaskWorker`，路线 `AGENT`；调度按任务 route 选择执行器 | `TaskDispatchService`、`TaskWorker.route()` |
| 循环 | 模型决定下一步 → 串行执行只读工具 → 材料回到下一轮 → 显式提交或终止 | `AgentTaskWorker.loop` |
| 提交 | `submit_result` 结构、引用与行号校验；结果与任务终态同一事务发布 | `AgentSubmission`、`AgentResultPublisher` |
| 限额 | 48 轮、96 次网络尝试、192 次工具、20 分钟、上下文 80%、连续 3 轮无进展、单轮最多 4 个工具 | `AgentBudget`、`AgentTaskWorker` |
| 存储 | 新迁移 `agent_run`、`agent_tool_call`、`model_call.turn_no` | `V6__agent_run.sql`、`AgentRun`、`AgentToolCall` |
| 保护 | 取消／期限在每次模型与工具调用前检查；迟到响应不发布结果；协议错误最多 2 次纠错 | `TaskExecutionGuard`、`AgentResultPublisher` |

## 2. 执行循环

1. 读取任务快照：要求、范围、版本、比较关系、指导提示词、参考结果与模型快照；模型修订变化即停止。
2. 每轮经唯一组装入口生成完整请求（含历史与工具定义）；发送前检查轮次、网络尝试、期限与上下文。
3. 响应处理：
   - 只有正文、没有工具调用 → 计入协议错误，提示必须调用工具或提交；超过 2 次按 `PROTOCOL_INVALID` 终止。
   - 同批包含 `submit_result` 与其他工具，或单轮超过 4 个工具 → 整批不执行，计入协议错误。
   - 普通工具 → 串行执行；返回 envelope 原样进入下一轮 tool 消息，材料按 `materialId` 记录。
   - `submit_result` → 校验结构与引用；失败计入协议错误并返回工具错误，允许有限纠错。
4. 连续 3 轮工具组合、参数与返回相同且无新增材料 → `NO_PROGRESS_LIMIT`；第 2 轮先插入一次提示。
5. 命中限制或期限：保留已发生调用与材料，不追加总结调用，不发布完整结果；有调查记录 `PARTIAL`，无记录 `FAILED`。

## 3. 结果提交与发布

- `submit_result` 字段：`title`、`summary`、`answerStatus`、`markdown`、`citations`、`unresolved`；未知字段、越界长度与非法状态直接拒绝。
- 引用只能指向实际送入过模型请求的材料（`call_material.disposition=SENT`），行号必须落在材料范围内；正文 `[E#]` 必须全部解析，未使用的引用不发布。
- `ANSWERED` 至少一个实际引用；`INSUFFICIENT_EVIDENCE` 必须写明未解决事项，可作为完整结果进入报告候选。
- 发布事务：锁定任务行 → 拒绝取消／已终态／重复结果 → 写入 `review_result(kind=AGENT, schema_version=1)` → 条件更新任务为 `SUCCEEDED`；任一步失败整体回滚。

## 4. 存储

- `agent_run`：任务级轮次、工具／网络计数、当前动作、覆盖摘要、状态与停止原因；用于 P33.5 进度读取与重启收敛，不表示恢复原会话。
- `agent_tool_call`：每次实际工具执行一行，保存参数、envelope、材料 ID、状态与时间；唯一键 `(task_id, turn_no, ordinal)`。
- `model_call.turn_no`：Agent 每轮调用可追溯；专项、报告与验证调用保持为空。
- 发送材料关联：每轮成功发送后把上一轮材料写入 `call_material(role=TOOL, disposition=SENT)`。

## 5. 工程检查

- 新增回归：结果结构与引用 7 项、执行循环 6 项（提交、无进展、取消、协议纠错、上下文、无效提交纠错）、结果发布 4 项、调度路线沿用既有 10 项。
- 全量后端 783 项、0 失败、0 异常、9 跳过；前端本轮未改。

## 6. 真实任务执行

2026-10-22 17:00（Asia/Taipei）在本机服务（8081，真实新库，V6 迁移已应用）对真实项目 `codereview`（`01M2FZ8HA8H6CVXRTKA15JV7GQ`，分支 `refactor/agent`，HEAD `b8b0b50e…`）创建文件范围 Agent 任务，模型为 `DeepSeek(默认)`（修订 2）。

### 6.1 失败样本：提交动作未暴露

任务 `01M5HD85DE1VG36MZN304X728M` 运行 14 轮、18 次工具后以 `PARTIAL / PROTOCOL_INVALID` 终止。记录显示模型能正常调用只读工具，但工具列表里没有 `submit_result`，它只能反复输出正文或无效提交，最终耗尽 2 次纠错。保留该失败样本，不删除后只保留成功结果。

修正：把 `submit_result` 定义加入 Agent 工具清单（只读解析器仍不接受它），并记录每次提交尝试。

### 6.2 成功样本

任务 `01M5HDEKHM5R621JDG2CW77W6F`：

- 任务 `SUCCEEDED`，无停止原因；`agent_run` 记录 5 轮、7 次工具尝试、5 次网络尝试。
- 6 次 `AGENT` 模型调用，累计输出 7269 token；结果 `ANSWERED`，标题与结论指向任务创建入口与幂等键处理。
- 结果 `review_result(kind=AGENT, schema_version=1, completeness=COMPLETE)`：17 个引用、3 条未解决事项、Markdown 2913 字符；`provenance` 记录 7 次工具调用与 23 条材料。
- 工具序列：`tree`、`search_code`、`read_code`…；第 5 轮 `submit_result` 因引用校验返回 `INVALID_ARGUMENT`，第 6 轮修正后通过——有限纠错按设计工作。
- `call_material` 有 24 条 `SENT` 关联；引用均带 `materialId + sha + path + startLine/endLine`。
- 库中同任务只有一条完整结果；任务终态与结果发布在同一事务完成。

本轮同时修复了两个启动级装配问题：新 Mapper 未注册到 Agent 数据源、`AgentTaskService` 多构造器缺少 `@Autowired`。二者已由回归与真实重启验证。

## 7. 未验证与后续

- 进度接口、调用记录阅读与结果页属 P33.5；本轮只落库，不提供新读接口。
- 内网大仓库、多提交真实调查、Gitea 宿主与并发取消竞争未在本轮执行。
- 报告选材对 Agent 结果的适配与真实混合报告在 P33.6。
