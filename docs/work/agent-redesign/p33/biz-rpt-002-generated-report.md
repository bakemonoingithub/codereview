# BIZ-RPT-002 专项审查链路可靠性与扩展成本评估报告

## 1. 业务范围与结论

### 1.1 范围与口径

本报告依据冻结来源，评估 `codereview` 仓库版本 `738be95c0822f358dcb43034dfa1f919d78cc236` 上专项审查链路的可靠性、耦合与扩展成本。共 9 个来源、4 个任务，覆盖问题审查 3 个来源、耦合度分析 3 个来源、设计模式分析 3 个来源；共整理问题 19 个 [S1][S2][S3][S4][S5][S6][S7][S8][S9]。

来源与任务对应关系：

| 任务 | 来源 | 分析类型 | 目标文件 | 任务状态 |
|---|---|---|---|---|
| 01M5KRBT9PX2FS6Z59Z9Q7523R | S1 | 耦合度 | SpecialistTaskWorker.java | SUCCEEDED |
| 01M5KR7N76Z9MW0YJ77ZJX05J9 | S2 | 耦合度 | SpecialistResultPublisher.java | PARTIAL |
| 01M5KR7MQ2XF3G8BT51DGYSY9F | S3 | 问题审查 | SpecialistTaskWorker.java | SUCCEEDED |
| 01M5KR7N76Z9MW0YJ77ZJX05J9 | S4 | 耦合度 | TaskService.java | PARTIAL |
| 01M5KR7NN1E76DMTW6A2Q7KVMM | S5 | 设计模式 | SpecialistTaskWorker.java | SUCCEEDED |
| 01M5KR7MQ2XF3G8BT51DGYSY9F | S6 | 问题审查 | TaskService.java | SUCCEEDED |
| 01M5KR7MQ2XF3G8BT51DGYSY9F | S7 | 问题审查 | SpecialistResultPublisher.java | SUCCEEDED |
| 01M5KR7NN1E76DMTW6A2Q7KVMM | S8 | 设计模式 | TaskService.java | SUCCEEDED |
| 01M5KR7NN1E76DMTW6A2Q7KVMM | S9 | 设计模式 | SpecialistResultPublisher.java | SUCCEEDED |

本报告全部为只读静态审查材料整理，**未执行运行验证、未运行功能测试**；来源明确说明失败隔离、重试、事务与并发行为仅依据代码结构与注释判断，不能扩大为运行时结论 [S1][S2][S4][S6][S7]。

人工反馈方面：问题级反馈准确 0 / 不准确 0 / 无法判断 0 / 未反馈 19；结果级反馈准确 0 / 不准确 0 / 无法判断 0 / 未反馈 6。因此，本报告中的审查发现均**未取得人工确认**，不能作为已验收结论使用 [S1][S2][S3][S4][S5][S6][S7][S8][S9]。

### 1.2 总体结论

**发起端**：`TaskService.create` 已实现幂等键查询、请求哈希、来源固定 SHA 校验、目标规范化和事务插入，但存在幂等键查询与插入之间的竞态窗口、`requestHash` 仅在 key 非空时写入、损坏快照被静默读成空对象等风险 [S6]。同时 `TaskService` 是发起链路的高扇出聚合类，新增审查类型需要同时修改硬编码白名单、策略类型校验和路由判断 [S4][S6][S8]。

**执行端**：`SpecialistTaskWorker` 以 `TaskWorker` 接口实现者身份组织准备、增强、规划、调用、解析、校验、发布流程 [S5]。静态代码显示其失败隔离依赖 `Future.get` 异常分支；容量重规划可能留下旧代执行单元；单元部分失败仍会发布 `INCOMPLETE/PARTIAL` 结果；`read()` 解析异常被静默吞掉 [S3]。该类直接依赖 17 个协作 Bean 与 6 个 Mapper/实体，新增审查类型或调整模型调用流程会波及多个环节 [S1]。

**发布端**：`SpecialistResultPublisher` 在单个事务内完成合并结果落库、问题展开和 provenance 序列化，职责集中 [S2][S9]。静态审查指出发布入口未见幂等或重复发布防护，无法解析的问题位置会被静默丢弃，问题来源单元匹配为嵌套全量扫描 [S7]。这些是结果可信性的重点风险，但重复发布是否实际发生仍需调用方、Mapper 与数据库约束材料验证 [S7]。

