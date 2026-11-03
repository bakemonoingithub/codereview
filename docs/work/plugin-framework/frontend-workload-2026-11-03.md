# 插件体系前端工作量评估

日期：2026-11-03。依据：同日插件体系讨论纪要；对照仓库 `dev` 分支现有前端（Vue 3 + Ant Design Vue）。

评估范围仅限前端页面与组件；不含后端 API、插件执行器、Jira 适配与部署。人日按熟悉本仓库前端的中级开发估算，含联调与基础单测，不含正式视觉走查与权限体系。

## 1. 结论摘要

| 批次 | 目标 | 新增页面 | 新增核心组件 | 人日（约） |
| --- | --- | --- | --- | --- |
| 第一批（闭环 3.1） | 用户可选 skill、填表发起、看结果 | 2 个新页 + 审查中心改造 | 3～4 个 | **10～14** |
| 第二批（管理面） | Skill / 插件导入与维护 | 3～4 个新页 | 2～3 个 | **12～18** |
| 可选增强 | 引用跳转、结束并追问、自由插件分析 | 视需求 | 视需求 | **4～8** |

第一批优先复用 Agent 分析链路；管理面工作量接近再做一套「提示词管理」量级，且 Skill YAML 编辑与动态表单是主要新增成本。

## 2. 现有可复用基线

| 现有能力 | 路径 | 与插件任务的关系 |
| --- | --- | --- |
| 审查中心任务列表 | `views/ReviewCenter.vue` | 可扩类型筛选与发起入口；已有 Agent / 专项审查并列 |
| Agent 发起弹层 | `views/AgentLaunch.vue` + `LaunchModalShell` / `LaunchFormSections` | 发起页布局、分区、校验提示可参考；字段本身不同，不宜硬改同一弹层 |
| Agent 任务详情三标签 | `views/AgentTaskDetail.vue` | 结构模板：结果 / 输入 / 调用；进度条、取消、复制参数、轮询可复用模式 |
| 结果 Markdown + 代码证据侧栏 | `AgentResultPanel.vue` + `MarkdownView.vue` | Markdown 与引用点击可复用；侧栏需扩展为「代码证据 + 调用证据」 |
| 代码引用弹层 | `CodeReferenceModal.vue` / `CodeViewer.vue` | 代码类证据继续用 |
| 调用记录 | `AgentTaskDetail` 内列表 + `CallReaderModal` / `MaterialReaderModal` | 调用列表与全文阅读可复用或抽公共组件；插件调用需展示插件名、操作编号 |
| 列表页壳 | `ListPageLayout` / `EmptyGuide` / `LoadErrorAlert` / `PageNotice` / `StatusBadge` | 管理列表与任务列表通用 |
| 管理 CRUD 范式 | `PromptList.vue` / `ModelConfigList.vue` | Skill、插件列表的表格 + 弹层模式 |
| 仓库范围选择 | `CommitPickerModal` / `ScopePickerModal` | Skill 引用代码仓库插件时复用选仓库、分支、提交 |
| 路由与侧栏 | `router/index.ts` | 新路由自动进菜单；详情页 `hideMenu` |

说明：`ActualCallList.vue` 当前偏报告侧「模型调用」摘要，Agent 详情用的是自建列表。插件任务更接近 Agent 的「工具调用」形态，优先抽 Agent 详情里的调用列表为公共组件，而不是直接套 `ActualCallList`。

## 3. 第一批：用户闭环（对应场景 3.1）

### 3.1 需要新增或改造的页面

| 页面 | 建议路由 | 做什么 | 新建 / 改造 |
| --- | --- | --- | --- |
| Skill 任务发起 | `/skills/new` 或审查中心弹层 | 选 skill、任务名称、本次要求、skill 声明字段；提交后进详情 | **新建**（可仿 `AgentLaunch` 弹层或独立页） |
| Skill 任务详情 | `/skills/:id` 或复用 `/reviews/:id` 分流 | 三标签：分析结果 / 任务属性（输入） / 工具调用；进度与取消 | **新建**（结构仿 `AgentTaskDetail`） |
| 审查中心 | `/reviews` | 增加「发起 Skill 分析」按钮；类型筛选项增加 Skill/插件任务；打开详情路由分流 | **改造** |

第一批不做独立菜单项也可以：入口只放在审查中心，与「发起 Agent 分析」并列。若产品希望对管理者更显眼，再加工作台菜单「Skill 分析」（约 +0.5 人日）。

### 3.2 结果页三个标签与复用

