# P30.1–P30.4 审查问题修复与复验

状态：R01、R07、R09 的二次复验遗漏已补齐（2026-10-20，Asia/Taipei）。原审查 HEAD 为 `2589c55`，首轮修复记录提交为 `630cd84`，审查记录见 [P30.1–P30.4 成果审查](p30-1-4-review.md)。本轮定向检查为 **55 项、0 失败、0 跳过**，全量后端回归为 **409 项、0 失败、7 跳过**。R02 按负责人决定接受明文存储风险，其余 10 项已修复并通过本地工程复验。

本文只记录修复与复验，不改写原审查记录。首轮曾记录「11 项全部修复」，二次复验发现 R01、R07、R09 尚有遗漏；本轮补修及关闭证据见第 5 节。真实资源验证仍未执行。

## 1. 逐项修复

| 编号 | 原判定 | 处理 | 证据 |
| --- | --- | --- | --- |
| R01 | P1 底层缓存绕过项目及凭据隔离 | 缓存键为 `siteRoot/owner/repo#凭据指纹@ref`；站点根保留协议、宿主及子路径，凭据只以 SHA-256 前缀出现；SSH 地址没有站点根时使用宿主 | `GitCacheTest.keySeparatesSiteRootsAndSchemesOnTheSameHost`、`GiteaReadBoundaryTest.sameHostDifferentSiteRootsDoNotSharePrivateFileCache`，以及既有凭据隔离用例 |
| R02 | P1 credential_ref 保存原始凭据 | **负责人决定沿用既有明文层**：字段改名为 `credential`，文档如实描述"明文、只写不读、不进入日志/快照/响应"，不再声称是引用；不引入加密或密钥服务 | `V1__agent_init.sql`、P30.1 §6.7、P30.2 收敛表；视图只给 `credentialConfigured`（`AgentModelServiceTest.credentialIsNeverPartOfTheView`） |
| R03 | P1 建库脚本会执行固定占位密码 | `-Execute` 必须提供 `-AppPassword`，占位值（`<...>`、`运行时密码`、`password`）一律拒绝；口令经 stdin 交给 mysql，打印路径始终脱敏 | `scripts/agent-db-init.ps1`、`scripts/tests/agent-db-scripts.tests.ps1`（9 项通过） |
| R04 | P2 截断目录被标为完整 | `GitHostClient.treeWithMeta` 带出宿主截断（GitHub 读 `truncated`，Gitea 翻页到上限标记）；`PathList.truncated` 原样返回；提交清单截断时差异返回 `SNAPSHOT_INCOMPLETE` 而不是 `NOT_FOUND` | `GitTree`、`GitHubClientTest.treeCarriesHostTruncationInsteadOfDroppingIt`、`RepositoryReadServiceTest.truncatedCommitListNeverClaimsAFileIsAbsent` |
| R05 | P2 行范围丢失开头空行 | 按行索引拼接，首行空行保留 | `RepositoryReadServiceTest.readsAnInclusiveLineRangeAndKeepsLeadingBlankLines` |
| R06 | P2 用旧路径读取重命名差异会失败 | after 用变更项规范新路径，before 用旧路径；返回的 `path` 固定为新路径、`oldPath` 保留旧路径 | `RepositoryReadServiceTest.renameReadsAfterFromTheNewPathAndBeforeFromTheOldPath` |
| R07 | P2 修改后侧缺失标记不准确 | 两侧返回 `Side{status,reason}`；二进制、超限及宿主读取错误分别标为 UNREADABLE，后者保留错误码；before 或 after 的宿主错误不影响另一侧，失败后可以重新读取；入口参数等错误仍抛出 | `RepositoryReadServiceTest.hostFailureOnBeforeSideKeepsAfterAndAllowsRereading`、`hostFailureOnAfterSideKeepsBeforeAndAllowsRereading`，以及既有二进制、超限及缺失用例 |
| R08 | P2 请求格式错误被返回为 500 | 补齐缺查询参数、类型不匹配、请求体不可解析的 400 映射，错误体保持 `VALIDATION_FAILED` | `V2ExceptionHandler`、`V2BindingErrorTest`（2 项） |
| R09 | P2 仓库 HTTP 错误分类丢失 | `HostHttpException` 保留状态码；读取层映射 404→`NOT_FOUND`、401/403→`REPO_UNAUTHORIZED`、429→`RATE_LIMITED`、超时→`UPSTREAM_TIMEOUT`、5xx→`REPO_UNAVAILABLE`；Gitea `.diff` 请求保留该异常类型及重试信息，不再被通用包装吞掉 | `GiteaReadBoundaryTest.diffEndpointFailuresKeepTheirClassificationAndAreNotCached`、`GiteaClientTest.commitDetailFailsLoudlyWhenDiffEndpointFails`，以及既有宿主状态映射用例 |
| R10 | P2 提示词描述及标签无法清空 | 更新改用 `UpdateWrapper.set(...)` 显式写 NULL；模型的 tokenizer、窗口、输出同为可空字段，一并处理 | `AgentPromptService.update`、`AgentModelService.update`、对应「清空字段」用例 |
| R11 | P2 非 JSON 响应也能通过模型验证 | 真解析 JSON，要求对象且 `choices` 为非空数组；普通文本不再通过 | `HttpModelProbe.checkJson`、`HttpModelProbeTest`（4 项） |

