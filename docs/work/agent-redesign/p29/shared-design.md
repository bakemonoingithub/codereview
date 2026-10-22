# P29 共用数据、接口与存储设计

状态：P29 已批准设计基线（2026-10-20，契约 D-88）。业务依据为[契约](../business-contract.md)第 5、6、7 节及 B-01、B-03 至 B-05、B-08 至 B-11、X-02。本文为 P30–P32 划分实现边界，Agent 专属执行设计留到 P33。字段和接口作为实施起点，例行技术修订在相应任务中记录；批准不代表候选参数或真实环境已验证。

## 1. 服务边界

| 职责 | 拟定入口 | 输入和输出边界 | 实施阶段 |
| --- | --- | --- | --- |
| 固定版本读取 | `RepositoryReadService` | 仓库标识、固定 SHA、路径及可选行范围；返回版本、原始位置、完整性及错误 | P30 |
| 提示词组装 | `PromptAssemblyService` | 类型化任务输入、材料及协议；返回有序消息、请求参数和计量依据 | P30 |
| 公共模型调用 | `ModelInvocationService` | 已组装输入、模型配置快照和执行票据；落盘后调用，返回响应及消耗 | P30 |
| 任务与执行控制 | `TaskService`、`ExecutionGuard` | 幂等提交、状态、取消、期限、调用配额及终态条件更新 | P30 |
| 专项执行 | `SpecialistRunner` | 固定文件目标及规则，按文件准备和执行，发布不可变结果 | P31 |
| 反馈与统计 | `FeedbackService` | 稳定对象 ID、评价、独立评论和编辑版本；统计使用数据库聚合 | P32 |
| 报告生成 | `ReportService` | 所选结果及反馈版本的冻结输入，经统一组装及调用生成独立报告 | P32 |

公共读取不暴露任意命令执行。任务、结果和材料不保存凭据，执行只取得受控凭据引用。P33 通过相同任务、调用、结果和报告适配接口接入；本阶段不设计 Agent 工具参数或循环。

## 2. 标识与关系

拟采用服务端生成的字符串 ID，JSON 不暴露数据库大整数。所有日期用带时区的 ISO 8601 输出，存储使用 UTC；界面按本地时区展示。稳定 ID 与展示排序分离，刷新和重排不改变反馈对象。

| 实体 | 最小字段 | 关系和不变量 |
| --- | --- | --- |
| `review_task` | `id, project_id, route, specialist_type, state, input_snapshot, config_snapshot, revision, created_at, deadline_at` | 一次发起一个任务；`route=SPECIALIST` 或预留 `AGENT`；专项类型为 `ISSUE/PATTERN/COUPLING` |
| `task_target` | `id, task_id, ordinal, path, old_path, base_sha, head_sha, note, state, error_code` | 一个文件一个目标；文件内容用 `head_sha`，差异保留比较关系及前后路径 |
| `execution_unit` | `id, target_id, generation, ordinal, ranges, state` | 整文件也有单元；重规划产生新 generation，旧代不能发布结果 |
| `model_call` | `id, task_id, target_id?, unit_id?, purpose, attempt, state, request_body, response_body, error, usage, timestamps` | 实际每次网络尝试一条；单元可空以接入报告和 P33，调用正文不随模板修改 |
| `material` | `id, project_id, sha, path, side, ranges, content, content_hash, completeness, diagnostic` | 保存实际用到的目标和证据；SHA、路径和侧不可互换 |
| `call_material` | `call_id, material_id, role, disposition` | 区分目标／辅助、实际发送／读取后未发送；调用正文为实际发送的最终依据 |
| `review_result` | `id, task_id, target_id?, kind, schema_version, body, provenance, completed_at` | 仅完整有效结果可选材；专项关联目标，预留 Agent 任务级结果；发布后正文不修改 |
| `issue` | `id, result_id, ordinal, category, severity, title, reason, suggestion, locations, source_units` | 只在问题结果内创建；空问题结果不虚构对象 |
| `feedback` | `id, subject_kind, issue_id?, result_id?, assessment?, comment, revision, updated_at` | `ISSUE` 只填 issue_id；`RESULT` 只填 result_id；主体唯一，约束检查与事务同时保护 |
| `report` | `id, title, state, model_snapshot, instruction_snapshot, frozen_input, body, error, timestamps` | 独立生命周期，所选结果及反馈一并冻结；下载内容等于保存正文 |
| `report_source` | `report_id, result_id, source_snapshot, ordinal` | 永久保留当时正文、来源、版本和反馈；不以外键联查最新数据代替冻结 |

