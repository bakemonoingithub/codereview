# P30.1 复用与功能差异核对

状态：P30.1 执行中（2026-10-20，Asia/Taipei）。依据 [ROADMAP](../../../ROADMAP.md) P30.1、[业务契约](../business-contract.md) D-58、D-59、D-62、D-69、D-71、D-84、D-85、[P29 共用设计](../p29/shared-design.md)第 4、7、8 节及[成果盘点](../review-inventory.md)。本文确定 P30.2 起的实现起点，不新增业务范围。

## 1. 核对范围与方法

- 工程基线：`../code` 工作树 `refactor/review`，HEAD `160b806`。它与固定来源 `8f20d6f` 的差异只有 4 个本地启停脚本提交（`scripts/start-dev.ps1`、`scripts/stop-dev.ps1` 及 README、ROADMAP、STATE 的少量行），业务代码完全一致；因此本文的现状事实对两者同时成立，不需要另取 `git show` 版本比对业务代码。
- 核对层次：页面（路由与 Vue 组件）、接口（Controller、DTO、统一响应）、数据（Flyway V1–V12 与实体）。每项给出四类结论之一：直接保留、改造、新建、已确认舍弃。
- 方法：只读静态核对源码与文档。未启动服务、未连接或迁移数据库、未调用模型、未执行前端构建；凡涉及时序、容量、并发与真实仓库规模的事实不写入已验证结论。
- 原型只作界面与交互基线。原型缺省控件、演示数据和简化操作不构成删除既有能力的依据；ROADMAP 第 1 节列出的搜索、分页、版本对比、错误重试及防误操作不得因原型未画而消失。

## 2. 结论摘要

| 结论 | 数量 | 说明 |
| --- | --- | --- |
| 直接保留 | 11 | 组件职责、防误操作与阅读基础，最多适配数据来源 |
| 改造 | 14 | 保留职责与用户可感知行为，按新任务模型重组接口、数据或交互 |
| 新建 | 10 | 新路线独有、来源仅有近似基础的能力 |
| 已确认舍弃 | 10 | 契约已明确不迁入的机制与界面 |

编号 K/M/N/D 分别对应四类，后文逐项展开。合计 45 项核对结论。

## 3. 现状事实

### 3.1 HTTP 接口

旧接口统一前缀 `/api`，响应恒为 HTTP 200 的 `{code,message,data,traceId}` 信封；错误码为四位数字（0 成功、1xxx 通用、2xxx 提示词、3xxx 模型、4xxx 策略、5xxx 项目、6xxx 审查/工作流、7xxx 报告）。列表用 `pageNum/pageSize` 返回 MyBatis `Page{records,total}`。

