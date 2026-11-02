# PAS-02 独立等价验证手册

本手册供其他会话在 PAS-01 交接后执行。执行结果见 [独立验证报告](verification-report.md)；本手册保留操作步骤，不能单独作为通过记录。目标、范围和分工见 [工作计划](README.md)，业务细节以正式契约及 BRC 维护索引为准。

## 1. 开始条件与证据目录

1. 阅读工作区 `AGENTS.md`、`docs/STATE.md`、`docs/ROADMAP.md`、本工作包 README 和实施交接文件 `handoff.md`。若缺少交接文件，先补齐候选来源，不猜测最新工作区就是交付版本。
2. 对照交接记录核对改造前版本、候选版本、未提交文件和哈希。规划时起点为 `67d411e150deb5299bb9dd1cc11f746672ae9915`；若实施前发生其他改动，采用交接中明确的 PAS-01 实际基准，并说明差异。
3. 固定两个源码副本，分别称为 before 和 after。优先从已存在的提交创建隔离副本；无提交时使用交接补丁及新增文件复现候选，核对哈希后再运行。不得对共享工作树执行 reset、clean 或覆盖检出。
4. 两个副本使用相同 Java 17、Maven Wrapper、配置和样本输入，构建目录分别隔离。记录 `java -version`、Wrapper 版本、操作系统及 Git 提交，不记录凭据。
5. 创建本工作包 `evidence/` 目录，保存源码及资源哈希清单、命令日志、测试报告汇总、差异明细和最终 `verification-report.md`。完整 Surefire 报告可保留在对应构建目录，记录绝对路径和候选标识。

原有固定资源相对仓库根目录为：

- `backend/src/test/resources/prompt-baseline/requests.json`：20 组组装、请求和容量基线。
- `backend/src/test/resources/tool-baseline/tools.json`：工具定义、字节数和指纹基线。
- `backend/src/main/resources/prompts/`：清单与正文资源，实际文件数量以 before 清单为准，不硬编码历史数量。

以上文件不得通过删除、重录或改期望来消除失配。正常执行禁止传 `par.captureBaseline`。PAS-01 新增 `prompt-baseline/pas01-boundaries.json`，同样禁止重录及开启 `pas.captureBoundaryBaseline`；来源及候选信息见 [handoff.md](handoff.md)。定向检查补入 PromptAssemblyBoundaryBaselineTest、PromptAssemblyCompositionTest，并将相同测试及边界期望复制到 before 副本核对来源。

## 2. 隔离真实探针

前序 BRC 曾因继承凭据触发旧网关探针，本次应复用其隔离要求。

1. 先扫描当前测试源码中的 `System.getenv`、`System.getProperty`、`EnabledIf` 和 live／probe 开关，核对是否新增触发入口。
2. 仅在测试子进程中移除 `DEEPSEEK_API_KEY`、`GITEA_TEST_BASE_URL`，显式传空 `deepseek.api-key`，不传 `agent.live.cancel` 和 `probe.root`。真实取消测试仅在 `agent.live.cancel=true` 时启用；显式传 false 会被应用严格配置校验拒绝，PAS-02 已在改造前版本复现。检查 `JAVA_TOOL_OPTIONS`、`JDK_JAVA_OPTIONS` 和 Maven 启动选项是否注入探针开关；不将完整环境或选项原文输出到日志。
3. 使用替身或测试本地服务完成工程检查。不得读取用户真实密钥来满足测试；不连接现有业务数据库、不启动业务应用、不运行真实模型或仓库探针。
4. 若全量检查发现新的外部依赖，先定位触发条件并隔离。环境缺失或探针跳过按事实记录，不改成「实际业务验证通过」。

可使用专用 PowerShell 子进程运行下节命令，在子进程内设置上述环境；不要为方便污染或清除用户全局环境。Java 17 路径以本机实际安装为准。

## 3. 第一轮：原有基线与接口核对

### V01 源码与资源完整性

