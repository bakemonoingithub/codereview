# 运行配置规范（CFG-02）

日期：2026-10-26（Asia/Taipei）。依据：[改造计划](README.md)。本规范对应运行配置实现。2026-10-28 已补充 LLM 重试、验证期限及部署模板；工程证据见[本轮记录](llm-deployment-20261028.md)，目标内网验收仍属 CFG-07。

## 1. 加载、生效与兼容

- 配置使用 Spring Boot 现有加载机制。当前 Docker 镜像入口以 `--spring.config.location=file:/app/config/application.yml` 指定外置文件。部署脚本在数据库启动前使用新镜像执行静态配置检查；目标机实际加载与重建验收仍待执行。
- 2026-10-28 负责人要求停用环境变量配置。应用在 ConfigData 和 `SPRING_APPLICATION_JSON` 处理前移除 Spring 的系统环境属性来源，正式启动与 `--check-config` 使用相同规则。环境变量不参与文件搜索、profile 选择、参数绑定、`@Value` 或占位符解析；宿主进程及 Git 子进程仍保留正常操作系统环境。
- 应用运行值直接填写外置文件，参数缺省采用类型化配置默认值。为保留现有显式启动入口，命令行参数与 Java `-D` 系统属性仍有效，优先级为命令行 > Java 系统属性 > 配置文件 > 默认值；标准部署不追加业务覆盖。显式 `spring.config.location` 替换默认文件搜索位置，不能再通过 `SPRING_CONFIG_LOCATION` 环境变量指定文件。
- 所有有效自有参数在数据库、任务调度和外部请求组件构造前校验。YAML／properties／命令行中的未知自有键、错误类型及非法组合仍拒绝启动，不再扫描宿主环境变量或维护名称豁免表。
- 数据库值、`github.token`、`git.gitea.token` 与 `git.gitea.work-dir` 直接填写实际 YAML。原有 `${DB_URL}`、`${GITHUB_TOKEN:}` 等环境引用须在升级前改为实际值；带默认值的占位符不会再取得环境值。部署目录 `.env` 仅供脚本／Compose 管理容器与数据库初始化，不作为应用配置来源。
- 重启后的缓存、线程池和 HTTP 客户端使用新进程参数；新建 Agent 冻结执行预算与工具约束。已打开的前端页面须刷新；遗留任务／报告仍按现有中断收敛，不断点续跑。
- 模型网关、模型窗口、项目与提示词继续由数据库和现有界面维护；运行文件不覆盖业务记录。本轮无数据库迁移。

## 2. 全量键表

表中默认值为代码缺省值，不含本机实际凭据。除注明范围外，数字须为有限正数；时间与数量均使用表中单位。敏感字段不进入公开接口或任务运行配置。源码入口按配置组提供。

### `agent.datasource`

源码：[AgentDataSourceProperties.java](../../../../backend/src/main/java/com/codereview/agent/db/AgentDataSourceProperties.java)。

| 键名 | 类型／单位 | 默认值 | 约束 | 功能 | 敏感 |
| --- | --- | --- | --- | --- | --- |
| `agent.datasource.url` | String／— | 必填（运行时提供） | 非空字符串 | 唯一运行数据库的 JDBC 连接地址与连接选项 | 否 |
| `agent.datasource.username` | String／— | 必填（运行时提供） | 非空字符串 | 数据库账号 | 否 |
| `agent.datasource.password` | String／— | 必填（运行时提供） | 字符串，可为空；凭据按实际接入要求填写 | 数据库密码 | 是 |
| `agent.datasource.driver-class-name` | String／— | "com.mysql.cj.jdbc.Driver" | 内部固定项，沿用当前默认值 | 数据库驱动类名 | 否 |
| `agent.datasource.expected-database` | String／— | "ai_code_review_agent" | 非空字符串 | 核对实际库名，防止误连 | 否 |
| `agent.datasource.migration-location` | String／— | "classpath:db/agent-migration" | 内部固定项，沿用当前默认值 | 迁移脚本位置（内部固定项） | 否 |
| `agent.datasource.history-table` | String／— | "agent_schema_history" | 内部固定项，沿用当前默认值 | 迁移历史表（内部固定项） | 否 |
| `agent.datasource.maximum-pool-size` | int／— | 10 | 正整数 | 连接池最大连接数 | 否 |
| `agent.datasource.minimum-idle` | int／— | 1 | 非负整数 | 最少保留的空闲连接数 | 否 |
| `agent.datasource.connection-timeout-millis` | long／毫秒 | 10000 | 至少 250 | 等待取得数据库连接的时限 | 否 |

