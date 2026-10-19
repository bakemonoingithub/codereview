# refactor/review 成果盘点

日期：2026-10-19（Asia/Taipei）。用途：为 `refactor/agent` 的逐项确认提供事实与取舍依据。

**本盘点不是最终实施范围，也不是迁移任务清单。** 「值得评估」「可简化」「可作为基础」均为助手判断。除负责人已明确认可的业务方向外，条目均待逐项确认；已认可方向也不等于批准原样复用组件或提交。

业务方向以[专项审查与 Agent 分析业务契约](business-contract.md)为准。本文区分 `dev` 已有能力、`refactor/review` 增量、已知限制和新需求，避免把既有能力重复列为重构成果。

## 1. 盘点范围与证据口径

| 对象 | 固定版本或说明 |
| --- | --- |
| 新分支基线 | 本地 `dev`：`d61b499ddbdede4b5f179091b9322b9437d8979a` |
| 成果来源 | `refactor/review`：`8f20d6f24b9c22d3309ac1cb7bda1caf20e1bce0` |
| 共同祖先 | `d114f622674c5de97196f8eab37c6933ee4da1cb` |
| dev 独有增量 | 两次交付文档提交 `86684b4`、`d61b499`；创建新分支时保留 |
| 比较方法 | 核对两端文件差异、review 独有提交、关键实现及交付记录；按能力归类，不按提交数量判断价值 |
| 工作区例外 | 盘点时源工作区只有 `frontend/components.d.ts` 未提交；不计入来源成果，也不迁入新分支 |
| 验证范围 | 本轮为源码及文档静态盘点，未重新运行应用、模型调用、数据库迁移或业务测试 |

源码与测试路径均相对于仓库根，默认指成果来源固定版本。来源文件尚未迁入新分支时，可在任一工作树用 `git show 8f20d6f:<路径>` 阅读；基线用 `git show d61b499:<路径>`。下列证据编号不是开发任务编号。

来源 STATE 中有历史「未提交」和运行记录；本盘点以实际 Git 状态和固定提交为准。历史测试数字是对应交付记录的事实，不是本轮复测结果，也不自动证明新分支可直接采用。

## 2. 前端成果

### UI-01 审查发起弹窗与共用表单

- 基线：`dev` 的 `ProjectDetail.vue` 把分支、策略、合并开关、文件范围和结果放在页面内。
- 增量：`ReviewTriggerDialog.vue` 把发起动作放入弹窗，项目与全局入口共用；支持项目、分支、策略、文件或提交选择、草稿、防重复提交和状态重置。预算折叠，但仍有三模式和较多技术字段。
- 业务价值：减少页面常驻配置，集中完成一次发起操作。**负责人已明确认可表单化方向**。
- 复用边界：保留交互方向不等于照搬旧字段；三模式、预算默认值、历史配置和准入检查依赖需重新梳理。当前前端默认输出预算为 1024，后端未指定时通常取不超过 2048，不能将两者记为统一默认。
- 待确认：专项与 Agent 是否共用表单、最小必填项、项目及全局入口关系。证据：E-01。相关提交：`d2f145c`、`383084e`、`9f46efc`。

### UI-02 代码浏览与审查中心

- 基线：已有项目页代码树、提交、文件预览、审查记录及报告。
- 增量：`ProjectCodeBrowser.vue` 与 `ReviewCenter.vue` 分别承担浏览和跨项目记录查询；新旧记录通过统一入口进入详情。
- 业务价值：浏览、发起和结果阅读职责更清楚，跨项目找记录更方便。
- 复用边界：`ReviewEntry`、capabilities 和隐藏验收路由属于双轨切换机制；来源提交未完成默认入口切换。新分支是否需要这些过渡层待定。
- 待确认：菜单结构、保留哪些列表及筛选，不把双轨机制直接迁入。证据：E-02。提交：`7f2ff37`、`383084e`、`9f46efc`。

