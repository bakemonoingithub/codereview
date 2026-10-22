# P33.1 原型复用与评审

状态：负责人已按 D-91 确认，P33.1 done。日期：2026-10-22（Asia/Taipei）。本轮未修改 Vue 页面或正式 API。

## 1. 指定基线

负责人已明确使用 [p29/prototype/index-agent.html](../p29/prototype/index-agent.html)，结合[代码定位与多段高亮原型](../code-reference-highlight/index.html)。原指定路径 `code-reference-highlight/index-agent.html` 不存在，已在会话中澄清；不重做布局，不创建另一份 Agent HTML 副本。

保留现有白色侧栏、主内容卡片、发起表单外壳、文字／文件／多提交范围选择、指导提示词、结果／执行记录／实际调用／任务输入页签，以及复制参数、引用旧结果和混合报告演示。本轮仅增加外部脚本 [prototype-extension.js](prototype-extension.js)，由原 HTML 相对加载，及内置示例指令的完成动作说明。

## 2. 可直接预览的场景

| 场景 | 入口 | 评审内容 |
| --- | --- | --- |
| 审查结果 | [完整结果](../p29/prototype/index-agent.html?agent=a1) | Markdown、代码证据、结果与报告区分、引用旧结果新建任务 |
| 多提交 | [非连续提交](../p29/prototype/index-agent.html?agent=a2) | 每次比较关系、第一父提交、各版本材料不混用 |
| 执行中 | [执行记录](../p29/prototype/index-agent.html?agent=a3) | 动作、已查范围、逐次调用、取消及演示下一步 |
| 限制终止 | [容量停止](../p29/prototype/index-agent.html?agent=a4) | 明确停止原因、保留过程、无完整报告候选 |
| 材料不足 | [完整提交的材料不足说明](../p29/prototype/index-agent.html?agent=a5) | 正常结束、已回答与未解决部分、允许带限制选材的设计方案 |
| 关键失败 | [读取失败](../p29/prototype/index-agent.html?agent=a6) | 错误记录与失败边界 |
| 发起 | [发起表单](../p29/prototype/index-agent.html?agent=form) | 可选指导提示词、必填要求、范围互斥、历史输入快照 |

页面底部情境控制为演示功能，不进入正式产品。参数只选择固定样本，不执行任何真实模型或仓库操作。

## 3. 本轮补齐

- 最后一次模型返回改为原生 `submit_result` 工具请求；实际调用页保留完整演示请求、工具定义、参数和返回。
- 「材料不足」样本补齐显式提交及固定正文，允许选为报告来源；冻结快照带回答情况与未解决事项。失败、取消和限制终止仍不发布完整候选。
- Agent 代码证据沿用代码高亮草稿：按同结果、路径、SHA、前后侧分组，默认固定版本全文；当前文件多处引用同时高亮，当前范围加深、原始行号置顶；支持保存片段、上一处／下一处及 Alt+方向键，端点禁用。
- 全文读取失败保留当前引用，提供重试和返回片段；弹窗不切其他文件，关闭后从正文或证据侧栏打开另一文件。
- 保留原有布局及设计 token；补齐没有引入独立规划步骤或流程编排界面。

## 4. 演示与正式设计的区别

HTML 使用 7 位版本、示例工具和固定代码，部分旧演示字段仍使用 `version` 中的 SHA、`commit/parent`；普通场景限额已更新为实验确定的默认值，边界情境保留较小演示限额。正式工具版本标识、`comparisonId` 及完整 envelope 以[技术设计](design.md)为准；不把示例协议直接搬成正式接口。目录选择仍以有限文件样本演示，正式目录目标的校验和覆盖按设计实现。累计计量文案已改为「累计用量」，不将 token 当作货币费用。

代码弹窗用固定数据模拟全文、片段与读取失败，没有真实接口的加载延迟、epoch、授权或大文件性能。本轮多段高亮覆盖 Agent 结果样本；既有报告冻结代码的原始阅读器仍是旧的演示实现，正式混合报告阅读及全部前后侧隔离须在 P33.5–P33.6 验证。

## 5. 浏览器自查

本轮 Chrome headless 23 项检查通过，覆盖结果及原生完成动作、引用分组、行号置顶、端点、快捷键、片段、失败重试、Esc、材料不足选材及冻结不随来源变化、多版本隔离、多提交关系、必填校验、取消和 1366／1920 宽度无页面横向溢出。检查只证明独立原型的演示逻辑，正式 Vue、真实数据及负责人已按 D-91 确认原型；正式页面视觉验收仍未执行。

已查看以下截图，确认既有布局保留、代码定位及表单阅读可用：

- [1366 结果页](evidence/result-1366.png)、[1920 结果页](evidence/result-1920.png)。
- [代码多段引用](evidence/code-references-1366.png)、[发起表单](evidence/launch-1366.png)。
- [材料不足](evidence/insufficient-1366.png)、[限制终止](evidence/limit-1366.png)、[实际调用](evidence/calls-1366.png)。

测试程序：[check-prototype.cjs](check-prototype.cjs)，结果：[prototype-checks.json](evidence/prototype-checks.json)。
