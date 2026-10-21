# P32.4 报告统一组装、生成及失败处理

状态：done（2026-10-21，本地工程与真实模型单例验证）。独立表单、多结果阅读与下载见 [P32.5](p32-5-report-ui.md)；真实资源验收见 [P32.6](p32-6-validation.md)。

## 边界

- 只读取 P32.3 冻结的 `report_source.source_snapshot`，不联查最新结果、反馈、材料或仓库。反馈、配置或源码在冻结后变化不改变报告。
- 程序先整理来源清单、版本分组、问题、模式、耦合事实、人工反馈和已保存代码证据；模型只负责组织正文，不提供数字、版本或位置。
- 正文经 `PromptAssemblyService` 组装、`ModelInvocationService` 调用。报告与专项共用容量判断、并发许可、请求落盘、调用记录和传输重试。
- 报告输出预留独立为 `agent.report.output-tokens`（默认 16384），仍不超过模型声明的最大输出；专项默认值不变。
- 生成失败保留冻结输入、来源快照、错误码和实际调用，不写成功正文；不把程序事实草稿标成完成报告。
- 长材料不做静默截断：完整请求超过模型窗口时由公共调用抛 `CONTEXT_LIMIT`，报告落为失败并提示减少选材。

## 接口

| 方法与路径 | 用途 |
| --- | --- |
| `POST /api/v2/reports/{id}/generate` | 选择模型和补充要求；`INPUT_FROZEN` 条件更新为 `QUEUED`，返回 202 |
| `GET /api/v2/reports` | 游标分页读取报告记录、状态、来源数量与类型分布 |
| `GET /api/v2/reports/{id}` | 状态、正文、错误、模型快照、来源摘要与实际调用摘要 |
| `GET /api/v2/reports/{id}/sources/{ordinal}` | 按序号读取单条冻结来源；不查询最新数据 |
| `GET /api/v2/reports/{id}/calls` | 报告实际调用摘要 |
| `GET /api/v2/reports/{id}/download` | 仅 `SUCCEEDED` 且有正文时返回 Markdown；失败返回 409 |

生成请求：

```json
{ "modelConfigId": "01...", "instruction": "优先说明高风险问题，保留来源引用。" }
```

校验：模型存在、接口地址与模型名非空、已声明上下文窗口、协议为 `OPENAI_CHAT`；补充要求最多 4000 字符。报告必须处于 `INPUT_FROZEN`，其他状态返回 409。

## 状态与恢复

```text
INPUT_FROZEN -> QUEUED -> RUNNING -> SUCCEEDED
                                  -> FAILED
```

- 状态写入均带前置状态和 `revision` 条件；重复调度或迟到回调不能把终态改回成功。
- 服务重启时 `ReportRecoveryRunner` 把遗留 `QUEUED/RUNNING` 标为 `FAILED/REPORT_INTERRUPTED`，保留冻结来源。
- `FAILED` 不提供重试同一报告；用户重新选材生成新报告。`INPUT_FROZEN` 可在详情页补选模型生成。

## 事实与引用

- 全局事实：来源数、任务数、仓库版本数、类型分布、问题数、问题级／结果级反馈计数、版本分组。
- 每条来源：项目与仓库、任务与状态、分支／提交／比较基线、目标路径、结论、问题、模式候选、耦合观察与指标、限制、人工反馈、已保存证据。
- 代码证据只取来源引用指向的已保存 material 范围；材料缺失写入「材料缺口」，不读取当前源码补写。
- 正文引用使用 `[S1]` 形式；至少一处引用，且每个序号必须落在冻结来源范围内。未知来源、无引用或空正文均失败。
- 正文默认上限 100 万字符；超限明确失败。
- Markdown 渲染由前端 DOMPurify 净化；代码按文本转义，危险链接协议不保留。

## 验证

独立审查补修了冻结评论、辅助证据、变更关系、材料缺口、完整版本身份与范围校验；报告等待队列默认最多 100 项，worker 异常会结束活动报告，发布前再次检查总期限。新增回归与本轮复验见[修复与复验记录](p32-4-7-review-fixes.md)。

- 后端新增 `ReportFactsBuilderTest`、`ReportCitationValidatorTest`、`ReportExecutionWorkerTest`、`ReportGenerationServiceTest` 及迁移契约检查；覆盖事实分组、证据复制、材料缺口、损坏快照、引用越界、空正文、失败不写正文、模型输出预算、下载状态与调用关联。
- 真实本机：4 个完整结果冻结后，`deepseek-flash` 首次因公共输出默认 4096 被截断而失败；报告输出改为 16384 后第二次生成成功，正文 7264 字符，`finish_reason=stop`，4 个来源均被引用。详见 [P32.6](p32-6-validation.md)。
- 未执行：PC 用户视觉确认、双页面并发生成、真实上下文超限注入、生产数据库与部署。

## 代码位置

- `backend/src/main/java/com/codereview/agent/report/`：事实组装、引用校验、生成入口、后台执行、调度和重启收敛。
- `backend/src/main/java/com/codereview/agent/api/ReportV2Controller.java`：读取、生成与下载入口。
- `backend/src/main/resources/db/agent-migration/V4__agent_report_generation.sql`：报告时间列与 `model_call.report_id`。