材料与结果正文拟用 JSON／长文本，查询状态、关联、分页和统计的字段使用普通列及索引。P30 根据 MySQL 实际版本确认 JSON、CHECK 与事务能力；不能只依赖可能未生效的 CHECK。`feedback` 以主体唯一索引和 `revision` 条件更新防覆盖；`model_call` 索引覆盖 `(task_id, target_id, unit_id, attempt)`，任务索引覆盖项目、状态、时间、ID 的分页查询。

原始凭据只在既有凭据维护层加密或受保护配置内存放，快照仅保存 credential reference 与配置版本；日志、输入快照及错误正文中移除 Authorization、cookie 和连接口令。业务消息中用户自行输入的代码和文本按原样保存，不以脱敏为由篡改实际消息；访问权限继承所属项目。

## 3. 任务状态与并发提交

任务状态候选：`QUEUED → RUNNING → SUCCEEDED/PARTIAL/FAILED/CANCELLED`。取消先写 `cancel_requested_at` 并推进 `revision`；终态只通过带预期 revision 的事务写入。取消事务先于成功事务生效时，结果不能再作为成功发布；成功先提交时取消返回当前终态，不回写已完成任务。

文件状态为 `QUEUED/PREPARING/RUNNING/SUCCEEDED/PARTIAL/FAILED/CANCELLED`。任务全部文件成功才是 SUCCEEDED；既有成功或片段可用而又有失败时是 PARTIAL；没有有效内容且均失败为 FAILED。用户取消时任务为 CANCELLED，已完成文件结果保留可读，未开始文件明确为 CANCELLED。任务期限或资源上限命中后停止新调用，按已有结果给出 PARTIAL 或 FAILED，`stop_reason` 说明原因。

片段失败的文件可以保留诊断性部分内容，文件和任务均明确未完成；该文件不发布正式 `review_result`，不进入准确率或报告选材。已完整完成的其他文件正常发布。没有重试原任务或继续执行按钮；显式复制参数创建新任务。

幂等提交使用请求头 `Idempotency-Key`；同项目、同用户、同键及相同正文返回原任务，不同正文返回 409。P30 以事务唯一索引保证多节点有效。任务启动用一次性执行票据及版本条件更新，单进程重启后对遗留 RUNNING 标记 `INTERRUPTED` 停止原因并收敛至 PARTIAL/FAILED，不恢复原执行。关闭等待期间的线程及网络取消为尽力行为，终态保护必须有效。

## 4. HTTP 接口草案

统一前缀拟为 `/api/v2`，不与旧入口共享 DTO。成功查询返回业务对象；异步创建返回 202 与 `id/state/location`。错误结构 `{code,message,retryable,requestId,details}`，不把 HTTP 成功等同任务成功。分页采用稳定 `(createdAt,id)` 游标；前端保留已成功页及选择，下一页失败不清空。

| 方法与路径 | 关键输入 | 输出及校验 |
| --- | --- | --- |
| `POST /review-tasks` | `projectId, route, specialistType, source, targets[], modelConfigId?, strategyId?, smartSplit=false` | 选策略时解析关联模型，不接受冲突模型；不选策略时 modelConfigId 必填。targets 为文件路径及逐文件 note；source 为 `FILES(branch,observedSha)` 或 `DIFF(commitSha,baseSha,paths)`；禁止空目标和目录目标 |
| `GET /review-tasks` | `projectId?, route?, type?, state?, cursor?, limit?` | 任务摘要、逐文件计数和下一页游标；上限由服务端控制 |
| `GET /review-tasks/{id}` | ID | 任务及固定范围、文件状态、结果 ID 和停止原因；正文按需获取 |
| `POST /review-tasks/{id}/cancel` | `expectedRevision` | 当前状态；重复取消幂等，迟到请求不覆盖终态 |
| `GET /review-tasks/{id}/copy-input` | ID | 仅可编辑参数；不返回旧材料、快照凭据或承诺复用旧 SHA |
| `GET /review-results/{id}` | ID | 类型化不可变正文、代码版本、位置、材料缺口与反馈主体 |
| `GET /review-tasks/{id}/calls` | `targetId?, unitId?, cursor?` | 调用摘要、次数、状态；长正文不在列表重复返回 |
| `GET /model-calls/{id}` | ID、`part=request/response` | 实际持久化的完整正文与内容长度、哈希、是否完整；不得重新组装历史 |
| `GET /materials/{id}` | ID、可选显示范围 | 固定版本原文、行号及侧；阅读截断明确 `hasMore`，全文可另取 |
| `GET /feedback/{subjectKind}/{subjectId}` | 稳定主体 | 当前评价、独立评论、revision、修改时间；不存在返回空主体 revision=0 |
| `PUT /feedback/{subjectKind}/{subjectId}` | `assessment:ACCURATE/INACCURATE/UNKNOWN/null, comment, expectedRevision` | 可仅评论或清除评价；评论不强制评价；并发冲突 409 返回当前版本且保留客户端输入 |
| `GET /feedback-statistics` | `taskId?`、项目、类型、版本和日期范围 | 任务详情传 taskId 限定当前任务；按问题／模式结果／耦合结果分别返回准确、不准确、无法判断、未反馈及准确率 nullable |
| `GET /report-candidates` | 项目、类型、游标 | 完整发布的单文件结果；无问题的成功结果仍可选；失败和未完成内容不可选 |
| `POST /reports` | `title, resultIds[], modelConfigId, instruction?` | 事务检查归属、状态并冻结输入；可跨项目，分仓库与版本组织；幂等创建 |
| `GET /reports/{id}` | ID | 生成状态、正文、失败、来源列表及冻结时间 |
| `GET /reports/{id}/download` | ID | 完成报告的 UTF-8 Markdown，失败时无成功正文下载 |
| `GET /reports/{id}/sources/{sourceId}` | ID | 冻结的结果、版本、当时反馈及代码引用，源对象不可读时仍不替换为新内容 |