### UI-03 统一详情与专项结果呈现

- 基线：已有问题列表、diff、耦合图、设计模式结果及旧记录查看。
- 增量：按 reviewId 进入统一详情，关联 runId 后共享结果与诊断；发起后可立即定位运行中的任务。五类结果继续保留各自展示，完善部分失败、历史缺字段、图表重布局与固定版本 diff。
- 业务价值：任务位置稳定，用户能够查看正在执行或部分完成的结果。
- 复用边界：概览三卡、覆盖、核验、提交意图和多诊断页签依赖旧工作流数据，不能整页视为已获认可的新布局。来源记录仍有视觉与真实使用验收余项。
- 待确认：专项详情保留的最小信息、Agent 对话与最终报告如何呈现。证据：E-03。提交：`685dbc4`、`875e66f`、`5d71a55`、`56667a1`。

### UI-04 选择范围、异步状态与返回现场

- 增量：文件和提交选择器按项目、分支及提交隔离状态；迟到响应不覆盖新选择；加载更多失败保留页次；返回详情来源时恢复筛选、分页、页签和选择。
- 业务价值：减少选错范围、切换项目后串数据及返回后重复操作。
- 复用边界：这些防错能力适合按新页面需要吸收，部分轮询、分页和错误显示在 `dev` 已存在，不能全部归为新增。尚未提供任意行范围作为发起目标的完整产品流程。
- 待确认：新范围类型和状态保留规则。证据：E-04。提交：`238ca6c`、`0069021`、`d679e2b`、`383084e`。

### UI-05 代码、证据及长内容阅读

- 增量：冻结版本的文件及片段阅读、OLD/NEW 侧、行号、全文或 JSON 阅读与复制、报告问题引用跳转；避免缺数据被解释为无问题。
- 业务价值：用户可以核查结论的来源，适合专项结果和 Agent 回答共同使用。
- 复用边界：保留易读的路径和内容即可讨论价值；证据 ID、hash、完整 JSON 和内部阶段是否默认展示，应按新页面重新取舍。净化显示等能力部分在 `dev` 已有。
- 待确认：默认展示与展开详情、引用到行的方式。证据：E-05。提交：`3ca7df7`、`685dbc4`、`875e66f`、`58ce5ac`。

### UI-06 人工判定与草稿

- 基线：`dev` 已有「误报／采纳」标记。
- 增量：按稳定 findingId 保存真值、处置、理由、修订历史；草稿保留、并发冲突提示及指标刷新。
- 业务价值：问题判断不会与「是否采纳建议」混为一谈，反馈目标更稳定。
- 复用边界：新方向明确要求问题准确度反馈与评论，但旧 truth/action 双维表单和修订流程是否全部保留待定。已有 `reason` 是判定理由，不能据此宣称独立评论能力已完成。
- 待确认：反馈标签、评论编辑及历史、复杂度适当的冲突处理。报告级反馈尚需补齐，见 N-03。证据：E-06。提交：`807fce9`。

### UI-07 报告生成及阅读

- 基线：已有记录多选、名称、模型、提示词、生成前确认、历史阅读及 Markdown 下载。
- 增量：冻结范围、来源及质量提示、问题引用、旧新结果输入兼容和下载事实保留。
- 当前限制：`ReportPanel.vue` 仍把候选记录和生成配置平铺在页面；`tipOpen` 是生成确认弹窗，`reportOpen` 是阅读弹窗。**报告生成配置尚未迁入独立弹窗表单**。
- 负责人新方向：报告生成也应放入表单；属于待实现的新需求，不能写作 review 已交付成果。
- 待确认：选记录是否在表单内、具体字段、预览和反馈入口、旧报告数据如何阅读。证据：E-07。提交：`807fce9`、`9f46efc`、`58ce5ac`。

### UI-08 帮助文案与诊断布局