**扩展成本**：新增审查类型的修改点至少分散在 `TaskService` 的类型白名单与策略校验、`SpecialistTaskWorker.facts()` 的 `"COUPLING"` 字符串分支与 `resultParser.parse(..., specialistType)`、`SpecialistResultPublisher` 的 `IssueResult` 分支与发布写入逻辑 [S1][S2][S4][S5][S6][S7]。高扇出与集中职责是**修改成本信号，不等同于缺陷判定**；识别出策略或流程骨架结构也不等于设计合理 [S1][S4][S5][S8][S9]。

---

## 2. 可靠性问题

### 2.1 已由代码证据确认的结构性问题（静态确认，非运行确认）

以下问题均有代码位置或代码事实支持；“确认”仅指代码结构层面，不代表已在运行中复现。

| 编号 | 严重度/类型 | 问题 | 代码证据与影响 | 来源 |
|---|---|---|---|---|
| R1 | HIGH/RELIABILITY | 容量重规划循环未清理旧代单元，可能留下悬挂执行单元 | 每代循环都会 `createUnit` 插入新单元；`CONTEXT_LIMIT` 触发重规划时仅 `continue`，旧代已插入行未标记废弃；若后续代成功，数据库可能同时存在多代单元记录，影响结果可追溯性与状态一致性 [S3] | [S3] |
| R2 | HIGH/CORRECTNESS | 单元部分失败仍发布结果，与“完整发布要求全部单元成功”的注释语义存在不一致 | `unitFailed` 为 true 时仍以 `complete=false` 调用 `publisher.publish` 并返回 `PARTIAL`；`execute` 层又把 `PARTIAL` 计入 `usable` 并返回 `TaskOutcome.partial`，下游可能将其当作可用结果 [S3] | [S3] |
| R3 | MEDIUM/RELIABILITY | `read()` 吞掉 JSON 解析异常并返回空对象 | 解析失败时返回空 `ObjectNode`，不记录日志也不抛出；`command()`/`strategy()` 可能静默使用默认值继续执行，难以定位根因 [S3] | [S3] |
| R4 | MEDIUM/RELIABILITY | 线程池 `shutdownNow` 中断正在执行的目标，可能造成状态不一致 | `finally` 中无条件 `pool.shutdownNow()`；中断可能使单元停留在 `RUNNING`，而 `executeTarget` 的 catch 只处理 `ApiException`，中断路径未显式更新目标终态 [S3] | [S3] |
| R5 | MEDIUM/RELIABILITY | `linkMaterials` 在 `finally` 中执行，异常会覆盖原始失败 | `executeUnit` 的 `finally` 调用 `linkMaterials`，其中包含多次数据库 insert；若其再次抛异常，会覆盖模型调用原始失败原因 [S3] | [S3] |
| R6 | MEDIUM/CORRECTNESS | `facts()` 对空 targets 直接 `get(0)` 可能越界 | `COUPLING` 类型下先按 `AFTER`、再按 `FILE` 过滤，最后 `orElse(target.targets().get(0))`；空列表会抛 `IndexOutOfBoundsException`，该异常不是 `ApiException`，上层会记为 `EXECUTION_ERROR` 并丢失具体原因 [S3] | [S3] |
| R7 | LOW/RELIABILITY | `execute` 中 `finishTarget` 返回值被忽略 | `ExecutionException` 分支调用 `finishTarget` 但未检查返回值；若目标已终态，更新行数为 0，代码仍添加 `FAILED` outcome，可能与数据库实际状态不一致 [S3] | [S3] |
| R8 | MEDIUM/CORRECTNESS | 幂等键查询与插入之间存在竞态，且 `requestHash` 仅在 key 非空时写入 | `create` 先 `findByKey` 再事务 insert；并发同 key 请求依赖 `DuplicateKeyException` 兜底，但兜底分支只在 `key != null` 时重查；`requestHash` 在 key 为空时为 null，`replay` 对旧记录哈希为 null 的情况未做冲突判断 [S6] | [S6] |
| R9 | MEDIUM/RELIABILITY | `readJson` 对损坏快照静默返回空对象 | 与同文件 `readStoredJson` 明确抛 `INTERNAL_ERROR` 的行为不一致；`toView`、`copyInput` 使用 `readJson`，快照损坏时会返回字段为空的“正常”视图 [S6] | [S6] |
| R10 | LOW/CORRECTNESS | `cancel` 中任务状态更新与目标状态更新不在同一事务 | 先更新任务为 `CANCELLED`，再更新目标状态，两次 update 未包在 `db.inTransaction` 中；第二次失败会造成任务已取消但目标仍非终态 [S6] | [S6] |
| R11 | LOW/RELIABILITY | `normalizeTargets` 未对 targets 判空 | 服务层直接 for-each 遍历 targets，依赖上层 `@NotEmpty`；内部调用或未来新增入口可能抛 NPE 而非业务校验错误 [S6] | [S6] |
| R12 | LOW/CORRECTNESS | `resolveSource` 对 DIFF 分支未校验 branch 与 commit 的一致性 | DIFF 分支只校验 commitSha/baseSha 为固定 SHA 且不相同，branch 仅 trim 后保存；快照中 branch 与 commitSha 语义可能不一致 [S6] | [S6] |
| R13 | HIGH/RELIABILITY | 发布入口无幂等或重复发布防护 | `publish` 每次调用都生成新的 result id 并插入新结果行，未检查该 task/target 是否已有结果，也未使用任务 revision 或幂等键做条件写入；重试、超时重入或并发调用可能为同一 target 产生多条结果 [S7] | [S7] |
| R14 | MEDIUM/CORRECTNESS | 位置无法解析时静默丢弃 | `locations` 在 materials 中找不到 `location.materialId` 对应材料时直接 `continue`，不记录缺口或告警；问题可能以空 locations 落库，违反“每条问题至少一处目标位置”的输出约定 [S7] | [S7] |
| R15 | MEDIUM/PERFORMANCE | 问题来源单元匹配为嵌套全量扫描 | 对每个合并后问题遍历全部 executions，并在每个片段内再遍历其全部 issues 重新计算 `issueKey`，复杂度为 O(问题数 × 片段数 × 片段问题数) [S7] | [S7] |
| R16 | LOW/MAINTAINABILITY | 发布规则与存储细节耦合在单一类中 | completeness 字符串常量、按 `IssueResult` 类型分支写问题、provenance 结构拼装都硬编码在发布类中；新增审查类型或调整发布规则需修改多处 [S7] | [S7] |
| R17 | LOW/RULE | `publish` 缺少 Javadoc | 类有 Javadoc，但 public 方法 `publish` 未说明参数、`complete=false` 语义、返回值与异常行为 [S7] | [S7] |
| R18 | LOW/MAINTAINABILITY | 魔法数字 3 与 0.75 缺少常量或注释 | 重规划代数上限 3、窗口收缩系数 0.75 直接写在循环与计算中，属于影响容量行为的业务规则，未提取常量也未注释依据 [S3] | [S3] |

