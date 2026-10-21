# P32.4–P32.7 审查修复与复验

日期：2026-10-21（Asia/Taipei）。工作树：`agent`，分支：`refactor/agent`。审查基线：DS 交付提交 `352b97b`；依据为业务契约、P29 共用设计和 P32.4–P32.7 记录。

本轮按负责人授权独立检查、修复并本地提交。保留会话前 `frontend/components.d.ts` 和 `docs/work/agent-redesign/p29/prototype/index-agent.html` 的改动，不纳入本轮提交。

## 问题与修复

| 编号 | 触发与影响 | 修复 | 验证 |
| --- | --- | --- | --- |
| R01 | 模式／耦合结果有人工评论时，事实组装只传反馈计数，模型看不到评论；来源摘要也误取问题级计数 | 传入冻结的结果级评价和独立评论；按主体选取摘要计数 | `ReportFactsBuilderTest`：已判定评论、未判定独立评论与未反馈计数 |
| R02 | 问题正文中的辅助 evidence、changeRelation，耦合建议与 provenance 材料缺口未进入报告输入 | 从冻结正文与来源补入辅助代码、变更关系、建议和材料缺口，不重新读取仓库 | `ReportFactsBuilderTest`：辅助证据、BEFORE 侧、变更关系、材料缺口 |
| R03 | materialId 缺失时按同名 path 选首条材料，可能混用前后侧；证据尾部越界时被静默截短 | 仅按冻结材料 ID 取证，范围必须完整覆盖；否则明确记录缺口；证据标明侧与完整 SHA | `ReportFactsBuilderTest`：同路径缺失 ID 不替代、越界不截断 |
| R04 | 版本分组使用项目显示名和 12 位短 SHA，身份不同的来源可能被合并 | 按项目 ID、完整 head/base SHA 分组；传给模型的版本保留完整 SHA | `ReportFactsBuilderTest`：短 SHA 相同的不同版本分组 |
| R05 | 模型调用返回后未再检查报告总期限，迟到正文可能发布成功 | 从 worker 开始处理计时，发布前检查总期限；超时失败并保留冻结来源 | `ReportExecutionWorkerTest`：模拟返回时已超过 10 分钟，不写成功正文 |
| R06 | 固定线程池使用无界等待队列；worker 外层异常只记日志，可能长期停留在 QUEUED/RUNNING | 新增等待队列上限，默认 100，可通过 `agent.report.dispatch-queue-capacity` 配置；队列拒绝或 worker 异常条件更新活动报告为失败 | `ReportDispatchServiceTest`：单 worker、单等待位并发满队列；后台异常只结束活动状态 |
| R07 | 候选切换筛选或报告列表刷新时，旧分页响应失效但 loadingMore 不清理，后续分页卡住；刷新期间可使用旧游标 | 新一轮加载重置分页忙碌标记，首屏加载期间禁用追加，旧响应不写入；卸载使请求失效 | `ReportCreate.spec.ts`、`ReportList.spec.ts`：旧分页晚到后仍可继续新游标 |
| R08 | 冻结尚未返回时可重复触发提交；模型与补充要求在第二次请求才取值，可能混入提交后的编辑；离页后会被迟到结果跳回 | 提交重入保护，提交时固定生成参数；后台受理完成后仅在当前页面导航 | `ReportCreate.spec.ts`：重复提交、提交后改配置、离页迟到响应 |
| R09 | 详情路由切换后旧正文、来源、调用和生成表单仍存在；旧生成／下载响应可影响新报告 | 按报告身份清空阅读与生成现场，生成和下载响应校验身份，下载使用发起时标题；生成重入保护 | `ReportDetail.spec.ts`：切换读取失败不显示旧正文、旧生成错误不污染新报告、重复生成只发一次 |
| R10 | 冻结来源抽屉不展示问题变更关系、辅助证据、模式／耦合证据位置及材料缺口 | 从冻结正文与 materialId 还原位置、侧、SHA，补充变更关系、建议和缺口展示 | `ReportSourceDrawer.spec.ts`：冻结证据位置、BEFORE 侧、变更关系和缺口 |
| R11 | 交付页笼统建议沿用旧部署备份／回退脚本，未说明 7 天清理及只切镜像的边界 | 明确脚本前提与限制，封存副本独立保留，切换前必须完成独立恢复核对；不宣称已有新路线运维演练 | 人工核对 `deploy/scripts/backup.sh`、`rollback.sh` 与交付操作单；未执行生产操作 |