- 增量：问号提示集中于 `workflowHelp.ts`，补充范围、质量与失败说明；P20 按主文件、背景和核验补取展示材料获取及使用情况。
- 业务价值：统一解释，降低同一概念在不同页面的歧义。
- 复用边界：帮助文案中的三模式和核验术语需随业务重写；大量概览、诊断和技术状态不宜自动迁入专项首页。截图自查不等于负责人视觉认可。
- 待确认：用户实际需要哪些说明与错误信息。证据：E-08。提交：`8cc3608`、`5d71a55`、`a712670`。

### UI-09 项目编辑与模型配置

- 增量：项目名称和令牌编辑复用弹窗，仓库地址锁定；模型增加能力档案及探针反馈。
- 业务价值：项目日常维护更方便，模型参数可显式管理。
- 复用边界：项目编辑与新分析路线相对独立；模型能力表单的字段、探针和复杂提示则需结合新调用方式简化。新令牌现场验证和视觉验收不能由历史单元测试替代。
- 待确认：项目编辑是否纳入首批，模型配置保留哪些用户可见参数。证据：E-09。提交：`10d6d95`、`c16cf2a`。

## 3. 后端成果

### B-01 固定版本、配置快照及失败语义

- 增量：明确冻结仓库、SHA、范围、模型及提示词版本；无法解析必需输入时拒绝，避免悄悄回退；失败、部分完成和无候选分别处理。
- 业务价值：结果可追溯，减少把失败当成「无问题」的情况。
- 取舍：可评估保留这一能力，具体快照字段和存储层待定。旧策略快照与新快照的凭据处理不同，不能认为所有历史路径已完成脱敏。
- 待确认：新专项与 Agent 共同需要的最小输入快照。证据：E-10。提交：`f625983`、`0c49805`、`c0c38bc`。

### B-02 目标覆盖、结构解析与 diff 修复

- 增量：旧 Chunker 补充字段、初始化块和类型上下文，改善漏行；diff 完善跨区域、删除、旧新侧及缺失 previousPath 处理；JavaParser 改为每次调用独立配置 Java 17，记录解析失败。
- 业务价值：减少漏审和结构分析静默缺失。
- 取舍：基础修复值得逐项评估。`Chunker` 的语义分块改进与新工作流 `CandidateDiscovery` 的字符分块是两条实现，不能把前者描述成新路线已经具备方法边界分块。
- 待确认：新路线采用的解析和分块方式，以及 diff 产品归属。证据：E-11。提交：`f625983`、`e9fd8c5`、`4a83226`。

### B-03 提示词、完整请求预算与两道分块限制

- 增量：`PromptAssembler` 统一系统协议、阶段任务、规则、用户重点、带位置的证据和 schema；公共调用保留 finish_reason、usage，校验 JSON、字段及引用。
- 当前事实：`CandidateDiscovery` 初始上限为 `min(chunkMaxChars, max(1, contextWindowTokens / 8))`，默认配置字符上限为 140000；随后检查完整请求是否符合「窗口减输出预算减安全余量」。不足时移除可选材料或减半片段，优先换行，不按方法边界。完整输入以 UTF-8 字节数保守估算；先切开的片段不再因容量充足而合并。
- 业务价值：基本结构化输出与全请求容量管理可作为候选公共能力。
- 取舍：`/ 8` 没有找到明确推导或实测依据；不能直接作为新要求。旧 schema 中的候选核验字段、完整证据规则、协议修复轮数也不自动继承。
- 待确认：基础指令、输出协议、估算方式、整文件优先及语义分块。证据：E-12。提交：`a2ccc6d`、`c0c38bc`、`5fba5bf`、`4920108`。

### B-04 有界调用、超时与执行稳定性

