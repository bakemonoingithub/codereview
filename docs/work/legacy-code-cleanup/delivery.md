# LGC-04 交付记录

日期：2026-11-03（Asia/Taipei）。LGC-02、LGC-03 为 `done`。本记录覆盖远程可执行的核对与检查。LGC-04 保持 `partial`，不因本文件存在而关闭。

## 1. 候选

- 分支：`cursor/lgc-02-03-legacy-cleanup-cc85`。
- 清单基准：`9dc1ff7dd12292f2515890e11dd9ad22f97f30d9`。LGC-01 文档入库为 `08ec5be732217eb244ab7975183f0275c81eeaba`。
- 实现提交：`db9f7c1`（前端入口与独占依赖）、`4a2de95`（后端旧实现与专属测试）。本记录是其后的文档提交。
- 被删除源码用「删除前提交 `08ec5be`＋原路径」定位。历史文档中的旧路径未批量改写。

## 2. 清单复核

对照 [机器清单](evidence/file-inventory.json)：

| 处置 | 结果 |
| --- | --- |
| 删除 183 份 | 工作区均已不存在 |
| 保留项 | 均仍在 |
| 必要调整 5 份 | `V2ExceptionHandler.java`、`components.d.ts`、`package.json`、`pnpm-lock.yaml`、`router/index.ts` 已按清单调整 |

现行 Java 没有对已删除旧包或旧类的 import。`CodeReviewApplication` 仍只扫描 `com.codereview.agent` 与 `com.codereview.git`，并导入 `WebMvcConfig`、`JacksonConfig`。

## 3. 接口

[接口快照](evidence/http-contracts.json) 中 60 条现行路由与控制器上的普通映射注解一致，没有多出或缺少。40 条旧路由的控制器源码均不存在，因此不能注册。

另外两条现行删除接口使用全限定 `@DeleteMapping`，不在上述 60 条快照里：`DELETE /api/v2/review-tasks/{id}`、`DELETE /api/v2/reports/{id}`。它们属于当前 v2，Postman 中的对应请求保留。

没有启动应用做运行时路由表导出。`AgentOnlyStartupTest` 只验证缺少 agent 配置时启动失败，不枚举 URL。

## 4. 测试变动

消失的测试都随所属旧功能删除，没有迁入保留测试，也没有为了变绿改期望。

后端基线 142 套件、1025 项、跳过 11。删除 38 套件、221 项后，复跑为 104 套件、804 项、跳过 7。`MigrationContractTest` 保留。

| 测试类 | 基线项 | 基线跳过 | 责任 |
| --- | --- | --- | --- |
| `ApiReviewAnalyzerTest` | 5 | 0 | 旧分析器 |
| `CouplingAnalyzerTest` | 5 | 0 | 旧分析器 |
| `DiffReviewAnalyzerTest` | 10 | 0 | 旧分析器 |
| `LlmReviewAnalyzerDeadlineTest` | 3 | 0 | 旧分析器 |
| `ChunkerTest` | 4 | 0 | 旧分块 |
| `TokenEstimatorTest` | 2 | 0 | 旧分块 |
| `TextWindowTest` | 8 | 0 | 旧文本窗口 |
| `MybatisPlusConfigTest` | 1 | 0 | 旧分页插件 |
| `DiffUnitBuilderTest` | 9 | 0 | 旧差异单元 |
| `UnifiedDiffParserTest` | 6 | 0 | 旧差异解析 |
| `ProjectSerializationTest` | 2 | 0 | 旧项目实体 |
| `AiGatewayProbeTest` | 2 | 2 | 旧网关探针 |
| `LlmClientTimeoutTest` | 4 | 0 | 旧模型客户端 |
| `DepGraphTest` | 3 | 0 | 旧材料图 |
| `ModuleGraphTest` | 10 | 0 | 旧材料图 |
| `StructureExtractorTest` | 1 | 0 | 旧结构抽取 |
| `JavaParserProbeTest` | 7 | 1 | 旧解析探针 |
| `StructureLlmProbeTest` | 1 | 1 | 旧结构探针 |
| `RetryPolicyTest` | 8 | 0 | 旧重试 |
| `ReviewStatusTest` | 4 | 0 | 旧状态 |
| `IssueMarkServiceMarkTest` | 9 | 0 | 旧标记 |
| `IssueMarkServiceTest` | 5 | 0 | 旧标记 |
| `ModelConfigServiceTest` | 7 | 0 | 旧模型服务 |
| `ProjectServiceCreateTest` | 3 | 0 | 旧项目服务 |
| `ProjectServiceDeleteTest` | 13 | 0 | 旧项目服务 |
| `ProjectServiceDetailTest` | 2 | 0 | 旧项目服务 |
| `ProjectServiceFileViewTest` | 15 | 0 | 旧项目服务 |
| `ProjectServiceListTest` | 2 | 0 | 旧项目服务 |
| `PromptServiceTest` | 5 | 0 | 旧提示词服务 |
| `ReportAggregationBuilderTest` | 8 | 0 | 旧报告 |
| `ReportExecutorTest` | 9 | 0 | 旧报告 |
| `ReportServiceListTest` | 2 | 0 | 旧报告 |
| `ReviewExecutorDeadlineTest` | 4 | 0 | 旧执行器 |
| `ReviewExecutorResilienceTest` | 8 | 0 | 旧执行器 |
| `ReviewExecutorScopeTest` | 4 | 0 | 旧执行器 |
| `ReviewServiceListTest` | 9 | 0 | 旧审查服务 |
| `ReviewServiceRetryTest` | 6 | 0 | 旧审查服务 |
| `ReviewStrategyServiceTest` | 15 | 0 | 旧策略服务 |

