# STATE - 专项审查与 Agent 分析

跨会话接续入口；工作树为 `H:\myProjects\aiCodeReview\agent`，分支为 `refactor/agent`。

## 当前状态

当前: -

成果复用讨论及五批顺序已确认，[ROADMAP](ROADMAP.md) 已编制。P29–P33 均为 todo，尚未启动设计、HTML 原型或业务开发。原 32 项业务方向均已确认，剩余为契约第 8.3 节所列的技术设计与验收细化。

## 接续依据

- [工作区规则](../AGENTS.md)：会话入口及工作范围。
- [业务契约](work/agent-redesign/business-contract.md)：业务规则、D-01 至 D-87 及复用边界。
- [成果盘点](work/agent-redesign/review-inventory.md)：固定版本源码事实、已确认取舍及依赖。
- [ROADMAP](ROADMAP.md)：P29 共用设计与专项 HTML 原型 → P30 公共基础与新库结构 → P31 三类专项 → P32 反馈统计报告及专项验收 → P33 Agent，Agent 相关内容最后开展。

## 下一步

负责人明确要求启动后，从 P29 的共用设计与专项 HTML 原型开始，按 ROADMAP 细化并执行。当前不得因读取本文件自动启动阶段，也不从旧分支 P20/P18 或其他历史 active 继续开发。

## 工作区与验证

- 负责人已明确要求后续开发均在本工作树的 `refactor/agent` 分支进行，并授权提交业务契约、成果盘点、ROADMAP、工作区规则及本状态文件。未跟踪的 `未决事项.xlsx` 继续保留，不随本次文档提交纳入。
- 本轮只维护文档和修正会话入口，未实施业务代码、操作数据库或运行服务。
- 新路线实际开发与业务验收尚未开始，来源版本的历史成绩仅作参考。