- 增量：统一调用入口、输出预留、总预算、有限重试、协议修复、截止时间与并发许可；异步错误传回持久化边界。
- 业务价值：模型或宿主异常时可结束并说明原因，可为 Agent 循环提供执行基础。
- 当前限制：新文件审查在 `WorkflowPilotService` 中按单元顺序调用并等待结果；底层线程池不代表同一审查的单元已并行。不同预算及异常类型已形成较多机制。
- 待确认：必要的资源限制与用户可见配置、是否并行、如何精简错误展示。证据：E-13。提交：`a2ccc6d`、`19e06b0`、`f6a92d1`。

### B-05 材料缓存、固定版本读取与 HTTP 重试

- 基线：`dev` 已有 Git 宿主适配、文件获取及材料缓存。
- 增量：缓存增加宿主／仓库／版本边界、容量控制；P20 共享固定版本读取，区分逻辑请求与真实 HTTP 尝试；恢复保留消耗，暂态失败不固化为永久缺失，目录不完整不误报文件不存在。
- 业务价值：减少重复读取，改善错误判断，可服务专项常规取材及 Agent 工具。
- 取舍：缓存和读取修复是可拆取候选；当前额度账本、发现／核验份额及恢复机制相互关联，不能直接只复制一个缓存类。
- 待确认：最小共享读取服务、缓存失效及资源边界。证据：E-14。提交：`f625983`、`a712670`、`d4cdd76`。

### B-06 相关材料规划与上下文工具

- 增量：关联接口、公共返回契约、调用候选、测试、SQL、配置和规则；支持读取范围与多类受限检索，保存出处、版本和缺口。
- 业务价值：常规材料增强和 Agent 取材均有可利用基础。
- 取舍：专项按新契约限制增强深度；Agent 初版只明确仓库树、`glob`、获取内容，有缓存再讨论内容检索。现有 `ContextRequest.Tool` 包含 READ_FILE、READ_METHOD、FIND_SYMBOLS 等，但没有对外开放给通用 Agent 的 tree/glob 工具合同，不能标为现成 Agent 工具集。
- 待确认：哪些规划规则留在专项、哪些读服务可用于 Agent；不直接把既有扫描逻辑全部开放。证据：E-15。提交：`ab285fc`、`71481e7`、`a712670`。

### B-07 自动候选核验与提交意图

- 增量：发现后逐候选验证，补取支持／反证，记录 CONFIRMED、REJECTED、NEEDS_HUMAN、UNVERIFIED；diff 另有提交级意图聚合。
- 当前边界：流程围绕候选问题组织，有固定协议和有限补取；属于专用核验机制，不等于面向任意用户诉求的通用 Agent。
- 新方向：负责人已决定专项不保留强制候选核验，复杂调查转 Agent。候选核验的字段、界面与默认循环不列为直接迁移范围。
- 待确认：证据读取、停止与过程记录中哪些基础部件仍有价值；提交意图如何归入新业务。证据：E-16。提交：`960437e`、`83f11d9`、`acf60e6`。

### B-08 人工判断、评论基础与统计

- 增量：稳定 findingId、真值／处置区分、理由和修订、冲突防护；精确率仅计算已判真假的已发布 V2 AI 问题，无分母不显示成功比例。
- 业务价值：可支撑问题级反馈，避免 `dev` 旧公式把未标记问题计入正确数量。
- 当前限制：新统计只覆盖分析器 1/5 的 V2 findings；设计模式和耦合报告的准确度反馈及评论没有对应完整实现。判定理由也不等于通用评论模块。
- 待确认：反馈对象、标签、评论模型和统计分母；旧双维表单、修订粒度是否简化。证据：E-06、E-17。提交：`807fce9`。

### B-09 报告输入冻结与事实组成

- 基线：已有报告生成、记录选择、耗时及 Markdown 下载。
- 增量：冻结输入与问题清单，按仓库／版本分章，由程序组成事实与指标；模型补充受引用和格式限制；模型失败时保留确定性正文，旧输入保留未知标记。
- 业务价值：报告不随源数据变化，缺失数据不被自动补造。
- 取舍：冻结范围、来源引用等可评估保留；当前任何模型补充即标 DEGRADED、额外数字限制及较复杂质量文案需讨论，不视为新业务既定要求。
- 待确认：专项报告、综合报告与 Agent 回答的关系，以及最小生成合同。证据：E-07、E-18。提交：`807fce9`、`58ce5ac`。

