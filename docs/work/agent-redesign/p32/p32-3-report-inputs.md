# P32.3 报告候选选择与输入冻结

状态：done（2026-10-21，后端能力）。独立报告表单与阅读页面归 P32.5；正文组装与生成归 P32.4。

## 依据与边界

- 候选只来自 `review_result.completeness=COMPLETE` 的已发布结果。任务处于 `PARTIAL`、`CANCELLED` 等状态时，其中已完成文件仍可作为材料；未完成结果不进入候选，也不能冻结。
- 跨任务、类型与版本可追溯：候选返回项目、任务、任务状态、来源类型、分支、目标路径与 head/base SHA；冻结快照保存同一组来源字段。
- 冻结的是生成时的材料，不是对最新数据的引用：结果正文、问题、来源 JSON（含材料 ID／路径／版本／行号引用）、任务与目标版本、当时的问题级或结果级评价及评论都写入快照。之后修改反馈或配置不改写已冻结来源。
- 本阶段不选择模型、不组装提示词、不生成正文；`report.state` 固定为 `INPUT_FROZEN`。

## 接口

| 方法与路径 | 用途 |
| --- | --- |
| `GET /api/v2/reports/candidates` | 游标分页读取完整结果；支持 `projectId`、`taskId`、`kind`（ISSUE／PATTERN／COUPLING／AGENT）、`cursor`、`limit` |
| `POST /api/v2/reports/inputs` | 按所选结果创建冻结输入，返回 201 与 `Location: /api/v2/reports/{id}/inputs` |
| `GET /api/v2/reports/{id}/inputs` | 读取已保存的冻结来源，不联查最新结果或反馈 |

冻结请求：

```json
{ "title": "订单服务专项审查报告", "resultIds": ["01...", "01..."] }
```

校验：标题非空且不超过 200 字符；结果 1–200 个；ID 形状合法、不重复；全部存在且完整，否则分别返回 404（不存在）或 409（不完整）。写入在单个新库事务内完成：`report` 一条、`report_source` 每条结果一条，`ordinal` 按请求顺序。

## 快照内容

`report_source.source_snapshot` 保存：

- `resultId`、`taskId`、`targetId`、`kind`、`schemaVersion`、`completeness`、结果发布时间；
- `project`：项目 ID、名称、仓库地址；
- `task`：路由、专项类型、任务状态、来源类型、分支、提交与比较基线；
- `target`：目标路径、旧路径、head/base SHA；
- `result.body` 与 `result.provenance` 原始 JSON；
- `materials`：按来源引用复制当时已保存的代码、hash、路径、SHA、前后侧、原始行号、完整性及缺失说明；不重新读取仓库，材料记录缺失时拒绝冻结；
- `issues`：每个问题的正文、类别、严重度、位置、来源单元以及当时反馈；
- `resultFeedback`：模式／耦合结果级当时反馈。

问题结果按问题冻结评价；模式／耦合按整个结果冻结。Agent 的 Markdown 固定样本始终按原文字符串保存，即使正文恰好符合 JSON 语法；专项正文、来源及问题位置的 JSON 结构损坏时明确失败。实际 Agent 协议与联调留到 P33。

`report.frozen_input` 另存摘要：冻结时间、标题、来源数量、各类型数量、任务列表、版本列表与结果 ID 列表，供列表与来源核对，不代替逐条快照。

## 验证

2026-10-21 审查后，全部输入读取与快照写入共用新库事务，补入代码证据、损坏数据拒绝及 Agent 原文保护。新增 3 项回归，详见[修复与复验记录](p32-1-3-review-fixes.md)；真实 MySQL 的一致性及回滚仍未执行。

- 后端 `ReportInputServiceTest` 7 项：部分完成／取消任务的候选、候选来源字段、冻结顺序与快照、Agent Markdown 正文、未完整／不存在／重复拒绝、冻结读取不联查最新结果、报告不存在。
- 未执行真实 MySQL 事务、报告表单页面与 PC 视觉验收；按 P32.5／P32.6 安排。
- 本任务不实现报告正文生成、模型选择、下载或来源跳转；这些分别由 P32.4、P32.5 承担。
