# P33.5 进度、调用记录、Markdown 结果与来源阅读

状态：工程实现与本地回归完成；真实接口核对待重启后回填。日期：2026-10-22（Asia/Taipei）。

接续：[P33 入口](README.md)、[技术设计第 5、8 节](design.md#5-显式结果提交及证据)、[P33.4 执行](p33-4-execution.md)、[ROADMAP 第 7 节](../../../ROADMAP.md#7-p33-agent-专属设计html-原型实现及验收)。

## 1. 读取接口

| 接口 | 返回 | 边界 |
| --- | --- | --- |
| `GET /api/v2/agent-tasks/{id}/progress` | 状态、停止原因、轮次、工具／网络尝试、当前动作、已查覆盖、冻结限额 | 只读本任务；任务必须为 `AGENT` 路线 |
| `GET /api/v2/agent-tasks/{id}/tool-calls?pageNum=&pageSize=` | 页码列表（默认 20、上限 100），只含轮次／序号／工具／状态／时间 | 不放大参数与返回正文 |
| `GET /api/v2/agent-tasks/{id}/tool-calls/{callId}` | 单次调用的参数、完整 envelope、材料 ID、状态与时间 | 校验调用属于本任务 |
| `GET /api/v2/agent-tasks/{id}/result` | 已发布结果：正文 JSON（标题、摘要、答案状态、Markdown、引用、未解决事项）与 `provenance` | 未发布时 404，不伪造空结果 |
| `GET /api/v2/review-tasks/{id}/materials/{materialId}` | 材料正文与元数据（复用公共端点） | 通过 `call_material` 校验材料属于本任务 |

进度来自 `agent_run`，限额来自任务冻结的 `configSnapshot.limits`；结果只读 `review_result(kind=AGENT)`，不在读取时重建正文。

## 2. 前端

- `/reviews/{id}` 先读取任务路线：`AGENT` 渲染 Agent 详情，专项继续使用原详情页，避免两套页面互相污染。
- Agent 详情默认展示「结果」：标题、摘要、已回答／材料不足标签、未解决事项、Markdown 与来源列表；点击来源打开固定版本材料阅读，行号来自保存的材料范围。
- 「调用记录」按轮次／序号分页；点击行打开抽屉，显示原始参数与 envelope，材料 ID 可直接打开阅读。
- 「进度」展示轮次、工具／网络尝试、当前动作与已查覆盖，并列出本次冻结限额；运行中每 2 秒轮询，终态停止。
- 终态但没有完整结果时明确显示停止原因，并说明只保留调查过程、不进入报告候选。
- 支持取消（按当前修订号）与 Markdown 下载；不把 Agent 结果自动标为已生成报告。

## 3. 工程检查

- 后端新增回归：读取服务 5 项（进度与限额、调用分页与归属、结果读取、非 Agent 拒绝、未发布结果）。
- 前端新增回归：Agent 详情 5 项（结果与来源、无结果提示、轮询转终态、调用详情、取消修订号）。
- 全量后端 788 项、0 失败、0 异常、9 跳过；前端 64 个文件、506 项通过；`vue-tsc --noEmit` 与 `vite build` 通过。跳过项不计为通过。

## 4. 真实接口验证

2026-10-22 17:13（Asia/Taipei）在本机服务（8081，P33.5 代码）读取 P33.4 的真实任务：

- 成功任务 `01M5HDEK…`：进度 `SUCCEEDED`、5 轮、7 次工具、5 次网络、限额 48；调用记录 9 条（7 次只读 + 2 次提交尝试），首条 `tree`、末条 `submit_result:OK`；单次详情返回参数、`envelope.status=OK` 与材料 ID。
- 结果：`ANSWERED`、17 个引用、3 条未解决、Markdown 2913 字符、`provenance.toolCalls=7`；引用材料可经公共端点读取（`ReviewTaskV2Controller.java` 39–43 行，284 字节，完整）。
- 失败任务 `01M5HD85…`：进度 `PARTIAL / PROTOCOL_INVALID`、14 轮、18 次工具；结果端点返回 404，没有伪造空结果。

## 5. 未验证与后续

- PC 正式界面视觉、同文件多引用高亮、长列表滚动与并发双页面属 P33.6。
- 报告选材、冻结、引用校验与混合报告联调仍未执行。
