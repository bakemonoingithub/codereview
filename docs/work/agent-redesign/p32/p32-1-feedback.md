# P32.1 问题级／结果级反馈与独立评论

状态：done（2026-10-21）。本地工程验证通过；真实应用、数据库读写与 PC 正式页面验收归 P32.6。

## 依据与边界

- 问题结果按稳定 issue ID 保存；设计模式和耦合度按完整 result ID 保存。
- 评价值为 `ACCURATE`、`INACCURATE`、`UNKNOWN` 或空；评论独立于评价，可单独保存或清除。
- 不完整结果不可反馈；问题结果不提供整份结果级评价。
- 以 P29 原型的反馈区为 UI 基线：状态、三类评价、清除评价、可选评论、保存按钮、页面内输入保留及手动解决版本冲突。
- P31.9 仍未完成，本任务完成只表示反馈功能的本地工程验证通过。

## 实现

- `GET /api/v2/feedback/{subjectKind}/{subjectId}` 读取反馈；尚无记录时返回空值和 revision 0。
- `PUT /api/v2/feedback/{subjectKind}/{subjectId}` 用 `expectedRevision` 进行条件写入。新记录从 revision 0 写入为 revision 1；冲突返回 `REVISION_CONFLICT` 和当前版本，不自动覆盖。
- 新库已有 `feedback` 表及 `(subject_kind, subject_id)` 唯一键，无需新增迁移。
- 结果页为每个完整问题挂载独立反馈编辑器；模式／耦合结果显示结果级编辑器。不完整结果不显示反馈入口。
- 草稿按 task、主体类型及主体 ID 缓存在页面会话内；切换问题或文件可恢复输入。保存失败保留输入；离开任务详情时提示，离页确认后才丢弃草稿。
- 冲突时保留本地输入并展示服务端最新评价与评论；用户明确核对后沿用最新 revision 保存手工合并内容。
- 保存成功后通知任务详情刷新 P32.2 统计。

主要文件：[反馈接口](../../../../backend/src/main/java/com/codereview/agent/api/FeedbackV2Controller.java)、[反馈服务](../../../../backend/src/main/java/com/codereview/agent/service/FeedbackService.java)、[结果页编辑器](../../../../frontend/src/components/FeedbackEditor.vue)、[专项结果页](../../../../frontend/src/components/SpecialistResultView.vue)。

## 本轮验证修正

- 新主体读取失败时先清空表单，避免把上一个问题的评价或评论误存到新主体。
- 「尚未保存」改为与服务端基线比较，初始化和切换主体不再被误标为脏；保存响应不覆盖保存在途时的新输入。
- 补上任务详情测试的路由守卫桩，避免新增离开守卫后原 7 项回归失效。
- 结果页新增反馈保存事件，任务统计随评价更新。

## 验证

- 后端 `FeedbackServiceTest` 10 项：空读取、首次写入、清除评价、仅评论、旧 revision 冲突、条件更新、竞争失败、主体完整性、非法类型／ID、缺少 revision。
- 前端 `FeedbackEditor.spec.ts` 4 项：读取与保存、冲突保留输入、切换主体保留草稿、新主体读取失败清空旧值；`TaskDetail.spec.ts` 原有 7 项轮询与返回现场回归继续通过。
- 全量结果见 [P32 工作记录](README.md)。

## 审查修复

2026-10-21 审查修复：成功响应返回本次条件写入值，避免读到另一页面的后续版本；草稿保存基线，清空输入及读取失败仍受保护；冲突核对同步更新基线；卸载失效读取、保存成功按原主体结算并通知任务统计；首次读取失败提供重试，旧读取不回退成功保存值。新增后端 2 项、前端反馈 8 项及任务详情 4 项回归，详见[修复与复验记录](p32-1-3-review-fixes.md)。

## 未覆盖

- 真实应用启动、MySQL 读写、并发双页面保存和 PC 正式页面截图按 P32.6 执行；本任务不以组件测试替代真实页面验收。
