# SRF 字段与消费者定稿

日期：2026-10-27。适用工作项：SRF-02–05。业务规则见[契约第 11 节](../business-contract.md#11-专项审查结果展示与逐条反馈2026-10-27)。

## 1. 本轮范围

负责人要求「请完成 SRF-02 到 SRF-05 的开发」。据此实施必要后端、新表迁移、历史回填能力、统计和新旧报告快照衔接，以及专项结果 tab。此指令替代此前仅限前端的开发限制；现有库升级、服务重启和内网部署不在本轮执行。

tab 外采用以下最小数据适配，作为本轮实现选择：

| 消费者 | 适配内容 | 展示边界 |
| --- | --- | --- |
| `TaskAccuracySummary.vue` | 模式使用 `patterns`，耦合使用 `couplings`；标题和口径说明随统计主体调整 | 保留原位置和结构；旧接口数据仍按旧单位显示 |
| 报告候选 | 新增 `itemFeedback` 聚合；候选反馈单元格显示四类条目计数 | 保留现有筛选、分页、选择及表格结构 |
| 报告创建预览 | 公共结果组件显式启用 `itemFeedback`，以服务端条目 ID 读写反馈 | 沿用现有结果结构；默认公共行为保持兼容 |
| 报告冻结来源 | 第 2 版按 `items` 显示当时逐条反馈；旧版继续读 `resultFeedback` | 沿用现有结果／材料／当时反馈三个页签 |
| 报告事实与来源摘要 | 第 2 版使用逐条反馈，模式、耦合分别聚合 | 不修改已冻结报告，不将新计数写入旧结果级字段 |

任务头部的其他内容、目标文件列表、输入与配置、实际调用和 Agent 页面不改。

## 2. 条目身份与发布

追加 `V9__review_result_item.sql`，不修改 V1–V8。

| 字段 | 类型 | 约束与含义 |
| --- | --- | --- |
| `id` | `CHAR(26)` | 主键；服务端生成的稳定 ULID |
| `result_id` | `CHAR(26)` | 所属不可变 `review_result` |
| `kind` | `VARCHAR(20)` | PATTERN 或 COUPLING |
| `ordinal` | `INT` | 最终已发布正文中的序号，从 1 开始 |
| `created_at` | `DATETIME(3)` | 建立索引的时间 |

唯一键为 `(result_id, kind, ordinal)`。表内不复制分析正文；问题继续使用 `issue.id`。完整模式结果的 `patterns` 与完整耦合结果的 `observations` 在合并、去重后建索引。空数组不建条目；未完成结果与 Agent 不建条目。索引写入加入既有发布事务，发布失败时整体回滚。

## 3. 启动与历史回填

初始化顺序：数据库身份检查 → Flyway 全链 → MyBatis 与事务模板 → 条目回填 → 数据库 Bean 可供业务组件使用。回填失败时关闭数据库连接池并阻止业务启动，调度器不能先取得可用数据库。

按结果 ID 每批读取 100 份完整模式／耦合结果，每份结果单独事务。先锁定结果行，再读取和核对全部索引。已存在的 ID 保留；缺失序号补齐；类型、归属、重复序号、越界、正文损坏或正文类型不符明确失败，并记录结果 ID。进程中断后重新扫描，已提交结果再次核对，未提交结果由事务回滚后重试。多实例通过相同结果行锁串行补建，唯一键再提供数据库约束。

日志记录 `scanned`、`repaired`、`unchanged`、`added`、`failed`，不记录正文或凭据。空库扫描为零。原正文、历史 RESULT 反馈及冻结报告均不改写。真实数据库的行锁、事务回滚和多实例行为仍须在 SRF-07 验证。

## 4. 结果与反馈接口

`GET /api/v2/review-tasks/{taskId}/targets/{targetId}/result` 在原 DTO 上追加：

```json
{
  "items": [
    { "id": "服务端生成的条目ID", "kind": "PATTERN", "ordinal": 1 }
  ]
}
```

`body`、`issues` 和 `schemaVersion` 原义不变；模型输出协议不新增 ID。问题、未完成结果返回空 `items`。完整模式／耦合结果的索引不完整时返回 `INTERNAL_ERROR`，不能给出虚假的可保存状态。

反馈仍使用 `GET/PUT /api/v2/feedback/{subjectKind}/{subjectId}`，新增 PATTERN、COUPLING 两种主体。`subjectId` 必须属于对应类型的完整结果，且索引通过完整性核对。保存字段仍为 `assessment`、`comment`、`expectedRevision`；revision 条件更新及首次插入竞争保护沿用原实现。

不存在的条目返回 `NOT_FOUND`；类型不匹配或所属结果未完成返回 `CONFLICT`；索引损坏返回 `INTERNAL_ERROR`；反馈版本过期返回 `REVISION_CONFLICT` 及最新反馈。ISSUE 和历史 RESULT 接口继续兼容。新结果 tab 和报告创建预览均不向 RESULT 主体保存新反馈。

## 5. 统计字段

任务 `/statistics` 保留 `issues`（问题数）、`results`（整份模式／耦合结果数），追加 `patterns`（模式候选数）、`couplings`（依赖解读数）。四个字段的结构均为：

```text
total, accurate, inaccurate, unknown, unassessed, accuracyPercent
```

条目统计从索引表枚举全部完整结果主体，再读取同种类反馈；不解析全部正文，不遗漏没有反馈行的条目。只评论且没有评价的条目计入 `unassessed`。准确率按业务契约计算，空分母返回 null。历史 RESULT 反馈不计入 `patterns` 或 `couplings`。

报告候选追加可空的 `itemFeedback`，字段结构相同；仅完整模式／耦合候选返回条目计数。原 `feedback` 字段继续表示历史整份结果反馈，前端对新候选优先显示 `itemFeedback`，不改变原字段含义。

## 6. 新旧报告快照

新报告的 `source_snapshot.snapshotVersion` 和冻结总输入 `version` 为 2。来源保存 `items: [{id, kind, ordinal, feedback}]`，`feedback` 含冻结时的评价、评论、revision 和更新时间，未反馈时为 null。正文、代码材料、问题和历史 `resultFeedback` 仍保留。

第 2 版模式／耦合的来源摘要及事实只使用 `items`，按 ordinal 关联当时正文。事实包含条目标识、类型、序号、名称／标题及当时反馈；问题、模式候选、依赖解读分别聚合，历史整份评价不折算为条目评价。条目数量、标识、类型和序号异常时拒绝构造完整事实。

未声明版本或第 1 版继续按原问题／整份结果结构解释。读取历史来源不联查当前反馈，不重写旧报告；第 2 版快照冻结后也不随当前反馈改变。
