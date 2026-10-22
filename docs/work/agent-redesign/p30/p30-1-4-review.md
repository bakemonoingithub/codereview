# P30.1–P30.4 成果审查记录

审查日期：2026-10-20（Asia/Taipei）。工作树：`H:\myProjects\aiCodeReview\agent`；分支：`refactor/agent`；被审查 HEAD：`2589c557e86c905960510f0b62d4aee5ece728a7`。

## 1. 审查结论

**暂不通过。共发现 11 项待修复问题，其中 P1 为 3 项，P2 为 8 项。建议先修复并复验，再接续 P30.5。**

现有全量回归通过，但新增边界检查复现了缓存隔离、凭据存储、材料完整性、错误分类及配置更新问题。工程回归通过不能替代这些边界的验收。

本文记录本次审查结果，不修改 STATE、ROADMAP 中的阶段状态，不启动后续实施任务。各问题状态均为「待修复、待复验」。

## 2. 范围与依据

审查范围为 DS 交付的 P30.1–P30.4 文档、实现及测试，相关提交如下：

| 子任务 | 提交 | 交付记录 |
| --- | --- | --- |
| P30.1 | `4123463`、`7161e14` | [复用与功能差异](p30-1-reuse-diff.md) |
| P30.2 | `377c695` | [新库结构与访问层](p30-2-schema.md) |
| P30.3 | `397367a` | [配置资源 v2 接口](p30-3-config-api.md) |
| P30.4 | `2589c55` | [固定版本读取与有界缓存](p30-4-fixed-version-read.md) |

审查依据为[业务契约](../business-contract.md)、[成果盘点](../review-inventory.md)、[P29 共用设计](../p29/shared-design.md)及[当前 ROADMAP](../../../ROADMAP.md)。来源修复仅从固定提交 `8f20d6f24b9c22d3309ac1cb7bda1caf20e1bce0` 只读核对。

审查采用静态检查、隔离构建、现有测试复跑、本地 HTTP 替身、MockMvc 和 MyBatis-Plus 生成 SQL 检查。未连接真实仓库、模型或数据库，未执行真实建库、迁移、服务启停及部署。

## 3. 问题清单

### R01 · P1 · 底层缓存绕过项目及凭据隔离

位置：[RepositoryReadService.java](../../../../backend/src/main/java/com/codereview/agent/repository/RepositoryReadService.java) 第 96–97 行；相关底层实现为 [GitHubClient.java](../../../../backend/src/main/java/com/codereview/git/GitHubClient.java) 和 [GiteaClient.java](../../../../backend/src/main/java/com/codereview/git/GiteaClient.java)。

外层文件缓存虽包含项目及项目 revision，两个宿主客户端内部仍按 `owner/repo/SHA/path` 缓存，不含宿主、项目或凭据身份。外层缓存未命中时，底层仍可直接返回旧缓存。

本地 HTTP 替身已复现：第一次使用有效凭据读取成功，随后在测试中更换保存的凭据并增加 revision，再次读取仍返回旧内容，没有重新请求宿主。该用例直接设置项目测试数据，没有经过项目编辑接口的新令牌验证。

影响：新的项目修订隔离不能贯通读取链，凭据变更后的读取仍可复用旧身份缓存；不同宿主的同名仓库也缺少底层缓存身份区分。

修复建议：统一整个读取链的缓存身份及凭据边界，避免旧缓存绕过公共读取服务；补充使用真实客户端和本地 HTTP 替身的隔离回归。

### R02 · P1 · credential_ref 实际保存原始凭据

位置：[AgentModelService.java](../../../../backend/src/main/java/com/codereview/agent/service/AgentModelService.java) 第 72、91 行；[AgentProjectService.java](../../../../backend/src/main/java/com/codereview/agent/service/AgentProjectService.java) 第 68、95 行。

模型及项目服务直接把请求中的原始密钥或令牌写入 `credential_ref`，没有凭据引用或加密维护层。测试捕获的模型实体中，`credential_ref` 与传入的合成密钥完全相同。

影响：实现不符合 P30.1 第 6.7 节「凭据只保存引用」及 P29 共用设计中的凭据维护边界。接口不回显不能替代存储隔离。

修复建议：落实受保护的凭据维护方式，由业务配置保存引用，读取时受控解析；验证数据库字段、快照和错误信息中的凭据边界。

### R03 · P1 · 建库脚本会执行固定占位密码

位置：[agent-db-init.ps1](../../../../scripts/agent-db-init.ps1) 第 34、54 行。

按照文档使用 `-Execute` 时，脚本会直接以字面量 `<运行时密码>` 创建应用账号，没有应用密码参数，也没有拒绝占位符的检查。输出「需要人工填入口令」不会改变随后执行的 SQL。

已使用替身客户端捕获脚本实际传入的 SQL，确认其中仍为固定占位密码，脚本随后报告建库完成；未连接或修改真实数据库。

影响：按交付脚本执行会创建使用已知占位密码的应用账号。