### 2.2 潜在风险（需跨文件或运行时材料才能定性）

1. **重规划旧代悬挂对结果可追溯性的影响**：代码事实确认旧代单元行未标记废弃 [S3]；但实际是否产生悬挂记录取决于 `CONTEXT_LIMIT` 在真实模型调用中是否触发，以及发布时是否只按当前代过滤。该影响需数据库状态与发布查询条件验证 [S3][S7]。
2. **部分失败结果进入下游选材**：`TaskOutcome.partial` 与 `INCOMPLETE` 的语义是否被下游正确过滤，材料未提供下游选材代码；因此只能标记为潜在风险 [S3]。
3. **重复发布导致同一 target 多条结果**：`SpecialistResultPublisher.publish` 未见幂等防护 [S7]；但是否存在并发/重试重入路径，需调用方代码与 Mapper/表约束确认。来源明确说明未提供调用方，无法确认重复发布实际路径 [S2][S7]。
4. **幂等键竞态导致重复任务**：`TaskService` 的兜底依赖 `DuplicateKeyException` [S6]；数据库是否对 `(project_id, idempotency_key)` 建唯一索引未在材料中确认 [S6]。
5. **损坏快照被静默降级**：`readJson` 与 `read()` 均存在静默返回空对象的行为 [S3][S6]；实际影响取决于快照损坏频率及下游对缺失字段的容忍度，材料未提供运行数据。
6. **线程中断导致单元停留在 RUNNING**：代码结构显示中断路径未显式写单元终态 [S3]；需运行时中断测试验证。
7. **位置静默丢弃导致问题定位为空**：需确认 materials 映射是否覆盖所有问题引用的 materialId；来源未提供 `PreparedTarget.allMaterials` 与 `SpecialistUnitPlan.target` 实现 [S7]。
8. **O(n²) 发布耗时**：需单元数/问题数规模数据才能判断是否构成实际性能问题 [S7]。

