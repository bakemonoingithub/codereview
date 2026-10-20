# P30.6 任务记录、调度、取消与终态保护

状态：P30.6 完成（2026-10-20，Asia/Taipei）。依据 [ROADMAP](../../../ROADMAP.md) P30.6、[共用设计](../p29/shared-design.md) 第 3、4 节与 [业务契约](../business-contract.md) 的任务状态约定。真实数据库与真实宿主未连接。

## 1. 交付

| 交付 | 位置 |
| --- | --- |
| 任务服务 | `agent/service/TaskService.java` |
| 任务接口 | `ReviewTaskV2Controller`、`ModelCallV2Controller` |
| 调度与终态保护 | `agent/execution/TaskDispatchService.java` |
| 执行检查 | `TaskExecutionGuard` |
| 期限扫描 | `TaskDeadlineSweeper` |
| 重启收敛 | `TaskRecoveryRunner` |
| 状态与参数 | `TaskStates`、`TaskProperties` |
| 增量迁移 | `V3__agent_task.sql`：`review_task.idempotency_key`、`request_hash`、唯一索引 |

## 2. 创建与快照

- `POST /api/v2/review-tasks` 返回 202、任务 ID 与 location；HTTP 成功不代表任务成功。
- 支持 `Idempotency-Key`：同一项目内同键同正文返回原任务，正文变化返回 409 `IDEMPOTENCY_CONFLICT`。唯一索引兜底并发插入；`request_hash` 为请求 JSON 的 SHA-256。
- FILES 来源必须带完整 40 位 `observedSha`；服务端重新解析分支 HEAD，移动时返回 409 `SOURCE_MOVED` 并给出 observed/current。DIFF 来源要求完整 `commitSha`，`baseSha` 可空且不能与目标相同。
- 创建时固定模型、策略、提示词当前版本与来源 SHA，写入 `config_snapshot`／`input_snapshot`；凭据不进入任何快照。仓库地址、名称进入输入快照，模型地址、名称、窗口、输出、tokenizer 与协议进入配置快照。
- 目标按顺序写 `task_target`，保留 `path/oldPath/baseSha/headSha/note`；一个文件一条，空目标和重复路径在入口拒绝。
- 同时创建 `task_quota`：上限 200、已预留 0。任务期限默认 30 分钟。

## 3. 调度与终态

- 任务状态：`QUEUED → RUNNING → SUCCEEDED/PARTIAL/FAILED/CANCELLED`。
- 启动只做一次条件更新：`where state=QUEUED and revision=?`；取消或重复调度后不会二次启动。
- P30 只提供 `TaskWorker` 接口。没有执行器时任务明确失败为 `EXECUTOR_UNAVAILABLE`，不会永远停在 QUEUED。P31 实现执行器后接入。
- 终态只通过 `where state=RUNNING` 的条件更新写入；成功的更新额外要求 `cancel_requested_at is null`。取消或期限先落库时，迟到的成功回调更新 0 行，不覆盖终态。
- 取消先检查当前是否终态；已终态直接返回当前视图（幂等）。否则按 `revision` 条件更新为 `CANCELLED`、写 `cancel_requested_at`、`stop_reason=USER_CANCELLED`，并把未完成目标标为取消。重复取消返回当前状态。
- 期限扫描每 30 秒（可配）查找 `QUEUED/RUNNING` 且已过期的任务：有成功目标按 PARTIAL，没有则 FAILED，`stop_reason=TASK_DEADLINE`；未完成目标标为取消。
- 启动收敛把遗留 `QUEUED/RUNNING` 任务标为 FAILED、`stop_reason=INTERRUPTED`；运行中的调用标为 FAILED，未开始目标标为取消。默认单节点假设，可在 `agent.task.recover-on-start=false` 关闭。

## 4. 接口

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/v2/review-tasks` | 创建；支持 `Idempotency-Key` |
| `GET /api/v2/review-tasks` | 按项目、route、专项类型、状态筛选；`(createdAt,id)` 游标分页 |
| `GET /api/v2/review-tasks/{id}` | 任务、来源、目标与配置快照视图 |
| `POST /api/v2/review-tasks/{id}/cancel` | `expectedRevision`；终态幂等 |
| `GET /api/v2/review-tasks/{id}/copy-input` | 只返回可编辑参数；FILES 明确要求重新观察分支 |
| `GET /api/v2/review-tasks/{id}/calls` | 调用摘要，可按 target／unit 过滤 |
| `GET /api/v2/model-calls/{id}?part=request|response` | 已保存正文、长度、SHA-256 与完整性 |

## 5. 验证记录

| 项目 | 状态 | 说明 |
| --- | --- | --- |
| 任务、调度、恢复、期限定向测试 | 通过 | 18 项：任务服务 10、调度 6、重启 1、期限 1 |
| 全量后端回归 | 通过 | 见 P30.8 汇总；条件跳过不计为执行通过 |
| 真实数据库／HTTP 接口 | 未执行 | 无隔离新库；P30.8 输出清单，P32.6 验收 |
| 真实执行器 | 未接入 | P31 实现 `TaskWorker`；当前创建的任务会明确失败 |
| 真实取消与迟到响应 | 未执行 | 终态条件更新由单元测试覆盖，真实网络取消仍为尽力行为 |

## 6. 已知边界

- 目标路径在 head SHA 是否存在、是否为允许的文本文件，留 P31.1 准备材料时校验；本任务只校验路径形状与重复。
- 幂等键作用域为项目，尚无用户身份维度；接入认证后需补用户维度。
- 重启收敛按单节点设计；多节点部署必须关闭默认收敛并改用租约。
- 取消是立即终态，已成功文件的保留与展示由 P31 的结果发布负责。
