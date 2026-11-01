# Gitea 1.16.1 基础插件验证草案

[插件 JSON](gitea-1.16.1-basic.plugin.json)用于讨论并验证插件框架的输入契约。`schemaVersion=0.1.0-draft` 是本次提出的草案版本，不是已存在的系统标准。当前仓库尚无对应插件导入器、请求执行器或权限执行实现，不能宣称该文件已可在现有页面导入使用。

## 接口依据

- [Gitea 官方 v1.16.1 Swagger 定义](https://github.com/go-gitea/gitea/blob/v1.16.1/templates/swagger/v1_json.tmpl)
- [同版本 API 路由及认证声明](https://github.com/go-gitea/gitea/blob/v1.16.1/routers/api/v1/api.go)

接口路径、operationId、参数类型和枚举取自上述版本。仅开放必要参数，其余参数默认拒绝。响应按草案中的 JSON Pointer 投影，未整批复制官方响应定义。

| 操作编号 | 方法及相对 API 路径 | 验证用途 |
| --- | --- | --- |
| getVersion | GET /version | 实例版本与连通性 |
| userGetCurrent | GET /user | token 对应身份 |
| repoGet | GET /repos/{owner}/{repo} | 路径参数及仓库范围 |
| repoListBranches | GET /repos/{owner}/{repo}/branches | 分页与分支数据 |
| issueListIssues | GET /repos/{owner}/{repo}/issues | 查询参数；type 固定为 issues |
| repoListPullRequests | GET /repos/{owner}/{repo}/pulls | PR 列表及分页 |
| repoGetPullRequest | GET /repos/{owner}/{repo}/pulls/{index} | 整数路径参数及 PR 详情 |

## 预期使用流程

框架实现相应契约后，导入插件并由管理员批准操作，再创建连接：

1. 设置实例根地址，例如 `https://gitea.example.internal`。子路径部署可以使用 `https://example.internal/gitea`。两者均不附加 `/api/v1`，执行器须保留部署子路径。
2. 绑定服务端保存的 token 凭证引用。认证头为 `Authorization: token <凭证>`；JSON 和模型消息中均不填写真实 token。
3. 设置非空的允许仓库列表，每项包含 `owner` 和 `repo`。调用前由后端核对仓库范围，导入 JSON 不授予权限。
4. 依次验证版本、身份、仓库详情和列表；PR 详情参数取列表中的 `number`，不要使用全局 `id`。
5. 示例参数中的 `example-team` 和 `example-repo` 替换为实际已授权仓库。没有 Issue 或 PR 时接受空结果，不创建测试数据。

`operations[].inputSchema` 使用 JSON Schema 的对象、类型、必填、枚举、边界和正则规则；默认值必须由执行器根据 `request` 映射实际补齐。分页由 Agent 明确请求，禁止自动遍历所有页或直接跟随返回 URL。`connection`、`requestedPolicy`、`runtimeContract` 及请求映射等其他结构均是本次草案约定，仍待正式框架设计确认。

## 验证边界

- 已离线核对 JSON、操作唯一性、官方 GET 路径、参数类型／位置／枚举、请求映射、必填路径参数及响应字段引用。
- 未连接内网实例，未验证实际账号、网络、反向代理、服务配置或分页响应头。
- 未执行导入或任何 HTTP 业务请求；声明的授权、网络和资源限制尚无执行实现，JSON 本身不构成安全隔离。
- 所有操作均为选定的查询接口。本样例不验证 POST body、特殊认证、文件传输、代码内容或 diff 读取。
- 名称采用 ASCII、页号最多 100、每页最多 20 条等属于本验证配置的主动限制，不代表 Gitea 的完整参数范围；暂不覆盖 Unicode 仓库名。
- 不假定 1.16.1 支持较新版本的细粒度 token scopes；实际权限必须同时由服务账号权限、操作白名单与仓库范围控制。
- 列表为实时数据，不承诺跨页快照一致性；缺少可验证分页信息时标为未知，不按列表长度推断已经查遍。
- 先验证这 7 个接口，再决定增加能力。插件定义升级不应自动开放新增权限。
