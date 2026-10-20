# P30.1–P30.4 审查问题修复与复验

状态：11 项全部修复并复验（2026-10-20，Asia/Taipei）。被审查 HEAD `2589c55`，审查记录见 [P30.1–P30.4 成果审查](p30-1-4-review.md)。修复后全量 `mvn -o test` 为 **404 项、0 失败、9 跳过**；另有建库脚本回归 9 项通过。

本文只记录修复与复验，不改写原审查记录的结论；原记录中的「待修复」由本文逐项关闭。

## 1. 逐项修复

| 编号 | 原判定 | 处理 | 证据 |
| --- | --- | --- | --- |
| R01 | P1 底层缓存绕过项目及凭据隔离 | 缓存键改为 `host/owner/repo#凭据指纹@ref`，宿主与凭据指纹都参与；凭据只以 SHA-256 前缀出现 | `GitCache.key`、`GitCacheTest.differentCredentialsNeverShareRawFileEntries`、`keyScopesByHostRepositoryAndCredential` |
| R02 | P1 credential_ref 保存原始凭据 | **负责人决定沿用既有明文层**：字段改名为 `credential`，文档如实描述"明文、只写不读、不进入日志/快照/响应"，不再声称是引用；不引入加密或密钥服务 | `V1__agent_init.sql`、P30.1 §6.7、P30.2 收敛表；视图只给 `credentialConfigured`（`AgentModelServiceTest.credentialIsNeverPartOfTheView`） |
| R03 | P1 建库脚本会执行固定占位密码 | `-Execute` 必须提供 `-AppPassword`，占位值（`<...>`、`运行时密码`、`password`）一律拒绝；口令经 stdin 交给 mysql，打印路径始终脱敏 | `scripts/agent-db-init.ps1`、`scripts/tests/agent-db-scripts.tests.ps1`（9 项通过） |
| R04 | P2 截断目录被标为完整 | `GitHostClient.treeWithMeta` 带出宿主截断（GitHub 读 `truncated`，Gitea 翻页到上限标记）；`PathList.truncated` 原样返回；提交清单截断时差异返回 `SNAPSHOT_INCOMPLETE` 而不是 `NOT_FOUND` | `GitTree`、`GitHubClientTest.treeCarriesHostTruncationInsteadOfDroppingIt`、`RepositoryReadServiceTest.truncatedCommitListNeverClaimsAFileIsAbsent` |
| R05 | P2 行范围丢失开头空行 | 按行索引拼接，首行空行保留 | `RepositoryReadServiceTest.readsAnInclusiveLineRangeAndKeepsLeadingBlankLines` |
| R06 | P2 用旧路径读取重命名差异会失败 | after 用变更项规范新路径，before 用旧路径；返回的 `path` 固定为新路径、`oldPath` 保留旧路径 | `RepositoryReadServiceTest.renameReadsAfterFromTheNewPathAndBeforeFromTheOldPath` |
| R07 | P2 修改后侧缺失标记不准确 | 两侧改为 `Side{status,reason}`：PRESENT/MISSING/UNREADABLE，原因区分 BINARY、TOO_LARGE、READ_FAILED、ADDED、REMOVED、ROOT_COMMIT；任一侧失败不影响另一侧 | `RepositorySnapshots.Side`、`unreadableAfterSideKeepsItsReasonAndTheBeforeSide`、`removedFileHasAfterSideMarkedMissing` |
| R08 | P2 请求格式错误被返回为 500 | 补齐缺查询参数、类型不匹配、请求体不可解析的 400 映射，错误体保持 `VALIDATION_FAILED` | `V2ExceptionHandler`、`V2BindingErrorTest`（2 项） |
| R09 | P2 仓库 HTTP 错误分类丢失 | 新增 `HostHttpException` 保留状态码；客户端统一包装 4xx/5xx 与传输失败；读取层映射 404→`NOT_FOUND`、401/403→`REPO_UNAUTHORIZED`、429→`RATE_LIMITED`、超时→`UPSTREAM_TIMEOUT`、5xx→`REPO_UNAVAILABLE`（可重试） | `HostHttpException`、`GitHubClientTest.hostHttpStatusIsPreservedForTheReadLayerToClassify`、`RepositoryReadServiceTest.hostHttpStatusesAreMappedToTheirOwnErrors` |
| R10 | P2 提示词描述及标签无法清空 | 更新改用 `UpdateWrapper.set(...)` 显式写 NULL；模型的 tokenizer、窗口、输出同为可空字段，一并处理 | `AgentPromptService.update`、`AgentModelService.update`、对应「清空字段」用例 |
| R11 | P2 非 JSON 响应也能通过模型验证 | 真解析 JSON，要求对象且 `choices` 为非空数组；普通文本不再通过 | `HttpModelProbe.checkJson`、`HttpModelProbeTest`（4 项） |

## 2. 复验范围

| 层次 | 状态 | 说明 |
| --- | --- | --- |
| 全量后端回归 | 通过 | 404 项，0 失败，9 跳过（跳过为环境条件，非失败） |
| 新增／改写的边界用例 | 通过 | 仓库读取 17 项、缓存键 2 项、GitHub HTTP 2 项、绑定错误 2 项、探针 4 项、可空字段 2 项 |
| 建库脚本 | 通过 | 9 项：脱敏、拒绝占位、拒绝缺口令、真实口令下发、旧库名拦截 |
| 真实数据库、宿主与模型 | 未执行 | 仍按 P30.8、P31.9、P32.6 安排；本组修复没有建立真实环境验证 |

## 3. 与原审查预期的差异

- R02：原审查用例要求"必须保存引用"。负责人明确选择沿用既有明文层，因此该断言不再适用；修复改为让字段名和文档如实反映明文存储，并把"不回显、不写日志、不进快照"作为可验证边界。
- R08 的 405 分支：请求方法不支持属于映射阶段异常，`@RestControllerAdvice` 不会进入；本次只处理能进入控制器的绑定错误，不伪造 405 契约。

## 4. 未消除的风险

- 凭据为明文存储，安全性依赖数据库访问控制；这是负责人已知并接受的风险（见 P30.1 §6.7）。
- 缓存键中的凭据指纹用于隔离，不用于鉴权；凭据泄露风险仍由存储层承担。
- 全量回归没有真实宿主，HTTP 分支由本地替身与桩验证。