修复建议：执行前要求提供有效的应用账号密码，并拒绝默认占位值；输出 SQL 与直接执行两条路径应分别验证。

### R04 · P2 · 截断目录被标为完整

位置：[RepositoryReadService.java](../../../../backend/src/main/java/com/codereview/agent/repository/RepositoryReadService.java) 第 78–88、133–135 行。

`PathList.truncated` 被固定写为 `false`，底层 `GitHubClient.tree` 又丢弃宿主的 `truncated` 信息。替身返回 `truncated=true` 时，公共接口仍把目录标为完整。

静态检查还发现，`diff` 在未找到文件时直接返回 `NOT_FOUND`，没有检查 `CommitDetail.truncated`，可能把尚未返回的文件判为不存在；这一分支未另加运行用例。

影响：不完整的仓库清单可能被当作完整范围使用，产生漏文件或错误的缺失结论。

修复建议：贯通目录和差异清单的完整性信息，补齐读取，或明确返回不完整状态；不能以缺页数据证明文件不存在。

### R05 · P2 · 行范围读取丢失开头空行

位置：[RepositoryReadService.java](../../../../backend/src/main/java/com/codereview/agent/repository/RepositoryReadService.java) 第 111–115 行。

使用 `text.length()` 判断是否插入换行，会吞掉范围开头的空行。已复现：原文为 `\nalpha\nbeta\n`，读取第 1–2 行，预期 `\nalpha`，实际仅返回 `alpha`。

影响：返回正文与声明的原始行号不一致，后续材料定位和证据阅读可能错位。

修复建议：按行索引连接内容，并覆盖开头为空行、连续空行及全空行的范围。

### R06 · P2 · 用旧路径读取重命名差异会失败

位置：[RepositoryReadService.java](../../../../backend/src/main/java/com/codereview/agent/repository/RepositoryReadService.java) 第 143 行。

`findChanged` 允许通过 `previousPath` 匹配变更项，但读取 after 时仍使用请求传入的 `safePath`。请求重命名前的 `Old.java` 时，会去 head 读取已经不存在的旧路径，而非变更项中的 `New.java`。

本地用例已复现读取异常。

修复建议：使用变更项的规范新路径读取 after，使用旧路径读取 before，并保持返回路径身份一致。

### R07 · P2 · 修改后侧缺失标记不准确

位置：[RepositoryReadService.java](../../../../backend/src/main/java/com/codereview/agent/repository/RepositoryReadService.java) 第 143–150、168–169 行。

`afterMissing` 仅取 `file.removed()`。修改后文件因超大或二进制不可读时，`after=null` 却仍返回 `afterMissing=false`；所有 `FILE_NOT_READABLE` 又被统一当成二进制。

已复现：超过 4 MiB 的文本文件返回 after 缺失，但标记未说明该侧缺失；静态代码同时表明该文件被标为 `binary=true`。

影响：调用方无法准确区分文件不存在、读取失败、超限及二进制状态。

修复建议：分别表达两侧的可用性、缺失原因与二进制状态，并验证任一侧失败时另一侧仍可阅读。

### R08 · P2 · 请求格式错误被返回为 500

位置：[V2ExceptionHandler.java](../../../../backend/src/main/java/com/codereview/agent/api/V2ExceptionHandler.java) 第 46–49 行。

统一处理器没有覆盖缺少查询参数和不可解析 JSON 等 MVC 异常，这些异常最终进入 `INTERNAL_ERROR`。

MockMvc 已确认：缺少 `sha` 的文件读取请求、正文为 `{` 的模型创建请求，均返回 HTTP 500，而非约定的 HTTP 400 `VALIDATION_FAILED`。

影响：前端把用户可修正的输入错误显示为服务内部故障，错误契约失真。

修复建议：补齐请求绑定、类型转换及正文解析异常的映射，并通过 HTTP 层测试验证。

### R09 · P2 · 仓库 HTTP 错误分类丢失

位置：[RepositoryReadService.java](../../../../backend/src/main/java/com/codereview/agent/repository/RepositoryReadService.java) 第 226–228 行。

真实宿主客户端抛出的 HTTP 异常统一落入 `RuntimeException`，被转换为不可重试的 HTTP 422 `REPO_UNREACHABLE`。

本地 HTTP 替身已复现：宿主返回 404，公共读取服务实际返回 `REPO_UNREACHABLE`，而非 `NOT_FOUND`。静态检查表明限流和超时同样无法经此路径保留已有的专用错误分类；这些分支未另加运行用例。

影响：确定性缺失、认证或权限失败、限流、超时及传输失败不能按各自语义处理。

修复建议：保留宿主状态及异常分类，映射专用错误码、重试属性和必要的重试等待信息。

### R10 · P2 · 提示词描述及标签无法清空

位置：[AgentPromptService.java](../../../../backend/src/main/java/com/codereview/agent/service/AgentPromptService.java) 第 100–101 行。