### 2.3 待验证事项

- `AgentDatabase.inTransaction` 的事务传播、嵌套调用与回滚行为未提供实现，重复发布与部分写入风险只能基于调用方式判断 [S2][S6][S7]。
- `TaskExecutionGuard.requireActive` 的异常类型、`ModelInvocationService.invoke` 的中断行为、`SpecialistSplitPlanner.plan` 的返回约束均为声明摘要，无法确认 [S3][S5]。
- `TaskDispatchService.selectWorker` 的具体选择条件未提供，调度层如何选择 `TaskWorker` 实现无法确认 [S4][S5][S8]。
- `SpecialistResultMerger.merge` 与 `issueKey` 的实现未提供，合并去重规则是否与发布来源单元匹配一致无法确认 [S7][S9]。
- `AgentReviewResultMapper`、`AgentIssueMapper` 的 SQL 与表约束未提供，是否存在唯一约束可阻止重复发布无法确认 [S7]。
- `SpecialistMaterials.PreparedTarget.allMaterials` 与 `SpecialistUnitPlan.target` 的实现未提供，materials 映射是否覆盖所有问题引用的 materialId 无法确认 [S7]。
- `Logger`、`ObjectMapper`、`JsonNode`、`ObjectNode`、`Material`、`ApiException` 等类型在仓库版本中未定位或辅助源码读取达到上限，相关日志、序列化与异常边界未核实 [S1][S2][S3][S4][S6][S7]。
- 未运行功能测试，失败隔离、重试、事务与并发行为均未运行验证 [S1][S2][S3][S4][S6][S7]。

---

## 3. 耦合与修改成本

### 3.1 耦合指标与覆盖限制

| 文件 | 已解析出向依赖 | 未解析类型 | 覆盖率 | 来源 | 覆盖限制 |
|---|---:|---:|---:|---|---|
| `SpecialistTaskWorker.java` | 31 | 8 | 0.7948717948717948 | [S1] | 单文件依赖事实；未计算 fan-in/instability；`SpecialistResultPublisher` 源码读取达到上限；8 个类型未定位 |
| `SpecialistResultPublisher.java` | 11 | 4 | 0.7333333333333333 | [S2] | 仅单文件；未获得调用方与 Mapper 实现；未提供包级依赖图 |
| `TaskService.java` | 37 | 6 | 0.8604651162790697 | [S4] | 仅单文件；辅助材料为声明摘要；未提供 Controller 层与包级依赖事实 |

上述覆盖率均未覆盖全部出向依赖，不能据此推断全仓耦合状况；高扇出是修改成本信号，**不能将高耦合直接认定为缺陷** [S1][S2][S4]。

### 3.2 发起端：TaskService 的聚合与修改点

`TaskService` 同时承担创建/幂等、配置解析、来源校验、目标规范化、快照序列化、查询读取与结果组装，并直接依赖 14 个 Mapper 与 6 个外部服务/配置类 [S4]。新增审查类型的修改点集中在：

- `normalizeSpecialist` 使用 `List.of("ISSUE","PATTERN","COUPLING")` 硬编码允许值 [S4][S6][S8]；
- `resolveConfig` 使用 `specialistType.equals(strategy.getSpecialistType())` 校验策略与专项类型一致 [S4][S6][S8]；
- `normalizeRoute` 硬编码只允许 `SPECIALIST`，并拒绝 `AGENT` [S4][S6][S8]；
- `configSnapshot` 逐字段写入模型与提示词字段，模型调用流程变更会扩散到快照写入点 [S4]。

