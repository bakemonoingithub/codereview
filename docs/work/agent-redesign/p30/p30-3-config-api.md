# P30.3 配置资源 v2 接口

状态：P30.3 完成（2026-10-20，Asia/Taipei）。依据 [P30.1](p30-1-reuse-diff.md) 第 6.2、6.3、6.7 节与 ROADMAP P30.3。本项只做后端接口，不改前端页面（P30.7 接入）。

## 1. 接口清单

全部挂在 `/api/v2`，只在配置 `agent.datasource.url` 时注册；未配置时返回 404，旧接口不受影响。

| 方法与路径 | 行为 |
| --- | --- |
| `GET /projects?q&cursor&limit` | 游标分页，按名称模糊匹配；不返回总数 |
| `POST /projects` | 校验仓库连通后创建；`201` + `Location`；地址重复 `409 CONFLICT` |
| `GET /projects/{id}` | 项目视图，只给 `credentialConfigured`，不回传凭据 |
| `PUT /projects/{id}` | 只改名称与新令牌；令牌留空保留，新值先验证再替换；`expectedRevision` 不符 `409` |
| `GET /models?q&cursor&limit` | 名称或模型名模糊匹配 |
| `POST /models`、`PUT /models/{id}` | 名称、地址、模型名、凭据、窗口、最大输出、tokenizer、协议；更新带 revision |
| `DELETE /models/{id}` | 被策略引用时 `409 CONFIG_IN_USE`，不级联破坏引用 |
| `POST /models/{id}/validate` | 连通探针；结果写入 `model_validation` 并绑定当前 revision |
| `GET /prompts?q&cursor&limit` | 名称、标签、描述模糊匹配 |
| `POST /prompts` | 事务内创建提示词与版本 1，并把 `currentVersionId` 指向版本 1 |
| `PUT /prompts/{id}` | 只改元数据（名称、描述、标签、用途），带 revision |
| `POST /prompts/{id}/versions` | 追加正文生成新版本并设为当前；`expectedRevision` 不符 `409` |
| `GET /prompts/{id}/versions` | 版本列表，按版本号倒序 |
| `GET /prompts/{id}/versions/diff?fromVersionId&toVersionId` | 复用 `TextDiff`，两版本必须同属该提示词 |
| `DELETE /prompts/{id}` | 被策略引用时 `409 CONFIG_IN_USE` |
| `GET /strategies?q&type&cursor&limit`、`POST/PUT/DELETE` | 专项类型 + 模型 + 可选提示词；类型只允许 `ISSUE/PATTERN/COUPLING` |

## 2. 关键行为

- **凭据只写不读**：请求接受 `credential`，响应只有 `credentialConfigured`；凭据以明文保存在写保护列（负责人 2026-10-20 决定，见 P30.2），探针使用服务端保存的凭据，不把凭据回显或写日志。
- **令牌替换先验证**：项目编辑提交新令牌时先调用宿主分支接口，成功后才在条件更新中替换；失败返回 `REPO_UNREACHABLE`，原值保持不变。
- **验证随修订失效**：`model_validation.config_revision` 与模型 `revision` 比对，视图状态为 `NONE / EXPIRED / PASSED / FAILED`，无需后台清理任务。
- **验证失败不是接口失败**：探针失败写 `FAILED` 记录并返回 200；只有模型不存在才是 404。验证范围明确为"可达、凭据被接受、返回 JSON"，不宣称完整窗口或工具协议。
- **提示词正文唯一入口**：正文只经 `POST /prompts/{id}/versions` 写入；历史版本不可修改，版本对比限制在同一提示词内。
- **策略引用校验**：模型必填且必须存在；提示词可选，但用途必须是 `SPECIALIST`，避免把报告或 Agent 提示词当成审查规则。
- **错误体**：`{code,message,retryable,requestId,details}`，HTTP 语义化；`requestId` 取链路追踪标识。

### 2.1 新增错误码

在 P30.1 目录基础上补 `REPO_UNREACHABLE`（422，不可重试）：仓库地址不可解析、宿主未接入或凭据被拒绝时使用，`details` 保留字段名或原因。

## 3. 验证记录

| 项目 | 状态 | 说明 |
| --- | --- | --- |
| 编译与新增单元测试 | 通过 | 新增 25 项：项目 6、模型 6、提示词 6、策略 4、错误映射 3；合计 agent 测试 41 项 |
| 全量回归 | 通过 | `mvn -o test`：376 项，0 失败，9 跳过（含原有 335 项基线） |
| HTTP 层真实调用 | 未执行 | 需要新库实例；接口形状由 DTO/控制器静态核对，实际请求验证留 P30.8 |
| 真实模型探针 | 未执行 | 未连接任何网关；`HttpModelProbe` 只做 8 token 的连通请求 |
| revision 并发 | 单元覆盖 | 以条件更新影响行数为准；多节点同时更新未实测 |

## 4. 交接

- P30.5：模型视图与 `model_config` 是容量判断输入；统一组装读取 `context_window`、`max_output`、`tokenizer`。
- P30.6：任务创建引用 `review_strategy` 与 `model_config`，需继续使用 revision 条件。
- P30.7：前端按这组接口接入四个管理页；页面级搜索、分页与错误重试按 `q`、`cursor`、错误体实现。
