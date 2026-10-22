# P33 Agent 设计与实施入口

当前：P33.1 done，负责人已按 D-91 统一确认整体设计及原型（2026-10-22，Asia/Taipei）。P33.2 active，但本次仅更新文档与提交，不开展开发；后续需负责人明确指示开始。P33.3–P33.6 保持 todo。原型自查和协议探针不替代负责人批准或正式工程验收。

负责人已按 D-90 确认材料不足结果可选材、指定只使用 DeepSeek，并授权按数据库实际项目实验确定预算。实验、失败样本及默认值见[预算与接口对照实验](budget-calibration.md)；整体设计及原型已随后按 D-91 确认。Qwen 仅保留之前的历史探针证据，不再调用，也不要求补齐其配置。

## 阅读顺序

1. [业务契约第 4 节与 D-89](../business-contract.md#4-agent-分析的业务契约)：已确认业务边界。
2. [极简 Agent 调研](minimal-agent-research.md)：官方事实、工程建议及适用限制。
3. [P33.1 技术设计](design.md)：工具和完成协议、版本、提示词、预算、存储、接口及报告衔接。
4. [原型复用与评审](prototype.md)：负责人指定基线、补齐内容与可预览场景。
5. [预算与接口对照实验](budget-calibration.md)：真实项目、API／本地对照、质量问题与预算依据。
6. [验证记录](validation.md)与[验收样本](acceptance.md)：本轮证据、未验证项及正式实施的检查安排。

后续 P33 开发会话必须按上述顺序阅读，尤其核对实验报告中的失败样本、预算依据与未验证边界；本入口摘要不替代报告正文。

## 本轮评审要点

- 现有公共调用层扩展原生工具消息，一个任务执行器、固定五类只读工具和一个显式提交动作。
- 材料不足选材已按 D-90 确认；模型限定 DeepSeek，运行预算已按授权实验确定，不重新开放这三项选择。
- 其余工具资源限额及和公共任务、调用、反馈、报告的衔接方案；正式搜索和原子预算仍须实施验证。
- 沿用既有 Agent 页面，结合代码多段高亮，确认补齐后的结果、材料不足与实际调用展示。

以上均以设计中标明的批准属性为准，不将本入口的概要当作新的业务事实。负责人已按 D-91 确认设计及原型，STATE、ROADMAP 和契约已同步。P33.2 虽设为 active，本次不开展开发；正式实现及验收尚未执行。

## 产物

- HTML 入口：[既有 Agent 原型](../p29/prototype/index-agent.html)，通过相对路径加载本目录的 [prototype-extension.js](prototype-extension.js)，两者需一起保留。
- 代码阅读参考：[代码定位与多段高亮](../code-reference-highlight/index.html)。
- [浏览器检查程序](check-prototype.cjs)及[检查结果](evidence/prototype-checks.json)。
- [隔离协议探针](ProtocolProbe.java)及[执行脚本](run-protocol-probe.ps1)。脚本只在显式传入模型参数时发出真实模型请求，不自动随测试运行；默认无传输重试。
- [真实项目实验](budget-experiment.py)与[离线审计](audit-budget.py)；实验是临时工具桥，不进入正式业务执行链。
- 截图及脱敏协议证据见 [evidence](evidence/)。