| 旧接口 | 现状职责 | 处理 |
| --- | --- | --- |
| `POST/GET /projects`、`GET/PUT /projects/{id}`、`DELETE /projects/{id}` | 项目新建、列表、详情、名称与令牌更新、逻辑删除 | M-01、M-14 |
| `GET /projects/{id}/delete-impact` | 删除前统计记录与报告数并提示是否被进行中审查阻止 | K-06 |
| `GET /projects/{id}/tree|branches|commits|commits/page|commits/{sha}|commits/{sha}/patch|changed-files|file` | 固定/分支版本读取、提交分页、单文件 patch、文件正文（默认 1000 行、`full` 放宽、2MB 上限） | M-02 |
| `POST /projects/{id}/reviews/trigger` | 同步创建审查记录，接受分支、策略、文件范围、`mergeFiles`、`commitSha`、`workflowPilot`、预算与模式 | M-07 |
| `POST /reviews/{id}/retry`、`POST /workflow/runs/{id}/resume` | 原任务重试与恢复 | D-04 |
| `GET /reviews`、`GET /projects/{id}/reviews`、`GET /reviews/{id}` | 跨项目/项目内列表、详情 | M-08、M-09 |
| `GET /reviews/capabilities` | 双轨准入开关（统一切换、旧入口是否允许创建/重试） | D-01 |
| `GET /workflow/runs*`（列表、详情、阶段、调用、上下文、输出、证据、取消、恢复） | 工作流诊断页与运行资源阅读 | M-09、N-03、D-07 |
| `GET/POST/PUT/DELETE /models`、`POST /models/{id}/verify|probe` | 模型列表、增删改、连通验证、能力探针 | M-04 |
| `GET/POST/GET/PUT/DELETE /prompts`、`PUT /prompts/{id}/content`、`GET /prompts/{id}/versions/diff` | 提示词列表与详情、正文更新生成新版本、版本对比 | M-05 |
| `GET/POST/PUT/DELETE /strategies` | 策略列表与增删改（含分析器类型、模型、提示词、阈值参数） | M-06 |
| `POST/GET /projects/{id}/reports`、`GET /reports/{id}` | 项目内报告生成、列表、阅读 | M-11 |
| `POST/GET /findings/{id}/assessments`、`GET /workflow/runs/{id}/assessments/latest`、`GET /projects/{id}/review-metrics` | 问题级人工判定与指标 | M-12、D-05 |
| `GET/POST/DELETE /reviews/{id}/marks`、`GET /projects/{id}/accuracy` | 旧误报/采纳标记与双维准确率 | D-05 |
| `GET /api/health/ready` | 数据库与迁移结构就绪检查 | M-13 |

### 3.2 数据

旧库 Flyway 链为 V1–V12，库名与账号由旧配置决定。实体字段反映旧模型：`review_record(id,project_id,strategy_id,strategy_snapshot_json,branch,commit_sha,scope_json,status,progress,started_at,finished_at,result_json,error_message,…)` 以单行承载整次审查结果；`report` 带 `published_run_ids_json`、`quality`、`schema_version` 等旧报告字段；`model_config` 只有 `capability_profile_json`、`capability_probe_json`、`output_parameter`，没有窗口、最大输出、tokenizer、凭据引用和 `revision`；`review_strategy` 只有 `analyzer_type` 与 `params_json`，模型与提示词放在 `params_json` 内、没有外键与引用校验，也没有专项类型；`prompt` 没有用途与 `revision`。

旧表清单：`sys_user`、`project`、`prompt`、`prompt_version`、`model_config`、`review_strategy`、`review_record`、`report`、`report_record`、`issue_mark`、`review_finding`、`finding_assessment`、`workflow_run`、`workflow_stage`、`workflow_call`、`workflow_context`。

### 3.3 前端页面与路由

当前菜单由路由推导：项目、模型、策略、提示词、工作流试点；项目详情、审查详情、审查中心、代码浏览、运行诊断均为 `hideMenu`。`ReviewEntry.vue`（25 行）与 `ReviewDetail.vue`（47 行）是双轨包装，实际内容在 `ProjectDetail.vue`（1157 行）与 `workflow/WorkflowReviewDetail.vue`。`ProjectDetail` 把分支、策略、文件范围、发起、结果、报告与人工判定放在一页；`ReviewCenter` 有项目/状态筛选、`pageNum` 分页、2 秒轮询和请求代次防串用。

### 3.4 ID、分页与状态

旧接口 JSON 暴露数据库自增大整数 ID；前端部分类型已声明为 `string`，与运行时 number 不一致。状态是 `0 排队 / 1 执行中 / 2 成功 / 3 失败 / 4 部分成功`，没有 `CANCELLED`、`INTERRUPTED`，也没有取消请求时间与终态条件更新。分页只有页码与总数，没有稳定游标。

## 4. 逐页与逐组件的结论