### `git`

源码：[GitProperties.java](../../../../backend/src/main/java/com/codereview/git/GitProperties.java)。

| 键名 | 类型／单位 | 默认值 | 约束 | 功能 | 敏感 |
| --- | --- | --- | --- | --- | --- |
| `git.api-base` | String／— | "https://api.github.com" | HTTP(S) 地址；Gitea 可为空 | 宿主 API 根地址；Gitea 可空并从项目地址推导 | 否 |
| `git.raw-base` | String／— | "https://raw.githubusercontent.com" | 非空字符串 | GitHub 原始文件请求根地址 | 否 |

### `git.http`

源码：[GitHttpProperties.java](../../../../backend/src/main/java/com/codereview/git/GitHttpProperties.java)。

| 键名 | 类型／单位 | 默认值 | 约束 | 功能 | 敏感 |
| --- | --- | --- | --- | --- | --- |
| `git.http.connect-timeout-seconds` | int／秒 | 10 | 正整数 | 建立 HTTP 连接的最长等待 | 否 |
| `git.http.read-timeout-seconds` | int／秒 | 60 | 正整数 | 读取宿主 HTTP 响应的最长等待 | 否 |

### `git.github`

源码：[GitHubHostProperties.java](../../../../backend/src/main/java/com/codereview/git/GitHubHostProperties.java)。

| 键名 | 类型／单位 | 默认值 | 约束 | 功能 | 敏感 |
| --- | --- | --- | --- | --- | --- |
| `git.github.hosts` | `Set<String>`／— | ["github.com"] | host[:port] 集合；不与另一客户端重复 | 由该客户端处理的仓库 host[:port] 集合 | 否 |

### `git.gitea`

源码：[GiteaProperties.java](../../../../backend/src/main/java/com/codereview/git/GiteaProperties.java)。

| 键名 | 类型／单位 | 默认值 | 约束 | 功能 | 敏感 |
| --- | --- | --- | --- | --- | --- |
| `git.gitea.hosts` | `List<String>`／— | ["localhost:3000", "192.104.224.172"] | host[:port] 集合；不与另一客户端重复 | 由该客户端处理的仓库 host[:port] 集合 | 否 |
| `git.gitea.api-base` | String／— | 空字符串 | HTTP(S) 地址；Gitea 可为空 | 宿主 API 根地址；Gitea 可空并从项目地址推导 | 否 |
| `git.gitea.token` | String／— | 空字符串 | 字符串，可为空；凭据按实际接入要求填写 | 项目没有独立凭据时使用的兜底令牌 | 是 |
| `git.gitea.work-dir` | String／— | 空字符串 | 字符串，可为空；凭据按实际接入要求填写 | 持久化 Git bare 镜像的目录；空值使用临时目录 | 否 |
| `git.gitea.local-clone-enabled` | boolean／— | true | 布尔值 | 启用本地镜像实现 Gitea 比较 | 否 |
| `git.gitea.mirror-timeout-seconds` | int／秒 | 120 | 正整数 | 克隆／拉取镜像的最长等待 | 否 |
| `git.gitea.diff-max-bytes` | long／字节 | 5242880 | 正整数 | 单提交差异上限，超限不保留半截 patch | 否 |

### `github`

源码：[GitHubProperties.java](../../../../backend/src/main/java/com/codereview/git/GitHubProperties.java)。

| 键名 | 类型／单位 | 默认值 | 约束 | 功能 | 敏感 |
| --- | --- | --- | --- | --- | --- |
| `github.token` | String／— | 空字符串 | 字符串，可为空；凭据按实际接入要求填写 | 项目没有独立凭据时使用的兜底令牌 | 是 |

### `agent.tools`

源码：[ToolProperties.java](../../../../backend/src/main/java/com/codereview/agent/tools/ToolProperties.java)。

