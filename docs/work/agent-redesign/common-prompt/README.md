# 通用提示词用途

日期：2026-10-28。负责人先确认影响评估，随后授权实施，并要求不要另建数据库测试。业务规则统一维护在[契约第 9.5 节](../business-contract.md#95-通用提示词用途2026-10-28)。

## 1. 实现范围

- 提示词管理沿用单选用途，增加 `COMMON`（通用）选项和列表名称；修改用途仍使用元数据接口，不新增正文版本。
- 前端在 `promptSelection.ts` 集中适用性判断，专项发起、Agent 发起、报告创建和报告详情的列表筛选、详情读取与复制参数使用同一规则。Agent 补充读取详情时的用途复核，阻止列表加载后已被修改的错用途配置。
- 后端 `PromptApplicability` 集中判断资源适用范围；提示词创建／更新 DTO 接受新增值，专项、Agent、报告入口调用统一判断。空用途和未知用途拒绝；Agent 的空用途错误信息避免空值导致异常。
- 保留原具体版本、归属、正文非空检查以及调用输入冻结。实际调用的 `PromptPurpose` 枚举、默认指令、输出协议、执行循环和容量规则未修改。
- `prompt.purpose` 已是 `VARCHAR(32)`，无需新增数据库迁移或转换已有数据。本次没有操作数据库、启停服务或调用真实模型。

工作区原有删除功能相关未提交内容继续保留；本次在重叠服务和测试文件中仅调整提示词用途及对应检查，不把其他工作包成果计入本项交付。

## 2. 界面范围

[独立示例](prototype.html)先于框架修改保存，用于核对用途单选和三类入口的候选行为。正式页面沿用现有结构及控件，只增加选项和适用性规则。负责人本轮已授权按评估直接改造；示例及组件测试不代替负责人正式页面视觉确认。

## 3. 工程检查

后端使用现有 Maven 和 Java，输出目录为 `backend/target/common-prompt-20261028`；只执行显式指定的模拟存储单测，不启动应用或数据库。命令在 `backend/` 执行：

```text
mvn -B -Dbackend.buildDirectory=target/common-prompt-20261028 -Dtest=PromptApplicabilityTest,PromptPurposeValidationTest,AgentPromptServiceTest,TaskServiceTest,AgentTaskServiceTest,ReportGenerationServiceTest,PromptAssemblyServiceTest,PromptAssemblyAgentTest,SpecialistPromptBuilderTest,ReportExecutionWorkerTest test
```

后端 10 个套件、100 项通过，0 失败／异常／跳过。检查覆盖：

- 创建和更新请求的真实 Bean Validation 白名单；管理服务保存／返回通用用途。
- 完整用途适用矩阵，三个入口对其他用途、未知值和空用途的拒绝。
- 专用与通用用途选择历史版本，版本缺失、归属错误或正文为空时拒绝，失败不新增任务或派发执行。
- 专项与 Agent 实际插入的快照，以及报告实际 UPDATE 参数中的所选版本和正文；后续改用途／正文不改写冻结内容。
- Agent、专项、报告原有组装及执行相关单测，防止把资源用途带入执行协议。

前端检查命令在 `frontend/` 执行：

```text
node node_modules/vitest/vitest.mjs run --maxWorkers=2 src/utils/__tests__/promptSelection.spec.ts src/views/__tests__/PromptList.spec.ts src/views/__tests__/AgentLaunch.spec.ts src/views/__tests__/ReviewLaunch.spec.ts src/views/__tests__/ReportCreate.spec.ts src/views/__tests__/ReportDetail.spec.ts
```

前端 6 个文件、91 项通过，无未处理异常；覆盖通用选项保存、三类入口筛选、具体版本提交、专项／Agent 参数复制、报告详情继续生成、分页加载及用途变更后阻止提交。初轮新增用例的模拟详情缺少标签和版本创建时间，补全模拟数据后通过；未据此修改正式阅读组件。

生产构建通过，产物隔离到 `backend/target/common-prompt-frontend-20261028`，未覆盖正式静态资源。构建使用基于现有配置的临时配置，仅关闭组件声明文件生成，构建结束后已移除临时配置；保留原有大分包提示。

改造前后均从根目录运行 `node frontend/node_modules/vue-tsc/bin/vue-tsc.js --noEmit -p frontend/tsconfig.json`。最终只有以下两处既有诊断，没有新增诊断；类型检查不记为通过：

- `ProjectDetail.vue:561`：`CommitInfo` 必填字段与可能为空的值不一致，TS2769。
- `ProjectList.vue:117`：未使用的 `PageNotice`，TS6133。

本机 `pnpm` 启动器引用的缓存路径缺失，因此使用已安装依赖的 Node 入口执行检查，未安装或升级依赖。过程日志位于本机忽略目录 `backend/target/common-prompt-20261028/evidence/`（backend、frontend、types、build）；单测 XML 位于同一构建目录的 `surefire-reports/`。

本轮修改文件的 UTF-8 无 BOM、LF、相对文档链接和 `git diff --check` 在收尾时核对。

## 4. 运行及未验证边界

- 后端修改待下次启动加载，前端须加载更新后的页面资源；本轮未操作正在运行的服务。
- 未验证真实数据库读写、实际模型生成、部署或正式页面 PC 视觉；工程检查不记为这些验收通过。
- 本轮不处理其他工作包的验收状态，不新增多规则叠加、报告参数复制或用途多选。