S4 建议拆分为 `TaskCreationService`、`TaskQueryService`、`TaskSnapshotCodec`，并引入 `SpecialistTypeRegistry` 与配置解析接口 [S4]。S6 也建议将合法专项类型与路由抽取为集中常量或枚举 [S6]。这些是降低修改成本的建议，不代表现有结构已被判定为缺陷。

### 3.3 执行端：SpecialistTaskWorker 的高扇出

`SpecialistTaskWorker` 直接依赖 17 个协作 Bean 与 6 个 Mapper/实体，类型分派与失败处理集中在单类中 [S1]。其扩展影响点包括：

- `executeTarget` 串联准备、增强、规划、调用、解析、校验、发布全流程，依赖签名或行为变化会传导到本类 [S1]；
- `facts()` 以字符串 `"COUPLING"` 判断是否构建耦合事实，`executeUnit` 又把 `specialistType` 传给 `resultParser.parse`，新增审查类型需同时修改这些分支点 [S1][S5]；
- 直接依赖 `AgentDatabase` 与多个 Mapper，执行层同时承担持久化细节 [S1]；
- 模型调用失败处理与重试逻辑内嵌在执行流程，调整调用流程会同时改动内嵌逻辑 [S1]。

S1 建议抽取 `SpecialistTargetPreparer`、`SpecialistUnitRunner`、`SpecialistTypeHandler`、`SpecialistExecutionRepository`、`CapacityReplanPolicy`、`TargetExecutionCoordinator` 等组件，以缩小影响面 [S1]。这些是结构性建议，需结合后续设计评审验证。

### 3.4 发布端：SpecialistResultPublisher 的集中职责

`SpecialistResultPublisher` 在单个事务内集中承担合并结果落库、问题展开与 provenance 序列化三类职责，并直接依赖 `AgentDatabase` 与实体/Mapper [S2]。S2 建议按“持久化端口→结果类型写入器→provenance 组装”拆分 [S2]；S7 也指出 completeness 常量、`IssueResult` 分支和 provenance 拼装硬编码在发布类中，新增审查类型需修改多处 [S7]；S9 将其概括为事务脚本式集中编排 [S9]。

扩展新审查类型时，若产生新的 `Result` 子类型，当前代码需要在发布类增加分支与新的落库路径 [S2][S7]。但 S2 为 PARTIAL 任务，且 `SpecialistResultPublisher` 辅助源码读取达到上限，发布类内部依赖未完全纳入分析 [S2][S3][S5]。

### 3.5 跨文件关联：修改范围如何叠加

在来源证据支持范围内，可以确认以下**修改点叠加**，但不能断言已发生端到端故障：

1. 新增审查类型需要同时改 `TaskService` 的白名单/策略校验 [S4][S6][S8]、`SpecialistTaskWorker` 的 `"COUPLING"` 分支与解析类型传递 [S1][S5]、`SpecialistResultPublisher` 的 `IssueResult` 分支与写入逻辑 [S2][S7]。
2. 发起端幂等竞态 [S6] 与发布端无幂等防护 [S7] 都指向“同一任务/目标可能产生重复记录”的风险，但两者位于不同环节，材料未提供调用方与数据库约束，不能合并为已确认的端到端重复发布缺陷。
3. 执行端重规划旧代未清理 [S3] 与发布端只接收当前代 `executions` [S3][S7] 可能共同影响可追溯性；但发布查询与单元表约束未提供，需验证。

---

## 4. 设计模式与职责

### 4.1 已识别的模式候选