比较 before／after 的修改清单：应集中在提示词组装、必要测试接缝及维护文档。提示词模板、输出契约、工具能力、模型请求序列化和执行循环若发生改动，应逐项说明，不能默认视为职责迁移所必需。核对原有两份基线文件内容及哈希完全不变。

### V02 门面和构造兼容

检查所有生产调用和测试／手工装配，覆盖 `assemble`、`assembleAgent`、两个工具验证入口、`correction`、`systemPrompt`、`protocolBody` 及有效静态辅助入口。无参构造和正常应用装配应获得相同内容及资源校验行为。测试正常装配可以使用无数据库的最小上下文或显式装配，不启动完整业务应用。

策略缺失、重复选择或未覆盖用途应在明确位置报错，不默默回退到错误场景。VALIDATION 需要独立核对，不能被专项的「必须有目标」检查误拦截。不要通过断言必须有几个类、必须使用某种注解来代替行为检查。

### V03 20 组固定请求

在 before 和 after 分别运行 `PromptRefactorBaselineTest`，先确认 before 通过，再核对 after。既有样本包括六种非 Agent 用途各自最小／丰富输入、TEXT／FILES／COMMITS 的首轮和工具往返、工具验证双轮。

比较完整 `AssembledPrompt`、最终 HTTP 请求 JSON 和容量估算：

- 文本按原字符比较，包括末尾换行、空白、标题、中文、行号、材料标记、截断提示和顺序。
- JSON 对象键可按既有测试语义忽略顺序，数组、消息顺序、工具顺序、null 与字段缺失不可混同。
- 核对 purpose、protocolVersion、jsonMode、system／user 快捷字段、消息列表、工具、toolChoice、bytes、estimate、估算方法及适配版本。
- 核对 requestBody 的 model、temperature、max_tokens、stream、response_format、供应商参数、messages、tool_calls 和 tool_call_id。
- JSON 结构比较通过不代表序列化字节完全相同。工具序列化字节数必须通过 V04；若请求序列化有差异，单独定位并判断是否影响文本、计量或下游，不用键排序掩盖差异。

### V04 工具定义及四类纠正消息

运行 `ToolDefinitionBaselineTest`、`PromptCorrectionTest`、`AgentToolSchemaTest`，核对默认及自定义限额下工具定义、含 submit_result 的工具集合、序列化字节数和版本指纹。对 ModelOutputInvalid、MissingToolCall、InvalidToolBatch、RepeatedToolCalls 四类事件比较 before／after 的完整正文，包含动态诊断和批次上限。

## 4. 第二轮：补充差分样本

现有固定请求只覆盖有限输入，不得只因 20 组通过就结束验证。先核对既有测试，以下缺口优先补少量有意义的样本；共用同一份输入在 before／after 独立运行。期望必须来自原实现或明确契约，不能由候选策略自行生成再回填。

若本会话没有在重构前捕获补充样本，验证会话可在 before 隔离副本添加仅使用原门面 API 的临时采集测试，再将同一测试放入 after。生产代码不复制两套到一个运行时，不为采集修改原实现。记录输入、旧输出、来源 SHA、采集命令和哈希，新增基线独立存放，不覆盖 PAR 基线。

