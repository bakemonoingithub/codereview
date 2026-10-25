# UPI-03/04 实施记录

日期：2026-10-25（Asia/Taipei）。分支：`dev`。业务范围见[契约第 9 节](../business-contract.md#9-提示词输入统一与策略退出2026-10-25)，原型与字段基线见[工作包](README.md)。

## 交付

- UPI-03：新任务请求直接指定必填模型，可选地指定专项提示词及具体版本；服务校验提示词用途、版本归属和正文，冻结模型修订与提示词正文。创建请求显式拒绝旧 `strategyId`，复制只支持 schema version 2，并回填新配置字段。提示词组装和专项执行路径改用“通用规则”语义。
- 移除策略专属的 v2 控制器、服务、DTO、实体、Mapper 及其测试；模型和提示词删除不再查询策略引用。历史迁移和策略表未修改，Agent／报告流程不增加通用规则字段。
- UPI-04：专项发起页直接选择模型、可选规则和具体版本，显示冻结正文，复制任务重新校验版本并保留已有输入保护。任务详情展示配置快照。侧栏不再提供策略管理，旧 `/strategies` 地址显示页面停用说明。
- 更新模型与提示词管理页文案，说明直接选择资源的使用方式；新界面不出现“无需选择策略”等解释。实际调用阅读提示改为描述冻结请求，不再引用策略。

## 工程检查

- 后端主代码编译通过。
- 后端定向测试：`TaskServiceTest`、`PromptAssemblyServiceTest`、`SpecialistTaskWorkerTest`、`AgentPromptServiceTest`、`AgentModelServiceTest`、`AgentListPaginationTest`，61 项通过；含旧 `strategyId` JSON 请求拒绝验证。
- 前端涉及的发起页、API、菜单、列表排序、分页、任务详情、提示词／模型管理和调用阅读测试共 100 项通过。
- Vite 隔离生产构建通过；构建提示仍有大于 500 kB 的既有分包警告。
- `vue-tsc --noEmit` 当前有 3 处错误，位于本次未改动的 `AgentLaunch.vue`、`ProjectDetail.vue`、`ProjectList.vue`；本次触及文件没有新增类型错误。
- `git diff --check` 通过。Vitest 与构建检查更新了 `frontend/components.d.ts`；该文件会话前已有本地改动，本轮保留现状供后续核对，没有用仓库版本覆盖。

## 未验证与边界

- 本记录编写时尚未执行数据库迁移、空库初始化、真实模型调用或正式页面 PC 视觉验收；UPI-05 后续进展见[验收记录](validation-20261025.md)。
- 旧 `ProjectDetail.vue` 及遗留的报告／快照组件仍含旧策略数据形状，依赖已关闭的旧 `/api` 接口，不属于本次 v2 新任务入口；没有批量改写历史组件。旧策略页地址已单独退出。
- 全新数据库初始化和既有任务切换策略仍需按验收计划确认；历史策略迁移表保留，不删除数据。