前端基线 73 个文件、658 项。删除 23 个文件、226 项，新增 `legacyProjectRoute.spec.ts` 的 4 项路由覆盖，复跑为 51 个文件、436 项。

| 文件 | 基线项 | 责任 |
| --- | --- | --- |
| `ChangedFileTree.spec.ts` | 10 | 旧变更树 |
| `CouplingResult.spec.ts` | 13 | 旧耦合结果 |
| `DiffReviewResult.spec.ts` | 11 | 旧差异结果 |
| `FileViewerModal.spec.ts` | 8 | 旧文件查看 |
| `ReportPanel.spec.ts` | 9 | 旧报告面板 |
| `ReportPanelGenerateTip.spec.ts` | 6 | 旧报告面板 |
| `ReportPanelPolling.spec.ts` | 4 | 旧报告面板 |
| `ReviewConfigSnapshot.spec.ts` | 13 | 旧审查快照 |
| `ReviewRecordViewer.spec.ts` | 4 | 旧审查记录 |
| `ReviewResult.spec.ts` | 22 | 旧审查结果 |
| `SplitPane.spec.ts` | 6 | 旧分割面板 |
| `accuracy.spec.ts` | 10 | 旧准确率工具 |
| `changedFiles.spec.ts` | 18 | 旧变更文件工具 |
| `enums.spec.ts` | 9 | 旧枚举 |
| `records.spec.ts` | 14 | 旧记录分页数据 |
| `reviewResult.spec.ts` | 20 | 旧结果工具 |
| `useRecordPagination.spec.ts` | 9 | 旧记录分页 |
| `ProjectDetailFileView.spec.ts` | 6 | 旧项目详情 |
| `ProjectDetailLoadFailure.spec.ts` | 4 | 旧项目详情 |
| `ProjectDetailPagination.spec.ts` | 8 | 旧项目详情 |
| `ProjectDetailPolling.spec.ts` | 4 | 旧项目详情 |
| `ProjectDetailRetry.spec.ts` | 4 | 旧项目详情 |
| `ProjectDetailStructure.spec.ts` | 14 | 旧项目详情 |

## 5. 本次检查

环境：Linux；OpenJDK 21.0.12.1；Maven Wrapper，编译 `release 17`；Node 22.23.4；pnpm 10.33.4。测试子进程去掉模型凭据、远端仓库变量和可能注入参数的环境项，传入空 `deepseek.api-key`，不传 `probe.root` 或 `agent.live.cancel`。

本机 Git 配置强制 SSH 提交签名。JGit 用独立 `user.home`，命令行 Git 用 `GIT_CONFIG_COUNT` 关闭 `commit.gpgsign`。期望未改。首次后端全量因此有 12 个错误，均来自 `git commit` 找不到签名密钥：`GitGrepRunnerTest` 5、`SearchCodeToolTest` 6、`GitMirrorTest` 1。隔离后复跑通过。

```bash
# frontend
pnpm exec vitest run --reporter=json --outputFile=/tmp/lgc04-frontend-test.json
pnpm exec vue-tsc --noEmit
pnpm exec vite build --outDir /tmp/lgc04-frontend-dist --emptyOutDir

# backend
GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=commit.gpgsign GIT_CONFIG_VALUE_0=false \
  bash ./mvnw -Ddeepseek.api-key= -DargLine=-Duser.home=/tmp/lgc-test-home \
  -Dbackend.buildDirectory=/tmp/lgc04-backend-test test
bash ./mvnw -q -DskipTests -Ddeepseek.api-key= \
  -Dbackend.buildDirectory=/tmp/lgc04-backend-package package
```

| 检查 | 结果 | 范围 | 退出码 |
| --- | --- | --- | --- |
| 前端测试 | 通过 | 51 个文件、436 项，失败 0、跳过 0 | 0 |
| 前端类型检查（修复前） | 既有失败 | 仅 `ProjectList.vue` TS6133，未使用的 `PageNotice` 导入 | 2 |
| 前端类型检查（删除该导入后） | 通过 | 0 条诊断 | 0 |
| 前端构建 | 通过 | 隔离目录；未写入 `backend/src/main/resources/static` | 0 |
| 后端测试 | 通过 | 104 套件、804 项，失败 0、异常 0、跳过 7 | 0 |
| 后端打包 | 通过 | `backend-0.0.1-SNAPSHOT.jar`，42287320 字节 | 0 |