| 键名 | 类型／单位 | 默认值 | 约束 | 功能 | 敏感 |
| --- | --- | --- | --- | --- | --- |
| `agent.tools.git-executable` | String／— | "git" | 非空字符串 | 内容搜索调用的 Git 程序路径 | 否 |
| `agent.tools.mirror-timeout-seconds` | int／秒 | 120 | 正整数 | 克隆／拉取镜像的最长等待 | 否 |
| `agent.tools.search-total-seconds` | int／秒 | 10 | 正整数 | 搜索操作总时限；当前实现包含镜像准备，之后核算剩余时间 | 否 |
| `agent.tools.search-global-concurrency` | int／— | 2 | 正整数 | 全局搜索同时执行数 | 否 |
| `agent.tools.search-cache-weight-bytes` | long／字节 | 33554432 | 正整数 | 搜索结果缓存最大权重 | 否 |
| `agent.tools.search-cache-minutes` | int／分钟 | 5 | 正整数 | 搜索结果缓存过期时间 | 否 |

### `agent.tools.limits`

源码：[ToolLimitProperties.java](../../../../backend/src/main/java/com/codereview/agent/tools/ToolLimitProperties.java)。

| 键名 | 类型／单位 | 默认值 | 约束 | 功能 | 敏感 |
| --- | --- | --- | --- | --- | --- |
| `agent.tools.limits.envelope-max-bytes` | int／字节 | 32768 | 4096–1048576 | 单次工具结果 JSON 的 UTF-8 大小上限 | 否 |
| `agent.tools.limits.material-max-bytes` | int／字节 | 24576 | 至少 256，且 ≤ envelope 上限 − 2048 | 单条可引用材料正文的 UTF-8 大小上限 | 否 |
| `agent.tools.limits.tree-default-depth` | int／— | 2 | 正整数，且 ≤ 对应最大值 | 省略 depth 时的目录展开深度 | 否 |
| `agent.tools.limits.tree-max-depth` | int／— | 3 | 正整数 | 允许请求的最大目录深度 | 否 |
| `agent.tools.limits.tree-default-limit` | int／— | 100 | 正整数，且 ≤ 对应最大值 | 省略 limit 时的目录条目数 | 否 |
| `agent.tools.limits.tree-max-limit` | int／— | 200 | 正整数 | 单次最大目录条目数 | 否 |
| `agent.tools.limits.find-default-limit` | int／— | 50 | 正整数，且 ≤ 对应最大值 | 省略 limit 时的文件查找条目数 | 否 |
| `agent.tools.limits.find-max-limit` | int／— | 100 | 正整数 | 单次文件查找条目上限 | 否 |
| `agent.tools.limits.search-default-limit` | int／— | 50 | 正整数，且 ≤ 对应最大值 | 省略 limit 时的搜索命中数 | 否 |
| `agent.tools.limits.search-max-limit` | int／— | 100 | 正整数 | 单次搜索命中数上限 | 否 |
| `agent.tools.limits.search-context-lines` | int／— | 2 | 非负整数 | 每个命中前后各补充的上下文行数 | 否 |
| `agent.tools.limits.search-grep-max-hits` | int／— | 200 | 正整数 | 一次 grep 最多保留的原始命中数 | 否 |
| `agent.tools.limits.read-default-lines` | int／— | 200 | 正整数，且 ≤ 对应最大值 | 未指定 end 时读取的行数 | 否 |
| `agent.tools.limits.read-max-lines` | int／— | 400 | 1–1000000，且 ≥ 默认行数 | 单次代码读取行数上限 | 否 |
| `agent.tools.limits.diff-max-items` | int／— | 20 | 正整数 | 单次差异清单的最大条目数 | 否 |
| `agent.tools.limits.diff-max-hunks` | int／— | 10 | 正整数 | 单次差异正文的最大块数 | 否 |
| `agent.tools.limits.grep-output-max-bytes` | int／字节 | 2097152 | 正整数 | Git 搜索子进程原始输出上限 | 否 |

### `agent.repository`

源码：[RepositoryProperties.java](../../../../backend/src/main/java/com/codereview/agent/repository/RepositoryProperties.java)。

