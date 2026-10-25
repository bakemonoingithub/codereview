# UPI-02 专项接口字段定稿（原型评审稿）

依据：业务契约第 9 节、当前 `TaskDtos`／`TaskService`、`frontend/src/api/v2.ts` 及 `PromptVersionSelect.vue`。本稿是 UPI-02 的接口对齐建议，需随原型确认后冻结；不代表接口已实现。

## 发起请求

`POST /api/v2/review-tasks` 保持异步创建、既有范围字段、目标逐文件说明、智能拆分及 `Idempotency-Key`。

| 字段 | 新约定 |
| --- | --- |
| `modelConfigId` | 必填，最大 26 字符；模型独立于通用规则选择。 |
| `promptId` | 可选，最大 26 字符；选定用途为 `SPECIALIST` 的一份通用规则时填写。 |
| `promptVersionId` | 可选，最大 26 字符；与 `promptId` 同时填写，且必须属于该规则。 |
| `strategyId` | 新请求不再接受；若出现则明确返回校验错误，不静默忽略。 |

业务校验还需确认模型可用、提示词用途正确、提示词及版本有效且版本正文非空。规则为空时不发送两个提示词字段。固定具体版本，不增加「跟随当前版本」。将上述字段纳入幂等请求指纹。

```json
{
  "projectId": "...",
  "route": "SPECIALIST",
  "specialistType": "ISSUE",
  "source": { "kind": "FILES", "branch": "dev", "observedSha": "..." },
  "targets": [{ "path": "src/OrderService.java", "note": "核对重试与幂等" }],
  "modelConfigId": "...",
  "promptId": "...",
  "promptVersionId": "...",
  "smartSplit": false
}
```

项目、范围、提交或文件、目标顺序、拆分开关及文件说明均沿用现有结构。提示词版本读取复用 `GET /api/v2/prompts/{id}/versions`，返回版本 `id`、`versionNo`、`content`、`createdAt`；按当前版本默认选中，加载失败或无可用版本时禁止带规则提交。

## 任务配置摘要及冻结正文

`GET /api/v2/review-tasks/{id}` 的 `config` 由策略摘要改为直接配置摘要：

```json
{
  "config": {
    "modelConfigId": "...",
    "modelName": "DeepSeek Flash",
    "modelRevision": 2,
    "promptId": "...",
    "promptName": "Java 服务端审查规范",
    "promptVersionId": "...",
    "promptVersionNo": 4,
    "promptContent": "冻结时的提示词正文"
  }
}
```

未选择规则时，五个 `prompt*` 值为 `null`。正文从任务 `configSnapshot` 读取，不从提示词当前版本重新取值；详情页因此可展示创建时实际执行的正文。`configSnapshot` 继续冻结模型 API 配置及修订、提示词 ID／名称、版本 ID／编号／正文。删除策略快照键，不迁移旧任务快照。

## 复制参数

`GET /api/v2/review-tasks/{id}/copy-input` 对 UPI 新快照返回直接可编辑配置：

```json
{
  "modelConfigId": "...",
  "promptId": "...",
  "promptVersionId": "..."
}
```

实际响应仍包含既有项目、路线、专项类型、范围、目标说明、拆分及名称字段。复制时不回填冻结正文；页面重新加载可用模型与提示词版本，验证用途、归属及版本有效性。文件范围重新观察 HEAD；失效配置不自动替换。历史试运行快照若缺少可判断的新配置标识，应明确提示重新创建，不猜测旧策略关联。

## 页面状态映射

- 专项类型不改变模型列表或规则选择；模型与规则各自选择、各自校验。
- 规则清空时同步清空版本、正文及版本错误；规则切换期间清空旧版本并加载新规则版本，忽略迟到响应。
- 选择规则后，在版本选择器旁预览选定版本正文；不选规则显示「仍使用内置审查边界和输出协议」。
- 详情读取 `config.promptContent` 展示冻结正文；复制页只在接口校验完成后回填并允许提交。
- 模型失效或提示词版本不可用时，保留其他表单内容，阻止提交并提供重选提示。