## 2. 复验范围

| 层次 | 状态 | 说明 |
| --- | --- | --- |
| 本轮定向检查 | 通过 | `GitCacheTest` 8 项、`GiteaClientTest` 26 项、`GiteaReadBoundaryTest` 2 项、`RepositoryReadServiceTest` 19 项；合计 55 项，0 失败、0 跳过 |
| 本轮全量后端回归 | 通过 | 409 项，0 失败、0 异常、7 跳过；7 项跳过不计为执行通过 |
| 首轮后端回归 | 历史记录 | 原交付记录为 404 项、0 失败、9 跳过；二次复验实际输出为 404 项、0 失败、7 跳过，保留两次输出差异 |
| 建库脚本 | 既有验证通过 | 二次复验为 9 项：脱敏、拒绝占位、拒绝缺口令、真实口令下发、旧库名拦截；本轮未修改脚本，未重复运行 |
| 真实数据库、宿主与模型 | 未执行 | 仍按 P30.8、P31.9、P32.6 安排；本组修复没有建立真实环境验证 |

## 3. 与原审查预期的差异

- R02：原审查用例要求"必须保存引用"。负责人明确选择沿用既有明文层，因此该断言不再适用；修复改为让字段名和文档如实反映明文存储，并把"不回显、不写日志、不进快照"作为可验证边界。
- R08 的 405 分支：请求方法不支持属于映射阶段异常，`@RestControllerAdvice` 不会进入；本次只处理能进入控制器的绑定错误，不伪造 405 契约。

## 4. 未消除的风险

- 凭据为明文存储，安全性依赖数据库访问控制；这是负责人已知并接受的风险（见 P30.1 §6.7）。
- 缓存键中的凭据指纹用于隔离，不用于鉴权；凭据泄露风险仍由存储层承担。
- 全量回归没有真实宿主，HTTP 分支由本地替身与桩验证。

## 5. 二次复验遗漏与本轮关闭证据

负责人要求修复三处遗漏并整理提交，本轮仅处理 R01、R07、R09，未启动 P30.5 或操作真实数据库。

| 编号 | 二次复验遗漏 | 本轮修复与复验 |
| --- | --- | --- |
| R01 | 同一宿主的不同站点子路径仍共用底层缓存 | 键加入完整站点根；测试覆盖协议及子路径差异，并使用公共读取服务、真实 Gitea 客户端及本地 HTTP 替身，确认站点 A 的成功缓存不能绕过站点 B 的认证失败 |
| R07 | before 或 after 超时仍导致整个差异读取失败 | 对宿主读取错误返回 UNREADABLE，原因使用 `UPSTREAM_TIMEOUT`、`REPO_UNAUTHORIZED`、`RATE_LIMITED`、`REPO_UNAVAILABLE` 或 `REPO_UNREACHABLE`；两个方向分别验证超时、401、403、429、503，并确认错误解除后可重新读取 |
| R09 | Gitea `.diff` 错误被包装为 `IllegalStateException`，丢失分类 | 保留 `HostHttpException`；通过公共读取服务及真实客户端验证 `.diff` 的 404、401、403、429、503 分类，检查 429 的 `retryAfter`、可重试属性及失败不入缓存 |

新增 5 个回归用例先在未修复代码上运行，定向 29 项中出现 3 项断言失败、2 项异常，对应三处遗漏。修复后连同既有 Gitea 客户端用例运行，定向 55 项全部通过；全量 409 项、0 失败、0 异常、7 跳过。

构建使用 JDK 21、Maven 3.9.9，源码及资源从当前工作树读取，测试副本、编译产物和日志放在工作树外的隔离目录。真实数据库、宿主、模型及正式页面验收仍按原 ROADMAP 执行。