| 编号 | 对象 | 现状能力 | 结论 |
| --- | --- | --- | --- |
| K-01 | `MarkdownView.vue` | Markdown 渲染与净化、代码文本转义、受控链接 | 直接保留，报告与 Agent 结果继续使用 |
| K-02 | `DiffViewer.vue` | 统一 diff 行渲染，被提示词对比与审查结果复用 | 直接保留 |
| K-03 | `ListPageLayout`、`LoadErrorAlert`、`EmptyGuide` | 列表骨架、失败提示与重试、空态引导 | 直接保留 |
| K-04 | 提示词版本选择与对比 | 版本列表、双版本选择、`DiffViewer` 展示差异 | 直接保留，作为正文唯一编辑入口的配套 |
| K-05 | 项目编辑约束 | 仓库地址锁定、令牌留空保留、新值失败不覆盖 | 直接保留，接口改为 v2 |
| K-06 | 删除影响预览与二次确认 | 先查影响再确认，进行中审查阻止删除 | 直接保留（ROADMAP 明确防误操作不得消失） |
| K-07 | 列表请求代次与迟到响应防护 | `generation` 计数丢弃过期响应、失败保留上次数据并说明页次 | 直接保留，改为游标分页 |
| K-08 | 固定读取阅读组件 | `FrozenFileReader`、`WorkflowTextReader`、`WorkflowJsonViewer` 的行号、截断、加载全文、复制、JSON 阅读 | 直接保留，数据源换成材料快照接口 |
| K-09 | 帮助文案机制 | `workflowHelp.ts` 集中文案、按字段解释 | 保留机制，文案按新业务重写 |
| K-10 | 模型验证交互 | 验证防连点、失败透出原因、成功后刷新状态 | 直接保留，增加配置修订失效 |
| K-11 | 变更文件树基础 | `ChangedFileTree` 的树节点、变更状态、展开与路径筛选 | 直接保留，供范围弹窗与代码浏览共用 |
| M-01 | 项目列表页 | 列表、新建/编辑弹窗、删除 | 改造：增加搜索与令牌配置状态列，入口改为代码浏览与任务记录 |
| M-02 | 仓库读取接口 | 树、分支、提交分页、patch、文件正文与全文 | 改造：收敛为 `RepositoryReadService`，v2 显式携带固定 SHA 与完整性 |
| M-03 | 代码浏览页 | 树、分支/提交切换、文件预览、勾选、发起入口 | 改造：保留浏览与选择，移出内联表单与结果，接入固定版本与范围回填 |
| M-04 | 模型管理页与接口 | 列表、增删改、验证、能力探针、输出参数映射 | 改造：改为地址、模型名、凭据引用、窗口、最大输出、tokenizer、协议类型；验证记录按配置修订失效 |
| M-05 | 提示词管理页与接口 | 列表、搜索、标签、正文编辑、版本对比 | 改造：增加用途、`revision`，正文只经版本写入 |
| M-06 | 策略管理页与接口 | 列表、搜索、分析器类型、关联模型与提示词、外部 API 配置 | 改造：改为专项类型加模型与可选提示词、删除被引用返回 409；去掉阈值与方法窗口参数 |
| M-07 | 发起动作 | 弹窗发起、防重复提交、错误保留输入、历史配置复制 | 改造：接入 `POST /review-tasks`，预算面板改为固定服务端策略，保留防重与输入保护 |
| M-08 | 审查中心 | 跨项目列表、项目/状态筛选、页码分页、轮询、迟到响应防护 | 改造：改为任务列表与游标分页，去掉模式/执行类型列 |
| M-09 | 统一详情 | 五类结果、证据引用、报告与判定面板、诊断页签 | 改造：改为任务 → 目标文件 → 结果/材料/实际调用三页签，诊断并入材料与调用 |
| M-10 | 材料与固定版本阅读 | 冻结文件/证据阅读、代码弹窗、diff 与前后原文 | 改造：材料列表灰色小字显示 SHA 与行号，统一「查看代码」弹窗 |
| M-11 | 报告 | 页内选材与生成配置、阅读弹窗、Markdown 下载 | 改造：独立表单、仅完整结果可选、冻结来源阅读、下载等于保存正文 |
| M-12 | 人工反馈 | 问题级判定与理由、指标条 | 改造（P32）：问题级与结果级评价、独立评论、`revision` 防覆盖；本阶段只预留接口位置 |
| M-13 | 就绪检查 | 数据库与迁移可用性 | 改造：增加新库、配置身份与 schema 标记检查 |
| M-14 | 项目删除 | 逻辑删除并级联记录与报告 | 改造：v2 保留影响预览与阻止条件 |
| N-01 | 范围选择弹窗 | 来源只有页内多选与单提交选择；无「主表单 → 弹窗 → 预览」三层流程 | 新建：本地勾选草稿、确认回填、取消不变、来源变化清理 |
| N-02 | 逐文件说明与拆分开关 | 来源只有任务级文本与合并开关 | 新建：一文件一说明、默认关闭的拆分开关、移除前提示 |
| N-03 | 任务创建、取消与终态保护 | 创建同步返回，仅状态数字，无取消半程与终态条件更新 | 新建：202 + 幂等键 + `expectedRevision` 取消 |
| N-04 | 实际调用阅读 | 来源按阶段/单元分页读调用，未按任务-文件-片段组织 | 新建：调用列表与请求/响应全文，正文来自落盘而不是重新组装 |
| N-05 | 材料与调用关联接口 | 无独立材料实体，材料信息混在上下文与快照 | 新建：`material`、`call_material`、按 id 与范围读取 |
| N-06 | 幂等创建 | 无 | 新建：`Idempotency-Key` 同键同正文返回原任务 |
| N-07 | 复制输入 | 有按历史配置新建，但会带入模式与预算 | 新建：只返回可编辑参数并重定版本与配置 |
| N-08 | 报告候选接口 | 用 `reportable=true` 过滤记录，粒度为整条记录 | 新建：按完整发布的单文件结果出候选 |
| N-09 | 前端 v2 请求层 | 单一 axios 实例、固定 `/api`、200 信封、30 秒超时 | 新建：独立 v2 实例、错误结构与 `requestId`、按接口设置超时 |
| N-10 | 文件级状态与结果发布 | 结果整条存放 `result_json`，无逐文件不可变结果 | 新建（P31 落）：`task_target`、`execution_unit`、`review_result` 与发布条件 |
| D-01 | 双轨入口 | `ReviewEntry`、`capabilities`、隐藏验收路由、菜单改名 | 舍弃：关闭旧入口与切换层（D-85） |
| D-02 | 三模式与预算面板 | 模式选择与最大调用数、总 token、输出预算、安全余量、期限表单 | 舍弃：预算改服务端策略，参数不进发起表单 |
| D-03 | 合并文件 | `mergeFiles` 开关与合并超限报错 | 舍弃：逐文件目标（D-79、N-05） |
| D-04 | 原任务重试与恢复 | `retry`、`resume`、阶段恢复 | 舍弃：复制参数新建；重启只标中断（D-68） |
| D-05 | 旧标记与双维统计 | `issue_mark`、`accuracy`、`review-metrics` | 舍弃：P32 统一 `feedback` 与按结果类型统计（D-65） |
| D-06 | 候选核验与提交意图 | 核验状态、证据补取、提交意图聚合 | 舍弃（D-64），基础处理归公共能力 |
| D-07 | 专用运行诊断页 | `WorkflowRuns`、`WorkflowRunDetail`、阶段/上下文页签 | 舍弃：诊断并入任务详情的材料与调用 |
| D-08 | 外部分析器入口 | 分析器类型 4 与 `apiUrl/resultUrl/queryUrl/token` 表单 | 舍弃 v2 页面入口：配置保留、旧接口保留、不扩展（D-13） |
| D-09 | 旧报告兼容分支 | `quality`、`DEGRADED`、旧输入兼容 | 舍弃：新报告按冻结输入判定成功或失败（D-66） |
| D-10 | 模块级耦合聚合与旧阈值 | 模块聚合结果、扇出阈值、方法体窗口参数 | 舍弃：改为文件级已解析出向依赖（D-79、P29 专项设计 2.4） |

