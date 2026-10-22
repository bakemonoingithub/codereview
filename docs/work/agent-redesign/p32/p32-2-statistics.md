# P32.2 任务统计查询与准确率展示

状态：done（2026-10-21）。本地工程验证通过；真实数据库与 PC 正式页面验收归 P32.6。

## 依据与边界

- 统计范围限定单个 `taskId`，不提供跨任务总准确率。
- 问题级主体是完整问题结果中的问题；结果级主体是完整的模式／耦合审查结果。两类分别计数，不合成一个比率，也不恢复三模式效果对照。
- 只统计 `completeness=COMPLETE` 的正式结果；部分完成或已取消任务中已完成文件仍计入。
- 准确率 = 准确 ÷（准确 + 不准确）。未反馈、只有评论（评价为空）和无法判断分别展示，不进入分母。
- 分母为 0 时接口返回 `accuracyPercent=null`，页面显示「— · 暂无明确判定」，不显示 0% 或 100%。

## 接口

`GET /api/v2/review-tasks/{id}/statistics`

```json
{
  "taskId": "...",
  "specialistType": "ISSUE",
  "issues": { "total": 3, "accurate": 2, "inaccurate": 0, "unknown": 1, "unassessed": 0, "accuracyPercent": 100 },
  "results": { "total": 0, "accurate": 0, "inaccurate": 0, "unknown": 0, "unassessed": 0, "accuracyPercent": null }
}
```

实现只读取结果、问题与反馈的稳定 ID／评价，不加载结果正文。[TaskStatisticsService](../../../../backend/src/main/java/com/codereview/agent/service/TaskStatisticsService.java) 先按任务和完整性查询，再分别取问题 ID 与结果 ID，最后按 `subject_kind` 读取当前反馈。数据库条件之外再做一次完整性过滤，避免后续查询调整把未完成结果算进分母。

## 页面

任务详情在任务状态提示之后展示 [TaskAccuracySummary](../../../../frontend/src/components/TaskAccuracySummary.vue)：

- 问题审查显示「本任务问题判定准确率」及四个计数，说明仅统计已完成结果中的问题。
- 设计模式／耦合度显示「本任务模式结果判定准确率」或「本任务耦合结果判定准确率」，说明仅统计已完整发布的模式／耦合结果。
- 没有正式对象时不显示卡片；分母为 0 时显示暂无明确判定。
- 反馈保存成功后由结果页通知任务详情重新读取统计；读取失败保留上一次统计，不把未知写成 0。
- 任务切换、轮询和迟到响应通过独立 epoch 隔离，旧任务的统计不会回填到新任务。

## 验证

- 后端 `TaskStatisticsServiceTest` 5 项：问题／结果分开、只有评论与未反馈、四舍五入、无完整结果、任务不存在。
- 前端 `TaskAccuracySummary.spec.ts` 3 项与 `TaskDetail.spec.ts` 新增 2 项：比例与计数、空分母、模式／耦合取结果级计数。
- 全量结果见 [P32 工作记录](README.md)。

## 审查修复

2026-10-21 审查修复：结果摘要补选 completeness，避免数据库未映射该列导致所有结果被过滤；统计刷新失败时保留旧值并同时提示；任务按自身保存通知刷新，编辑器卸载不再丢刷新。新增数据库列映射回归及任务通知、错误提示、迟到统计回归，详见[修复与复验记录](p32-1-3-review-fixes.md)。

## 未覆盖

- 真实 MySQL 查询、真实任务数据与 PC 正式页面按 P32.6 执行。
- 任务列表不展示准确率；本任务只承诺任务详情限定 taskId 的查询，不新增跨任务聚合。
