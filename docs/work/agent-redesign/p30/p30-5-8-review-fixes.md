# P30.5–P30.8 审查修复与复验

日期：2026-10-21（Asia/Taipei）。修复基线：`3651689bad4e6bb075fce9c9a32895cf6021d8d8`，工作树为 `agent/`，分支为 `refactor/agent`。

本轮处理 P30.5–P30.8 审查发现的 9 项问题。范围包括公共模型调用、任务终态与四个管理页面；P31 未启动。

## 1. 问题与修复

| 编号 | 级别 | 原问题及影响 | 修复与回归证据 |
| --- | --- | --- | --- |
| F01 | P1 | v2 成功响应在拦截器和 API 方法重复解包，页面拿到 `undefined` | 拦截器保留 Axios 响应，由 API 方法提取一次正文。`v2.spec.ts` 使用真实 Axios 适配器和拦截链，覆盖四页列表、详情、版本、对比、写入、验证及错误结构 |
| F02 | P1 | 429 缺少 `Retry-After` 时向 `Map.of` 写入 null，抛异常且调用记录残留 RUNNING | 无可用重试时间时仅记录状态码，按默认退避重试。`rateLimitWithoutRetryAfterRecordsFailureAndRetries`、`malformedRetryAfterUsesDefaultBackoff` |
| F03 | P1 | 模型调用内部重试未复查取消，取消后仍可能发送下一次请求 | 每轮尝试、取得许可后、请求落盘后和退避前调用任务守卫；取消后不再发新请求。`cancellationDuringTheFirstAttemptStopsRetries`、`cancellationWhileWaitingReleasesPermitWithoutReservingQuota`、`cancellationDuringPersistenceClosesTheUnsentCall` |
| F04 | P2 | 读超时在等待并发许可前计算，等待后可能越过期限或沿用过长超时 | 取得许可后复查期限；落盘后按剩余期限重新计算发送超时；获取第二层许可也使用剩余时间，失败释放全局许可。`deadlineExpiredWhileWaitingPreventsSendingAndReleasesPermit`、`timeoutIsRecalculatedAfterRequestPersistence`、`deadlineBetweenGlobalAndTaskPermitsReleasesTheGlobalPermit` |
| F05 | P2 | 无执行器、执行失败、期限等路径仅结束父任务，留下未完成目标 | 失败、取消或部分完成时，调度器在同一新库事务中条件更新父任务并关闭未完成目标；扫描器复用此入口，已完成目标不重写，竞争失败不改目标。成功结果仍由执行器发布。`missingWorkerClosesOpenTargetsAndPreservesTerminalOnes`、两种期限结果、`losingTerminalUpdateCannotRewriteTargets`、`targetWriteFailurePropagatesOutOfTheTransaction` |
| F06 | P2 | 连通验证固定预留 64 个输出 token，忽略模型声明的更小上限 | 验证与正常调用均取配置预留和模型上限的较小值，包括未声明上下文窗口的验证。`validationHonoursMaximumOutputWithOrWithoutADeclaredWindow` |
| F07 | P2 | 策略页面发送 `specialistType`，服务端读取 `type`，类型筛选失效 | 客户端将类型筛选映射为 `type`，保留关键词、游标及条数。`v2.spec.ts` 检查实际发送参数 |
| F08 | P2 | 四页的旧列表响应可覆盖新筛选结果，旧错误和加载状态也会串入 | 四页按请求代次接受最新响应；忽略过期成功、错误和完成回调，筛选切换时清除旧游标。`AdminListOrdering.spec.ts` 对四页分别覆盖旧响应、旧错误与加载状态、旧分页追加，共 12 项 |
| F09 | P2 | 提示词元数据保存成功、正文版本保存失败后仍持有旧修订号，重试持续冲突 | 元数据提交成功即更新编辑修订号；正文失败保留草稿和弹窗，重试使用最新修订。`PromptList.spec.ts` 模拟服务端修订递增，断言第二次保存成功 |

审查阶段使用临时用例复现上述问题；本轮将回归用例纳入正式测试目录。空白 200 响应的既有处理已通过审查用例，不列为本轮缺陷。

## 2. 验证记录

构建与测试产物写入工作树外的临时目录；前端复制源码与公共资源后复用现有依赖联接，避免生成声明或构建产物污染工作区。

| 检查 | 结果 |
| --- | --- |
| 后端针对性测试 | 39 项，0 失败、0 异常、0 跳过 |
| 后端全量回归 | 474 项，0 失败、0 异常、7 跳过，即 467 项实际执行通过 |
| 前端针对性测试 | 3 个文件、24 项通过 |
| 前端全量回归 | 41 个文件、360 项通过，无失败或未处理异常 |
| 前端类型检查 | `vue-tsc --noEmit` 通过 |
| 前端生产构建 | Vite 构建通过；存在超过 500 kB 的分块提示 |

后端跳过数以本轮实际输出的 7 项为准：5 项真实 Gitea 用例与 2 项探针用例。原 P30.8 交付记录写为 9 项，不能将跳过项计为通过。前端测试伴随既有组件替身属性、路由组件及树节点告警。

## 3. 验证边界

- 后端使用映射器替身和本地 HTTP 替身；事务用例验证父子更新共用事务入口、异常向外传播及条件更新边界，未验证真实 MySQL 提交／回滚行为。
- 任务请求落盘后若发现取消或期限，调用记录收敛为 FAILED，不发送网络请求；已预留额度不返还，保持保守计量。
- 取消和期限检查发生在发送前的检查点；不承诺撤销已经发出的网络请求。
- 未执行真实数据库迁移、真实 Git 宿主、目标仓库、模型网关或 PC 页面视觉验收；继续按 [P30.8](p30-8-validation.md) 转入 P31.9／P32.6。
- 本轮不改动数据库迁移，不部署、不推送；未跟踪的 `../未决事项.xlsx` 保持原状。

此前 P30.1–P30.4 的修复记录见 [P30 审查修复](p30-review-fixes.md)。
