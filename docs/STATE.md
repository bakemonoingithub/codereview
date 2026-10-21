# STATE - 专项审查与 Agent 分析

跨会话接续入口；工作树为 `H:\myProjects\aiCodeReview\agent`，分支为 `refactor/agent`。

## 当前状态

当前: P31

P29 已完成并经负责人批准（D-88）。P30 于 2026-10-20 完成，P30.1–P30.8 全部交付。2026-10-21 负责人依次要求完成 P31.1–P31.3、P31.4–P31.6、P31.7–P31.9。P31.1–P31.8 已完成工程实现与本地测试；P31.9 工程交付完成但目标仓库校准与真实资源未执行，P31 保持 active。

P31.1–P31.3 交付：
- 材料准备与结构解析：固定版本整文件/差异两侧、JavaParser 声明事实、完整性/原始行号、`material` 来源记录；读取失败或必需 AST 缺失时该文件失败，不返回空成功。见 [P31.1](work/agent-redesign/p31/p31-1-materials.md)。
- 有限声明增强：限定名/导入/同包定位、读取后核对声明、父类与接口广度优先、循环/菱形去重、声明摘要与直接类型补充，限额和缺口分别记录。见 [P31.2](work/agent-redesign/p31/p31-2-declarations.md)。
- 协议与位置校验：三类 schema 版本 1、严格 JSON、目标/辅助与前后侧规则、changeRelation、耦合事实与 dependencyId 校验、初次加最多 2 次格式重试策略。见 [P31.3](work/agent-redesign/p31/p31-3-protocol.md)。

2026-10-21 已修复 P31.1–P31.3 审查的 R01–R09，补入 27 项正式回归；源码根、限定身份、构造器、摘要范围、依赖关系、JSON 类型及共享读取预算均已复验。见[修复与复验记录](work/agent-redesign/p31/p31-1-3-review-fixes.md)。

P31.4–P31.6 交付：
- 容量与拆分：`SpecialistSplitPlanner` 整文件优先、辅助材料裁剪、类型/成员/行区间拆分、原始位置与 32 单元上限；`SpecialistResultMerger` 只合并确定重复项。见 [P31.4](work/agent-redesign/p31/p31-4-capacity.md)。
- 执行与发布：`SpecialistTaskWorker` 接入 `TaskWorker`，逐目标独立执行；格式重试最多 2 次，部分成功发布 `INCOMPLETE`，完整结果/问题/单元/调用材料落库。见 [P31.5](work/agent-redesign/p31/p31-5-execution.md)。
- 发起与复制：审查中心、发起表单、独立范围弹窗与文件/差异预览、复制参数重定版本；后端补分支 HEAD、提交列表与提交详情读取。见 [P31.6](work/agent-redesign/p31/p31-6-launch.md)。

2026-10-21 完成 P31.4–P31.6 的 R01–R12 审查修复，覆盖整文件估算、拆分容量与覆盖、结构化超限重规划、取消后发布、调用材料关联、结果来源、配置快照、前端来源隔离、幂等提交及列表分页。见[修复与复验记录](work/agent-redesign/p31/p31-4-6-review-fixes.md)。

P31.7–P31.9 交付：
- 代码浏览与审查中心：`CodeBrowser` 分支/提交/文件/差异浏览与勾选带入；任务 ID 搜索、筛选、游标分页、返回现场与异步防串用。见 [P31.7](work/agent-redesign/p31/p31-7-browser.md)。
- 统一详情：三类结果、目标状态、材料片段/全文/差异、实际调用完整请求与返回，反馈仅预留。见 [P31.8](work/agent-redesign/p31/p31-8-detail.md)。
- 验证与交付：A07–A15、A21、A24 的本地证据映射、固定版本/位置/拆分/去重/片段失败复核；目标仓库与真实资源未执行。见 [P31.9](work/agent-redesign/p31/p31-9-validation.md)。

P30.5–P30.8 交付：
- 统一组装与公共调用：`PromptAssemblyService`、`ModelCapacityService`、`ModelInvocationService`、`ModelCallConcurrencyLimiter`、`HttpModelTransport`；请求先落盘、200 次原子额度、全局 4／单任务 2 并发、一次暂态重试；验证调用同入口。见 [P30.5](work/agent-redesign/p30/p30-5-assembly-invocation.md)。
- 任务控制：`TaskService`、`TaskDispatchService`、`TaskExecutionGuard`、`TaskDeadlineSweeper`、`TaskRecoveryRunner`；幂等键、固定快照、取消、期限与重启收敛、终态条件更新。见 [P30.6](work/agent-redesign/p30/p30-6-task-control.md)。
- 四页管理：项目、模型、提示词、策略改用 `/api/v2`；设计基线与人工视觉清单见 [design.md](design.md) 和 [P30.7](work/agent-redesign/p30/p30-7-admin-pages.md)。
- 隔离验证：A01–A06、A22、A23 证据、V2／V3 迁移契约与计量样本。见 [P30.8](work/agent-redesign/p30/p30-8-validation.md)。

2026-10-21 完成 P30.5–P30.8 的 9 项审查修复，覆盖 v2 响应与筛选、列表乱序、提示词保存重试、调用取消与期限、429、验证输出上限和任务目标终态。见[修复与复验记录](work/agent-redesign/p30/p30-5-8-review-fixes.md)。

## 验证与未验证