| 文件 | 模式候选 | 置信/性质 | 职责与扩展含义 | 来源 |
|---|---|---|---|---|
| `SpecialistTaskWorker` | 策略（BEHAVIORAL） | 结构证据：实现 `TaskWorker` 接口，`TaskWorker` 声明 `execute` 与 `route`，调度层按 route 选择 | 具备可替换算法结构；但本文件未见调度层选择逻辑，`route()` 实现被省略 | [S5] |
| `SpecialistTaskWorker` | 模板方法（BEHAVIORAL） | 更接近流程骨架，非经典可扩展模板方法 | `execute`、`executeTarget`、`executeUnit` 固定步骤顺序，子步骤为私有方法而非可覆盖钩子 | [S5] |
| `SpecialistTaskWorker` | OTHER：组合根/协调者 | 结构证据：构造注入 17 个协作依赖，串联准备、增强、规划、调用、解析、校验、发布 | 属于职责链式编排；类型分派依赖 `specialistType` 字符串比较，扩展成本集中在字符串分支 | [S5] |
| `TaskService` | 模板方法（形似） | 低置信：单一具体类，无抽象父类或钩子 | `create` 固定发起流程骨架，各步骤由私有方法实现 | [S8] |
| `TaskService` | 策略（雏形） | 低置信：无策略接口或可替换实现类 | 按 `specialistType` 与 route 做分支判断，选择逻辑内联 | [S8] |
| `TaskService` | 建造者（形似） | 低置信：未使用 Builder 类型或链式接口 | `inputSnapshot`、`configSnapshot`、`insertTargets` 分步组装对象 | [S8] |
| `SpecialistResultPublisher` | OTHER：事务脚本 | 结构证据：`publish` 以 `db.inTransaction` 包裹合并、结果写入、问题写入与 provenance 组装 | 发布规则与存储写入集中；扩展新审查类型需修改分支与字段映射 | [S9] |

### 4.2 职责判断

- `SpecialistTaskWorker` 的职责组织是“执行编排 + 类型分派 + 失败处理 + 持久化细节”混合在单类中；策略接口提供了执行器可替换的结构，但**类型维度的扩展仍靠字符串分支**，不能因为存在 `TaskWorker` 策略结构就认定扩展设计合理 [S1][S5]。
- `TaskService` 的创建流程呈固定骨架，但类型白名单、策略校验与路由判断分散在私有方法中；S8 明确其模式候选均为低置信形似结构，不能断言为经典模式实现 [S8]。
- `SpecialistResultPublisher` 是集中式事务脚本，发布规则、行映射、JSON 序列化与来源组装集中在一个类中；S9 未发现可确证的单例、工厂、观察者、策略、装饰器、适配器、代理或模板方法模式 [S9]。
- **识别出设计模式不等于设计合理**：S5 的策略结构仅证明存在可替换执行器接口，S8 的模板方法/策略/建造者均为形似，S9 的事务脚本也不是质量背书；是否合理需结合修改成本、职责边界与后续验证判断 [S5][S8][S9]。

---

## 5. 综合行动建议

以下优先级只依据本次冻结来源中的代码事实与静态风险，不代表已排期或已修复。验证方式为建议，不是已执行结果。

### 5.1 P0：优先处理结果可信性与重复记录风险

| 优先级 | 行动 | 依据 | 建议验证 |
|---|---|---|---|
| P0-1 | 为 `SpecialistResultPublisher.publish` 增加幂等/重复发布防护：按 `taskId+targetId` 查重，或使用任务 revision/幂等键做条件写入，并确认数据库唯一约束 | S7 指出每次发布都生成新 result id、未见幂等防护；S2 指出未获得调用方与 Mapper 实现，重复发布路径未确认 | 先补调用方、Mapper SQL 与表约束材料；再设计并发/重试场景测试；不能以当前静态材料断言已存在重复发布 |
| P0-2 | 处理 `TaskService` 幂等键竞态：确认 `(project_id, idempotency_key)` 唯一索引；明确 `requestHash` 为 null 的 replay 语义；`DuplicateKeyException` 分支给出明确错误 | S6 指出查询与插入竞态、`requestHash` 仅在 key 非空时写入、replay 对 null 哈希未做冲突判断 | 检查 schema 与唯一约束；补并发同 key 请求测试；验证 key 为空与 key 非空两条路径 |
| P0-3 | 处理重规划代际未收敛：在 replan 分支显式标记旧代单元为 `SUPERSEDED/FAILED`，或在发布前按 generation 过滤清理；考虑 `(targetId, generation, ordinal)` 唯一约束 | S3 指出旧代单元行保持 RUNNING/FAILED 且未标记废弃，可能影响可追溯性与状态一致性 | 构造 `CONTEXT_LIMIT` 触发重规划的单测；检查发布查询是否只取当前代；检查单元表约束 |
| P0-4 | 明确 `PARTIAL/INCOMPLETE` 的业务语义：要么发布时写入不可选材标记并由下游过滤，要么单元失败时直接 `finishTarget FAILED` 不发布 | S3 指出 `unitFailed=true` 仍发布，且 `execute` 把 `PARTIAL` 计入 `usable`；下游选材代码未提供 | 先补下游选材与 `TaskOutcome.partial` 消费方材料；再决定发布语义；补部分失败场景测试 |