| 键名 | 类型／单位 | 默认值 | 约束 | 功能 | 敏感 |
| --- | --- | --- | --- | --- | --- |
| `agent.repository.max-file-bytes` | int／字节 | 4194304 | 正整数 | 单个文件完整读取体积上限，超过直接拒绝 | 否 |
| `agent.repository.file-cache-weight-bytes` | long／字节 | 134217728 | 正整数 | 固定版本文件内容缓存容量 | 否 |
| `agent.repository.file-cache-minutes` | int／分钟 | 30 | 正整数 | 固定版本文件内容缓存过期时间 | 否 |
| `agent.repository.tree-cache-size` | int／— | 256 | 正整数 | 固定版本路径清单缓存条数 | 否 |
| `agent.repository.tree-cache-minutes` | int／分钟 | 10 | 正整数 | 路径清单缓存过期时间 | 否 |
| `agent.repository.allowed-extensions` | `Set<String>`／— | 当前文本白名单 | 非空小写后缀集合，不含点 | 可进入专项审查的文件后缀集合（小写、不含点） | 否 |

### `agent.model`

源码：[ModelInvocationProperties.java](../../../../backend/src/main/java/com/codereview/agent/invocation/ModelInvocationProperties.java)。

| 键名 | 类型／单位 | 默认值 | 约束 | 功能 | 敏感 |
| --- | --- | --- | --- | --- | --- |
| `agent.model.connect-timeout-seconds` | int／秒 | 10 | 正整数 | 建立 HTTP 连接的最长等待 | 否 |
| `agent.model.probe-timeout-seconds` | int／秒 | 15 | 正整数 | 轻量 ModelProbe 响应等待；不控制管理界面的公共调用连通验证 | 否 |
| `agent.model.probe-connect-timeout-seconds` | int／秒 | 5 | 正整数 | 模型连通探测建连等待 | 否 |
| `agent.model.safety-reserve-min-tokens` | int／token | 1024 | 正整数 | 上下文容量判断的最低安全余量 | 否 |
| `agent.model.safety-reserve-ratio` | double／比例 | 0.1 | (0, 1) | 按模型窗口比例计算的安全余量，与最低值取大者 | 否 |
| `agent.model.transport-retries` | int／— | 1 | 0–10 | 暂态失败允许的额外传输重试次数，不含首次；Agent 还受冻结剩余网络次数限制 | 否 |
| `agent.model.retry-base-millis` | long／毫秒 | 500 | 正整数，且 ≤ retry-max-millis | 本地递增等待基数 | 否 |
| `agent.model.retry-max-millis` | long／毫秒 | 30000 | 正整数 | 本地退避等待上限，不截短网关 Retry-After | 否 |
| `agent.model.retry-multiplier` | double／倍数 | 2.0 | 1–10 | 每次失败后的等待增长倍数 | 否 |
| `agent.model.retry-jitter-ratio` | double／比例 | 0.2 | 0–1 | 等待时间的对称随机变化比例，0 表示关闭 | 否 |
| `agent.model.retry-check-millis` | long／毫秒 | 1000 | 正整数 | 等待重试时检查取消和期限的间隔 | 否 |
| `agent.model.connectivity-validation-timeout-seconds` | int／秒 | 60 | 正整数 | 管理界面普通连通验证总期限，包含排队、等待和重试 | 否 |
| `agent.model.tool-validation-timeout-seconds` | int／秒 | 120 | 正整数 | 工具准入每轮总期限，包含排队和重试，共两轮 | 否 |
| `agent.model.tool-validation-output-tokens` | int／token | 1024 | 正整数 | 工具验证输出预留，仍受模型最大输出限制 | 否 |
| `agent.model.max-read-timeout-seconds` | int／秒 | 180 | 正整数 | 正式模型单次响应等待上限 | 否 |
| `agent.model.output-reserve-tokens` | int／token | 4096 | 正整数 | 普通调用默认输出预留，受模型最大输出限制 | 否 |
| `agent.model.validation-output-tokens` | int／token | 64 | 正整数 | 普通模型验证的输出预留 | 否 |
| `agent.model.task-call-limit` | int／— | 200 | 正整数 | 公共单任务真实调用硬上限；Agent 另冻结更小额度 | 否 |
| `agent.model.global-concurrency` | int／— | 4 | 正整数 | 所有任务和报告共用的模型请求并发 | 否 |
| `agent.model.per-task-concurrency` | int／— | 2 | 正整数，且 ≤ 全局并发 | 同一任务的模型请求并发 | 否 |

### `agent.task`

源码：[TaskProperties.java](../../../../backend/src/main/java/com/codereview/agent/execution/TaskProperties.java)。