| 标签 | 内容 | 复用 | 新增工作 |
| --- | --- | --- | --- |
| 分析结果 | 纯 Markdown；引用可点 | `MarkdownView`；下载 Markdown 逻辑可参考 Agent | 扩展引用 ID（调用编号，不只是 `E1`）；侧栏同时展示代码证据与调用证据 |
| 任务属性 | skill 名/版本、表单值、连接快照、完整性状态 | `TaskDetailMetadata`、`StatusBadge`、详情页 input 区布局 | 字段模型不同，基本是新模板，复用样式与卡片结构 |
| 工具调用 | 逐次调用列表 + 详情 | Agent 调用列表交互、`StatusBadge`、阅读弹层 | 字段：插件、操作、脱敏参数、状态；点击引用从结果跳到本标签对应行 |

建议新增组件（第一批）：

1. **`SkillDynamicForm`**：按 skill `inputs` schema 渲染表单项（string / select / 从代码插件复用的 repo、ref）。
2. **`PluginEvidenceAside`**：在 `AgentResultPanel` 旁栏模式上扩展，或抽「结果主栏 + 证据侧栏」壳，侧栏可插代码证据与调用证据两段。
3. **`ToolCallTimeline`**（从 Agent 详情抽出）：列表 + 选中详情；Agent 与 Skill 详情共用。
4. （可选）**`CitationJump`**：统一处理 Markdown 引用点击 → 打开代码弹层或切换到调用标签。

### 3.3 发起表单字段与复用

公共字段：Skill（必填）、本次要求（必填）、任务名称（可选）。

3.1 skill 字段示例：`issueKey`；`repo` / `ref` 来自 `system.code-repo` 声明。

| 字段来源 | 复用组件 |
| --- | --- |
| 项目 / 仓库选择 | Agent / 审查发起里的 `a-select` 模式；与现有项目列表 API |
| 分支 / 提交 | `CommitPickerModal`；AgentLaunch 中选分支与 SHA 的交互 |
| 弹层壳与分区标题 | `LaunchModalShell`、`LaunchFormSections` |
| 校验与错误条 | `a-alert`、现有 message 模式 |

不复用：Agent 的「范围类型 TEXT/FILES/COMMITS」、提示词版本选择（第一批提示词在 skill 内）、模型选择（内网固定模型、不展示）。

### 3.4 第一批工作量拆分

| 项 | 人日 | 备注 |
| --- | --- | --- |
| 审查中心入口与类型筛选改造 | 1～1.5 | 含路由分流到 Skill 详情 |
| Skill 发起弹层 / 页 + `SkillDynamicForm`（含代码插件字段） | 3～4 | schema 驱动表单是主要不确定点 |
| Skill 任务详情壳（进度、取消、复制、轮询） | 2～3 | 大量抄 AgentTaskDetail |
| 结果标签：Markdown + 双证据侧栏 + 引用跳转 | 2～3 | `MarkdownView` 引用正则需扩展 |
| 调用标签公共化与插件字段 | 1.5～2 | 抽公共组件可摊薄后续成本 |
| API 客户端类型与联调、基础单测 | 1.5～2 | |
| **合计** | **约 10～14** | 后端契约稳定前提下 |

风险：若第一批后端仍复用 Agent 任务 API 只加类型字段，前端改造偏小；若全新任务资源模型，联调可能再加 2～3 人日。

## 4. 第二批：管理面

讨论结论：Skill 由管理员维护（YAML + 可校验结构）；插件 JSON 导入；第一批一插件一连接时可弱化连接 UI。

| 页面 | 建议 | 复用 | 新建重点 | 人日 |
| --- | --- | --- | --- | --- |
| Skill 列表 | `/manage/skills`，管理分组 | `ListPageLayout`、`PromptList` 表格/空态/删除确认 | 启用状态、依赖插件是否齐全的校验提示 | 2～3 |
| Skill 编辑 | 独立页优于小弹层 | 表单布局、DiffViewer（版本对比可仿提示词） | YAML（或分栏表单）编辑、`inputs`/`tools`/`prompt`、保存前校验结果展示、试运行入口占位 | 5～7 |
| 插件列表 | `/manage/plugins` | 同上列表范式 | 导入 JSON、版本、操作数、系统插件只读标记 | 2～3 |
| 插件详情 / 操作目录只读 | 详情或抽屉 | `a-descriptions`、表格 | 展示操作、参数、指南；系统插件不可删 | 1.5～2 |
| 连接配置 | 可延后 | `ModelConfigList` 凭证类表单经验 | 地址、凭证引用、验证按钮；一连接策略下可仅后端配置 | 0～3（延后则 0） |

Skill 编辑是前端最大单项：既要利于人读 YAML，又要把校验错误标到字段/行；若做成「纯上传文件、不做在线编辑」，可压到约 2～3 人日，但维护体验差，不建议作为默认方案。

## 5. 建议不做或后置的前端项

