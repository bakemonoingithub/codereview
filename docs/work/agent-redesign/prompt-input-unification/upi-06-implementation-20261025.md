# UPI-06 实施记录

日期：2026-10-25（Asia/Taipei）。工作树：`H:\myProjects\aiCodeReview\code`，分支：`dev`。

## 实施内容

- 报告新建与重新生成表单增加可选 REPORT 通用规则、具体版本和固定正文预览；界面顺序为通用规则在上、报告补充要求在下。
- 规则列表按分页读取并只展示 REPORT 用途；规则、版本切换期间清理旧预览，加载失败、错用途或版本不可用时阻止提交。
- 生成 API 接收成对的 `promptId`／`promptVersionId`。后端校验规则用途、版本归属及非空正文，并在报告进入 `QUEUED` 的同一条件更新中冻结规则身份、名称、版本和正文。
- 冻结规则保存在 `instruction_snapshot.prompt`；新增 V8 将 `instruction_snapshot` 扩大为 `MEDIUMTEXT`。本次报告要求仍独立保存，来源快照和来源顺序没有改动。
- 执行器只读取报告快照并将冻结规则传给 REPORT 提示词组装；详情显示当次规则、版本、正文及补充要求。旧报告兼容缺少 `prompt` 的历史快照。
- 报告系统提示声明优先级为内置事实与引用约束、本次补充要求、通用规则；冻结材料作为只读数据。未选择规则时维持原生成路径。

## 工程检查

- 后端定向测试：`ReportGenerationServiceTest`、`ReportExecutionWorkerTest`、`PromptAssemblyServiceTest`，21 项通过。
- 前端定向测试：`ReportCreate.spec.ts`、`ReportDetail.spec.ts`，17 项通过。
- Vite 生产构建通过，输出写入 `frontend/node_modules/.cache/upi06-build`，未写入后端静态资源；构建提示存在既有大体积分块警告。
- `vue-tsc --noEmit` 仍有 3 项类型错误：`AgentLaunch.vue` 的未使用变量、`ProjectDetail.vue` 的 `CommitInfo` 类型不匹配、`ProjectList.vue` 的未使用导入；均不在 UPI-06 改动文件中。修复 UPI-06 新增的 `ReportDetail.vue` 响应式状态类型问题后，报告页面不再报错。
- `git diff --check` 通过；仅出现仓库行尾格式提示。

## 尚未验收

- 尚未启动后端或执行 V8 数据库迁移；后端服务保持停止。
- 尚未运行真实模型报告或隔离库初始化，也未验证混合专项／Agent 来源的实际生成；不将这些项目记为通过。
- PC 正式页面视觉验收由负责人执行。完成后再按 UPI-06 矩阵补齐业务记录并决定是否关闭该工作项。