跳过 7 项：`GiteaClientLiveTest` 5 项、`AgentCancellationLiveTest` 2 项。缺少远端仓库变量和显式真实取消开关，不记为真实环境通过。

随后按负责人要求去掉 `ProjectList.vue` 里未使用的 `PageNotice` 导入。模板里对应提示仍在注释中，没有重新接上。复跑：

```bash
pnpm exec vue-tsc --noEmit
```

退出码 0，输出为空，没有新增诊断。`PageNotice.vue` 仍由审查中心使用。

打包对照：`resources/prompts` 31 个文件与 jar 内 `BOOT-INF/classes/prompts` 逐字节一致。`db/migration` 7 份和 `db/agent-migration` 10 份 SQL 同样一致。jar 中没有旧 `controller`、`service`、`analyzer`、`llm`、`probe`、`material`、`gateway`、`entity`、`dto`、`mapper`、`chunk`、`diff`、`review` 类。

`9dc1ff7` 到当前实现之间，`resources/prompts`、`PromptRefactorBaselineTest`、`PromptAssemblyBoundaryBaselineTest`、`ToolDefinitionBaselineTest` 无差异。基线未重录。

仓库内的 `deploy/scripts/*.sh` 是部署与离线组包入口，依赖真实 Docker 与发布流程，本轮未执行。

## 6. Postman

工作区 `codereview`（`e836fa8c-474b-4936-8439-3f333f4ed9f3`）。集合「aiCodeReview 后端接口（按业务组织）」，UID `27085315-71513aa5-741e-4abc-9041-e5da34ea8439`。仓库内没有 Postman 集合文件。

计划原先默认保留「99 已停用接口」。负责人于本步授权删除这些旧请求，而不是只留在停用目录。已删除文件夹 `1c10649c-6a73-49fc-15fa-6dc12d95a917` 及其 7 个子目录、40 个请求。删除后集合为 10 个现行目录、62 个请求，路径均以 `/api/v2` 开头。集合说明已改成记录这次删除。变量 `legacyId` 仍在，已无请求使用。

| 子目录 | 删除的请求 |
| --- | --- |
| 问题标记与准确率 `3d03e796-3e4a-154b-91e3-18cdca1e2b86` | `POST /api/reviews/{recordId}/marks`；`DELETE /api/reviews/{recordId}/marks`；`GET /api/reviews/{recordId}/marks`；`GET /api/projects/{projectId}/accuracy` |
| 模型配置 `783dc716-b1c9-bffa-3b44-897d76748a28` | `GET /api/models`；`POST /api/models`；`PUT /api/models/{id}`；`DELETE /api/models/{id}`；`POST /api/models/{id}/verify` |
| 项目与代码浏览 `ad161403-067c-9baa-6581-78efd162161c` | `POST /api/projects`；`GET /api/projects`；`GET /api/projects/{id}`；`GET /api/projects/{id}/tree`；`GET /api/projects/{id}/branches`；`GET /api/projects/{id}/commits`；`GET /api/projects/{id}/changed-files`；`GET /api/projects/{id}/commits/page`；`GET /api/projects/{id}/commits/{sha}`；`GET /api/projects/{id}/commits/{sha}/patch`；`GET /api/projects/{id}/file`；`GET /api/projects/{id}/delete-impact`；`DELETE /api/projects/{id}` |
| 提示词管理 `be87924b-9445-3958-41cb-6ced3915c8a0` | `POST /api/prompts`；`GET /api/prompts`；`GET /api/prompts/{id}`；`PUT /api/prompts/{id}`；`PUT /api/prompts/{id}/content`；`DELETE /api/prompts/{id}`；`GET /api/prompts/{id}/versions/diff` |
| 报告 `98def48a-8660-363a-1470-3aa1d54d49e4` | `POST /api/projects/{projectId}/reports`；`GET /api/projects/{projectId}/reports`；`GET /api/reports/{id}` |
| 审查记录 `6055a32a-a065-4756-92a8-c6a874cd586b` | `POST /api/projects/{projectId}/reviews/trigger`；`POST /api/reviews/{id}/retry`；`GET /api/projects/{projectId}/reviews`；`GET /api/reviews/{id}` |
| 审查策略 `238d2d7c-85fb-e84f-96cd-b68dad37dddc` | `GET /api/strategies`；`POST /api/strategies`；`PUT /api/strategies/{id}`；`DELETE /api/strategies/{id}` |

## 7. 未验证，LGC-04 不关闭

- 未连接现有数据库，未执行迁移或清理。
- 未启动或停止业务服务，也没有运行时路由表导出。
- 未调用真实模型。真实模型 token 消耗为 0。
- 未做正式页面视觉验收，也未在真实浏览器复测旧地址。路由覆盖在 happy-dom。
- 未执行 `deploy/scripts` 的离线组包、部署或发布。
- 未合并 PR。
- 7 项真实探针仍跳过。
