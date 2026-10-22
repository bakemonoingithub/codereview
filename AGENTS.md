# aiCodeReview 新路线工作区

## 会话接续

- 本目录 `H:\myProjects\aiCodeReview\agent` 是新路线 Git 工作树，后续开发均在本目录的 `refactor/agent` 分支进行。从本目录核对分支与工作区状态，保留现有未提交内容。
- 先读 [STATE](docs/STATE.md) 与 [ROADMAP](docs/ROADMAP.md)，再读[业务契约](docs/work/agent-redesign/business-contract.md)和[成果盘点](docs/work/agent-redesign/review-inventory.md)。业务以契约为准，实施顺序以本目录 ROADMAP 为准。
- `../code` 是旧 `refactor/review` 工作树，不从其 STATE、ROADMAP、P20/P18、历史授权或 skills 自动接续任务；外层 docs 也不是当前行动来源。
- 当前 active 以 STATE 与 ROADMAP 为准。P29–P32 与 P33.1 已完成；负责人于 2026-10-22 按 D-91 确认 P33.1 设计及原型，将 P33.2 设为 active，但明确本次不做开发。后续需负责人明确指示开始 P33.2 开发；P33.3–P33.6 保持 todo。先读 [P33 入口](docs/work/agent-redesign/p33/README.md)。后续阶段仍须明确启动，编制 ROADMAP、讨论或读取不构成启动业务开发的指令。

- P33 设计、实现或验收前，必须阅读[预算与接口对照实验](docs/work/agent-redesign/p33/budget-calibration.md)，核对失败样本、预算依据与未验证边界；不得只读状态摘要，也不得把实验工具桥的结果当作正式实现已通过验收。

## 已确认边界

- 原 32 项业务方向、全部成果复用范围及五批顺序均已确认，不重复讨论；技术参数、接口、算法和验收样本在相应设计阶段细化。
- P29–P32 依次完成共用设计与专项 HTML 原型、公共基础与新库结构、三类专项、反馈统计报告及专项验收；P33 最后开展 Agent 专属设计、HTML 原型、实现及验收。
- 界面先用独立 HTML 原型确认结构及展示，再进入框架实现；Agent 调用记录界面同样适用。
- 提示词由统一组装类集中处理。首版 Agent 不压缩上下文、不提供压缩继续或普通中断恢复，命中容量限制按契约终止。
- 来源源码只读固定提交 `8f20d6f24b9c22d3309ac1cb7bda1caf20e1bce0`，用 `git show <提交>:<路径>` 核对；不拿旧工作区未提交内容作为来源成果，也不整批迁入旧组件或提交。

## 文档、验证与工作区

- 中文文档遵循 `tech-doc-style-chinese`，新增文本使用 UTF-8 无 BOM、LF。一个业务事实在契约维护，其他文档引用；更新阶段状态时同步 STATE 与 ROADMAP。
- 按任务范围检查。纯文档修改验证编码、链接、编号及依赖一致性；本工作树没有旧管理脚本时，不为检查而整套导入旧框架。
- 工程检查、真实使用及 PC 用户视觉验收分别记录，不用旧测试数量或构建通过代替新路线验收。
- 不顺带修改任务外代码，不覆盖或清理原有未提交内容；数据库操作、服务启停和业务代码实施按负责人明确指定的工作范围执行。
