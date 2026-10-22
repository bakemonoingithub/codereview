# P31.1–P31.3 审查修复与复验

日期：2026-10-21（Asia/Taipei）。修复基线为 `3f48ac1`，工作树为 `agent/`，分支为 `refactor/agent`。

本轮修复审查发现的 R01–R09，范围为材料准备、声明定位与摘要、耦合事实及协议校验。P31 保持 active，P31.4–P31.9 未启动。

## 1. 修复明细

| 编号 | 原问题 | 修复及正式回归证据 |
| --- | --- | --- |
| R01（P1） | 按仓库根拼包路径，遗漏常见源码根，也无法发现多个源码根的同名声明 | 按固定 SHA 的路径清单匹配包路径后缀，读取后核对声明；多处可靠声明返回 AMBIGUOUS，候选核对不完整时不认定唯一。`findsDeclarationsUnderMavenSourceRoot`、`duplicateSourceRootsAreAmbiguous`、`incompleteCandidateSearchCannotClaimAUniqueDeclaration` |
| R02（P2） | 删除目标错误地强制要求 Java AST | BEFORE 侧仅在耦合专项且没有 AFTER 时强制结构解析；问题专项保留非 Java 或解析失败目标的原文。`deletedNonJavaIssueTargetKeepsItsReadableBeforeSide`、`deletedBrokenJavaIssueTargetFallsBackToOriginalText` |
| R03（P2） | 简单名相同导致外部限定类型被当作内部类型 | 区分完整限定身份、包内嵌套引用和简单名；耦合事实复用解析器的身份判断。`qualifiedExternalTypeIsNotCollapsedIntoLocalSimpleName`、`internalQualifiedAndNestedReferencesRemainInternal`；限定写法的 Java 平台类型保持排除 |
| R04（P2） | 构造器未进入结构模型，参数依赖遗漏且摘要暴露实现 | 抽取普通及紧凑构造器、参数和成员范围；构造器参数参与签名依赖及直接声明增强，正文参与实现省略。`constructorsContributeSignatureDependencies`、`constructorParameterDeclarationsAreEnhanced`、`declarationSummaryDoesNotIncludeConstructorImplementation` |
| R05（P2） | 按整行省略方法体，删除同一行中的类型头和字段 | 保存 AST 实现的行列范围，仅替换花括号内文本；保留同行范围外声明和换行数量，重叠范围只处理最外层。`compactMethodRedactionKeepsAdjacentDeclarations`、`summaryPreservesLinesAndNestedDeclarationsWithTabsAndCrLf`、`summaryRedactsMultipleAndOverlappingBodiesWithoutLosingDeclarations` |
| R06（P2） | 泛型基础类型及数组被误计为 GENERIC | 用擦除后的基础类型判断原关系，保留 INHERITANCE／FIELD／SIGNATURE／NEW，泛型实参单独计为 GENERIC。`genericAndArrayBaseTypesKeepTheirActualRelations`、`fullyQualifiedPlatformTypesDoNotBecomeUnresolvedDependencies` |
| R07（P2） | JSON 标量自动转换，数字正文或小数行号被接受 | 禁止标量强转、浮点转整数和基本类型 null；文本字段拒绝数字、布尔值转换。`strictParserRejectsNumericText`、`strictParserRejectsFractionalLineNumbers`、`strictParserRejectsNonIntegerLineTypes` |
| R08（P2） | 文件及字节预算只限制最终材料，不能阻止继续读取 | 解析器在每次新文件读取前预留次数，取得全文后按 UTF-8 字节核算，再决定是否解析；前后版本共用预算，缓存命中不重复计费；超限停止后续读取。`exhaustedAuxiliaryFileBudgetStopsReadingLaterBranches`、`zeroAuxiliaryBudgetDoesNotReadSource`、`byteBudgetCountsFullUtf8SourceBeforeSummarizing`、`oversizedRepositoryFailureAlsoStopsFurtherReads`、`diffSidesShareTheSameAuxiliaryReadBudget`、`nestedDeclarationsShareOneReadAndOneFileSlot` |
| R09（P2） | 实际提示词丢失耦合覆盖率、缺失数量和限制说明 | 统计值作为只读事实进入统一组装；耦合 limitations 合并到缺失说明。统计项不是 dependencyId，校验仍仅接受实际依赖边。`couplingPromptRetainsMissingCoverageDiagnostics` 及既有 `SpecialistPromptBuilderTest` |

上述新增用例统一位于 `backend/src/test/java/com/codereview/agent/specialist/SpecialistBoundaryRegressionTest.java`。既有耦合与组装测试同步检查新增统计事实，不放宽协议断言。

## 2. 验证

| 检查 | 结果 |
| --- | --- |
| 原始复现 | 审查时 13 项失败，覆盖 9 项问题；修复后全部通过 |
| 专项定向回归 | 86 项，0 失败、0 异常、0 跳过；含 27 项新增回归 |
| 后端全量隔离回归 | 560 项，0 失败、0 异常、9 跳过，即 551 项实际执行通过 |
| 编码、文档链接与差异检查 | UTF-8 无 BOM、LF；本地链接和 `git diff --check` 通过 |

构建和报告位于工作树外的独立临时目录，使用离线 Maven 与映射器／本地 HTTP 替身。运行进程清空 `DEEPSEEK_API_KEY`、`GITEA_TEST_BASE_URL`，并显式传入空的 `deepseek.api-key`；本轮修复验证没有真实模型或宿主调用。9 项跳过包括 5 项真实 Gitea、2 项网关探针和 2 项项目探针，不计为通过。

## 3. 边界与接续

- 辅助字节预算按取得的完整正文核算；现有仓库接口返回整文件，无法在取得响应前获知正文大小。单个超出剩余预算的候选仍可能被读取一次，但立即拒绝解析／发送，停止后续读取；此预算不声明为 HTTP 流量的硬上限。最终摘要另受累计发送字节上限约束。
- 同一 SHA、路径的源码只读取一次；多个声明可共用一个文件名额。不同 SHA 分别计费，但共用同一目标的总预算。
- 行列范围仅供准备期摘要使用；数据库继续保存正文、来源和完整性，未新增迁移或变更业务协议字段。
- 未执行真实新库迁移、目标仓库采样、P31 专项模型调用或 PC 视觉验收，继续按 P31.9／P32.6 验证。
- 本轮保留原有未跟踪表格，未推送或部署。执行器、容量拆分与结果发布仍按后续任务接入。

阶段入口见 [P31 总览](README.md)，原交付记录见 [P31.1](p31-1-materials.md)、[P31.2](p31-2-declarations.md)、[P31.3](p31-3-protocol.md)。