## 工程复验

定向后端 15 项通过，定向前端页面 13 项和来源抽屉 1 项通过，类型检查通过。新增后端 7 项、前端 7 项正式回归。

首次定向后端编译发现新增测试使用了高于项目 Java 编译目标的 `List.getFirst()`；已改为 `get(0)`，随后定向检查通过。该问题属于本轮测试代码，未将失败记为通过。

全量复验结果：

| 检查 | 命令与结果 |
| --- | --- |
| 后端全量 | `backend` 下 `mvn clean test`：657 项、0 失败、0 异常、7 跳过，BUILD SUCCESS |
| 前端全量 | `frontend` 下 `node node_modules/vitest/vitest.mjs run`：57 个文件、454 项通过 |
| 类型检查 | `node node_modules/vue-tsc/bin/vue-tsc.js --noEmit`：退出码 0 |
| 生产构建 | `node node_modules/vite/bin/vite.js build`：退出码 0；仍有既有大资源分块提示 |

本次环境启用了已有 `AiGatewayProbeTest`：连通性 1 次、固定示例 JSON 审查 3 次真实调用均通过，因此跳过数为 7，而 DS 原记录为 9。该探针不使用报告冻结来源，也不是修复后报告生成联调。其余跳过项为 Gitea 真实宿主 5 项、真实项目解析 1 项、结构 LLM 探针 1 项；跳过不计为执行通过。前端仍有既有组件替身解析警告，不影响断言通过。

原始检查输出位于本机临时目录的 `p32-4-7-backend-full.log`、`p32-4-7-frontend-full.log`、`p32-4-7-typecheck.log` 与 `p32-4-7-build.log`；本页保存长期接续所需的结果与边界。

## 真实记录只读复核

在本机现有 8081 服务上读取 DS 留存记录，未重新调用模型、修改反馈或重启服务：

- `/api/v2/health` 返回 `database=agent`、`schemaVersion=1`。
- 成功报告 `01M5F8XNRZKZ918EYV0RJQ4HF3` 为 `SUCCEEDED`，4 个来源、正文 7264 字符；下载正文逐字一致，来源 S1 可读取。
- 失败报告 `01M5F8M6PZEX9Y8ZEMB41DEESJ` 为 `FAILED/MODEL_OUTPUT_INVALID`。

这些结果复核 DS 的历史流程证据，不代表本轮修复已在运行服务重新部署。修复后的行为以本轮正式回归与构建为据。

## 保留的验收边界

- 未重新运行真实模型报告生成、真实输入超限注入、服务重启试验、双页面操作或负责人 PC 视觉确认；全量测试中的网关探针另见上文。本轮并发回归使用本地替身，不等同双页面真实验收。
- P31.9 的目标仓库校准、真实宿主完整采样和专项质量验收未完成；Agent 实际联合报告验证留在 P33。
- 实际备份、长期封存、生产部署、入口切换与恢复演练未执行。P32.6、P31.9 与 P32 保持 active，P32.7 仍只表示交付准备完成。
- 已保存报告保持不可变，本次不重写历史成功正文；修复后的事实组装用于后续新报告。

## 相关记录

- [生成与失败处理](p32-4-report-generation.md)
- [报告界面](p32-5-report-ui.md)
- [专项验收](p32-6-validation.md)
- [交付与回退准备](p32-7-delivery.md)
