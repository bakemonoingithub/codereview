# Skill 分析 UX/UI 原型

> **约束（给实现与其他 agent）**
>
> 1. **仅 UX / 流程示意**：本目录用于评审交互结构、信息架构、文案与证据链展示，**不是**前端实现稿，也不是视觉设计源。
> 2. **禁止当实现依据**：不要按本 HTML 的布局、组件样式、弹窗/抽屉实现方式、圆角阴影、表单控件或 CSS token 去改正式 Vue / Ant Design 代码；正式 UI、弹出方式与组件以仓库**当前线上/主干**的 Agent 相关页面为准（如 `AgentLaunch`、`AgentTaskDetail`、审查中心等）。
> 3. **已知偏差**：本页 UI 与系统最新情况不一致（含弹层、控件、间距与视觉语言），属有意简化的静态 mock，**不得**据此「对齐」或「迁移」到产品代码。
> 4. **允许参考的范围**：仅可参考「要有哪些屏幕 / 字段 / Tab / 证据点击行为」等产品流程与信息结构；实现时在现有组件体系内复用并适配最新 UI。
> 5. **落盘位置**：默认放在本机/沙箱 `out/`，**不要**未经确认提交进业务仓库的 `src/` 或可被当作设计稿的路径；若入库，仅可进明确标注为讨论/示意的 `docs/work/`，且须保留本约束段。
> 6. **其他 agent**：读到本文件或 `index.html` 时，不得据此发起对正式前端的样式或组件改动；需要改产品 UI 时，以真实 Vue 源码与现网为准，并先征得用户确认。

自包含前端原型，无后端、无 CDN、无外部资源。用于评审 **Skill / 插件分析** 第一批交互与视觉（流程层面）。


## 如何打开

1. 用浏览器直接打开本目录下的 `index.html`（仓库路径 `docs/work/plugin-framework/skill-analysis-prototype/index.html`）。
2. 也可在仓库根目录起静态服务，例如：

```bash
cd docs/work/plugin-framework/skill-analysis-prototype
python3 -m http.server 8765
# 浏览器访问 http://127.0.0.1:8765/
```

顶栏固定标签：**原型 · Skill 分析**。全部为演示数据，刷新页面后重置。

## 建议审查的屏幕

| 屏幕 | 入口 | 看什么 |
|------|------|--------|
| **审查中心** | 侧栏「审查中心」 | 任务列表含 Skill；筛选含类型「Skill 分析」；主按钮 **＋ 发起 Skill 分析**；专项 / Agent 为占位 toast |
| **发起 Skill 分析** | 中心主按钮 | Skill 下拉（`根据 Issue 评估代码改动点` 可选；另一项禁用）；可选任务名；必填「本次要求」；动态字段 Issue / 仓库 / 分支 / 提交；提交后 toast 并进入详情 |
| **Skill 任务详情** | 列表行「查看详情」或提交后 | 状态徽章、用量 meters、刷新 / 取消（执行中）/ 复制参数新建；三 Tab：**分析结果** / **任务属性** / **工具调用** |
| **分析结果 + 证据** | 详情 · 分析结果 | Markdown 内 `[E1]`/`[C1]` 可点；右侧「代码证据」「调用证据」；代码打开 modal，调用切 Tab 并高亮 |
| **任务属性** | 详情 · 任务属性 | Skill 名/版本、表单快照、自动绑定「研发 Jira」、完整性「完整」 |
| **工具调用** | 详情 · 工具调用 | 左侧 C1–C4 列表，右侧请求/返回 JSON |
| **Skill 管理** | 侧栏 Manage「Skill 管理」 | 已启用列表 + 依赖 OK；主按钮 **安装 Skill**；每行 **编辑** 复用安装表单（mode=edit，预填）；点击卡片打开只读抽屉（YAML 预览）；notice 交叉链到插件管理 |
| **插件管理** | 侧栏 Manage「插件管理」 | 业务/系统插件列表（`jira-issue` / `code-repo` / `gitea-api`）；行点击打开只读清单抽屉；主按钮 **新建插件**；业务行 **编辑** 复用新建表单（mode=edit）；`code-repo` 系统内置：编辑禁用 + toast「系统内置不可编辑」；发起分析时不暴露插件勾选器 |
| **安装 Skill** | Skill 管理 · 安装 Skill | 管理员结构化表单（mock）：标识 / 显示名称 / 版本 / 简介 / 依赖插件勾选 / 输入表单 Schema / 定义正文 / 启用；校验 id+名称+正文；保存 toast + 前置到内存列表 |
| **编辑 Skill** | Skill 管理 · 行「编辑」 | 同安装表单结构（`#install-skill-dialog`，mode=`edit`）；标题「编辑 Skill」；**标识只读**；预填名称/版本/简介/依赖/Schema/正文/启用；保存 **更新** 内存列表对应项 |
| **新建插件** | 插件管理 · 新建插件 | 仅业务（类型固定）；认证 JWT|Legacy REST 动态字段；工具列表为主；OpenAPI/清单为选填高级；连接「一插件一连接」+可选默认名；创建 toast + 追加内存列表。`code-repo` 系统内置仍在列表只读 |
| **编辑插件** | 插件管理 · 业务行「编辑」 | 同新建表单结构（`#create-plugin-dialog`，mode=`edit`）；标题「编辑插件」；**id 只读**、类型固定业务；预填认证动态字段 / 工具列表 / 可选 OpenAPI 详情；保存 **更新** 内存项。系统内置无编辑流（抽屉仍只读） |