## 5. 数据映射与不迁移范围

新库从新 V1 初始化（`db/agent-migration`），旧 V1–V12 不混入新链、不做 baseline。旧记录不迁移、不要求新产品读取（D-71、X-02）；旧库只读封存与入口关闭在实际操作阶段执行，不在 P30.2 中建库或迁移。

| 旧表 | 处理 | 新位置 |
| --- | --- | --- |
| `project` | 改造复用字段 | 新 `project`（补 `revision`、`credential_ref`） |
| `model_config` | 拆分 | `model_config`（补窗口、最大输出、tokenizer、协议、`credential_ref`、`revision`）+ `model_validation` |
| `prompt`、`prompt_version` | 保留结构，补字段 | `prompt`（补 `purpose`、`revision`）+ `prompt_version` |
| `review_strategy` | 重构 | `review_strategy`（`specialist_type`、`model_config_id`、`prompt_id?`、`revision`） |
| `review_record` | 拆分 | `review_task`、`task_target`、`review_result` |
| `report`、`report_record` | 拆分 | `report`、`report_source` |
| `review_finding` | 拆分为稳定主体 | `issue`（随 `review_result` 发布） |
| `issue_mark`、`finding_assessment` | 不迁移 | `feedback`（P32 实现，主体为 issue 或 result） |
| `workflow_run/stage/call/context` | 不迁移 | `model_call`、`material`、`call_material`；阶段概念取消 |
| `sys_user` | 暂不迁移 | 新库鉴权与用户模型另行确认，P30 不引入多用户界面 |