### 5.2 P1：修复静默失败与状态一致性问题

| 优先级 | 行动 | 依据 | 建议验证 |
|---|---|---|---|
| P1-1 | 将 `SpecialistTaskWorker.read()` 与 `TaskService.readJson()` 的解析失败改为记录带 taskId 的错误日志并抛出明确异常，或至少区分“空值”与“损坏” | S3、S6 均指出损坏 JSON 被静默返回空对象，可能以错误配置继续执行或返回字段为空的视图 | 构造损坏 `inputSnapshot/configSnapshot` 的测试；核对 `readStoredJson` 的既有抛错行为 |
| P1-2 | 修复 `linkMaterials` 在 `finally` 中覆盖原始异常的问题：捕获并记录 `linkMaterials` 异常，不覆盖原始失败；或移出 `finally` | S3 指出数据库 insert 异常会覆盖模型调用原始失败原因 | 构造模型调用失败 + linkMaterials 数据库异常的组合测试 |
| P1-3 | 处理线程池中断路径：在 `executeTarget` 捕获 `InterruptedException` 并显式写入目标/单元终态，或在 `shutdownNow` 前等待任务收敛 | S3 指出中断可能使单元停留在 `RUNNING`，中断路径未更新目标终态 | 构造取消/超时中断测试；检查单元与目标终态 |
| P1-4 | 将 `cancel` 的任务状态更新与目标状态更新放入同一事务 | S6 指出两次 update 未包在 `db.inTransaction` 中，第二次失败会造成任务与目标状态不一致 | 构造目标更新失败场景；验证回滚后任务与目标状态 |
| P1-5 | 对无法解析的问题位置记录告警或写入 `materialGaps`；若问题最终 locations 为空，拒绝发布或标记不完整 | S7 指出 `locations` 找不到 materialId 时直接 `continue`，问题可能以空 locations 落库 | 构造材料映射缺失场景；核对 `PreparedTarget.allMaterials` 覆盖范围 |

### 5.3 P2：降低扩展成本与可维护性债务

| 优先级 | 行动 | 依据 | 建议验证 |
|---|---|---|---|
| P2-1 | 将专项类型白名单、路由判断、策略类型校验收敛到集中的 `SpecialistTypeRegistry`/枚举，错误文案由注册表生成 | S4、S6、S8 均指出 `ISSUE/PATTERN/COUPLING`、`SPECIALIST/AGENT` 硬编码分散，新增类型需改多处 | 以新增一个假想类型为演练，检查修改点是否收敛到注册表 |
| P2-2 | 在 `SpecialistTaskWorker` 引入 `SpecialistTypeHandler` 或等价扩展点，收敛 `facts()` 的 `"COUPLING"` 分支与 `resultParser.parse` 的类型传递 | S1、S5 指出类型分派散落在 Worker 内，新增类型需同时修改多个分支点 | 以新增类型验证 Worker 是否无需修改；确认 handler 注册机制 |
| P2-3 | 将 `SpecialistResultPublisher` 的 `IssueResult` 写入、completeness 和 provenance 组装拆分为独立组件/写入器 | S2、S7、S9 指出发布类集中承担合并、行映射、JSON 序列化与来源组装，新增类型需改多处 | 以新增 Result 子类型验证发布类是否只保留事务编排 |
| P2-4 | 优化问题来源单元匹配：合并前一次性构建 `Map<issueKey, Set<unitId>>`，避免嵌套全量扫描 | S7 指出当前复杂度为 O(问题数 × 片段数 × 片段问题数) | 以单元数/问题数规模数据做基准测试；确认 `issueKey` 语义一致 |
| P2-5 | 修复 `facts()` 空 targets 越界、`finishTarget` 返回值忽略、魔法数字 3/0.75 提取常量并注释 | S3 分别指出越界异常丢失、状态可能不一致、容量规则难理解 | 补空 targets 测试、终态竞争测试；代码评审确认常量语义 |

### 5.4 建议的验证顺序