项目编辑及模型配置沿用现有资源职责，在 P30 适配 DTO，不在此阶段扩展外部 API 预留能力。项目只修改名称和可选新令牌，仓库地址锁定；令牌为空保留，新值验证成功后原子保存。模型字段草案为地址、模型名、凭据引用、上下文窗口、最大输出、tokenizer 标识和协议类型。配置变化使对应验证记录过期；一次可达和 JSON 返回测试不能证明完整窗口或 Agent 工具能力。

版本确认流程：选择文件时取得 `observedSha`，服务端提交时再次解析分支；发生移动返回 409 `SOURCE_MOVED` 并展示新旧 SHA，刷新范围重新确认后提交。已确认路径必须在该版本可取得且为允许的文本文件，不能静默取最新文件。差异发起保存固定 head/base、原路径、删除侧，合并提交展示比较关系；根提交以空树为 base，二进制或不可读内容明示不可审查，不伪装空成功。

### 4.1 范围弹窗与文件预览

按负责人评审，发起表单只显示目标文件数量、摘要和打开选择器的按钮；范围弹窗承担树形选择和文件预览入口。代码浏览页与选择器复用同一树控件职责，文件名及键盘激活只触发查看，复选框只变更审查目标，目录展开不成为目录级目标。选择器提供路径搜索、变更状态筛选、展开／收起、全选当前筛选结果及清空，列表不完整时保留已显示条目并明确提示。

选择器维护本地勾选草稿，确认时核对项目、分支、SHA、范围模式和提交身份仍与打开时一致；取消不修改主表单。主表单保留逐文件说明，确认后仅为新增目标创建空说明，保留未移除目标的输入，移除有说明的目标前提示。切换分支、文件内容／差异或不兼容专项时，先提示并清理不适用目标和说明，不能把旧选择默默解释为新版本目标。

蓝色文件名在选择器上层打开只读预览。文件内容查看固定版本原文；提交差异查看固定比较关系的改动，并可切换前后原文，新增／删除侧不存在时明确说明。关闭预览回到选择器，保持搜索、勾选和展开状态；预览动作不改变勾选。读取失败、截断、加载全文及关闭后的迟到响应按 UI-04／UI-05 接入，原型仅以固定样本和失败情境演示，不表示真实异步能力已实现。

框架实现选择性复用固定来源的 `WorkflowScopePicker`、`ChangedFileTree` 和 `FileViewerModal` 职责；不整件迁入旧模式、即时回填语义或原来源版本未固定 SHA 的调用方式。

### 4.2 材料与边界中的代码阅读

材料列表不内嵌代码正文。「材料 / 固定版本」列显示文件名，灰色小字提示 SHA 和原始行号范围；变更材料分别提示修改前后 SHA 及行号范围。统一点击「查看代码」后打开阅读弹窗，切换材料仅改变阅读对象，不改变目标或调用内容。目标文件使用完整原始行号；辅助声明按保存片段的 firstLine 显示，不把片段包装成完整文件。每项保留 materialId、路径、SHA／前后侧、完整性及发送状态，可区分已读取但未发送、实际发送片段和按需扩展的固定版本全文。逐调用关联以 call_material 为准，完整消息仍在实际调用中查看。