新表初始清单（字段以 P29 共用设计第 2、8 节为起点）：`project`、`model_config`、`model_validation`、`prompt`、`prompt_version`、`review_strategy`、`review_task`、`task_target`、`execution_unit`、`model_call`、`material`、`call_material`、`review_result`、`issue`、`feedback`、`report`、`report_source`。除 `project`、配置表外，业务表均带 `revision`（乐观并发）、`created_at`、`updated_at`（UTC）。

## 6. 实现契约（P30.2–P30.8 的依据）

### 6.1 路径与版本

- 新业务接口前缀 `/api/v2`，不复用旧 DTO 与旧信封。旧 `/api` 在对应页面替换前保持原样，二者互不代理。
- 版本以路径承载；破坏性变更才升 `/api/v3`，兼容性字段增补不升版本。每个结果正文另带内容协议版本（6.6）。

### 6.2 成功与错误

- 成功查询直接返回业务对象；创建成功返回 201 或异步任务 202（`Location` 指向任务资源）；删除成功返回 204。**v2 不使用「HTTP 200 + 业务错误码」表达失败。**
- 错误体：`{code,message,retryable,requestId,details}`。`code` 为大写下划线字符串，`retryable` 由服务端判定，`requestId` 沿用链路追踪标识，`details` 只放可安全展示的结构化信息（字段错误、当前 revision、新旧 SHA）。

| code | HTTP | retryable | 场景 |
| --- | --- | --- | --- |
| `VALIDATION_FAILED` | 400 | false | 参数或组合非法，`details.fields` 列字段 |
| `NOT_FOUND` | 404 | false | 稳定 ID 不存在或不可见 |
| `CONFLICT` | 409 | false | 状态或唯一性冲突 |
| `IDEMPOTENCY_CONFLICT` | 409 | false | 同幂等键不同正文 |
| `SOURCE_MOVED` | 409 | false | 分支已移动，`details.observedSha/currentSha` |
| `REVISION_CONFLICT` | 409 | false | 乐观并发失败，`details.currentRevision` |
| `CONFIG_IN_USE` | 409 | false | 删除被策略或任务引用的配置 |
| `UNSUPPORTED_LANGUAGE` | 422 | false | 耦合专项遇到非 Java 目标 |
| `CONTEXT_LIMIT` | 422 | false | 模型结构化上下文超限 |
| `QUOTA_EXCEEDED` | 429 | false | 任务请求或期限额度耗尽 |
| `RATE_LIMITED` | 429 | true | 上游限流，`details.retryAfterSeconds` |
| `NOT_READY` | 503 | true | 新库或 schema 标记未就绪 |
| `INTERNAL_ERROR` | 500 | false | 未分类错误，只在服务端记细节 |