| 键名 | 类型／单位 | 默认值 | 约束 | 功能 | 敏感 |
| --- | --- | --- | --- | --- | --- |
| `agent.task.dispatch-queue-capacity` | int／— | 64 | 正整数 | 等待执行的任务／报告队列容量 | 否 |
| `agent.task.deadline-minutes` | int／分钟 | 30 | 正整数 | 任务总期限；agent.task 用于专项，agent.budget 用于 Agent | 否 |
| `agent.task.dispatch-concurrency` | int／— | 4 | 正整数 | 同时执行的任务／报告数 | 否 |
| `agent.task.recover-on-start` | boolean／— | true | 布尔值 | 启动时将遗留任务标为中断；不续跑 | 否 |
| `agent.task.sweep-millis` | long／毫秒 | 30000 | 正整数 | 后台扫描超时任务的间隔 | 否 |

### `agent.specialist`

源码：[SpecialistProperties.java](../../../../backend/src/main/java/com/codereview/agent/specialist/SpecialistProperties.java)。

| 键名 | 类型／单位 | 默认值 | 约束 | 功能 | 敏感 |
| --- | --- | --- | --- | --- | --- |
| `agent.specialist.max-inheritance-depth` | int／— | 6 | 正整数 | 辅助父类型查找深度 | 否 |
| `agent.specialist.max-auxiliary-files` | int／— | 12 | 正整数 | 每目标辅助文件数量上限（失败候选也占次数） | 否 |
| `agent.specialist.max-auxiliary-bytes` | int／字节 | 262144 | 正整数 | 每目标辅助文件累计读取体积上限 | 否 |
| `agent.specialist.include-direct-declarations` | boolean／— | true | 布尔值 | 补充字段、参数、返回类型的一层声明 | 否 |
| `agent.specialist.max-units-per-file` | int／— | 32 | 正整数 | 每文件最多拆分的执行单元数 | 否 |
| `agent.specialist.max-block-lines` | int／— | 200 | 正整数 | 按行拆分时每个块的最大行数 | 否 |

### `agent.specialist.execution`

源码：[SpecialistExecutionProperties.java](../../../../backend/src/main/java/com/codereview/agent/execution/SpecialistExecutionProperties.java)。

| 键名 | 类型／单位 | 默认值 | 约束 | 功能 | 敏感 |
| --- | --- | --- | --- | --- | --- |
| `agent.specialist.execution.parallel-targets` | int／— | 4 | 正整数 | 同一专项任务并行处理的目标文件数 | 否 |

### `agent.report`

源码：[ReportGenerationProperties.java](../../../../backend/src/main/java/com/codereview/agent/report/ReportGenerationProperties.java)。

| 键名 | 类型／单位 | 默认值 | 约束 | 功能 | 敏感 |
| --- | --- | --- | --- | --- | --- |
| `agent.report.max-body-chars` | int／字符 | 1000000 | 正整数 | 报告正文可保存的字符上限 | 否 |
| `agent.report.generation-timeout-minutes` | int／分钟 | 10 | 正整数 | 单次报告生成总期限 | 否 |
| `agent.report.dispatch-concurrency` | int／— | 2 | 正整数 | 同时执行的任务／报告数 | 否 |
| `agent.report.dispatch-queue-capacity` | int／— | 100 | 正整数 | 等待执行的任务／报告队列容量 | 否 |
| `agent.report.output-tokens` | int／token | 16384 | 正整数 | Agent 每轮／报告生成的输出预留，受模型最大输出限制 | 否 |

### `agent.budget`

源码：[AgentBudgetProperties.java](../../../../backend/src/main/java/com/codereview/agent/execution/AgentBudgetProperties.java)。