### B-10 持久化运行、取消与恢复

- 增量：运行／阶段／调用记录，租约、版本检查、取消、预算原子预留及恢复，旧执行器与工作流通过快照分派。
- 业务价值：长任务可追踪，故障后能识别完成范围；Agent 可能需要其中部分能力。
- 取舍：依赖 V8–V12 表结构、BuildIdentity、双状态及调度；当前恢复拒绝跨构建且沿用原期限。整套迁入会同时带入旧三模式与双轨复杂度，采用粒度待确认。
- 待确认：新任务是否需要阶段恢复，取消、历史和失败重试的最小用户承诺。证据：E-19。提交：`0c49805`、`f6a92d1`、`acf60e6`。

### B-11 项目维护、模型档案及就绪检查

- 增量：项目更新限定名称和令牌，新令牌验证后保存；模型声明窗口、输出等能力并保存探针；就绪检查与迁移状态可查询。
- 业务价值：配置错误更容易发现，项目维护更直接。
- 取舍：项目编辑较独立；模型档案只支持 ESTIMATED 的现有限制、严格探针准入及就绪接口依赖需要结合新模型调用方案评估。
- 待确认：模型配置复杂度、探针策略及项目编辑范围。证据：E-09、E-20。提交：`10d6d95`、`c16cf2a`、`e36eaaf`。

## 4. 工程、数据与验证成果

### X-01 测试与隔离构建

来源增加覆盖、异常、迟到响应、草稿冲突、版本引用、缓存和 HTTP 边界等回归；`scripts/backend-check.ps1` 将后端检查输出隔离，避免覆盖运行 classes。可按最终采用能力选择相关测试，不将全部旧模式断言迁为新验收要求。

P20 交付记录报告本地后端完整 658 项，其中 623 通过、35 条件跳过；前端历史完整 584 项通过。它们属于来源版本的交付证据，本次未复跑；真实模型效果、生产数据兼容和 PC 用户视觉仍有待验收项。证据：E-21。

### X-02 数据库与历史兼容

`dev` 最高脚本 V7，来源最高 V12；新增工作流、问题、证据、恢复和反馈字段。来源还修复过已执行 V12 的 checksum，并增加迁移校验回归。

从 `dev` 建新分支不会回退或隔离既有数据库。新分支后续开发使用什么数据库、是否保留 review 数据、旧报告如何阅读，须单独确认。当前没有迁入 V8–V12，也没有连接或修改数据库。证据：E-22。

### X-03 文档、规范和验收流程

来源增加 AGENTS、STATE、ROADMAP、项目级 skills、文档检查和隔离脚本，形成明确接续入口；也包含大量历史交付和三模式验收条目。

可评估保留轻量的需求确认、状态接续与文档检查，但不把旧阶段树、旧 active、模式效果对照或全部历史文档自动复制为新路线计划。`dev` 上两次交付文档增量保留。证据：E-23。

### X-04 三模式效果对照

来源 E1/E2/E3 旨在比较基础发现、上下文增强及候选核验的收益、误报与资源消耗。负责人本次明确不保留该对照机制，改为问题级／报告级准确度反馈和评论。

该决定不取消所采用功能的正确性测试；也不把旧对照工具改名后重新加入实施范围。外部原始验收指标与新反馈口径的关系尚待确认。证据：E-24。

## 5. 新方向中的缺口