### 6.3 稳定 ID

- 服务端生成 26 位 ULID 字符串（时间可排序、大小写不敏感）；JSON 只暴露字符串，数据库以 `CHAR(26)` 保存。前端不得提交自造 ID。
- `issue.id` 在结果发布时生成，之后不随排序、刷新或重新发布而改变；反馈主体为 `(ISSUE, issue.id)` 或 `(RESULT, result.id)`，唯一约束加事务保护。
- 配置与项目使用稳定 ID；编辑以 `revision` 条件更新，不靠名称或时间定位。

### 6.4 分页与列表

- 游标分页：`cursor = base64url(createdAt 毫秒 + ":" + id)`，服务端不解释客户端构造的语义，只校验可解码。
- 响应 `{items,nextCursor,hasMore}`，`limit` 默认 20、服务端上限 100；不返回总数，界面显示「已加载 N 条」与「加载更多」。排序固定 `(created_at DESC, id DESC)`。
- 首页失败与下一页失败分别提示；下一页失败保留已成功页（沿用 K-07）。

### 6.5 状态、幂等与并发

- 任务状态 `QUEUED → RUNNING → SUCCEEDED/PARTIAL/FAILED/CANCELLED`，文件状态同构并增加 `PREPARING`；重启遗留 `RUNNING` 标 `INTERRUPTED` 后收敛，不恢复执行。
- `Idempotency-Key` 作用域为 `(project, user, key)`；同键同正文返回原任务与 202，不同正文返回 `IDEMPOTENCY_CONFLICT`。
- 取消 `POST /review-tasks/{id}/cancel {expectedRevision}`：先写 `cancel_requested_at` 并推进 `revision`，重复取消幂等；终态只经带预期 `revision` 的条件更新写入。

### 6.6 schema 版本与迁移身份

- 新库名、账号、数据源独立配置；`spring.flyway.locations=classpath:db/agent-migration`。
- 迁移身份检查：库名与配置一致、`flyway_schema_history` 中不存在旧 V1–V12 记录、非空库必须存在 `agent_schema_identity(schema_name,schema_version,installed_at,checksum)` 标记且版本匹配，否则拒绝启动迁移并返回 `NOT_READY`。
- 内容协议版本：`review_result.schema_version` 取 `issue/1`、`pattern/1`、`coupling/1`；任务输入快照为 `task-input/1`；报告为 `report/1`。版本随协议变化递增，历史结果按自身版本阅读，不按最新解析器重写。

### 6.7 凭据与快照

- 凭据只保存引用（`credential_ref`），接口只写不读；令牌留空保留、新值验证成功后原子替换。
- 配置修订使对应 `model_validation` 过期；任务保存配置与提示词版本快照，历史任务不重新读取当前配置。

## 7. 差异处理结论（原型未覆盖或来源已有）