| 键名 | 类型／单位 | 默认值 | 约束 | 功能 | 敏感 |
| --- | --- | --- | --- | --- | --- |
| `agent.budget.model-turns` | int／轮 | 48 | 正整数 | 单任务最大模型轮次 | 否 |
| `agent.budget.network-attempts` | int／— | 96 | 正整数，且 ≤ 公共调用限额 | 单任务真实网络尝试（含失败和重试）总上限 | 否 |
| `agent.budget.tool-attempts` | int／— | 192 | 正整数 | 单任务工具尝试总上限，错误参数也计数 | 否 |
| `agent.budget.deadline-minutes` | int／分钟 | 20 | 正整数 | 任务总期限；agent.task 用于专项，agent.budget 用于 Agent | 否 |
| `agent.budget.output-tokens` | int／token | 16384 | 正整数 | Agent 每轮／报告生成的输出预留，受模型最大输出限制 | 否 |
| `agent.budget.model-timeout-seconds` | int／秒 | 120 | 正整数 | Agent 每次模型响应等待上限 | 否 |
| `agent.budget.context-stop-ratio` | double／比例 | 0.8 | (0, 1] | Agent 完整输入估算占模型窗口的停止比例 | 否 |
| `agent.budget.no-progress-turns` | int／轮 | 3 | 至少 2 | 相同调用与结果且无新材料的连续轮数上限 | 否 |
| `agent.budget.max-tools-per-turn` | int／— | 4 | 正整数 | 单轮最多的工具数量，提交仍须单独调用 | 否 |
| `agent.budget.max-protocol-errors` | int／— | 2 | 0–2 | Agent 无效输出／提交的错误容忍次数 | 否 |

### `app.ui`

源码：[UiProperties.java](../../../../backend/src/main/java/com/codereview/agent/config/UiProperties.java)。

| 键名 | 类型／单位 | 默认值 | 约束 | 功能 | 敏感 |
| --- | --- | --- | --- | --- | --- |
| `app.ui.title` | String／— | "智能代码分析工具" | 1–100 字符 | 页面及侧栏的应用名称 | 否 |
| `app.ui.api-base` | String／— | "api/v2" | 同源路径；不含主机、查询参数或跳转 | 宿主 API 根地址；Gitea 可空并从项目地址推导 | 否 |
| `app.ui.request-timeout-millis` | int／毫秒 | 30000 | 正整数 | 普通浏览器业务请求响应超时 | 否 |
| `app.ui.model-validation-timeout-millis` | int／毫秒 | 180000 | 大于后端连通验证总期限的毫秒值 | 浏览器普通模型验证等待，需预留传输和保存时间 | 否 |
| `app.ui.agent-validation-timeout-millis` | int／毫秒 | 300000 | 大于两轮工具验证期限之和的毫秒值 | 浏览器 Agent 工具验证等待，需预留传输和保存时间 | 否 |
| `app.ui.agent-poll-millis` | int／毫秒 | 2000 | 正整数 | Agent 详情进度轮询间隔 | 否 |
| `app.ui.report-poll-millis` | int／毫秒 | 2000 | 正整数 | 报告详情轮询间隔 | 否 |
| `app.ui.specialist-poll-millis` | int／毫秒 | 3000 | 正整数 | 专项详情轮询间隔 | 否 |

### `git.cache` 与服务端端口

| 键名 | 默认值 | 约束／功能 |
| --- | --- | --- |
| `git.cache.commit-detail-max-size` | 200 条 | 正整数；共用 Git 提交详情缓存 |
| `git.cache.raw-file-max-size` | 500 条 | 正整数；共用 Git 原始文件缓存 |
| `git.cache.expire-minutes` | 30 分钟 | 正整数；缓存过期时间 |
| `server.port` | 样例 8080 | 整数 0–65535；0 仅作动态端口测试；须与容器内部端口及健康检查配套（CFG-06） |

`agent.tools.mirror-timeout-seconds` 是搜索准备镜像时允许的等待；共享 Git 镜像自身还受 `git.gitea.mirror-timeout-seconds` 约束，实际取更短者。当前搜索总时限也包含镜像准备，冷镜像耗时可能用完 10 秒总预算；内网部署须按冷镜像测试调整，而不是只增大镜像超时。

`agent.task.dispatch-queue-capacity=64` 是本期新增的有界默认值，按默认 4 个执行槽保留 16 批等待任务，避免无限积压；这只是初始资源保护值，尚无内网吞吐实测。队列满时任务和未执行目标明确失败，不永久停在排队。报告队列仍独立使用 100。

## 3. Agent 快照与工具指纹