| ID | 样本与边界 | 核对结果 |
| --- | --- | --- |
| D01 | 四类专项、报告、VALIDATION；逐用途调用 systemPrompt／protocolBody | 对应模板、契约片段及异常行为一致，无错误路由 |
| D02 | 专项目标为空；可选规则、说明、事实、缺失和辅助材料为空／空白／有值 | 相同接受范围、ApiException 错误码和消息；非空材料的来源、侧和顺序不变 |
| D03 | 文件说明 3999／4000／4001 字符，重试片段 1999／2000／2001，缺失说明和材料诊断的各自截断边界 | 原截断位置、原长度提示及换行一致 |
| D04 | Agent 描述 2000、指导 16000、参考线索 20000 的临界与超长样本 | 各段截断和原始长度统计不变 |
| D05 | 材料空内容、无末尾换行、有末尾换行、CRLF 输入、非 1 起始行、endLine 缺失、中文及非 ASCII 字符 | 原行数、编号、材料封装和 UTF-8 字节口径不变，不做全局换行归一化 |
| D06 | Agent TEXT／FILES／COMMITS，空路径／多个路径，空比较／根提交／多个比较，可选指导和旧结果 | 指定文件指引出现条件、版本顺序和第一父比较文字不变 |
| D07 | Agent 历史为 null／空／多轮，assistant 正文为空或 null、多个工具调用、不同 tool_call_id | 回放角色、正文、工具 ID 和顺序不变，不修改传入历史集合 |
| D08 | 默认／自定义限额、只读／含提交工具集合、工具验证首轮和回放轮 | 工具适配、toolChoice 和 JSON 模式一致 |
| D09 | 相同输入连续组装，不同策略交错组装；并发组装的确定性样本 | 无共享 StringBuilder／消息列表污染；输出不受调用先后影响 |
| D10 | 带 {{...}} 的用户规则和材料；清单缺失、模板缺失或变量不匹配 | 不二次展开，原资源失败检查仍有效；初始化不退化成网络调用时才发现 |

边界值以 before 的权威实现为准；D03／D04 是当前已读代码的定位提示。补充样本关注实际类型允许的输入，不为了验证未声明的非法对象构造而新增业务校验。异常比较不要求堆栈行号相同。

## 5. 第三轮：计量、流程及打包

### V05 公共封装计量

核对普通组装与工具组装迁移后的计算：正文 UTF-8 字节、每条消息封装开销、固定附加值、工具定义 JSON 字节、工具名和参数及原有调用开销。工具历史为 null 正文时仍按旧口径计数，不以「实际 HTTP 更准确」为由修正算法。使用 V03、D05、D07、D08 的输入逐字段比较。

`ModelCapacityServiceTest` 和 `SpecialistSplitPlannerTest` 应核对临界可容纳／不可容纳、输出预留和安全余量、关闭拆分时整份发送、开启拆分时辅助裁剪／分片及单行超限；before／after 相同输入的切分单元、材料范围和容量错误应一致。

### V06 调用方行为回归

| 范围 | 既有套件 | 重点 |
| --- | --- | --- |
| 专项 | SpecialistPromptBuilderTest、SpecialistTaskWorkerTest、SpecialistResultMergerTest | 类型映射、有限格式重试、分片汇总；不新增综合模型调用 |
| Agent | AgentTaskWorkerTest、AgentSubmissionTest | 多轮工具回放、无工具／非法批次／重复纠正、显式提交、容量和硬限额、取消与迟到响应 |
| 报告 | ReportExecutionWorkerTest、ReportCitationValidatorTest | 冻结事实与引用、规则顺序、失败语义 |
| 验证与传输 | AgentToolLoopValidatorTest、ModelInvocationServiceTest | 连通与工具双轮、输出预算、序列化、有限网络重试及用量记录 |
| 契约维护 | AgentContractMaintenanceTest、ToolContractMaintenanceTest、SpecialistOutputContractsTest、SpecialistResultValidatorTest | 契约仍为权威，迁移未复制新规则、漏掉维护关联或收紧旧差异 |

既有用例未覆盖的关键跨层差异，以本地替身补充；不要求重复全部历史真实环境验收。

### V07 测试执行

在完成第 2 节隔离的测试进程内，分别进入 before／after 的 backend 目录运行。以下目录名是示例；每轮应保证目录对应明确版本且不读取旧报告。

```powershell
./mvnw.cmd -q '-Ddeepseek.api-key=' '-Dtest=PromptRefactorBaselineTest,ToolDefinitionBaselineTest,PromptAssemblyServiceTest,PromptAssemblyAgentTest,PromptCorrectionTest,PromptTemplateRegistryTest,SpecialistPromptBuilderTest,AgentToolSchemaTest,ModelCapacityServiceTest,SpecialistSplitPlannerTest,SpecialistResultMergerTest,AgentTaskWorkerTest,SpecialistTaskWorkerTest,ReportExecutionWorkerTest,AgentToolLoopValidatorTest,ModelInvocationServiceTest,SpecialistResultValidatorTest,ReportCitationValidatorTest,AgentSubmissionTest,AgentContractMaintenanceTest,ToolContractMaintenanceTest,SpecialistOutputContractsTest' '-Dbackend.buildDirectory=target-pas-verify-core' test
```

