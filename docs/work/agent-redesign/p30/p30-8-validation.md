# P30.8 公共基础隔离验证与交付

状态：P30.8 完成（2026-10-20，Asia/Taipei）。验证范围是固定材料、单元测试与本地 HTTP 替身；**没有连接真实数据库、真实 Git 宿主或真实模型**。未执行项明确转入 P31.9／P32.6，不记为通过。

## 1. 验证方法与结果

| 层次 | 状态 | 结果 |
| --- | --- | --- |
| 后端全量回归 | 通过 | 461 项，0 失败、0 异常、9 跳过；跳过项不计为执行通过 |
| 前端全量回归 | 通过 | 39 个测试文件、339 项通过 |
| 前端类型检查 | 通过 | `vue-tsc --noEmit` |
| 本地 HTTP 替身 | 通过 | 路径、Bearer 鉴权、请求正文、429 Retry-After、超时与非法地址 |
| 迁移脚本契约 | 通过 | V2 允许独立调用并新增额度表；V3 增加幂等键与唯一索引 |
| 真实迁移执行 | 未执行 | 无隔离新库与账户；不能由契约测试代替 |
| 真实模型调用 | 未执行 | 无网关地址与凭据；P32.6 验收 |
| 真实宿主／目标仓库 | 未执行 | 无可用令牌；P31.9 采样 |
| PC 正式页面视觉 | 未执行 | 无浏览器自动化；P30.7 给出人工清单 |

## 2. 验收项证据

| 编号 | 场景 | 证据 |
| --- | --- | --- |
| A01 | 分支移动、同路径不同 SHA | `TaskServiceTest.sourceMovedIsRejectedBeforeAnyTaskIsWritten`；固定版本读取 19 项沿用 P30.4 |
| A02 | 相同幂等键并发／不同正文 | `TaskServiceTest.sameIdempotencyKeyAndBodyReturnsTheOriginalTask`、`sameIdempotencyKeyWithDifferentBodyConflicts`、`concurrentDuplicateKeyReplaysTheExistingTask` |
| A03 | 并发许可、格式与传输重试 | `ModelCallConcurrencyLimiterTest` 3 项；`ModelInvocationServiceTest.transientFailureRetriesOnceAndRecordsEveryAttempt` 断言两次尝试与两次额度预留 |
| A04 | 请求正文持久化失败 | `ModelInvocationServiceTest.requestPersistenceFailureMeansNoNetworkRequest` |
| A05 | 取消先成功／成功先成功 | `TaskServiceTest.cancelWritesTerminalStateAndClosesOpenTargets`、`cancelIsIdempotentForATerminalTask`；`TaskDispatchServiceTest.lateSuccessCannotOverwriteAnAlreadyTerminalTask` |
| A06 | 重启时存在执行中任务 | `TaskRecoveryRunnerTest.restartClosesTasksTargetsAndCallsWithoutResuming` |
| A22 | 新数据源误指旧库／旧 history／标记 | 既有 `AgentSchemaGuardTest`；新增 `AgentFoundationMigrationContractTest` 检查 V2／V3 结构 |
| A23 | 凭据留空／新值错误；模型验证过期 | `AgentProjectServiceTest.updateKeepsStoredCredentialWhenInputIsBlank`、`updateValidatesNewCredentialBeforeReplacingIt`；`AgentModelServiceTest.validationFromAnOlderRevisionIsReportedAsExpired`；前端四页测试 |

## 3. 新迁移与访问层一致性

- V1 建 18 张表；V2 允许 `model_call.task_id` 为空并新增 `task_quota`；V3 为 `review_task` 增加 `idempotency_key`、`request_hash` 与 `(project_id, idempotency_key)` 唯一索引。
- `AgentDataSourceConfig` 已注册 `AgentTaskQuotaMapper`；`AgentTaskQuota` 与 `task_quota` 列一一对应。
- 身份标记仍为代次 `1`，增量迁移由 Flyway 历史推进；`AgentSchemaGuard` 继续拒绝旧库、旧 history 与版本不符。
- 未实际执行迁移，因此「SQL 语法与 MySQL 版本兼容」仍是未验证项，不能由 H2／单元测试替代。

## 4. 初始计量记录

样本：单个 4 KiB 文件、无策略、无辅助材料、`ISSUE_FILE` 用途，行号从 1 开始。

| 项目 | 值 |
| --- | --- |
| 输入 UTF-8 字节 | 6,229 |
| 输入估算（tokens） | 6,325 |
| 计量方法 | `ESTIMATED_UTF8_BYTES_V1` |
| 输出预留 | 4,096（32,768 窗口示例） |
| 安全余量 | 3,277（`max(1024, 10%)`） |
| 合计所需 | 13,698，低于 32,768 |
| 真实模型 usage | 未取得 |

该记录锁定在 `PromptMeteringSampleTest`；它不是 tokenizer 对照，也不代表真实仓库文件的平均大小。

## 5. 实际验证清单

| 资源 | 当前状态 | 后续动作 |
| --- | --- | --- |
| 隔离新库 `ai_code_review_agent` | 未提供 | P32.6 建库、执行 V1–V3、核对访问层 |
| 新库独立账户与权限 | 未提供 | P32.6 最小权限验证，禁止写旧库 |
| 模型配置（地址、模型名、窗口、输出、凭据） | 未提供 | P32.6 配置并跑连通验证与真实专项 |
| Git 宿主与只读令牌 | 未提供 | P31.9 目标仓库采样、405／429／超时边界 |
| 目标仓库 | 未指定 | P31.9 大小文件、提交差异与容量校准 |
| 浏览器自动化／截图工具 | 不可用 | 按 P30.7 清单人工核对 1366／1920 |

## 6. 交付与回退边界

- 新库初始化与检查脚本沿用 P30.2：`scripts/agent-db-init.ps1`、`scripts/agent-db-check.ps1`；本阶段未执行任何建库或写库操作。
- 未部署、未切换入口、未封存旧库、未演练回退；这些属于 P32.7 的单独授权范围。
- 所有「通过」仅指本地工程检查；真实模型质量、数据库行为和 PC 视觉验收保持未完成。
