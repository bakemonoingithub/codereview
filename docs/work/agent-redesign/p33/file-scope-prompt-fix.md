# Agent 指定文件范围传递修复

日期：2026-10-23（Asia/Taipei）。负责人要求修复指定文件未进入模型提示词，并重跑同类真实分析。

## 原因与修复

原任务 `01M5HMKRCCJY91KD6ZGTS61W45` 的冻结范围保存了 5 个路径，但 `AgentPromptInput` 缺少路径字段，执行器构造提示词输入时也未提取 `scope.paths`。首轮实际请求只有 FILES 类型、分支与版本，没有文件清单。模型第 1–4 轮围绕全仓与 Python 实验脚本定位，第 5 轮才搜索到 Java 执行器，第 6 轮开始直接读取目标。

修复传递链为：冻结 `scope.paths` → 执行器 → `AgentPromptInput.paths` → 统一提示词组装。文件范围明确展示全部路径与固定 head，并提示优先直接读取；可按需读取同仓库背景，背景不自动成为目标。首轮和后续轮均保留范围，历史消息不改写。

## 工程验证

`PromptAssemblyAgentTest` 与 `AgentTaskWorkerTest` 共 19 项通过，0 失败、0 异常、0 跳过。新增回归覆盖组装输出及执行器从冻结快照到实际模型调用的完整传递，断言首轮、后续轮都包含全部目标路径与 V1 固定版本。TEXT、COMMITS 与既有协议恢复测试继续通过。

## 真实重跑

- 新任务：`01M5K73RVKSC04RDQYSQBG032J`，名称「文件范围回归：指定文件直接读取」。
- 原任务的 5 个路径、调查要求、项目与模型保持一致；重新观察远端 `refactor/agent` HEAD 后固定为 `738be95c0822f358dcb43034dfa1f919d78cc236`，原任务版本为 `8bef5fc33ce194246e941c893439ea83a14cf01f`。这不是同版本性能对照，不比较结论是否相同。
- 真实首轮请求已包含全部 5 个路径。第 1 轮直接读取 `AgentTaskWorker.java` 和 `ToolEnvelope.java`；第 2 轮续读执行器并读取 `PromptMessage.java`；第 3 轮读取 `PromptAssemblyService.java`、`AgentTaskWorkerTest.java`；第 4 轮起才搜索背景。
- 最终状态：SUCCEEDED，结果 ANSWERED；17 轮、17 次网络尝试、28 次普通工具调用，末条 submit_result 成功。29 条引用均能在结果来源中找到材料，行范围无越界；首轮路径无遗漏，前 3 轮读取覆盖全部指定目标。工具调用均为 OK。详见[核对记录](evidence/file-scope-verification.json)。

本次确认范围传递及实际读取行为；不将单次模型输出当作对全部结论真实性的自动验收。