变更材料同样点击「查看代码」打开弹窗，默认展示固定 base/head 的 diff，并允许在弹窗内查看前后原文；未参与变更的辅助材料保持原文阅读。新增／删除侧、原文未保存或取得失败须分别说明，不使用当前分支或另一侧代替。显示全文只扩展阅读范围，不将额外代码追记为已发送；拆分任务按实际单元／调用保存的材料范围组织，不推断为整份代码均已进入模型。

框架层选择性复用 `FrozenFileReader`、`FrozenEvidenceReader`、`WorkflowCodeModal`／`WorkflowTextReader` 及 `DiffViewer`，将内容来源适配为新材料快照接口；统一处理行号、代码高亮、滚动、复制、截断／加载全文和读取失败。来源固定文件阅读器存在按 SHA 再读取的路径，不能直接作为实际发送代码已保存的证明；固定版本扩展阅读与当时发送片段须明确区分。P29 的 HTML 只复用轻量渲染函数和固定演示数据，未迁入业务组件。

## 5. 反馈及统计

依据契约第 5 节，程序发布结果后产生可统计主体：问题结果的每个问题一项，模式／耦合每个完整文件结果一项。空问题数组计 0 个问题主体，不能计算成一次准确；模式候选为空仍是一次完整结果，可作结果级反馈。独立评论无评价时统计为未反馈。

查询以可统计主体为左表关联唯一当前反馈，不扫描历史调用或模型正文推算数量；分别返回 `accurate/inaccurate/unknown/unassessed/denominator/accuracy`。`denominator=0` 时 accuracy 为 null，界面显示「—，暂无明确判定」。跨类型不混为同一准确率；需要汇总时同时标明主体类型与数量。保存成功才更新统计，切换问题时用主体 ID 暂存页面内输入，离页提示，409 允许读取最新并手工合并再保存，禁止自动重试覆盖。

页面布局按负责人本次评审调整：审查中心顶部不展示跨任务的文件完成情况或问题判定准确率。文件完成情况在任务详情及逐文件状态中阅读，问题准确率限定当前任务的完整结果，并随已保存反馈更新；没有明确判定时保持空分母展示。任务列表仍显示各行任务状态及文件计数，统计能力和计算公式保持契约定义。

## 6. 报告冻结及引用

候选仅包含完整发布的 `review_result`，不能选择整个批次来默默包含失败文件。PARTIAL/CANCELLED 任务中已完整完成的文件可选，未完成文件在选材列表说明原因。Agent 类型的适配只预留 `kind=AGENT_MARKDOWN` 与同样的不变结果契约，P32 以固定样本验证，P33 做真实联合验证。

生成事务冻结：结果正文、问题及稳定 ID、项目和仓库标识、SHA/比较关系、代码位置及保存证据、当前评价和评论及 revision、缺失说明、模型及生成要求。反馈后续修改不改变已生成报告统计。程序组成来源清单和按仓库／版本分组的事实及指标，LLM 生成报告正文；要求引用来源 ID，校验引用必须指向冻结材料，不自行编造数据。长材料不能静默截断；完整请求超限则生成失败并提示减少选材，不自动分批综合报告。

拟定报告状态 `QUEUED/RUNNING/SUCCEEDED/FAILED`；模型或协议失败保留冻结事实、调用及诊断，界面展示生成失败，不把确定性事实草稿标为已完成报告。报告生成不修改原结果。下载使用已保存 Markdown 字节，不再次调用模型。Markdown 渲染净化，代码按文本转义，引用链接只允许受控内部目标及明确允许的 URL 协议。

多结果详情方案：页首显示结果数、来源任务数、仓库版本数及专项类型数；正文包含概览、主要结论、按仓库／版本分章的结果明细、分类型反馈快照、处理建议、范围和来源清单。跨类型的解释可以共同阅读，依赖指标仍归属原版本，问题和结果级评价不混用分母。正文引用使用报告内固定序号，指向冻结来源及当时保存的代码；来源面板不通过当前配置或最新候选列表重新查询正文。原型已加入四项专项结果示例，仅用于布局与交互评审。

## 7. 数据隔离、封存与切换要求

新库候选名称 `ai_code_review_agent`，必须使用独立显式数据源及仅有新库权限的账户；具体实例、库名、部署路径由实施环境确认。新 Flyway 路径候选 `db/agent-migration`，从新的 V1 初始化；旧 V1–V12 链不混入该路径，禁止基于现有旧库 baseline 后继续新链。P30 增加配置身份和 schema 标记检查，旧库名称、旧 history 或标记不匹配时拒绝启动迁移；新库账户的权限隔离作为最终防线。