空描述和空标签被转换为 `null`，但 MyBatis-Plus 默认更新忽略 `null`。生成 SQL 已确认：此类 patch 不包含 `description` 和 `tags` 更新。

影响：用户清空字段后保存，旧值仍保留；接口表面成功但数据未按输入更新。

修复建议：需要清空时显式写入 SQL NULL，并核对模型可选字段是否存在同类问题。模型字段的清空行为本次未另加运行用例。

### R11 · P2 · 非 JSON 响应也能通过模型验证

位置：[HttpModelProbe.java](../../../../backend/src/main/java/com/codereview/agent/model/HttpModelProbe.java) 第 58–60 行。

探针仅检查响应字符串是否包含 `"choices"`，没有解析 JSON。本地替身返回包含该字样的普通文本，仍被记录为 `PASSED`，并声称返回 JSON 结果。

影响：模型验证的实际检查范围小于界面和交付记录所声明的范围。

修复建议：实际解析 JSON 并检查最低响应结构；继续明确该验证不证明完整上下文窗口或 Agent 工具能力。

## 4. 验证证据

### 4.1 现有全量回归

使用本机 JDK 21 和 Maven 3.9.9，在工作树外的隔离目录构建，复跑现有后端测试。结果为：

```text
Tests run: 387, Failures: 0, Errors: 0, Skipped: 7
BUILD SUCCESS
```

387 为测试总数，其中 7 项跳过，不表示 387 项均实际执行通过。当前结果的跳过数量与 DS 交付记录中的 9 项不同，本文保留本次实际输出，不改写原交付记录。

### 4.2 临时边界检查

临时类名：`com.codereview.agent.review.P30BoundaryReviewTest`。这组检查按预期行为编写断言，用于暴露问题，未纳入项目现有测试集。

| 用例 | 对应问题 | 预期与实际 |
| --- | --- | --- |
| `changedCredentialMustNotReuseInnerClientCache` | R01 | 预期重新请求宿主并识别无效凭据；实际直接返回旧缓存 |
| `storedCredentialMustBeReferenceRatherThanRawKey` | R02 | 预期存储引用或受保护值；实际实体字段等于合成原始密钥 |
| `truncatedHostTreeMustNeverBeMarkedComplete` | R04 | 预期保留截断状态；实际为 `false` |
| `leadingBlankLineMustRemainInRange` | R05 | 预期 `\nalpha`；实际为 `alpha` |
| `renameCanBeReadThroughItsOldPath` | R06 | 预期读取重命名后的正文；实际抛出旧路径读取异常 |
| `unreadableAfterSideMustBeMarkedMissing` | R07 | 预期明确标记 after 缺失；实际为 `false` |
| `missingHttpQueryMustBe400` | R08 | 预期 HTTP 400；实际为 HTTP 500 |
| `malformedJsonMustBe400` | R08 | 预期 HTTP 400；实际为 HTTP 500 |
| `missingHostFileMustBeNotFound` | R09 | 预期 `NOT_FOUND`；实际为 `REPO_UNREACHABLE` |
| `nullablePromptFieldsMustBeClearableInGeneratedSql` | R10 | 预期包含清空字段的更新；实际 SQL 省略字段 |
| `invalidJsonMustNotPassModelProbe` | R11 | 预期验证失败；实际验证通过 |

运行结果为：

```text
Tests run: 11, Failures: 10, Errors: 1, Skipped: 0
BUILD FAILURE
```

其中 10 项为断言失败，1 项为重命名读取用例抛出的异常。11 个用例对应 10 项问题，R08 由两个用例覆盖；R03 另外通过脚本替身捕获验证。

### 4.3 本机临时证据位置

本次运行文件位于 `C:\Users\ysx\AppData\Local\Temp\codex-p30-review-20261020`：

- `test/java/com/codereview/agent/review/P30BoundaryReviewTest.java`：临时边界检查源码。
- `regression.log`：现有全量回归输出。
- `boundaries.log`：临时边界检查输出。
- `captured-init.sql`、`init-script.log`：建库脚本替身捕获结果。

这些文件位于系统临时目录，未纳入 Git，可能被系统清理。本文已保存用例名称、触发条件、预期和实际结果；后续修复应将必要用例整理为项目内的正式回归测试。

## 5. 后续复验范围

先处理 R01–R03，再修复 R04–R11。修复时应补充覆盖实际客户端、HTTP 层和生成 SQL 的测试，避免只模拟 mapper 或宿主接口后判断边界已经成立。

复验记录应逐项注明修复提交、测试结果及未验证部分。本次未执行的真实数据库初始化和访问层验证、真实宿主与模型调用、目标仓库采样及正式页面 PC 验收，仍按原 ROADMAP 的 P30.8、P31.9、P32.6 安排，不能由本文的本地替身检查替代。

审查结束时，原有未跟踪文件 `docs/work/agent-redesign/未决事项.xlsx` 保持原状；未修改业务源码、原交付文档或阶段状态。本次落盘仅新增本审查记录。