页底 **评审情境** 可切换：已完成任务 / 执行中 / 空列表。

## 演示数据要点

- Skill：`issue-code-impact` v1.0.0 — 根据 Issue 评估代码改动点  
- Issue：`ORD-1281` 支付重试需幂等  
- 仓库：订单服务 / `main` / `b7a312e`  
- 结论：改动点在 `OrderService.retryPayment` + `PaymentGateway`，证据 `[E1][E2]` 与 `[C1]` 等  
- 工具调用：C1 `jira.get_issue`（形状对齐 Server 8.7.1）；C2 `search_code`；C3/C4 `read_code`
- 插件：`jira-issue`（业务·已启用·**Basic / OAuth · Legacy REST**）、`code-repo`（系统内置·已启用）、`gitea-api`（业务·规划中灰态）
- **jira-issue ↔ Jira Server/Data Center 8.7.1 映射**（只读一期）：
  - 基址 `{baseUrl}/rest/api/2/...`（**非** Cloud `api/3`）
  - 认证：OAuth（首选）或 HTTP Basic（SSL）；备选 `POST /rest/auth/1/session` Cookie — **无** Cloud API token、**无** JWT 展示、**无** PAT（PAT 自 8.14+）
  - 工具：`get_issue` → `GET /issue/{issueIdOrKey}`；`search_issues` → `GET|POST /search`（JQL + `startAt`/`maxResults`）；`get_comments` → `GET .../comment`；`get_myself` → `GET /myself`（连通性）
  - 写评论 `POST .../comment` 标为「一期未开放」；默认 `fields=summary,status,assignee,description,comment`


## 安装 / 新建 / 编辑表单字段（本批补充）

编辑入口复用同一 dialog：Skill 用 `skillFormMode = install|edit`，插件用 `pluginFormMode = create|edit`；不另起一套 markup。

### 安装 / 编辑 Skill（`#install-skill-dialog`）

| 字段 | 说明 |
|------|------|
| Skill 标识 | 必填；稳定 id（如 `issue-code-impact`） |
| 显示名称 | 必填 |
| 版本 | 默认 `0.1.0` |
| 简介 | 可选 |
| 依赖插件 | 勾选 mock 插件：`jira-issue` / `code-repo` / `gitea-api` |
| 输入表单 Schema | textarea；预填示例 YAML/类 JSON Schema |
| Skill 定义正文 | 大 textarea；YAML/JSON（prompt + tools refs）；必填 |
| 启用 | checkbox，默认开 |
| 操作 | 取消 / 保存（install：`skills.unshift`；edit：按 id 原地更新） |

