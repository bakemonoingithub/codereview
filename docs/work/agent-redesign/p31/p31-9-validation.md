# P31.9 专项集成验证、目标仓库校准与交付

状态：已完成（2026-10-22，Asia/Taipei）。目标仓库采样、真实 GitHub 宿主、真实数据库与真实 `deepseek-flash` 调用已执行；PC 视觉按负责人指示由本人确认。未执行项与既有边界见第 5 节。

## 1. 验收项证据

| 编号 | 场景 | 本地证据 | 状态 |
| --- | --- | --- | --- |
| A07 | 同名类、多个源码根、显式/通配 import | `DeclarationResolverTest`；P31.1–P31.3 审查的 `SpecialistBoundaryRegressionTest` | 工程通过 |
| A08 | 菱形继承、循环、超深祖先、缺失第三方 | `DeclarationEnhancementServiceTest`、`SpecialistBoundaryRegressionTest` | 工程通过 |
| A09 | 字段、初始化块、类型外注释、超长方法覆盖 | `JavaStructureParserTest`、`SpecialistSplitPlannerTest` | 工程通过 |
| A10 | 关闭/开启拆分与容量超限 | `SpecialistSplitPlannerTest`、`ModelInvocationServiceTest`；关闭拆分时完整发送，模型端确定超限才失败 | 工程通过 |
| A11 | 连续结构错误、输出截断的 2 次重试 | `SpecialistProtocolPolicyTest`、`SpecialistTaskWorkerTest` | 工程通过 |
| A12 | 新文件、删除、重命名、根提交、删除行 | `MaterialPreparationServiceTest` | 工程通过 |
| A13 | 同位置不同原因、重叠片段去重 | `SpecialistResultMergerTest`、`SpecialistTaskWorkerTest` | 工程通过 |
| A14 | 批量中单文件或片段失败 | `SpecialistTaskWorkerTest` 单文件隔离、部分单元发布 INCOMPLETE | 工程通过 |
| A15 | AST 失败与未解析类型耦合 | `CouplingFactsBuilderTest`；无 AST 明确失败，未解析保留边界 | 工程通过 |
| A21 | 长代码/提示词、受控引用、恶意 Markdown | `SpecialistResultView.spec.ts` 恶意 HTML 文本渲染；`MaterialReaderModal.spec.ts` 固定版本全文与保存片段切换；长正文 PC 阅读由负责人本人确认 | 工程检查与负责人确认 |
| A24 | 返回现场、加载失败、切项目迟到响应 | `CodeBrowser.spec.ts`、`TaskDetail.spec.ts`、`ReviewCenter.spec.ts`、两个 ReaderModal 测试：乱序隔离、分页、轮询、失败保留及返回现场 | 工程通过 |

## 2. 固定版本、位置、拆分与失败

- 材料目标读取失败、二进制、超限、必需 AST 缺失均明确失败；不把空材料写成成功。
- 位置只按实际发送材料校验；新增文件不能用 BEFORE、删除文件不能用 AFTER；越界/未知材料拒绝。
- 拆分默认关闭；未授权拆分时保持完整请求，本地估算仅作诊断，模型端确定超限返回 `CONTEXT_LIMIT`。开启后仍优先整文件，再裁剪辅助材料，最后按类型/成员/行区间拆分。
- 完整文件只在全部单元成功时发布 `COMPLETE`；片段失败保留已得内容并发布 `INCOMPLETE`，不进入正式选材。
- 问题去重只合并位置、类别、标题和原因可确认一致的项；不追加 LLM 综合复查。

2026-10-21 已完成本批审查修复，当前行为、正式回归与最终复验见[修复与复验记录](p31-7-9-review-fixes.md)。

## 3. 工程检查

- 后端：`mvn clean test` 为 597 项、0 失败、0 异常、9 跳过。
- 前端：46 个测试文件、388 项通过；`vue-tsc --noEmit` 与 `vite build` 通过。
- 跳过项为显式禁用的真实网关/宿主探针，不计为通过。后续全量复验见 P31.7–P31.9 修复记录（603 项／417 项）。

## 4. 目标仓库采样（2026-10-22）

对象：项目 `codereview`（`https://github.com/bakemonoingithub/codereview`），分支 `refactor/agent`，固定提交 `b8b0b50e4cce07188debd775ee80f70e13709cf5`；数据库为本机隔离库 `ai_code_review_agent`；模型 `deepseek-flash`（上下文 1,048,576，最大输出 393,216）。真实任务与调用均写入该库，服务端时间按 UTC 记录。

