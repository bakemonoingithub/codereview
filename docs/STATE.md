# STATE - 专项审查与 Agent 分析

跨会话接续入口；工作树为 `H:\myProjects\aiCodeReview\agent`，分支为 `refactor/agent`。

## 当前状态

当前: P30

P29 已于 2026-10-20 完成。负责人明确「我看完了，都批准通过，请设计ROADMAP」，批准本轮共用设计和专项及配套管理的最终 HTML 原型，依据为契约 D-88。

[ROADMAP](ROADMAP.md) 已按原五批顺序细化：P29 done，P30–P33 共 30 个实施子任务，均列出依赖、交付与阶段验收。2026-10-20 负责人要求先提交 P29 成果使工作区变干净，再启动 P30.1；P29 文档与原型已分三次提交（6b852a8、44abc7b、6c53b0f）。

P30 为 active。P30.1 已完成，交付[复用与功能差异](work/agent-redesign/p30/p30-1-reuse-diff.md)：页面、接口、数据三层的 45 项结论，v2 路径、错误结构、稳定 ID、游标分页、schema 版本与迁移身份。

P30.2 已完成，交付[新库结构与访问层](work/agent-redesign/p30/p30-2-schema.md)：`db/agent-migration/V1__agent_init.sql` 建 18 张表，独立数据源 `AgentDatabase` 与启动期身份检查，17 组实体/映射器、ULID 与游标工具、建库与检查脚本；新增 16 项单元测试通过。

P30.3 已完成，交付[配置资源 v2 接口](work/agent-redesign/p30/p30-3-config-api.md)：`/api/v2` 下项目、模型、提示词、策略四组接口，令牌留空保留且新值先验证、模型验证随修订失效、提示词正文只经版本写入、删除被引用返回 409；新增 25 项单元测试，全量回归 376 项通过。实际建库、HTTP 调用与真实模型探针未执行，留 P30.8。

## 已批准基线与验证范围

- [P29 工作记录](work/agent-redesign/p29/README.md)：共用数据／接口／新库设计、专项执行与三类结果方案及最终原型。
- 界面包括独立项目／模型／提示词／策略管理，专项范围弹窗和共用代码树，文件阅读与勾选分离，任务级完成与准确率信息，材料灰色 hash／行号及「查看代码」弹窗，实际调用阅读及多结果报告与冻结来源。
- 原型 31 组隔离逻辑检查通过，负责人原型评审通过；助手未通过工具完成浏览器渲染及截图，不将用户批准写成工具测试结果。
- 原型仍使用演示数据。目标仓库参数校准、真实模型／宿主、隔离数据库及正式页面 PC 验收尚未完成，已分别纳入 P30.8、P31.9、P32.6。Agent 专属设计、原型与真实混合报告联调仍在 P33。

## 接续依据

- [工作区规则](../AGENTS.md)：工作范围及阶段启动约定。
- [业务契约](work/agent-redesign/business-contract.md)：D-01 至 D-88、业务规则及批准边界。
- [成果盘点](work/agent-redesign/review-inventory.md)：固定来源及已确认取舍，不是整批迁移清单。
- [ROADMAP](ROADMAP.md)：实施子任务、依赖及完成条件；不从旧工作树的历史 active 接续。
- [P29 验收清单](work/agent-redesign/p29/acceptance.md)：最终批准、31 组原型检查及 A01–A24 的后续验证索引。
- [P30.1 复用与功能差异](work/agent-redesign/p30/p30-1-reuse-diff.md)：v2 接口、错误结构、稳定 ID、分页与迁移身份的当前实现契约。
- [P30.2 新库结构与访问层](work/agent-redesign/p30/p30-2-schema.md)：18 张表的 V1 迁移、独立数据源、身份检查与建库脚本。
- [P30.3 配置资源 v2 接口](work/agent-redesign/p30/p30-3-config-api.md)：项目、模型、提示词、策略的 `/api/v2` 接口与错误处理。

## 下一步

P30.4（本会话已授权，紧随 P30.3 执行）：把 `ProjectService` 的读取收敛为固定 SHA 的 `RepositoryReadService`，提供路径清单、修改前后侧与旧路径、完整性信息及按 `(project,sha,path,range)` 隔离的有界缓存；不实现 Agent 搜索工具。

原型简化或遗漏不自动构成删除已有功能的决定；已确认业务不重新开放，必要差异按 P30.1 第 7 节处理。

## 工作区保留

P29 文档与原型已提交（6b852a8、44abc7b、6c53b0f）。未跟踪的 `docs/work/agent-redesign/未决事项.xlsx` 保持原状，不纳入变更。来源源码仅从固定提交读取，不复制旧工作区未提交内容；`../code` 工作树保留其原有 2 处未提交改动，不纳入本工作树。数据库和真实使用的执行结果另行记录。
