# 前端改动方案

业务依据：[契约第 9 节](../business-contract.md#9-提示词输入统一与策略退出2026-10-25)。任务及验收：[工作包](README.md)。本文为待实施方案。

## 1. 原型与交互

UPI-02 独立 HTML 原型见 [prototype.html](prototype.html)，接口字段见 [interface.md](interface.md)。原型覆盖专项类型、范围、模型、可选通用规则、具体版本、正文预览及逐文件说明。保留已有范围选择、提交弹窗和智能拆分，不重新设计这些流程。负责人确认结构和展示后才进入 Vue 实现。

模型和规则使用独立控件；提示词改变时重置并加载其版本，默认当前具体版本；清空提示词同时清空版本。复用 `PromptVersionSelect.vue`、`PromptInfoPreview.vue`，专项新入口不提供跟随当前版本选项。正文加载失败、无有效版本及关联校验未完成时阻止携带该规则提交，保留其他输入。切换提示词、关闭页面后忽略迟到响应。

## 2. 代码定位与修改顺序

路径均相对当前 `code/`。

| 位置 | 计划调整 |
| --- | --- |
| `frontend/src/views/ReviewLaunch.vue` | 移除策略选择及模型绑定；直接发送模型、提示词和版本；复制回填按新接口处理 |
| `frontend/src/components/PromptVersionSelect.vue`、`PromptInfoPreview.vue` | 优先复用；若修改公共行为，回归 Agent 现有选择及预览 |
| `frontend/src/router/index.ts`、`layouts/BasicLayout.vue` | 移除策略菜单和路由；旧地址落到已有未找到页面或明确退出提示 |
| `frontend/src/views/StrategyList.vue`、`components/StrategyAssociationInfo.vue` | 搜索全部引用后删除不再使用的策略专属代码；不误删公共预览能力 |
| 新路线 API 类型、任务详情与复制入口 | 配置摘要显示模型、规则名称及版本；不以当前提示词正文替代冻结正文 |

实施前用 `rg` 核对真实调用链。仓库仍有旧 `ProjectDetail.vue` 等策略字符串，先确认是否属于当前路由和新接口，不因同名就整批改写历史模块。

## 3. 接口协作

UPI-02 定稿专项 `promptId`、`promptVersionId` 字段及模型必填；名称最终与后端一致。新任务详情应能显示冻结的规则名称、版本号及正文，优先复用快照读取能力。复制仅保证调整后任务；不支持的试运行任务显示「该试运行任务不支持复制，请重新创建」，不拼装猜测配置。

保持当前幂等提交和重复点击保护，影响请求内容的规则／版本变化必须提交新内容；表单不得复用错误的旧请求标识。模型切换不清空规则，专项类型切换保留仍符合 SPECIALIST 用途的规则。

## 4. 报告后续项 UPI-06

仅负责人明确启动后修改 `ReportCreate.vue`、`PromptList.vue` 及相关 API 类型。增加报告用途筛选、规则／版本及预览，沿用现有结果选择弹窗。规则配置随生成请求提交，来源冻结仍使用既有选材流程；生成失败后输入保留及重试沿用报告状态约束，不重新冻结来源。`ReportDetail.vue` 展示本次实际配置。

Agent 继续使用现有字段和流程；如统一可见名称为「通用规则」，同时说明用途，避免为命名统一改写稳定接口。

## 5. 验证和交付

覆盖 ReviewLaunch、菜单、任务配置／复制及公共提示词组件相关测试；报告后续项另验 ReportCreate、ReportDetail、PromptList。执行类型检查和隔离生产构建。最近记录存在 4 处类型检查基线问题，接手时重测基线；本次触及文件中的问题应处理，其余逐项说明，不直接宣称完整检查通过。

交付记录列出 HTML 原型确认、工程检查、正式 PC 视觉和真实接口状态。构建不覆盖运行服务静态资源，测试后核对 `components.d.ts`，保留接手前改动。