确认实际类名仍存在，将新增差分测试加入定向范围；不得设置忽略失败或缺少测试也成功。随后对候选执行后端全量：

```powershell
./mvnw.cmd -q '-Ddeepseek.api-key=' '-Dbackend.buildDirectory=target-pas-verify-full' test
```

从本轮 Surefire XML 汇总 suites／tests／failures／errors／skipped，核对时间戳、退出码和跳过原因。历史 1021 项／11 跳过只是前序记录，不是本轮预期通过数。若候选有失败，在 before 运行同一失败套件及相同配置进行定位；必要时对 before 执行全量以判断环境或既有失败，不以「大概原来就失败」豁免。

### V08 资源打包及初始化

定向及全量通过后，在候选隔离目录执行 `./mvnw.cmd -q '-DskipTests' '-Dbackend.buildDirectory=target-pas-verify-package' package`；该命令只验证打包，不算再次通过测试。核对构建产物中的 `prompts/manifest.json` 及其引用的每份资源，与 before 和候选源资源逐字节比较，不能只检查文件名存在。

结合 `PromptTemplateRegistryTest` 和最小装配检查确认：缺失、重复、变量不匹配仍被拒绝；门面委托不会把模板校验延迟到首次真实请求。不启动业务 jar 来执行此项，避免自动连接数据库或执行迁移。

## 6. 差异处理及完成判定

1. 发现差异时记录样本 ID、字段路径、before／after 值、最早差异字符或字节位置、关联源码及可复现命令。长文本可只在报告显示片段，完整内容存证。
2. 分类为生产行为变化、测试接缝变化、环境问题或原有失败。生产差异交回实施会话；测试接缝可适配，但冻结期望和业务断言不变。环境失败修复后重新执行受影响检查。
3. 本次要求行为保持；正文、计量或接受范围出现有意变化也不能自动放行，应移出重构或另行明确变更范围。不得用更新基线、放宽断言、只比较包含关系或排除失败套件通过验收。
4. 修复后固定新候选，复跑受影响的差分和定向检查；生产代码变化后最终全量须覆盖最终候选。最终记录验证后是否又有改动。
5. PAS-02 完成要求 V01–V08 和适用 D01–D10 都有结果，未覆盖项有明确限制且不影响本次关闭条件；固定请求及工具基线不变，生产差异清零，必需检查失败／异常为零。必需项未执行时保持待完成，不能标为不适用后关闭。

## 7. 报告格式与接续消息

`verification-report.md` 至少包含：

- before／after 的完整标识、补丁／清单哈希、验证时间、工具链及隔离方式。
- V01–V08、D01–D10 的结果表：通过／失败／未执行／有依据的不适用，附证据路径和具体限制。
- 两份原有基线哈希、补充样本来源、资源源文件及 jar 比较结果。
- 定向、补充和全量的独立统计，退出码、跳过清单、失败定位及修复后的候选标识。
- 结构审查：统一入口是否保留、策略是否覆盖用途、公共计量是否同源、类型约束及初始化行为是否保持。
- 结论仅限已检查输入和流程；说明未调用真实模型、未连接现有数据库、未启动业务服务和未进行页面／部署验收。

完成后同步 README、STATE 和 ROADMAP。可使用以下接续说明启动另一个会话：

> 请先读 code/AGENTS.md、docs/STATE.md、docs/ROADMAP.md，再读 docs/work/prompt-assembly-strategies/README.md、verification.md 和 handoff.md。执行 PAS-02 独立等价验证，核对改造前与候选源码，不修改生产实现或重录既有基线；按手册隔离真实探针，完成请求／工具／计量／边界差分、调用方回归、资源打包和后端全量检查。发现生产差异记录并交回实施会话，验证结论绑定明确候选版本。