| 编号 | 已提出的方向 | 来源现状 | 后续确认内容 |
| --- | --- | --- | --- |
| N-01 | 通用 Agent 分析 | 只有围绕候选的专用补取／核验，缺任意诉求的完整 Agent 流程 | 任务状态、工具协议、停止与交互、结果呈现 |
| N-02 | 报告生成进入独立表单 | 当前为页内配置加生成确认弹窗 | 选材、字段、入口和草稿规则 |
| N-03 | 模式／耦合报告准确度反馈与评论 | 现有正式人工判定主要针对代码／diff 的 findings | 报告对象、标签、评论及统计 |
| N-04 | 专项保持简单、增强材料成为常规能力 | 当前按三模式分派，并绑定核验及诊断元数据 | 保留哪些材料增强，退出哪些模式和展示 |
| N-05 | 多粒度目标与相关文件分析 | 多选文件已有；新工作流拒绝 mergeFiles，旧合并超限报错；没有任意行目标的完整新流程 | 单行目标、联合审查是否纳入专项、与 Agent 的边界 |

上述是能力差距说明，不是已批准的开发阶段或任务顺序。

## 6. 源码与交付证据索引

同一编号内的路径共同支持对应事实；文件默认取第 1 节固定来源提交。测试文件证明存在相关回归设计，不代表本轮执行通过。

