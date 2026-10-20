# STATE - 专项审查与 Agent 分析

跨会话接续入口；工作树为 `H:\myProjects\aiCodeReview\agent`，分支为 `refactor/agent`。

## 当前状态

当前: -

P29 已完成并经负责人批准（D-88）。P30 于 2026-10-20 完成，P30.1–P30.8 全部交付；P31 未启动，等待负责人明确指定。

P30.5–P30.8 交付：
- 统一组装与公共调用：`PromptAssemblyService`、`ModelCapacityService`、`ModelInvocationService`、`ModelCallConcurrencyLimiter`、`HttpModelTransport`；请求先落盘、200 次原子额度、全局 4／单任务 2 并发、一次暂态重试；验证调用同入口。见 [P30.5](work/agent-redesign/p30/p30-5-assembly-invocation.md)。
- 任务控制：`TaskService`、`TaskDispatchService`、`TaskExecutionGuard`、`TaskDeadlineSweeper`、`TaskRecoveryRunner`；幂等键、固定快照、取消、期限与重启收敛、终态条件更新。见 [P30.6](work/agent-redesign/p30/p30-6-task-control.md)。
- 四页管理：项目、模型、提示词、策略改用 `/api/v2`；设计基线与人工视觉清单见 [design.md](design.md) 和 [P30.7](work/agent-redesign/p30/p30-7-admin-pages.md)。
- 隔离验证：A01–A06、A22、A23 证据、V2／V3 迁移契约与计量样本。见 [P30.8](work/agent-redesign/p30/p30-8-validation.md)。

## 验证与未验证

- 后端全量 461 项、0 失败、0 异常、9 跳过；前端 39 个文件 339 项通过；前端类型检查通过。
- 真实数据库迁移与访问层、Git 宿主、目标仓库、模型网关、PC 正式页面视觉均未执行；不把工程检查写成真实通过。P31.9／P32.6 继续。
- 当前任务创建后没有专项执行器：`TaskWorker` 未接入时任务明确失败为 `EXECUTOR_UNAVAILABLE`，这是预期行为，不是回归。

## 接续依据

- [工作区规则](../AGENTS.md)、[ROADMAP](ROADMAP.md)、[业务契约](work/agent-redesign/business-contract.md)。
- [P29 工作记录](work/agent-redesign/p29/README.md) 与 [验收清单](work/agent-redesign/p29/acceptance.md)。
- [P30.1 复用差异](work/agent-redesign/p30/p30-1-reuse-diff.md)、[P30.2 新库](work/agent-redesign/p30/p30-2-schema.md)、[P30.3 配置接口](work/agent-redesign/p30/p30-3-config-api.md)、[P30.4 固定读取](work/agent-redesign/p30/p30-4-fixed-version-read.md)。
- [P30 审查修复](work/agent-redesign/p30/p30-review-fixes.md)、[P30.5](work/agent-redesign/p30/p30-5-assembly-invocation.md)、[P30.6](work/agent-redesign/p30/p30-6-task-control.md)、[P30.7](work/agent-redesign/p30/p30-7-admin-pages.md)、[P30.8](work/agent-redesign/p30/p30-8-validation.md)。

## 下一步

1. 等负责人启动 P31.1：逐文件材料准备、结构解析与来源记录；再按 P31 顺序接入执行器、结果发布与阅读。
2. P30.8 清单中的真实新库、宿主、模型、目标仓库与 PC 视觉验收按 P31.9／P32.6 安排；未执行前保持未完成。
3. 不自动启动 P31、P32、P33，不操作真实数据库或部署入口。

## 工作区保留

- P29 文档与原型已提交（6b852a8、44abc7b、6c53b0f）；P30 代码与文档已本地提交（7a48762、d05997a、21c93b4、e45883d、7c712f1、463e83c），未 push。
- 未跟踪的 `docs/work/agent-redesign/未决事项.xlsx` 保持原状，不纳入变更。
- `../code` 工作树保留其原有 2 处未提交改动，不纳入本工作树。

## 已知坑

- 本次环境 JDK 21 在 `D:\IDEA\IntelliJ IDEA 2024.3\jbr`，Maven 在 `D:\IDEA\IntelliJ IDEA 2024.3\plugins\maven\lib\maven3\bin\mvn.cmd`；`java`／`mvn` 不在 PATH，命令前需设 `JAVA_HOME`。
- `frontend/node_modules` 当前是指向 `../code/frontend/node_modules` 的目录联接；`pnpm install --offline` 因 `I:\uv_cache` 缺少 `@antfu/utils` 元数据失败，未完成独立安装。重新安装前先确认 store，不要误删联接。
- Vitest／Vite 会改写 `frontend/components.d.ts`，结束后恢复基线；本轮已恢复。
- 未执行真实 MySQL；V1–V3 只做契约检查。新库验证必须在负责人指定的隔离库进行。