- P30 复验为后端 474 项、0 失败、0 异常、7 跳过；前端 41 个文件、360 项通过，类型检查和生产构建通过。详情见[本轮复验](work/agent-redesign/p30/p30-5-8-review-fixes.md)。
- P31.7–P31.8 完成后 `mvn clean test` 为 597 项、0 失败、0 异常、9 跳过；前端 46 个测试文件、388 项通过，`vue-tsc --noEmit` 与 `vite build` 通过。跳过项不计为执行通过，真实网关和宿主探针显式禁用。
- 真实数据库迁移与访问层、Git 宿主、目标仓库、模型网关、PC 正式页面视觉均未执行；P31.9 因目标仓库校准未完成保持 active，不把工程检查写成真实通过。
- 专项执行器已接入；`EXECUTOR_UNAVAILABLE` 不再是当前运行的预期行为。真实模型调用、并发竞争和取消边界仍按 P31.9／P32.6 验证。

## 接续依据

- [工作区规则](../AGENTS.md)、[ROADMAP](ROADMAP.md)、[业务契约](work/agent-redesign/business-contract.md)。
- [P29 工作记录](work/agent-redesign/p29/README.md) 与 [验收清单](work/agent-redesign/p29/acceptance.md)。
- [P30.1 复用差异](work/agent-redesign/p30/p30-1-reuse-diff.md)、[P30.2 新库](work/agent-redesign/p30/p30-2-schema.md)、[P30.3 配置接口](work/agent-redesign/p30/p30-3-config-api.md)、[P30.4 固定读取](work/agent-redesign/p30/p30-4-fixed-version-read.md)。
- [P30 审查修复](work/agent-redesign/p30/p30-review-fixes.md)、[P30.5](work/agent-redesign/p30/p30-5-assembly-invocation.md)、[P30.6](work/agent-redesign/p30/p30-6-task-control.md)、[P30.7](work/agent-redesign/p30/p30-7-admin-pages.md)、[P30.8](work/agent-redesign/p30/p30-8-validation.md)。
- [P31 总览](work/agent-redesign/p31/README.md)、[P31.1 材料](work/agent-redesign/p31/p31-1-materials.md)、[P31.2 声明](work/agent-redesign/p31/p31-2-declarations.md)、[P31.3 协议](work/agent-redesign/p31/p31-3-protocol.md)、[P31.4 容量](work/agent-redesign/p31/p31-4-capacity.md)、[P31.5 执行](work/agent-redesign/p31/p31-5-execution.md)、[P31.6 发起](work/agent-redesign/p31/p31-6-launch.md)、[P31.7 浏览](work/agent-redesign/p31/p31-7-browser.md)、[P31.8 详情](work/agent-redesign/p31/p31-8-detail.md)、[P31.9 验证](work/agent-redesign/p31/p31-9-validation.md)。

## 下一步

1. 等负责人提供目标仓库与只读令牌后补 P31.9 采样：小／普通／大文件、提交差异、材料量与 token 误差、并发与耗时，再校准 P29 候选参数。
2. P31.6–P31.8 的 PC 页面截图、真实仓库读取与 P31.9 真实宿主／数据库／模型验收仍待执行；未执行前不记为视觉或真实联调通过。
3. P30.8 清单中的真实新库、宿主、模型、目标仓库与 PC 视觉验收按 P31.9／P32.6 安排；未执行前保持未完成。
4. 不自动启动 P32、P33，不操作真实数据库或部署入口。

## 工作区保留

- P29 文档与原型已提交（6b852a8、44abc7b、6c53b0f）；P30 代码与文档已本地提交（7a48762、d05997a、21c93b4、e45883d、7c712f1、463e83c、0cded7d），未 push。
- P31.1–P31.3 代码与测试已本地提交（3d4e568、3b7704e、ec959e6），直接声明缺口修正为 7754931，交付文档为 3f48ac1，R01–R09 审查修复为 4f76b49。
- P31.4–P31.6 代码、前端与交付文档已本地提交（ab283af、4e3ae02、c7697c9），范围选择跨搜索保留的修正为 7de0a15，未 push。
- P31.4–P31.6 审查修复、正式回归及复验记录为 b9f56bb，未 push；详见[修复记录](work/agent-redesign/p31/p31-4-6-review-fixes.md)。
- P31.7–P31.9 代码、前端与验证文档已本地提交（6e50f33、73bb55c）；P31.9 因目标仓库校准未完成仍为 active，未 push。
- `../code` 工作树保留其原有 2 处未提交改动，不纳入本工作树。

## 已知坑

- 本次环境 JDK 21 在 `D:\IDEA\IntelliJ IDEA 2024.3\jbr`，Maven 在 `D:\IDEA\IntelliJ IDEA 2024.3\plugins\maven\lib\maven3\bin\mvn.cmd`；`java`／`mvn` 不在 PATH，命令前需设 `JAVA_HOME`。
- `frontend/node_modules` 当前是指向 `../code/frontend/node_modules` 的目录联接；`pnpm install --offline` 因 `I:\uv_cache` 缺少 `@antfu/utils` 元数据失败，未完成独立安装。重新安装前先确认 store，不要误删联接。
- 当前 pnpm 启动器引用的缓存脚本缺失；本轮通过 Node 直接调用现有依赖中的 Vitest、vue-tsc 和 Vite，没有重新安装依赖。
- Vitest／Vite 会改写 `frontend/components.d.ts`，结束后恢复基线；本轮已恢复。
- 未执行真实 MySQL；V1–V3 只做契约检查。新库验证必须在负责人指定的隔离库进行。
- Surefire 报告目录会累积历史失败；统计全量结果前先 `mvn clean test`，否则旧 XML 会污染计数。