1. **先补材料**：调用方代码、Mapper SQL/表约束、`AgentDatabase.inTransaction` 实现、`SpecialistResultMerger.issueKey` 实现、`SpecialistMaterials.PreparedTarget.allMaterials` 实现 [S2][S6][S7][S9]。
2. **再做单文件单测**：重规划旧代清理、部分失败发布、损坏快照、空 targets、`linkMaterials` 异常覆盖、线程中断终态 [S3][S6]。
3. **再做跨文件集成测试**：同 key 并发创建、重复 publish、任务取消与目标状态一致性、发布位置映射完整性 [S6][S7]。
4. **最后做端到端并发/重试测试**：验证发起幂等、执行重规划、发布幂等三者叠加时不会产生重复结果或悬挂单元 [S3][S6][S7]。

---

## 6. 限制与未验证项

### 6.1 材料覆盖限制

- S2 与 S4 的任务状态为 `PARTIAL`，其耦合结论仅覆盖单文件，未获得调用方、Mapper 实现、Controller 层与包级依赖事实；不能据此判断分层违规或循环依赖 [S2][S4]。
- `SpecialistResultPublisher` 辅助源码读取达到 `LIMIT_REACHED`，其内部依赖未纳入分析 [S1][S2][S3][S5]。
- `Logger`、`ObjectMapper`、`JsonNode`、`ObjectNode`、`Material`、`ApiException` 等类型在仓库版本中未定位（`NOT_AVAILABLE`）或辅助源码读取达到上限，相关日志、序列化与异常边界未核实 [S1][S2][S3][S4][S6][S7]。
- `SpecialistTaskWorker` 的 8 个未解析类型导致出向依赖统计未覆盖全部引用；`TaskService` 与 `SpecialistResultPublisher` 同样存在未解析类型，覆盖率分别为 0.8604651162790697 与 0.7333333333333333 [S1][S2][S4]。
- 未提供 fan-in、instability 等全仓指标，不能据此推断全仓耦合状况 [S1][S2][S4]。
- 未提供包级依赖图，无法判断是否存在循环依赖或分层违规的全局结论 [S2][S4]。
- 未提供 `TaskDispatchService.selectWorker` 的具体选择条件与 `TaskWorker` 接口完整定义，调度层如何选择执行器未确认 [S4][S5][S8]。
- 未提供 `AgentDatabase.inTransaction` 的实现，事务传播、嵌套调用与回滚行为未确认 [S2][S6][S7]。
- 未提供 `SpecialistResultMerger.merge` 与 `issueKey` 的实现，合并去重规则与发布来源单元匹配是否一致未确认 [S7][S9]。
- 未提供 `AgentReviewResultMapper`、`AgentIssueMapper` 的 SQL 与表约束，是否存在唯一约束可阻止重复发布未确认 [S7]。
- 未提供 `SpecialistMaterials.PreparedTarget.allMaterials` 与 `SpecialistUnitPlan.target` 的实现，materials 映射是否覆盖所有问题引用的 materialId 未确认 [S7]。

### 6.2 未运行验证

- 本次全部为只读静态审查，**未运行功能测试**；失败隔离、重试、事务、并发与状态一致性结论均不能视为运行时已验证 [S1][S2][S3][S4][S6][S7]。
- S3 明确说明跨文件状态一致性与事务边界结论不能扩大为全系统结论 [S3]。
- S7 明确说明重复发布与部分写入风险只能基于本文件调用方式判断 [S7]。
- S9 明确说明未发现经典模式证据，不能断言重复发布风险 [S9]。

### 6.3 人工反馈与结论状态

- 问题级反馈：19 个问题全部未反馈；准确 0、不准确 0、无法判断 0 [S3][S6][S7]。
- 结果级反馈：汇总为未反馈 6；准确 0、不准确 0、无法判断 0 [S1][S2][S3][S4][S5][S6][S7][S8][S9]。
- 因此，本报告中的“确认问题”仅指代码结构层面有证据支持，**不是人工确认或运行时确认的缺陷**；潜在风险与待验证事项不能写成已发生故障。
- 来源卡片中 S3、S6、S7 的结果级“未反馈 0”与汇总“未反馈 6”的计数口径差异，材料未解释；本报告保留该差异，不将其解释为已有正向反馈 [S3][S6][S7]。