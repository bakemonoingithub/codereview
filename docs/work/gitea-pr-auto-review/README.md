# GitHub 与 Gitea PR 自动审查方案

本方案供后续开发会话接续。

首版兼容 GitHub 和内网 Gitea 1.16.1。系统对指向配置分支的新建 PR 发起首次问题审查，并允许审核者按需触发复审。两端沿用现有 Git 宿主客户端和 PR 审查流程。

本轮使用 GitHub 测试仓库验收。Gitea 适配同步开发，以 1.16.1 固定样本验证，内网实测在环境可访问时进行。实施顺序见 [ROADMAP 的 PRA 工作包](../../ROADMAP.md#pra-github-与-gitea-pr-自动审查2026-11-12)。

[交互原型](prototype.html)使用 Gitea 示例，可直接在浏览器打开。正式页面沿用同一套交互，按项目展示对应宿主的配置、原始事件值和评论回执。模拟记录随页面刷新重置。

## 1. 业务流程与验收口径

1. 开发者创建指向配置分支的 PR。GitHub 或 Gitea 通过 Webhook 通知系统。
2. 系统识别仓库和 PR，核对目标分支，执行「专项审查 → 问题审查 → PR 审查」。审查范围为当前 PR 的全部可审查文本净变更。
3. 任务创建成功后，系统立即向原 PR 发布一条普通评论。正文只放本轮任务链接，链接文字可以带 PR 源提交短 SHA。审核者打开任务详情页查看进度和逐文件结果。各文件的结果和证据由现有系统展示，首版无需另行调用模型生成摘要。
4. 开发者需要修复时，向同一源分支追加提交，保留原 PR。审核者准备好后，在该 PR 下新发 `/recheck` 评论触发复审。系统使用最新读取并锁定的 PR 版本，重新审查整个 PR 的净变更，并在原 PR 发布新任务链接。
5. 审核者查看当前版本的结果，在对应宿主决定是否批准。验收需要演示合并受阻时，由目标分支保护要求有效人工批准。使用有合并权限的非管理员账号验证审批门禁，并验证追加代码变更后旧批准是否退出计数。

自动审查负责创建任务和发布结果入口。分支保护检查人工审批。上述验收能证明缺少审批时无法合并。原验收要求中的「命中质量阈值即自动拦截」需要另行确认阻断规则。若将来要求当前版本必须先生成报告，再单独设计必需状态检查。

## 2. Webhook 入口和复审命令

两端 Webhook 使用同一套接收和持久化流程。接收端按服务端配置识别宿主、实例和验签密钥。接收路径、Gitea 实例参数和错误响应见 [双宿主接收契约](pra-01-contract.md#11-请求头与验签格式)。

| 内容 | GitHub | Gitea 1.16.1 |
| --- | --- | --- |
| 投递 ID | `X-GitHub-Delivery` | `X-Gitea-Delivery` |
| 事件头 | `X-GitHub-Event` | `X-Gitea-Event`、`X-Gitea-Event-Type` |
| 验签 | `X-Hub-Signature-256`，值含 `sha256=` 前缀 | `X-Gitea-Signature`，值为十六进制摘要 |
| 新建 PR | `pull_request`，`action=opened`，取请求体 `number` | `pull_request`，`action=opened`，取请求体 `number` |
| 新建 PR 普通评论 | `issue_comment`，`action=created`，且 `issue.pull_request` 存在。取 `issue.number` | `X-Gitea-Event-Type=pull_request_comment`，`action=created`，且 `is_pull=true`。取 `issue.number` |

两端从已验签的请求体读取 `repository.id` 和 `sender.id`，评论事件另读 `comment.id` 与 `comment.body`。PR 状态、目标分支和净变更由现有读取服务查询。字段来源见 [GitHub Webhook 文档](https://docs.github.com/en/webhooks/webhook-events-and-payloads) 和 [Gitea 1.16.1 通知实现](https://github.com/go-gitea/gitea/blob/v1.16.1/modules/notification/webhook/webhook.go)。

各宿主的 Webhook 订阅决定系统收到哪些事件。其他已验签的投递也记录接收信息，并标记「已跳过」。增加审查触发条件时，在后端补充处理规则。

| 收到的事件 | 处理规则 |
| --- | --- |
| 新建 PR | 绑定到已启用自动审查的系统项目。PR 必须仍打开、未合并，且目标分支命中配置。 |
| 新建 PR 普通评论 | 复用项目、PR 状态和目标分支校验。核对触发人权限，再匹配复审命令。 |
| 普通 Issue 评论、追加提交、评论编辑或删除、其他事件 | 保留已接收投递的处理结果，标记跳过。 |
| 机器人发布任务链接 | 按机器人账号身份过滤，避免回写触发本系统。 |

复审命令固定为 `/recheck`。去掉正文首尾空白后，整条评论须与命令完全一致。行内提及、引用、附带说明和编辑旧评论均跳过。触发人须在项目配置认可的审核者范围内。首版采用审核者账号白名单，身份匹配使用项目绑定宿主上的用户 ID。PR Label 触发作为备选扩展。

接收端使用服务端绑定的 Webhook 密钥，对原始请求体计算 HMAC-SHA256 并验证签名，再使用仓库和触发人字段。验签失败只记最小拒绝信息，跳过业务处理。已验证的投递先持久化，再受理和异步处理。HTTP 受理表示收到投递，任务是否创建以处理记录为准。处理程序须能恢复受理后尚未完成的记录。

## 3. 项目配置与任务参数

配置依附已有 GitHub 或 Gitea 项目。以服务端配置的宿主、实例和已验签的仓库身份定位项目，优先采用实例内的仓库 ID。若存在多个匹配项目，记录配置冲突并停止。读取和回写使用匹配项目的仓库绑定，由 `GitHostClientRegistry` 选择已有宿主客户端。

| 配置 | 首版设置 |
| --- | --- |
| 项目与启用状态 | 选择已有 GitHub 或 Gitea 项目，为该项目启用 PR 自动审查。 |
| 目标分支 | 显式填写实际主分支名，例如 `main`。首次审查和复审共用此配置。 |
| 模型 | 选择已有 `modelConfigId`。 |
| 通用提示词 | 选用时同时指定 `promptId` 和 `promptVersionId`，固定具体版本。使用默认规则时两个字段一起省略。 |
| 审核者与机器人身份 | 逐个输入项目所属宿主的登录名，添加或移除可触发复审的审核者。名单为空时，评论复审停用。用专用服务账号回写评论，记录并过滤该账号产生的事件。 |
| Webhook 密钥与宿主凭据 | 由服务端配置或凭据引用提供。分别验证宿主端投递、系统接收可达性、后端读取及回写权限。 |
| 审查详情地址 | 配置审核者浏览器可访问的前端部署地址。`localhost:5173` 用于本机调试。 |

添加审核者时，通过项目绑定的宿主客户端按登录名查询。GitHub 使用 `GET /users/{username}`，Gitea 使用 `GET /api/v1/users/{username}`。查询成功后，在项目的宿主实例内按用户 `id` 保存名单成员，界面显示返回的 `login`。重复添加同一个用户时提示已存在。若返回 404，提示「未找到此账号，或当前凭据无法查看」，名单保持原值。评论事件通过验签后，用 `sender.id` 检查该项目的名单。人工批准资格由宿主分支保护管理。 [GitHub 用户查询接口](https://docs.github.com/en/rest/users/users#get-a-user)

Webhook 处理逻辑在后端复用现有仓库读取和任务创建服务。以下 HTTP 路径用于说明现有契约，后端内部调用可以直接复用对应服务。

1. 根据项目与 PR 编号读取当前 PR，核对仓库、状态和目标分支。通过 `GET /api/v2/projects/{projectId}/pulls/{number}/changes` 对应逻辑获取同一次 PR 读取的 `sourceHead`、`targetHead`、`mergeBase` 和完整文件清单。
2. 净变化由现有服务使用本地 Git 镜像计算，比较范围为共同祖先到源提交。`sourceHead` 是源分支提交，`targetHead` 是目标分支提交，`mergeBase` 是两者的共同祖先。每轮复审独立锁定版本。
3. 将 `category == "TEXT"` 的条目转成 `targets`。使用清单中的 `path`，存在 `oldPath` 时一并带上。文本删除和重命名按现有材料契约处理。二进制、LFS 指针、子模块、符号链接、超限和不可解码文件由现有分类排除，任务保留未覆盖数量。没有可审查文本时记录 `NO_TEXT_CHANGES`。现有净变化清单超过 3000 个文件时视为不完整。清单不完整、没有共同祖先、跨仓库 PR、PR 已关闭或文本目标超过 500 个时，记录具体原因并停止创建。超过 500 个文本目标时可以转由人工选择范围。
4. 按 `POST /api/v2/review-tasks` 的契约创建任务。`specialistType` 固定为 `ISSUE`，`source.kind` 固定为 `PR`，`smartSplit` 首版固定为 `false`。`projectId`、`modelConfigId` 和可选通用提示词版本取自项目配置。`source` 带 `pullNumber` 和三个 SHA，`targets` 带筛选后的路径。系统生成任务名称，例如「PR #13 问题审查 · 59abb71」。
5. 创建时沿用现有版本复核，并在创建使用的 PR 绑定上再次校验目标分支名称，避免 PR 改换到指向相同提交的其他分支时通过过滤。遇到 `SOURCE_MOVED` 且任务尚未创建时，可以在有限次数内重新读取 PR，重新校验目标分支，并成组刷新三个 SHA 与 `targets`。持续变化时记录失败原因，由审核者再次发起。直接调用服务层时，仍须沿用 HTTP 入口的请求约束，包括 500 个目标上限。
6. 获取审查 ID 后，用配置的前端地址拼接 `/#/reviews/{reviewId}?from=/reviews`，立即向原 PR 发布普通评论。评论只包含链接，例如「[查看提交 59abb71 的审查进度与结果](https://review.example.com/#/reviews/REVIEW_ID?from=/reviews)」。审核者从链接进入任务详情页，审查进度、结果及失败信息以该页面为准。

既有边界和具体字段见 [PRS-01 接口契约](../pr-review-scope/prs-01-contract.md)、[仓库读取服务](../../../backend/src/main/java/com/codereview/agent/repository/RepositoryReadService.java)和[任务创建参数](../../../backend/src/main/java/com/codereview/agent/dto/TaskDtos.java)。

## 4. 处理记录与失败恢复

### 4.1 表结构

拟在 `ai_code_review_agent` 新增 `pr_auto_review_delivery`。一条记录对应一次 Webhook 投递，同一投递重复到达时更新原记录。沿用新库的 InnoDB、`utf8mb4`、26 位 ULID、UTC `DATETIME(3)` 和逻辑外键约定。`project_id` 关联 `project.id`，`review_task_id` 关联 `review_task.id`。审查进度和逐文件结果从现有任务读取。

| 环节 | 字段与类型 | 用途 |
| --- | --- | --- |
| 接收 | `id CHAR(26)`、`host_type VARCHAR(16)`、`host_instance_key VARCHAR(500)`、`delivery_id VARCHAR(64)` | 主键、宿主类型 `github` 或 `gitea`、服务端配置的稳定实例标识、宿主投递头原值。有效投递须具备宿主类型、实例标识和投递 ID。 |
| 接收 | `first_received_at DATETIME(3)`、`last_received_at DATETIME(3)`、`receive_count INT` | 首次接收时间、最近接收时间和累计接收次数。次数初始为 1。 |
| 接收 | `event_name`、`event_type`、`event_action`，均为 `VARCHAR(100)` | `event_name` 保存 `X-GitHub-Event` 或 `X-Gitea-Event` 原值。`event_type` 保存实际收到的 `X-Gitea-Event-Type`，GitHub 投递留空。`event_action` 保存请求体 `action` 原值。字段缺失时存 `NULL`。 |
| 接收 | `repo_id BIGINT`、`repo_full_name VARCHAR(500)`、`pr_number BIGINT`、`actor_user_id BIGINT`、`source_comment_id BIGINT`、`payload_snapshot JSON` | 仓库和 PR 定位、触发人 ID、触发复审的评论 ID，以及保留原字段名的脱敏请求体。按实际收到的字段记录。 |
| 审查决定 | `project_id CHAR(26)`、`trigger_kind VARCHAR(16)`、`target_branch VARCHAR(200)` | 匹配到的系统项目、首次审查 `FIRST` 或评论复审 `RECHECK`、本次判定使用的目标分支。 |
| 审查决定 | `decision_state VARCHAR(16)`、`decided_at DATETIME(3)`、`decision_code VARCHAR(64)`、`decision_message VARCHAR(500)` | 处理状态、判定时间、可检索的跳过或失败原因码及脱敏说明。 |
| 审查决定 | `business_key VARCHAR(100)`、`canonical_record_id CHAR(26)`、`task_request_snapshot JSON` | 执行记录的业务幂等键、重复触发关联的原处理记录 ID，以及调用任务服务前保存的 `TaskDtos.Create` 参数。 |
| 审查决定 | `source_head`、`target_head`、`merge_base`，均为 `VARCHAR(64)`，`text_count`、`binary_count`、`unreadable_count`，均为 `INT` | 创建请求使用的三个提交 SHA 和文件覆盖数量。只有任务创建后，这组版本才作为该任务的冻结版本。 |
| 审查决定 | `review_task_id CHAR(26)`、`review_url VARCHAR(1024)` | 已创建任务的 ID 和本轮任务详情链接。 |
| 评论请求 | `comment_actor_id BIGINT`、`comment_body TEXT`、`comment_attempts INT`、`comment_last_attempt_at DATETIME(3)` | 回写机器人的用户 ID、发送正文、发送次数和最近发送时间。发送目标使用本条记录的仓库和 PR。发送次数初始为 0。 |
| 评论结果 | `comment_state VARCHAR(16)`、`comment_id BIGINT`、`comment_http_status SMALLINT`、`comment_error VARCHAR(500)`、`comment_checked_at DATETIME(3)` | 评论发送状态、宿主返回的评论 ID、最近一次发送的 HTTP 状态或脱敏错误，以及最近核对已有评论的时间。 |
| 并发与恢复 | `revision INT`、`lease_until DATETIME(3)`、`next_attempt_at DATETIME(3)`、`updated_at DATETIME(3)` | 处理版本、执行租约、下一次处理或回收时间、记录更新时间。 |

`id`、首次和最近接收时间、接收次数、两类处理状态、发送次数、`revision` 和 `updated_at` 必填。`revision` 初始为 0。其他字段按处理进度填写。验签失败时仅保存本地记录 ID、接收时间和拒绝原因等最小信息。未验证的投递 ID、事件头和请求体留空，避免占用有效投递的去重键。签名、认证头、令牌和凭据不进入快照、错误说明或日志。

### 4.2 约束、索引和列表查询

| 约束或索引 | 字段 | 用途 |
| --- | --- | --- |
| 主键 | `id` | 定位一条处理记录。 |
| 唯一索引 | `host_type, host_instance_key, delivery_id` | 同一宿主实例内的同一投递只落一条记录。重复接收时原子累加 `receive_count` 并更新 `last_received_at`。 |
| 唯一索引 | `project_id, business_key` | 同一项目内，同一次首次审查或同一条复审评论只有一条执行记录。 |
| 普通索引 | `project_id, first_received_at, id` | 当前项目列表按首次接收时间倒序分页，使用 ID 区分同一时刻的记录。 |
| 普通索引 | `host_type, host_instance_key, repo_id, pr_number, first_received_at, id` | 从宿主实例、仓库和 PR 定位处理历史。 |
| 普通索引 | `review_task_id` | 按审查 ID 找到执行记录。 |
| 普通索引 | `canonical_record_id` | 按执行记录 ID 查关联的重复触发记录。 |
| 普通索引 | `next_attempt_at, id` | 扫描待处理记录和租约到期的记录。 |

执行记录使用 `pr-auto-review:` 加 SHA-256 摘要的 64 个小写十六进制字符作为 `business_key`，并将同一个值传给现有任务服务的 `Idempotency-Key`。摘要输入为 UTF-8 JSON 数组，按顺序包含宿主类型、宿主实例标识、仓库 ID、PR 编号和触发方式。评论复审在末尾追加触发评论 ID。提交 SHA 和请求参数随任务请求冻结。两个独立的复审评论对应两个业务键。

同一宿主实例收到相同投递 ID 时更新接收次数，沿用原记录。收到不同投递 ID 且命中同一业务键时，保留各自的接收记录。通过项目、PR 和触发条件校验后，在创建任务前取得唯一键。首个取得唯一键的记录负责创建任务和回写评论，处理结束时保留已取得的业务键。其余记录写入 `SKIPPED / DUPLICATE_TRIGGER` 和 `canonical_record_id`，`business_key` 留空。查询重复记录时，系统从原记录读取任务和评论回执，并按关联后的任务 ID 和回写状态筛选、分页。

处理记录列表的事件显示与筛选均使用 `event_type`，缺失时使用 `event_name`。有 `event_action` 时一并显示。事件筛选从当前项目的投递记录取值。接收详情按宿主展示实际收到的投递头、事件头和脱敏请求体。调整宿主订阅范围时，列表可以直接展示和筛选新收到的事件。未匹配项目的投递可在排查时按宿主、实例和投递 ID 定位。

### 4.3 状态与恢复

| 环节 | 状态流转 |
| --- | --- |
| 接收和创建任务 | 通过验签后保存为 `RECEIVED`，受理后进入 `PROCESSING`。任务创建完成后为 `CREATED`。遇到过滤条件或重复触发时为 `SKIPPED`，创建失败时为 `FAILED`。验签失败等接收拒绝为 `REJECTED`。 |
| 评论回写 | 初始为 `NONE`。任务创建后保存发送目标和正文，同时置为 `PENDING`。开始发送前置为 `SENDING` 并记录尝试次数。收到成功回执后为 `SENT`，明确发送失败为 `FAILED`，超时或无法确认是否发布为 `UNKNOWN`。 |
| 核对评论 | `UNKNOWN` 找到原评论后补记评论 ID 并转为 `SENT`。确认原请求未发布且已结束后，可转为 `PENDING` 并使用原任务链接补发。暂时无法确认时保持 `UNKNOWN`。 |

处理程序按 `revision` 和租约条件领取记录。领取时将到期时间写入 `next_attempt_at`，供进程退出后回收。无待处理工作时将租约和下次处理时间置空。状态推进按处理版本做条件更新。重复接收只更新接收时间和计数，不改处理版本。典型跳过原因包括未绑定项目、功能停用、目标分支不匹配、PR 已关闭、事件或动作不适用、命令不匹配、机器人发言、触发人无权限和没有可审查文本。配置冲突、净变化读取失败、清单超限、模型或提示词配置失效分别记录具体失败原因。

调用任务服务前先保存完整待创建请求。恢复时先按项目与业务键查找原任务。找到原任务时，按任务快照回填三个 SHA 和覆盖数量，继续记录和回写。任务尚未创建时，复审按当前项目配置重新校验触发人的用户 ID。通过校验且已有请求快照时，使用已保存的请求和同一幂等键重试。

明确收到 `SOURCE_MOVED` 且确认任务尚未创建后，系统可以刷新待创建请求。完整读取新版净变更并准备好请求快照后，系统一并更新三个 SHA、覆盖数量和请求快照。刷新失败时，处理记录保留上次冻结的版本与请求，并记录失败或跳过原因。

`CREATED`、审查 ID、任务链接及评论发送意图在一次数据库更新中写入。任务排队后即可在审查中心按项目和「问题审查」查看。

回收租约已过期的 `SENDING` 记录时，先转为 `UNKNOWN`。核对依据为目标 PR、机器人身份和已保存的任务链接。短时间内查不到评论时，如果原请求可能仍在途，继续等待核对。明确失败或核对确认未发布后，恢复使用已保存的正文。评论重试沿用原任务 ID。

实施时确认保留期限和重试间隔。待处理记录保留恢复所需快照。清理已完成的载荷时，保留业务幂等键、审查 ID 和评论回执，避免后续重投再次执行。删除或归档审查任务按现有任务规则处理，投递记录仍可追溯原审查 ID。

## 5. 现有框架复用

| 边界 | 职责及复用 |
| --- | --- |
| Webhook 接入 | 各宿主适配验签、事件头和请求体字段，共用持久化和异步受理流程。 |
| Git 宿主访问 | 通过 [GitHostClientRegistry](../../../backend/src/main/java/com/codereview/git/GitHostClientRegistry.java) 按项目仓库选择 [GitHubClient](../../../backend/src/main/java/com/codereview/git/GitHubClient.java) 或 [GiteaClient](../../../backend/src/main/java/com/codereview/git/GiteaClient.java)。在现有 [GitHostClient](../../../backend/src/main/java/com/codereview/git/GitHostClient.java) 补齐用户查询、普通评论列表和发布能力。 |
| 自动审查流程 | 两端共用项目匹配、目标分支过滤、`/recheck`、幂等、任务创建和评论回写协调。 |
| 专项任务 | 复用现有版本锁定、文本净变更、任务执行和逐文件详情。手工与自动发起使用同一套规则。 |

PR 时间线的普通评论通过 GitHub 的 `/repos/{owner}/{repo}/issues/{issue_number}/comments` 或 Gitea 的 `/api/v1/repos/{owner}/{repo}/issues/{index}/comments` 读取和发布。评论列表须支持分页，用于回写结果核对。 [GitHub 普通评论接口](https://docs.github.com/en/rest/issues/comments) · [Gitea 1.16.1 普通评论接口](https://github.com/go-gitea/gitea/blob/v1.16.1/routers/api/v1/repo/issue_comment.go)

现有 Java 服务负责首版接入。`docs/plugin-framework-design` 的 `eec5db5` 所描述的 `ToolPlugin` 和 `ToolGateway` 将来可复用宿主客户端及回写回执。后续若扩展自动合并或拒绝，沿用项目仓库与 PR 版本校验，再明确动作权限、放行依据和验收标准。

## 6. 实施顺序与验收

1. 按 ROADMAP 的 PRA-01 固定两端 Webhook 样本。优先从 GitHub 测试仓库验证接收可达性、验签、仓库匹配和机器人权限。Gitea 1.16.1 可先使用版本对应的固定样本。
2. 按 PRA-02 至 PRA-05 实现双宿主接收、首次审查、`/recheck`、评论回写和页面。在固定样本中覆盖仓库 ID 或投递 ID 相同但宿主不同的场景。
3. PRA-06 先跑双宿主工程回归，再在 GitHub 测试仓库进行端到端验收。准备 GitHub 可访问的 HTTPS 接收地址。记录真实投递、审查 ID、三个 SHA 和评论 ID。人工审批门禁用测试仓库的分支保护独立验证。
4. PRA-07 在内网可访问时，用 Gitea 1.16.1 真实仓库复核同一套业务流程和权限。

| 验收场景 | 预期结果 |
| --- | --- |
| 指向主分支的 PR 同时修改文本和二进制 | 创建一个问题审查任务。仅文本进入目标。任务创建后立即出现可访问的链接，详情页可查看逐文件结果与覆盖范围。 |
| 非目标分支、仅有二进制、清单不完整或超限 | 处理记录给出对应原因。PR 上没有审查任务链接。 |
| 连续向同一源分支推送修复 | 原 PR 保持打开。推送本身不创建审查任务。 |
| 审核者新发 `/recheck` | 当前 PR 的整份净变更按新版本审查，原 PR 出现新链接。旧链接仍指向原任务。 |
| 普通讨论、编辑旧评论、无权限触发或机器人回写 | 记录跳过原因。 |
| GitHub 普通 Issue 新发 `/recheck` | 保留投递记录，标记跳过。PR 上符合条件的新建普通评论可触发复审。 |
| GitHub 和 Gitea 的投递 ID、仓库 ID 或用户 ID 重合 | 两端独立匹配项目、审核者和投递记录。 |
| 额外订阅 `push` 并收到一次有效投递 | 列表按原值显示 `push`，事件筛选出现 `push`。审查决定为「已跳过」。 |
| 同一投递重复接收 | 保留一条处理记录，接收次数累加。 |
| 同一触发以不同投递 ID 到达 | 保留各自的接收记录，只创建一个审查任务并发布一条任务链接评论。重复记录可定位原任务。 |
| 任务创建成功后、写入审查 ID 前中断 | 恢复后找回原任务，接着记录和发送该任务的评论。 |
| 评论发送超时或收到成功回执前中断 | 评论状态为「结果待确认」。找到原评论时补记回执，确认未发布后才补发。 |
| 主分支要求人工批准 | 有合并权限的非管理员在缺少有效批准时被拦截。追加代码变更后验证旧批准的计数，审核者查看新任务结果后重新批准。 |

实施前确认 GitHub 测试仓库、接收与详情地址、目标分支、审核者、模型和提示词版本。确定 Webhook 路径、配置载体、处理记录保留期限和重试间隔，并按第 4 节在 `backend/src/main/resources/db/agent-migration/` 新增 Flyway 迁移。专家若坚持验收自动质量拦截，先确认可复现的规则和放行条件。