| 项 | 原因 |
| --- | --- |
| 多轮对话暂停 UI | 讨论结论用「结束并追问」；首批只需结果态展示「需补充」与一键带参重开 |
| 补充材料上传 | 格式 / OCR / 大小未定 |
| 模型、预算、可见范围控件 | 与当前系统能力不符 |
| 连接选择器 | 第一批一插件一连接 |
| 按插件分的多个用户入口 | 按 skill 入口即可 |
| MCP 相关界面 | 排期最后 |
| 完整权限管理 UI | 权限缺口暂缓 |

「结束并追问」若做：在详情结果区增加状态条 +「补充后重开」跳到发起页并预填，约 **1～1.5 人日**，可挂在第一批尾巴或紧随其后。

## 6. 组件复用矩阵（按新页面）

### Skill 发起

| 需求块 | 复用 | 新写 |
| --- | --- | --- |
| 弹层框架 | `LaunchModalShell`、`LaunchFormSections` | 页面容器 |
| Skill 下拉 | `a-select` 模式同提示词选择 | 拉取 skill 列表 API |
| 动态字段 | — | **`SkillDynamicForm`** |
| 仓库 / 提交 | `CommitPickerModal`、项目 select | 与 `from: system.code-repo` 的映射 |
| 提交与错误 | AgentLaunch 校验模式 | 新提交 DTO |

### Skill 详情

| 需求块 | 复用 | 新写 / 改造 |
| --- | --- | --- |
| 页头、状态、操作 | `StatusBadge`、`TaskDetailMetadata`、取消/刷新按钮模式 | 文案与路由 |
| 进度条 | Agent meters 结构 | 指标字段对齐插件预算 |
| 结果 Markdown | `MarkdownView` | 引用 ID 规则扩展 |
| 代码证据 | `AgentResultPanel` 侧栏逻辑、`CodeReferenceModal` | 拆分或扩展为双证据 |
| 调用证据 | — | 侧栏条目 + 跳转调用 Tab |
| 输入摘要 | Agent input 卡片布局 | 展示 skill 快照与表单值 |
| 调用列表 | 抽自 `AgentTaskDetail` | **`ToolCallTimeline`** |

### 管理列表

| 需求块 | 复用 |
| --- | --- |
| 页框、筛选、空态、错误 | `ListPageLayout`、`EmptyGuide`、`LoadErrorAlert` |
| 表格操作列 | `PromptList` / `ModelConfigList` |
| 删除确认 | `a-popconfirm` / `RecordDeleteModal` |
| 版本对比（若要） | `DiffViewer` |

## 7. 与现有 Agent 页面的边界建议

- **不要**把 Skill 发起字段塞进 `AgentLaunch.vue`：范围模型不同，耦合后两边都难维护。
- **要**尽早把「任务详情三标签 + 调用列表 + 结果 Markdown」抽成与任务类型无关的壳，Agent 与 Skill 各传适配器。第一批若来不及抽取，允许先复制再收敛，但调用列表与 Markdown 引用建议第一次就抽，避免两套引用逻辑。
- 审查中心继续做统一任务入口；Skill 任务在类型列显示 skill 名称，而不是「插件名」。

## 8. 依赖与前提

前端排期依赖后端先给出（至少第一批）：

1. Skill 列表与详情（含 `inputs` schema、版本号）。
2. 创建 Skill 任务、进度、结果、工具调用分页与详情 API。
3. 结果体：Markdown、代码引用、调用引用、完整性状态。
4. （第二批）Skill / 插件的导入保存与校验错误结构。

无契约时，前端可用静态 mock 先做 `SkillDynamicForm` 与结果双证据交互原型（约 2～3 人日），再对接。

## 9. 推荐实施顺序

1. 抽 `ToolCallTimeline`，Agent 详情改用公共组件（小步，降低后续分叉）。
2. `SkillDynamicForm` + 发起弹层，对接 3.1 的固定 skill（甚至可暂时写死 schema）。
3. Skill 详情三标签：先 Markdown 纯展示，再接双证据与引用跳转。
4. 审查中心入口与列表类型。
5. 管理面：插件只读导入 → Skill 列表 → Skill 编辑。

## 10. 总览

| 范围 | 人日 |
| --- | --- |
| 第一批用户闭环 | 10～14 |
| 第二批管理面（含在线 Skill 编辑） | 12～18 |
| 第二批管理面（Skill 仅文件上传、弱编辑） | 7～10 |
| 结束并追问等增强 | 4～8 |
| **做到可演示的 3.1（不含管理编辑器）** | **约 2～3 周（一人）** |
| **含管理面完整维护** | **约 5～7 周（一人）** |

以上为讨论阶段估算，正式立项前应用后端 API 草案再收敛一次数字。