校验：标识、显示名称、定义正文；标识格式与唯一性（演示级）。**编辑时**标识只读，跳过「已存在」冲突（仅允许改自身）。

### 新建 / 编辑插件（`#create-plugin-dialog`）

仅可新建 **业务** 插件。系统内置（如 `code-repo`）为平台预装只读 Agent 工具集，列表/抽屉仍可展示「系统内置」，但不可在此表单创建；「内置无认证」亦不出现在创建表单。

| 字段 | 说明 |
|------|------|
| 插件 id | 必填 |
| 显示名称 | 必填 |
| 类型 | 固定只读「业务」（不可选系统内置） |
| 版本 | 默认 `0.1.0` |
| 简介 | 可选 |
| 认证方式 | **JWT** \| **Legacy REST**（二选一；切换时动态显隐字段）。复制 Jira Server 场景请选 Legacy REST（Basic/OAuth/Session）；`jira-issue` 演示插件本身不展示 JWT |
| ↳ JWT 字段 | Base URL（必填）、Token 获取地址（必填）、Client ID（可选）、密钥说明/占位（不落盘）、续期策略「执行器自动续期」checkbox（默认开） |
| ↳ Legacy REST 字段 | Base URL（必填，api/2 前缀示意）、认证材料说明（必填：Basic 用户名密码 / OAuth / `POST /rest/auth/1/session`）、兼容备注（可选；可注 8.7.1 · 非 Cloud/JWT/PAT） |
| 工具列表 | **主字段**；textarea；每行 `name — desc`；Skill/执行器消费的能力清单（必填） |
| 连接说明 | 只读「一插件一连接」提示 + 可选默认连接名 |
| 从 OpenAPI/清单导入 | **选填·高级**（`<details>` 折叠）；较小 textarea；提示：工具列表为主清单，契约用于导入/校验，避免重复填写；本原型不做导入解析 |
| 启用 | checkbox，默认开 |
| 操作 | 取消 / 创建或保存（create：`plugins.push`；edit：按 id 原地更新；只保留当前认证模式字段） |

校验：插件 id、显示名称、Base URL、工具列表；JWT 另需 Token 获取地址；Legacy REST 另需认证材料说明；id 格式与唯一性（演示级）。**编辑时** id 只读；系统内置（`kind === '系统内置'`，如 `code-repo`）列表编辑禁用 + toast「系统内置不可编辑」，且 `openEditPlugin` 会拦截。Escape 关闭各 dialog（含上述两个）。

## 明确未包含（按产品范围）

多轮对话、连接选择器、模型/预算控件、可见性、附件、评论 Jira、自由勾选插件、完整 Agent/专项/报告流程。

## 视觉说明

本页视觉仅 loosely 参考旧版 `index-agent.html` 的浅色侧栏语言，**已落后于系统最新 UI**（含弹出方式）。正式实现必须跟主干组件，不得「按本 HTML 还原」。本文件为聚焦 Skill 流程的独立 mock，未 fork 完整 Agent 应用。

## 有意简化

- Commit 选择器用简单 `<select>`，非完整 Commit 选择弹窗  
- 提交后直接进入「已完成」演示任务（可用情境条看「执行中」）  
- Skill / 插件安装、新建与 **编辑** 均为 **mock 表单 modal**（无后端、无持久化；刷新丢失）；列表「编辑」复用同一 dialog；只读抽屉不提供编辑  
- 「发起专项审查」「发起 Agent」为占位 toast  
- 安装 Skill / 新建插件不做真实 YAML·OpenAPI 语法校验；依赖与工具列表为示意勾选/文本  
