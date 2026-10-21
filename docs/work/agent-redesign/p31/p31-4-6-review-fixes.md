# P31.4–P31.6 审查修复与复验记录

日期：2026-10-21（Asia/Taipei）。工作树：`H:\myProjects\aiCodeReview\agent`，分支：`refactor/agent`。

负责人要求检查 DS 的 P31.4–P31.6 交付，发现问题后直接修复、复验并提交。本轮从干净工作区开始，审查基线为 `25a4efc`，交付范围从 `4f76b49` 之后核对。仅处理本批成果与其直接依赖；P31 保持 active，未启动 P31.7–P31.9、P32 或 P33。

## 1. 审查依据

- [业务契约](../business-contract.md)：D-18、D-19、D-22、D-25、D-30、D-41–D-43、D-45、D-52。
- [成果盘点](../review-inventory.md)：B-03、B-04、B-10、N-07、N-08、UI-01、UI-04。
- [已批准专项设计](../p29/specialist-design.md)：第 4 节容量与重规划、第 5 节汇总去重及发布。
- [P31.4](p31-4-capacity.md)、[P31.5](p31-5-execution.md)、[P31.6](p31-6-launch.md) 的交付代码及测试。

## 2. 问题与修复

| ID | 优先级 | 触发条件与原问题 | 修复与证据 |
| --- | --- | --- | --- |
| R01 | P1 | 关闭智能拆分时，UTF-8 字节上界估算超限便直接拒绝发送，违反整文件提交的批准行为 | 规划器保留完整目标和辅助材料；仅专项整文件请求可将估算作为诊断，公共调用仍保存完整请求并执行额度、并发及模型参数检查。`keepsTheCompleteRequestWhenTheSwitchIsOff`、`wholeSpecialistRequestCanExceedTheConservativeEstimate` |
| R02 | P1 | 超大成员二分后的各片段分别能容纳，但全部重新加入同一单元，最终请求再次超限 | 每次追加后按完整组装请求计量，超限先结束当前单元；处理过程中检查单元上限。Java 优先 AST 语句结束行，无法利用语句边界时注明 `lines-nonsemantic`；补当前区间的类型、相关字段和签名事实，背景超限显式说明。`oversizedSingleMethodProducesOnlyFittingUnits`、`oversizedPlainTextIsPartitionedIntoFittingRequests`、`unitLimitStopsAnOversizedMethod` |
| R03 | P1 | 只按成员及类型尾部取块，成员之间注释、空行、类型之间与文件尾区域可能遗漏，却仍发布完整结果 | 按原始行游标补全所有成员外区间，保持 SHA、侧、路径和逐行正文；无成员类型只重复类型头，其余内容作为目标覆盖。`preservesCommentsAndRegionsBetweenTypesAndMembers` 同时检查完整覆盖和每个最终请求的容量 |
| R04 | P1 | 模型明确报告上下文超限时没有重规划，且任意含「context length」的 400 文本都可能误判 | 只识别结构化 `error.code` 的已知容量错误；开启拆分时最多重规划 2 次，逐次收紧规划窗口，按 generation 保存单元；旧代内容只供诊断，不混入新结果。关闭拆分时保留 `CONTEXT_LIMIT` 失败原因。`onlyStructuredContextErrorsAreRecognised`、`contextReplanningDiscardsPriorGenerationResults`、`repeatedContextLimitsStopAfterTwoReplans` |
| R05 | P1 | 取消或到期后收到迟到成功响应，仍可能发布结果；结果行与问题行分别提交，写入失败可留下半份正式结果 | 准备前、每次调用后及发布前检查任务；发布事务锁定任务行，结果、问题与目标终态在同一新库事务中完成，目标已结束则抛错回滚。发布器自身也使用新库事务。`aLateResponseAfterCancellationCannotPublish`、`publicationChecksTheLockedTaskState`；真实 MySQL 锁竞争与回滚仍未执行 |
| R06 | P1 | 格式重试次数与传输 attempt 混用，遗漏传输重试材料；解析失败时重复关联同一调用，可能撞主键；协议错误没有留在对应调用中 | 单元结束时按 unitId 一次关联全部实际调用，材料按 ID 去重；解析或位置校验失败写入对应调用的错误码和错误信息。`linksEveryTransportAndFormatAttemptExactlyOnce`、`invalidFormatExhaustsExactlyThreeAttempts` |
| R07 | P2 | 程序归并只取首片段摘要，问题来源误写为全部成功单元，耦合计算事实没有随结果保存；部分不同建议或大小写敏感原因被误合并 | 保留不同片段摘要；问题仅关联实际产生同一问题的单元，并保留重复项的全部来源；保存耦合边、指标及版本与规划说明；去重键包含建议、证据、变更关系和 SHA，原因保留大小写。`issueSourcesExcludeUnrelatedUnitsAndKeepDuplicateSources`、`couplingFactsAreFrozenWithThePublishedResult`、`differentSuggestionsAndCaseSensitiveReasonsAreNotCertainDuplicates` |
| R08 | P2 | 快照中显式为 null 的模型容量或 tokenizer 被当前配置替代，历史输入随配置编辑变化 | 显式 null 按快照保留，只有缺少字段时才走原有缺省分支。`explicitNullConfigurationRemainsFrozenInsteadOfReadingCurrentValues` |
| R09 | P1 | 快速切换项目、分支或提交，迟到 HEAD、列表及提交详情可覆盖当前版本；差异模式换分支保留旧提交；版本读取中可提交旧范围 | 来源身份变化立即失效旧版本、提交和范围；HEAD、提交列表、提交详情分别使用请求序号防止迟到写回；详情对应当前提交后才允许选范围；重新读取 HEAD 移动则清空旧范围，刷新失败后的重试仍核对最后观察版本；复制时重新带入当前策略模型。`ReviewLaunch.spec.ts` 的版本及复制用例 |
| R10 | P1 | 范围弹窗切换版本或连续预览不同文件时，迟到响应可能串版本或串正文；刷新失败还能确认旧树 | 列表与预览分别隔离请求序号；来源变化或关闭时失效旧请求；加载前清空树，加载中或失败时禁止确认。`ScopePickerModal.spec.ts` 6 项 |
| R11 | P1 | 创建任务的响应丢失后重试，每次生成新的幂等键，可重复创建并执行同一任务 | 同一页面内按提交正文复用幂等键；输入变化后生成新键；`SOURCE_MOVED` 保留说明并阻止继续提交，显式刷新后重选。`ReviewLaunch.spec.ts` 的幂等和来源移动用例 |
| R12 | P2 | 审查中心只展示前 50 条，没有后续页入口；快速筛选时旧列表可覆盖新列表，类型和状态也不能清除 | 增加按服务端游标加载更多，刷新与筛选从第一页读取，丢弃迟到响应；类型和状态允许清除。`ReviewCenter.spec.ts` 2 项 |