| 编号 | 主要源码、测试或记录 |
| --- | --- |
| E-01 | `frontend/src/components/ReviewTriggerDialog.vue`；`frontend/src/components/__tests__/ReviewTriggerDialog.spec.ts`；`frontend/docs/review-unification/P18.4-entries.md` |
| E-02 | `frontend/src/views/ProjectCodeBrowser.vue`、`ReviewCenter.vue`、`ReviewEntry.vue`；`frontend/src/router/index.ts`；`frontend/docs/review-unification/P18.5-controls.md` |
| E-03 | `frontend/src/views/ReviewDetail.vue`；`frontend/src/components/workflow/WorkflowReviewDetail.vue`；`frontend/docs/review-unification/P18.3-detail.md`、`P18.7-layout-02.md` |
| E-04 | `frontend/src/components/workflow/WorkflowScopePicker.vue`、`WorkflowCommitPicker.vue`；`frontend/src/utils/reviewNavigation.ts`；`frontend/src/composables/useReviewDetail.ts` |
| E-05 | `frontend/src/components/workflow/FrozenFileReader.vue`、`FrozenEvidenceReader.vue`、`WorkflowTextReader.vue`、`WorkflowJsonViewer.vue`；`frontend/src/components/MarkdownView.vue` |
| E-06 | `frontend/src/components/FindingAssessmentPanel.vue`；`frontend/src/components/__tests__/FindingAssessmentPanel.spec.ts`；`backend/src/main/java/com/codereview/dto/FindingAssessmentReq.java` |
| E-07 | `frontend/src/components/ReportPanel.vue`、`ReportSnapshotDetails.vue`；`frontend/src/components/__tests__/ReportPanelTrusted.spec.ts`；`frontend/docs/review-unification/P18.5-controls.md` |
| E-08 | `frontend/src/utils/workflowHelp.ts`、`retrievalPresentation.ts`；`frontend/src/components/workflow/WorkflowRunOverview.vue`、`WorkflowRetrievalSummary.vue` |
| E-09 | `frontend/src/views/ProjectList.vue`、`ModelConfigList.vue`；`backend/src/main/java/com/codereview/service/ProjectService.java`、`ModelConfigService.java`；`docs/work/project-edit/impact.md` |
| E-10 | `backend/src/main/java/com/codereview/service/ReviewService.java`、`ReviewExecutor.java`；`backend/src/main/java/com/codereview/workflow/WorkflowSnapshotService.java`；`backend/docs/workflow-p2/P2-delivery.md` |
| E-11 | `backend/src/main/java/com/codereview/chunk/Chunker.java`；`backend/src/main/java/com/codereview/diff/DiffUnitBuilder.java`；`backend/src/main/java/com/codereview/material/StructureExtractor.java`；`backend/docs/workflow-runtime-fixes/README.md` |
| E-12 | `backend/src/main/java/com/codereview/workflow/PromptAssembler.java`、`CandidateDiscovery.java`、`ContextBudgeter.java`、`StageResultValidator.java`；`backend/src/main/java/com/codereview/llm/LlmClient.java` |
| E-13 | `backend/src/main/java/com/codereview/workflow/LlmInvocationService.java`、`RunBudget.java`、`WorkflowPilotService.java`；`backend/docs/workflow-runtime-fixes/README.md` |
| E-14 | `backend/src/main/java/com/codereview/material/MaterialCache.java`；`backend/src/main/java/com/codereview/git/GitReadScope.java`；`backend/src/main/java/com/codereview/context/RetrievalLedger.java`；`backend/docs/workflow-p20/README.md` |
| E-15 | `backend/src/main/java/com/codereview/context/ContextPlanner.java`、`ContextService.java`、`ContextRequest.java`；`backend/docs/workflow-p4/README.md` |
| E-16 | `backend/src/main/java/com/codereview/finding/VerificationPipeline.java`、`FindingVerifier.java`、`FindingVerificationService.java`；`backend/src/main/java/com/codereview/workflow/CommitIntentService.java`；`backend/docs/workflow-p5/P5-3-delivery.md` |
| E-17 | `backend/src/main/java/com/codereview/service/FindingAssessmentService.java`、`IssueMarkService.java`；`backend/src/main/java/com/codereview/finding/FindingRepository.java`；`backend/docs/workflow-p6/README.md` |
| E-18 | `backend/src/main/java/com/codereview/service/ReportSnapshotService.java`、`TrustedReportComposer.java`、`ReportExecutor.java`；`backend/src/test/java/com/codereview/service/ReportSnapshotServiceTest.java` |
| E-19 | `backend/src/main/java/com/codereview/workflow/WorkflowDispatch.java`、`WorkflowLeases.java`、`WorkflowRepository.java`、`BuildIdentity.java`；`backend/src/main/java/com/codereview/analyzer/WorkflowAnalyzerAdapter.java` |
| E-20 | `backend/src/main/java/com/codereview/workflow/ModelProfiles.java`；`backend/src/main/java/com/codereview/controller/ReadinessController.java`；`backend/docs/workflow-p7/README.md` |
| E-21 | `scripts/backend-check.ps1`；`backend/src/test/java/com/codereview/git/P20RetrievalIntegrationTest.java`；`backend/docs/workflow-p20/README.md`；`docs/STATE.md` |
| E-22 | `backend/src/main/resources/db/migration/`；`backend/src/test/java/com/codereview/db/AppliedMigrationChecksumTest.java`；`backend/docs/workflow-p6/README.md` |
| E-23 | `AGENTS.md`；`.agents/skills/`；`scripts/docs-lint.ps1`；`docs/STATE.md`、`docs/ROADMAP.md`；基线 `docs/delivery/dev-20261013/` |
| E-24 | `backend/docs/workflow-p7/README.md`；`backend/tools/compare_context_runs.py`；`docs/archive/legacy/design/代码审查系统-统一工作流与多阶段审查改造方案.md` |

## 7. 逐项确认与后续使用

负责人可直接按 UI、B、X、N 编号提出保留、简化、舍弃或延后的决定。每项确认应记录：要解决的用户问题、保留的业务行为、排除的复杂度、需要配套的前后端或数据改动，以及验收方式。没有确认的条目继续保持待定。

跨层依赖需要一起看：发起表单依赖范围和发起接口；问题反馈依赖稳定身份及持久化；报告引用依赖冻结输入；Agent 工具依赖版本读取与缓存；恢复依赖运行存储和数据库。可以选择能力，不必选择整批提交；最终采用方式需要按依赖重新设计。

本次 `refactor/agent` 只包含从 `dev` 创建分支及两份文档。下一步是逐项确认，再形成新的 ROADMAP；本盘点没有排期、完成承诺，也没有把来源测试成绩当作新路线验收通过。
