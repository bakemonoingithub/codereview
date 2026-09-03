# AI 网关技术实验报告

> 实验分支：`experiment/ai-gateway` · 对应里程碑：M0「技术小实验」任务 4
> 日期：2026-09-03 · 执行人：DSH（模型）· 复核：杨思逊

## 1. 实验目的

在进入业务开发前，验证 M0 的两个技术小实验之二：

> **AI 网关连通 + 稳定返回结构化 JSON。**

本实验直接调用 DeepSeek 的 OpenAI 兼容接口，验证两件事：

1. **连通性**：能否用 API Key 成功调用 `https://api.deepseek.com` 的 chat 接口；
2. **结构化输出**：能否稳定（多次、可复现）返回可解析、可校验的结构化 JSON——这是全系统最关键的未知点（M1 明确列为「全系统最关键的未知；不行就换 JSON mode / function call」）。

## 2. 实验环境

| 项 | 值 |
|---|---|
| 接口地址 | `https://api.deepseek.com/chat/completions`（OpenAI 兼容） |
| 请求模型 | `deepseek-chat`（官方别名；实测响应 `model=deepseek-v4-flash`，属 v4 系列 flash 档） |
| 鉴权 | `Authorization: Bearer <DEEPSEEK_API_KEY>` |
| 结构化模式 | `response_format = {"type": "json_object"}` |
| 温度 | `0`（提升可复现性） |
| 密钥来源 | Windows 用户环境变量 `DEEPSEEK_API_KEY`（注册表 User 作用域） |
| HTTP 客户端 | JDK 11 `java.net.http.HttpClient`（最小依赖；生产改 Spring RestClient/WebClient） |
| 代码位置 | `code/backend/src/main/java/com/codereview/gateway/AiGatewayProbe.java` + 同名测试 |

## 3. 实验方法

分两步：

1. **冒烟测试（PowerShell）**：从注册表 User 作用域读取密钥，用 `Invoke-RestMethod` 直连接口，确认密钥有效、网络可达。
2. **后端探针 + 断言测试（`mvnw test`）**：
   - `connectivity()`：1 次调用，断言 `finish_reason=stop`、返回内容可解析为 JSON 且含预期字段；
   - `structuredJsonReview()`：给一段 Java 代码 + 固定 JSON schema，**连续 3 次**调用，每次都断言返回合法 JSON 且 `issues[]`/`summary` 及 issue 内 `severity/title/suggestion` 字段齐全。

> 密钥从环境变量 `DEEPSEEK_API_KEY` 读取（测试内 `System.getenv`），运行命令里不出现密钥明文。

## 4. 实验结果

### 4.1 冒烟测试（连通性）

```
HTTP OK | model: deepseek-v4-flash | finish_reason: stop
prompt_tokens=62 completion_tokens=39
content: {"severity":"info","category":"format","title":"No issues found","suggestion":"The code appears to be well-formed and follows best practices."}
```

→ 密钥有效、网络可达、`json_object` 模式返回合法 JSON。

### 4.2 断言测试

```
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0  →  BUILD SUCCESS
connectivity OK | model=deepseek-v4-flash | tokens=55/5 | content={"ok": true}
structuredJson OK 第 1 次 | issues=6
structuredJson OK 第 2 次 | issues=2
structuredJson OK 第 3 次 | issues=6
```

| 指标 | 结果 |
|---|---|
| 连通性 | ✅ HTTP 200，`finish_reason=stop` |
| 结构化 JSON | ✅ **3/3 次**返回合法 JSON，`issues[]` + `summary` + issue 字段齐全 |
| 稳定性 | ✅ 温度 0 + `json_object` 模式下连续 3 次结构一致 |

## 5. 关键结论

| 子问题 | 结论 |
|---|---|
| 网关能否连通？ | **能**。`api.deepseek.com` 直连成功，鉴权正常。 |
| 能否稳定返回结构化 JSON？ | **能**。`response_format=json_object` 生效，3/3 次结构合法、字段完整。 |

### 必须记录在案的 4 条边界结论（影响后续 M1/M4 实现）

1. **`json_object` 模式需在提示词里出现「json」字样**：DeepSeek 要求启用 JSON 模式时 prompt 须包含 "json"，否则可能拒绝或返回非 JSON。→ 后续审查/报告的提示词模板里要保留 "JSON" 关键词。

2. **模型别名映射**：请求 `deepseek-chat`，响应 `model=deepseek-v4-flash`（v4 系列 flash 档）。→ `model_config` 模块的「model_name」字段需允许别名，保存时用最小请求实测回显真实模型名（呼应原型设计「验证连通并回显成功/失败」）。

3. **温度 0 + 固定 schema 是稳定性的关键**：结构化抽取类任务（审查结果、报告章节）建议默认 `temperature=0`，并给出明确的字段 schema，可显著降低「偶发返回格式漂移」。

4. **密钥获取方式**：Windows 用户变量晚于 DSH 进程写入时，子进程不自动继承，需用 `[Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY','User')` 显式读取。→ 生产部署时密钥放服务端环境变量/配置（呼应 `系统方案设计.md` 风险 5「凭据安全」），不落库明文。

## 6. 与后续里程碑的衔接

- **M1**：本探针的 `chat()` 能力直接用于「单文件 LLM 审查」——传入「方法模板 + 用户提示词 + 单元代码」，返回结构化 JSON 落 `review_record.result_json`；届时验证「DS 结构化输出（JSON mode / function call）」。
- **M4**：升级为 `model_config` 模块——base_url/token/model_name 落库（token AES 加密），保存时发最小请求验证连通并回显真实模型名；HTTP 客户端切换为 Spring `RestClient`/`WebClient`。

## 7. 遗留待办与风险

| 项 | 状态 |
|---|---|
| JSON Schema 校验库（M1） | 本实验用 Jackson 手写校验；M1 起可评估引入 JSON Schema 校验 |
| DS 上下文窗口实测 | 已由崔延盟实测（5×900 行 ≈ 6.5 万 token），M2 切分阈值据此回填 |
| 长输出截断（`finish_reason=length`） | 本实验均 `stop`；审查大单元时需监控并处理截断重试 |
| 内网部署切回 AI 网关 | 本实验走公网 `api.deepseek.com`；部署期按实际网关地址/鉴权切换 |

## 8. 附录：测试用例清单

| 测试方法 | 覆盖点 |
|---|---|
| `connectivity` | 连通性：finish_reason=stop + 返回合法 JSON 含 `ok` 字段 |
| `structuredJsonReview` | 结构化稳定性：连续 3 次返回 `issues[]`/`summary` 且 issue 字段齐全 |