## 3. 复验结果

本轮新增后端 17 项、前端 18 项正式回归。原有用例中，关闭拆分的容量用例改为核对批准的整文件发送行为；执行器和发布器的数据库替身执行事务回调。新增测试检查用户可见行为及失败边界，不把 Mockito 当作真实数据库证明。

| 检查 | 最终结果 | 范围 |
| --- | --- | --- |
| 后端定向回归 | 50 项通过，0 失败、0 异常 | 规划、归并、执行、发布、公共模型调用 |
| 后端 `mvn clean test` | 90 个测试类、593 项；0 失败、0 异常、9 跳过，实际执行 584 项 | 清理旧报告后统计；网关与宿主探针显式禁用 |
| 前端全量 Vitest | 45 个文件、385 项通过 | 发起页、范围弹窗、审查中心及既有回归 |
| `vue-tsc --noEmit` | 通过 | 前端类型检查 |
| `vite build` | 通过 | 保留既有 chunk 体积提示 |
| 文档与提交检查 | 通过 | UTF-8 无 BOM、LF、本地链接与 `git diff --check` |

使用 JDK 21 和本机 IntelliJ 随附 Maven。当前 pnpm 启动器指向缺失的缓存路径，因此前端直接通过 Node 调用现有依赖中的 Vitest、vue-tsc 和 Vite；没有重新安装依赖，也没有改动 node_modules 联接。Vite 生成的 `frontend/components.d.ts` 在交付前恢复到审查基线。

全量首轮因继承环境中的 `DEEPSEEK_API_KEY`，意外运行了 `AiGatewayProbeTest` 的连通与结构化 JSON 两项真实网关探针，两项均通过。它们只使用测试内固定示例，不涉及目标仓库，也没有证明专项完整流程；不纳入本轮隔离复验通过数量。发现后清空该环境变量及宿主探针变量，并指定空的 `deepseek.api-key`、`probe.root` 系统属性后重新运行。最终汇总以隔离重跑为准。首轮日志与最终日志在本地 `backend/target/p31-4-6-review/` 的 `backend/`、`frontend/` 子目录保留；该目录属于可清理构建产物，不纳入 Git，正式结论以本记录为准。凭据未写入修复记录。

中间复验还发现覆盖样本使用重复字段名，加入声明背景后不代表合理 Java 文件；已改为不同字段名的有效样本，并保留单元上限独立用例。最终结果不沿用中间失败报告。

## 4. 验证边界

- 已验证固定样本的工程行为；未执行真实新库迁移、真实数据库访问层或并发锁竞争。
- 两个通用真实网关探针不代替专项任务的真实模型执行、容量校准或结果质量验收。模型端容量重规划使用本地替身验证，75% 收紧比例仍为实现起点，待 P31.9 校准。
- 未操作目标仓库、真实 Git 宿主或部署入口；未启动额外服务，也未做 PC 正式页面截图与视觉验收。
- P31.7–P31.9、P32、P33 的原有边界不变。本轮只补当前已授权成果的缺陷。
- 修复提交保存在本地 `refactor/agent`；未 push。