- 新 Agent `configSnapshot.version=3`；输入快照保持 version 2，业务输入结构不变。
- `limits` 保存轮次、网络／工具次数、期限、输出、上下文比例、无进展、每轮工具数及协议错误容忍。`toolLimits` 保存工具限制；`invocationPolicy` 保存重试、读超时和安全余量；新增 `retryBackoff` 节点冻结最大本地等待、增长倍数、随机比例和取消检查间隔。旧节点缺少 `retryBackoff` 时使用历史固定等待、不添加随机变化，不读取新进程配置。
- 执行循环、工具 schema、参数校验、工具分页与返回大小、进度读取使用同一份冻结配置。连接、缓存、搜索槽位等属于进程资源参数。
- Agent 数据库调用额度直接冻结为 `networkAttempts`，公共配置只作为启动上限校验；每次真实发送继续原子计数。最后一次可用尝试关闭额外重试，不提前丢弃该机会。
- `limits.maxEstimatedTokenUnits = networkAttempts × (floor(contextWindow × contextStopRatio) + min(outputTokens, model.maxOutput))` 仅为推导资源规模诊断，不能当作已消耗 token。核对正式实现后确认，现有正式额度是请求次数；本期不把实验工具桥的累计 token 保险冒充正式运行能力，也不额外引入货币限额。
- 工具版本采用 `agent-tools-v2-<配置摘要>`；模型 optionsHash 绑定模型修订、适配器、工具指纹、每轮工具数及协议错误容忍。更改这些行为配置后须重新进行模型 Agent 验证，列表显示 EXPIRED。缓存和进程资源变更不影响工具指纹。
- 老快照沿用其中已有的 limits；缺失的新增字段使用历史固定默认值，不读取新部署值。旧快照的工具缺省值为 32 KiB／24 KiB、200／400 行等历史值。version 3 缺失整个配置节点、非法节点或未知未来版本明确失败，不静默换成新部署配置。
- 创建 Agent 前检查模型窗口能否容纳输出预留与安全余量；每次发送仍检查完整输入容量。引用真实性、材料行号与固定版本校验不配置化。

## 4. 前端公开配置协议

`GET /api/v2/runtime-config` 返回 `Cache-Control: no-store`，正文只有 `version`、`title`、`apiBase`、`requestTimeoutMillis`、`agentPollMillis`、`reportPollMillis`、`specialistPollMillis`、`modelValidationTimeoutMillis`、`agentValidationTimeoutMillis` 九个字段，version 为 1。验证接口使用独立等待时间；读取旧服务器七字段响应时回退到其中的普通请求超时，新字段存在但非法时拒绝加载。不返回任意配置对象、数据库连接、仓库地址或凭据。

页面先通过 `document.baseURI` 相对地址 `api/v2/runtime-config` 加载，再动态导入业务应用、创建 API 客户端和启动轮询。公开业务基址使用同源路径，默认 `api/v2`；引导地址不依赖该字段，也不依赖 VITE 环境变量。静态资源使用相对构建基址，为同源子路径保留部署兼容；反向代理路径改写仍需 CFG-06／07 验证。

读取超时、版本不兼容、字段错误或服务不可用时显示已确认的启动失败原型，业务应用不启动，允许重新加载。浏览器每次刷新重新读取配置；不热更新已打开页面。界面原型已由负责人于本会话确认。

## 5. 独立配置检查入口

完成 CFG-06 打包后，应用支持：

```sh
java -jar app.jar --check-config --spring.config.location=file:/app/config/application.yml
```

此入口复用 Spring ConfigData 与覆盖规则，只构建参数校验上下文，不扫描业务组件、不创建数据库池、不执行 Flyway、不启动 HTTP 服务、不访问模型和仓库。通过仅说明文件可读取、参数可绑定和静态组合合法；不证明凭据、网络、库身份或模型窗口可用。缺少必要文件、缺少数据库 URL 或参数错误以失败退出。

源码级检查也会覆盖同一入口。正式启动自动执行相同静态校验。错误只报告键名和约束，不输出凭据值；通过后输出模型并发、Agent 预算、工具指纹、文件上限及前端轮询的脱敏摘要。敏感值仍应通过文件权限与环境管理保护。

## 6. 配置样例与后续边界

[独立样例](application.yml.example) 可作为参数参考，数据库使用示例地址、账号与 `CHANGE_ME` 密码，部署前填写真实值，不将真实凭据提交到版本库。容器[正式模板](../../../../deploy/config/application.yml.example)由部署脚本首次渲染数据库占位符，令牌及持久化镜像目录直接写在 YAML。模板不覆盖已有正式文件；升级时先手工替换旧环境引用。目标内网端口／挂载、镜像排密及完整部署演练仍需按 CFG-06／07 核对。

内网真实重启、冷镜像、三类业务实际模型调用及回退验收仍属于 CFG-07。本期工程检查和模拟服务结果不能替代这些验收。