| 样本 | 任务 | 来源与文件 | 材料（UTF-8 字节） | 输入估算 | prompt tokens | token/估算 | 耗时 | 结果 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 小文件 | `01M5GQAZ784VAXC2BQYM4QNRFP` | FILES `api/ProjectV2Controller.java` | 2,378 | 16,790 | 4,404 | 0.26 | 7.0s | 4 问题 |
| 普通文件 | `01M5GQC6E0B9PBE4J8TYFV4CC2` | FILES `repository/RepositoryReadService.java` | 17,216 + 声明摘要 11,206 | 56,670 | 15,200 | 0.27 | 11.0s | 6 问题 |
| 大文件 | `01M5GQCX5ZRMF0PR50GYJ2MFWH` | FILES `service/TaskService.java` | 44,155 | 101,827 | 25,715 | 0.25 | 10.0s | 7 问题 |
| 大文件＋拆分 | `01M5GQDH1PVWPCVQ0S2HGEAYNW` | 同上，`smartSplit=true` | 44,155 | 101,830 | 25,715 | 0.25 | 11.0s | 7 问题，1 个单元 1–859 |
| 提交差异 | `01M5GQDWVZVCZ05J7EYDP3VFVE` | DIFF `d5e4125…` `service/AgentProjectService.java` | BEFORE 7,297 + AFTER 8,651 + 声明摘要 5,098 | 52,483 | 14,255 | 0.27 | 6.0s | 3 问题 |
| 并发 A | `01M5GQEVXRACAM7X0VH70XCC0R` | FILES `api/ProjectV2Controller.java` | 2,378 | 16,789 | 4,405 | 0.26 | 4.0s | 1 问题 |
| 并发 B | `01M5GQEWNMGQMQNKMED0CK094M` | FILES `repository/RepositoryReadService.java` | 17,216 + 11,206 | 56,666 | 15,201 | 0.27 | 9.0s | 6 问题 |

要点：

- 7 个任务全部 `SUCCEEDED`，结果 `COMPLETE`，每个目标 1 个单元；本轮没有格式重试（均为 `attempt=1`）。
- 估算方法 `ESTIMATED_UTF8_BYTES_V1` 的输出约等于消息 UTF-8 字节数；实际 `prompt_tokens` 是估算的 0.25–0.27，即字节估算比 token 数保守约 3.7–4.0 倍，未出现低估。
- 并发 A 与 B 同时创建，执行区间分别为 02:36:19–02:36:23 与 02:36:20–02:36:29（UTC），重叠约 3 秒，均成功；单任务内仍只发 1 次调用。
- 提交差异同时保存 BEFORE（165 行）、AFTER（193 行）与文件级声明摘要；普通文件附加声明摘要 11,206 字节。

Git 宿主边界：

| 场景 | 请求 | 结果 |
| --- | --- | --- |
| 项目记录的 `main` 分支 | `/head?branch=main` | 404 `NOT_FOUND`（远端无 main，实际分支为 `dev`、`experiment/*`、`refactor/agent`） |
| 不存在的分支 | `/commits?branch=no-such-branch` | 404 `NOT_FOUND` |
| 不存在的文件 | `/files?path=no/such/File.java` | 404 `NOT_FOUND` |
| 非法提交 SHA | `/commit?sha=0000…0000` | 422 `REPO_UNREACHABLE`（GitHub 返回 422） |

## 5. 未执行与备忘

- GitHub 429 限流与网络超时未构造：未通过滥用请求制造，仍无运行证据。
- 真实拆分（同一文件拆成 2 个及以上单元）与 `CONTEXT_LIMIT` 未触发：仓库最大文件 44 KB，远低于 1,048,576 窗口；开启拆分仍按整文件 1 个单元执行，属“整文件优先”的预期行为，但没有多单元样本。
- 专项侧输出截断未在本轮复现：历史 PATTERN 任务有两次格式失败后成功，报告截断见 P32.6；专项 ISSUE 输出截断仍只有工程回归证据。
- 1366／1920 双宽度截图未拍：PC 视觉按负责人 2026-10-22 指示由本人确认，本记录未附截图。
- 项目 `currentBranch=main` 与远端实际分支不一致，发起表单默认分支会落到不存在的 main；本次未擅自改配置，记入备忘待负责人决定是否改为 `dev` 或 `refactor/agent`。

## 6. 结论与交付边界

- 完成条件满足：三类专项与提交差异均真实执行并可阅读，目标仓库校准有记录，模型质量按 P32.6 验收；P31.9 标为 done。
- 校准结论：字节估算偏保守（约为 token 的 3.7–4.0 倍），容量判断安全；P29 候选值与默认值本轮不下调。
- 交付边界：未 push、未部署、未改生产配置；真实任务、调用与结果保留在本机隔离库。
