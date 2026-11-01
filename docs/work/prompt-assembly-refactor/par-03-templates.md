# PAR-03 内置正文抽取与资源加载

日期：2026-11-01。状态：done。范围：只把内置提示词正文从 Java 源码迁到 classpath 资源并加加载校验；不改业务协议、执行控制、页面、数据库或部署方式。后续职责收敛见 PAR-04，回归与真实使用见 PAR-05。

## 1. 改动

### 1.1 资源与清单

新增 `backend/src/main/resources/prompts/`，共 26 份模板和 1 份清单：

| 类别 | 文件 | 数量 | 变量 |
| --- | --- | --- | --- |
| 内置任务指令 | `instruction/<用途>.txt` | 8 | 无 |
| 输出协议 | `protocol/<用途>.txt` | 8 | 无 |
| 协议封套 | `protocol/suffix.txt` | 1 | `protocolVersion`、`purpose`、`schema` |
| 系统提示词 | `system/specialist.txt`、`report.txt`、`agent.txt`、`tool-check.txt` | 4 | `instruction`、`protocol` |
| Agent 用户段 | `agent/tool-check-user.txt`、`fixed-file-guidance.txt`、`tool-usage.txt` | 3 | 无 |
| 格式重试 | `retry/diagnostic.txt`、`excerpt-header.txt` | 2 | `attempt`、`errorCode`（仅前者） |

`prompts/manifest.json` 按清单结构版本 1 声明每份模板的标识、类别、用途、路径、版本与允许变量。正文按 Java 文本块处理后的实际字符串逐字导出，包含原有空白与是否带末尾换行；加载与渲染都不 strip、不补换行。资源为 UTF-8 无 BOM、LF。

### 1.1 换行与 Git 属性

本机 `core.autocrlf=true` 且原 `.gitattributes` 未覆盖该目录，新 Windows 工作区检出会把模板转成 CRLF，改变正文与 UTF-8 容量估算。已在 `.gitattributes` 增加 `backend/src/main/resources/prompts/** text eol=lf`，并让模板检查在正文出现 `\r` 时失败。

验证方式：用临时索引 `git add` 后 `git checkout-index` 模拟检出（不改真实索引、不提交），修复前 `system/specialist.txt` 检出为 719 字节且含 CR，修复后 27 个文件全部为 LF、字节数与工作区一致（该文件 706 字节）。未在另一台机器实际 `git clone` 复核，需在真实新工作区首次检出时留意。

### 1.2 代码

- 新增 `prompt/template/PromptTemplate`（不可变模板，含正文与允许变量）与 `PromptTemplateRegistry`（classpath 加载、清单校验、单次变量替换）。
- `PromptPurpose` 只保留 `wireName`、`jsonMode`、`isSpecialist`；内置指令正文与 `builtInInstruction()` 移除，用途仍由枚举标识。
- `SpecialistProtocol` 只保留 `VERSION`、`INSTRUCTION_VERSION`；协议正文与 `outputSchema`／`systemSuffix` 移入模板层，协议版本与用途仍按原样写入协议封套。
- `PromptAssemblyService` 通过注册表渲染 system 消息、Agent 用户段与格式重试诊断；场景选择、段落顺序、材料编排、行号、截断、历史回放与计量逻辑不变。模板标识与模板版本不进入模型正文。
- 仍留在程序中的是章节标题、字段标签、行号前缀、截断标记与材料拼装格式；它们描述结构而非模型规则，随 PAR-04 收敛组装边界时再处理。

## 2. 加载与失败边界

- 加载即校验，任一条件不满足都在构造时失败，不回退为空提示词、不做部分加载：清单缺失、清单结构版本不支持、清单为空、标识重复、字段缺失、资源缺失、资源为空、正文占位符与声明变量不一致（未声明、声明未使用、无法识别）。
- 渲染要求变量集合与声明完全一致：缺变量抛缺少变量错误，多传变量抛未知变量错误。替换只扫描模板一次，变量值中的 `{{...}}` 原样保留，材料或用户输入不会被二次展开。
- `PromptAssemblyService` 是 Spring 组件，注册表在其构造时加载；模板损坏会直接让应用启动失败，而不是运行中退化为空提示词。

## 3. 等价性证据

- 固定基线（PAR-02）20 组请求只读比较通过：system／user 正文、消息角色与顺序、工具定义、完整请求体与容量估算与改造前一致。基线文件未重捕获，`par.captureBaseline` 未使用。
- 抽取方式：用一次性测试从改造前实现逐字导出正文后删除该测试，未用改造后代码生成期望值。
- 定向复验：18 个相关套件 179 项，失败 0、异常 0、跳过 0（含固定基线、专项与 Agent 执行、容量、工具协议、报告、结果校验及新增模板检查）。日志中的执行异常为既有负向用例输出。
- 计量样本 `PromptMeteringSampleTest` 的 6235 字节与 6331 估算值未变。
- 打包检查：`mvn -DskipTests package` 产物内 `BOOT-INF/classes/prompts/` 含清单与全部 26 份模板，字节数与源文件一致，资源随应用发布。

## 4. 未验证与边界

- 未启动或重启服务、未调用真实模型、未连接数据库；本轮不改变运行环境。
- 未运行后端全量回归，按计划留给 PAR-05。
- 模板版本目前只用于清单与代码，未写入请求快照或数据库；历史记录仍按保存时的请求正文读取，不用当前模板重建。
- Agent 四类纠正事件、工具描述与协议适配仍留在 `AgentTaskWorker`／`AgentToolSchema`，属 PAR-04。
- 本机 Windows 沙箱曾拒绝工作区写入，经权限诊断确认不是目录权限问题；改为完全权限后完成构建与测试，该环境现象不写入业务结论。

## 5. 回退

代码与 `resources/prompts` 属于同一发布单元，回退应整体回退到 PAR-02 提交；不单独替换模板文件，避免代码与正文版本混搭。本轮未新增热更新、在线版本管理或数据库迁移。