P30 交付初始化、配置及校验脚本，实际执行另行安排。切换前清点旧数据库、报告文件、配置和服务版本；制作可恢复备份并校验清单，旧库只读封存，保留受限运维读取，不接入新产品历史界面。新服务使用独立端口和数据目录演练，确认配置、初始化、轻量就绪和必要业务流程后再切入口。回退恢复旧应用及原数据源配置，保留新库作为独立快照，不将新记录倒灌旧库，不删除任何已封存资料。

本轮未连接数据库、执行迁移、实际封存、部署或回退。迁移账户、备份位置、保留期限、切换窗口及恢复演练结果均须在实际操作前补齐；方案不代表操作已完成。

## 8. 提示词、模型与策略管理补充

负责人在原型评审中指出三项管理入口缺失。本节补齐既有管理能力的设计覆盖，并已随 P29 最终评审按 D-88 获批。当前基线 `frontend/src/router/index.ts` 已分别提供 models、prompts、strategies 路由；对应 `ModelConfigList.vue`、`PromptList.vue`、`StrategyList.vue` 已有列表及编辑，提示词还有版本对比，策略关联模型与可选提示词。上述是本工作树基线事实，不表示新原型已实现业务存储。

拟定侧边栏将「项目管理」「模型管理」「提示词管理」「策略管理」独立列出，避免把通用配置藏在任务详情或仅放一张模型卡片。

项目管理同样采用列表入口，展示项目名称、仓库地址、当前分支及令牌配置状态；支持搜索、新建及共享编辑弹窗，并按稳定项目 ID 进入代码浏览和审查记录。新建填写仓库地址，编辑只允许名称和新令牌，仓库地址锁定；令牌留空保留，新令牌验证失败不覆盖原值。切换项目时清空原文件选择及阅读位置，不将其他项目代码展示为当前项目材料。原型只为订单项目提供代码样本，其他项目显示明确空状态；这不代表正式产品不支持这些项目。

| 对象 | 列表与编辑 | 关联及校验 |
| --- | --- | --- |
| 模型 | 名称、接口地址、模型名、凭据配置状态、窗口、输出限制、计量方法、验证状态；新建、编辑、验证、删除 | 凭据只写不读、留空保留；配置修订使验证过期。被策略引用时提示先调整关联；历史调用用旧快照 |
| 提示词 | 名称、描述、标签、用途、当前版本；正文编辑、版本选择与前后正文对比 | 专项通用规则由策略选用，临时文件说明不在此自动保存；编辑可创建新版本。被策略引用时提示调整关联；历史实际输入保持原文 |
| 策略 | 名称、专项类型、关联模型、可选通用规则提示词；新建、编辑、删除 | 专项类型按 ISSUE/PATTERN/COUPLING 适配；问题类型兼容文件内容与提交差异。外部 API 预留配置按既有契约保留，不扩展 |

本轮原型采用的选用关系：先选专项类型，再选可选策略；选策略时带入其关联模型，模型只读并显示提示，不增加另一个互相冲突的模型选择。没有策略时可单独选模型，执行内置指令；提示词为空的策略仍可使用。发起时将模型配置、策略配置、实际提示词版本及正文保存为输入快照，策略后续修改只影响新任务。报告表单直接选择模型，Agent 的提示词用途、选用与专属界面仍在 P33 实施。

新库共用配置实体草案：`model_config(id,name,base_url,model_name,credential_ref,context_window,max_output,tokenizer,revision)`、`model_validation(model_id,config_revision,scope,result,created_at)`、`prompt(id,name,description,tags,purpose,current_version_id,revision)`、`prompt_version(id,prompt_id,version_no,content,created_at)` 和 `review_strategy(id,name,specialist_type,model_config_id,prompt_id?,revision)`。引用采用稳定 ID；正文版本与实际调用快照分开保存，凭据不写入这两种内容。删除或配置修订不能使历史记录重新读取当前值。

配置接口在 P30 拟按 `/api/v2/models`、`/api/v2/prompts`、`/api/v2/strategies` 适配列表、新建、读取、更新与删除；模型验证另设 `POST /models/{id}/validate`，提示词正文版本及对比分别提供子资源。被当前配置引用时删除返回 409 并说明对象；编辑使用 revision 防止覆盖，验证范围和关联失效单独返回。Agent 用途只保留数据扩展边界，P33 再接入；不迁入旧分析器的耦合阈值或方法截断参数。
