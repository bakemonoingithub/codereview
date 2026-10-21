# P31.9 专项集成验证、目标仓库校准与交付

状态：工程验证与交付已完成（2026-10-21，Asia/Taipei）；**目标仓库校准和真实资源验收未完成**，因此 P31.9 保持 active，不记为 done。

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
| A21 | 长代码/提示词、受控引用、恶意 Markdown | `SpecialistResultView.spec.ts` 恶意 HTML 文本渲染；`MaterialReaderModal.spec.ts` 固定版本全文与保存片段切换；长正文真实 PC 体验待验收 | 工程检查 |
| A24 | 返回现场、加载失败、切项目迟到响应 | `CodeBrowser.spec.ts`、`TaskDetail.spec.ts`、`ReviewCenter.spec.ts`、两个 ReaderModal 测试：乱序隔离、分页、轮询、失败保留及返回现场 | 工程通过 |

## 2. 固定版本、位置、拆分与失败

- 材料目标读取失败、二进制、超限、必需 AST 缺失均明确失败；不把空材料写成成功。
- 位置只按实际发送材料校验；新增文件不能用 BEFORE、删除文件不能用 AFTER；越界/未知材料拒绝。
- 拆分默认关闭；未授权拆分时保持完整请求，本地估算仅作诊断，模型端确定超限返回 `CONTEXT_LIMIT`。开启后仍优先整文件，再裁剪辅助材料，最后按类型/成员/行区间拆分。
- 完整文件只在全部单元成功时发布 `COMPLETE`；片段失败保留已得内容并发布 `INCOMPLETE`，不进入正式选材。
- 问题去重只合并位置、类别、标题和原因可确认一致的项；不追加 LLM 综合复查。

2026-10-21 已完成本批审查修复，当前行为、正式回归与最终复验见[修复与复验记录](p31-7-9-review-fixes.md)。

## 3. 初交付工程检查

- 后端：`mvn clean test` 为 597 项、0 失败、0 异常、9 跳过。
- 前端：46 个测试文件、388 项通过；`vue-tsc --noEmit` 与 `vite build` 通过。
- 跳过项为显式禁用的真实网关/宿主探针，不计为通过。

## 4. 未完成与不能替代的验收

| 项目 | 当前状态 | 后续 |
| --- | --- | --- |
| 目标仓库采样（小/普通/大文件、提交差异） | 未提供目标仓库和只读令牌 | 负责人指定后按 P31.9 采样，记录文件量、字节、token 误差、并发与耗时 |
| 真实 Git 宿主 | 未连接 | 分支/提交/差异/404/429/超时边界 |
| 真实数据库 | 未建库、未迁移 | 隔离新库执行 V1–V3 与读取/写入验证 |
| 专项真实模型 | 本批未调用；历史通用探针见 P31.4–P31.6 修复记录 | 三类 schema 遵循率、格式重试、输出截断、容量超限 |
| PC 正式视觉 | 无浏览器自动化截图 | 1366／1920，按 [design.md](../../../design.md) 第 5 节人工清单 |

上述缺项不能用本地单测、构建成功或模拟替身替代；P31.9 在这些证据补齐前保持 active。

## 5. 交付边界

- 交付代码、测试和文档；未 push、未部署、未切换入口、未操作真实数据库。
- 未生成目标仓库参数结论，不把 P29 候选值写成已验证生产默认值。
- P31.7／P31.8 的页面实现没有浏览器截图；P31.9 的真实页面视觉仍待执行。