| 编号 | 差异 | 结论与依据 |
| --- | --- | --- |
| R-01 | 原型没有项目删除入口，旧实现有影响预览与进行中阻止 | 保留为 v2 能力；删除是既有防误操作，不因原型简化取消（ROADMAP 第 1 节） |
| R-02 | 原型未画列表搜索，旧页面已有 | v2 项目、模型、提示词、策略列表保留搜索；服务端按名称/标签匹配 |
| R-03 | 原型未画模型验证与探针 | 保留验证与能力探针，明确「验证范围」不等于整窗口或工具能力 |
| R-04 | 旧提示词正文可直接改，新要求正文只经版本 | 统一走 `PUT /prompts/{id}/versions`，正文更新生成新版本；旧 `content` 接口随旧页面下线 |
| R-05 | 旧审查记录有进度百分比，新状态模型没有 | 以任务与文件状态表达进度，不保留百分比字段；界面用完成文件数/总数 |
| R-06 | 旧报告按项目归属，新报告可跨项目选材 | 报告改为顶层资源，`report_source` 记录每个来源的项目与版本 |
| R-07 | 旧列表有总数与页码 | 游标分页不返回总数；统计数字放任务详情或统计接口，不冒充全量 |
| R-08 | 旧外部分析器仍在旧页面使用 | 旧接口与表保留原样；v2 不提供入口、不迁移配置管理 |

## 8. 未决事项与风险

| 编号 | 事项 | 处理建议 | 状态 |
| --- | --- | --- | --- |
| Q-01 | 旧页面与 `/api` 的下线时点 | 随 P31.7/P32.5 页面替换逐页下线，P32.7 交付时记录 | 待实施 |
| Q-02 | 旧库封存与只读入口 | 按契约第 7.2 节在实际操作阶段执行，本文不建库不迁移 | 已知边界 |
| Q-03 | 新库鉴权与 `sys_user` 归属 | P30 不引入多用户界面；单用户假设须在 P30.2 明确实现方式 | 待 P30.2 |
| Q-04 | tokenizer 标识来源与估算标注 | 无适配时按 UTF-8 字节上界估算并明确 `ESTIMATED`（P29 专项设计 4.1） | 已定方向 |
| Q-05 | 模型协议适配与凭据加密实现 | P30.3/P30.5 细化；不引入新密钥管理组件 | 待实施 |
| Q-06 | 报告跨项目选材的权限边界 | 单用户阶段按项目可读即可选；多用户再收紧 | 待 P32 |
| Q-07 | 旧页面继续写入旧库的并行期 | 并行期只读新库、旧库可写；不双向同步 | 已定方向 |

## 9. 与后续任务的交接

- P30.2：按第 5 节新表清单与第 6.6 节迁移身份实现初始结构与访问层；不迁旧链、不建库。
- P30.3：按 6.2、6.3、6.7 实现项目、模型、提示词、策略的 v2 接口，落实 M-04 至 M-06 与 D-10。
- P30.4：把 M-02 的读取收敛为固定 SHA 读取，落实完整性、错误分类与有界缓存。
- P30.5：实现统一组装与调用，落盘后才发请求，落实 N-05、N-06 的持久化前置。
- P30.6：实现 N-03、N-06 的任务创建、取消、终态与幂等。
- P30.7：按第 4 节页面结论接入四项管理页；审查中心与详情替换留到 P31.7。
- P30.8：以第 6 节契约验证 A01–A06、A22、A23 的公共边界，并记录未实测项。

## 10. 验证记录

| 项目 | 状态 | 说明 |
| --- | --- | --- |
| 接口、页面、数据静态核对 | 完成 | 逐 Controller、路由与迁移脚本核对；未运行服务或数据库 |
| 固定来源一致性 | 完成 | HEAD 与 `8f20d6f` 业务代码一致，差异仅 4 个启停脚本提交 |
| 契约交叉核对 | 完成 | 逐项对应 D-58、D-59、D-62、D-65、D-68、D-71、D-79、D-84、D-85 |
| 文档编码、链接与编号 | 完成 | UTF-8 无 BOM、LF、相对链接、编号唯一；详情见提交说明 |
| A01–A06、A22、A23 | 未执行 | 依赖新库与新接口实现，交 P30.8 |

本文只确定实现起点，不替代 P30.2–P30.8 的工程检查，也不构成真实模型、数据库或正式页面验收。
