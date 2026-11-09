# BRC 规则维护索引

日期：2026-11-01。分档标准与工作安排见[改造计划](README.md#31-按三档维护输出契约)。本表只记录权威入口、关联位置和改动检查，不另写一份业务契约。业务语义以[业务契约](../agent-redesign/business-contract.md)为准。

## 1. 使用方式

- 改第一档的值或格式：从共同声明修改，核对 Schema、明示数字／枚举／格式、解析边界和固定请求；有意不对称要保留并说明原因。
- 改第二档的行为：在下表找到权威校验，核对关联说明、纠错诊断与正反例。仅优化措辞不必修改业务实现；说明与校验分处不同类不判为缺陷。
- 改第三档的文字：检查是否意外夹带字段、限额或接受条件变化；没有则保持可读并检查组装及示例。基线更新需有明确的文案变更依据，不能用重录掩盖失配。

关联注释用于修改提醒，不能自动证明语义一致。测试负责行为边界，审查负责核对文字含义；不建设自动推断语义的检查器。

## 2. 第一档入口

| 主题 | 共同声明 | 消费与检查 |
| --- | --- | --- |
| 专项字段、类型、枚举、数组及空值 | [SpecialistOutputContracts](../../../backend/src/main/java/com/codereview/agent/specialist/protocol/SpecialistOutputContracts.java)、[ContractField](../../../backend/src/main/java/com/codereview/agent/contract/ContractField.java) | SpecialistResultParser、协议示例及 SpecialistOutputContractsTest；格式示例不等于完整 JSON Schema |
| 专项目标依据最低数量 | [SpecialistResultValidator](../../../backend/src/main/java/com/codereview/agent/specialist/protocol/SpecialistResultValidator.java) 的 MINIMUM_PATTERN_TARGETS | instruction／协议中的数字及目标资格检查；不得改用 evidence 数组总数代替 |
| Agent 提交字段、数量、长度和状态 | [AgentSubmissionContract](../../../backend/src/main/java/com/codereview/agent/tools/AgentSubmissionContract.java) | 工具 Schema、AgentSubmission.parse、完成条件中的最低数量、AgentContractMaintenanceTest |
| 引用格式 | [CitationFormat](../../../backend/src/main/java/com/codereview/agent/contract/CitationFormat.java) 及 Agent／报告各自实例 | Schema、示例及正文识别；扫描器仍捕获非法长编号供诊断，见 CitationFormatTest |
| 报告最低引用数 | [ReportCitationContract](../../../backend/src/main/java/com/codereview/agent/report/ReportCitationContract.java) | instruction 数量及 ReportCitationValidator |
| 只读工具默认值与机械限额 | [AgentToolCapabilities](../../../backend/src/main/java/com/codereview/agent/tools/capability/AgentToolCapabilities.java)、[ToolParameter](../../../backend/src/main/java/com/codereview/agent/tools/capability/ToolParameter.java)、[ToolLimits](../../../backend/src/main/java/com/codereview/agent/tools/ToolLimits.java)、冻结 ToolLimitProperties | BRC-04 已核对默认起始行、版本标识长度与模式、行数／字节／条目限额；见 ToolContractMaintenanceTest、AgentToolSchemaTest 及 ToolArgumentParserTest，保留 Schema／运行时差异 |

## 3. 第二档关联

路径以 backend/src/main 为基准；表中的业务主题不是规则全文，修改须阅读关联实现及正式契约。

| 编号／主题 | 权威实现 | 模型说明及诊断位置 | 相关回归与修改提醒 |
| --- | --- | --- | --- |
| BRC-M01 Agent 材料资格、范围、正文解析、裁剪与完成适用条件 | [AgentSubmission.validate](../../../backend/src/main/java/com/codereview/agent/tools/AgentSubmission.java) | AgentSubmissionContract 的字段／总说明；prompts/system/agent.txt；prompts/agent/tool-usage.txt；validate 的纠错诊断 | [AgentSubmissionTest](../../../backend/src/test/java/com/codereview/agent/tools/AgentSubmissionTest.java)；检查未发送、越界、未声明标记、重复编号、未使用定义和材料不足 |
| BRC-M02 专项材料、角色、差异侧、changeRelation、依赖事实 | [SpecialistResultValidator](../../../backend/src/main/java/com/codereview/agent/specialist/protocol/SpecialistResultValidator.java) | SpecialistOutputContracts 的语义示例／规则句；prompts/instruction/{issue-file,issue-diff,issue-pr,pattern,coupling}.txt。PR 与 DIFF 共用问题结构，changeRelation 对两种来源都必填；PR 的 BEFORE 是共同祖先 | [SpecialistResultValidatorTest](../../../backend/src/test/java/com/codereview/agent/specialist/protocol/SpecialistResultValidatorTest.java)；检查目标／辅助混合、场景及已计算事实集合 |
| BRC-M03 报告来源资格、冻结与事实保留 | [ReportGenerationService](../../../backend/src/main/java/com/codereview/agent/report/ReportGenerationService.java)、ReportCitationValidator | prompts/instruction/report.txt、system/report.txt、protocol/report.txt；失败诊断 | [ReportGenerationServiceTest](../../../backend/src/test/java/com/codereview/agent/report/ReportGenerationServiceTest.java)、ReportCitationValidatorTest；区分编号格式与冻结来源事实 |
| BRC-M04 路径、查询、读取范围和完整性语义 | [ToolArgumentParser](../../../backend/src/main/java/com/codereview/agent/tools/ToolArgumentParser.java)、[RepositoryPaths](../../../backend/src/main/java/com/codereview/agent/repository/RepositoryPaths.java)、各工具实现。`read_diff` 的 COMMIT 仍读单提交第一父差异；RANGE 读冻结的共同祖先到源提交。一侧 UNREADABLE 或非预期缺侧（如 NOT_AVAILABLE）不合成整文件新增或删除；清单按序列化字节分页 | AgentToolCapabilities 参数／用途说明；prompts/agent/tool-usage.txt。工具参数未新增，PR 比较由任务冻结的 mode 决定 | [ToolArgumentParserTest](../../../backend/src/test/java/com/codereview/agent/tools/ToolArgumentParserTest.java)、[ReadDiffToolTest](../../../backend/src/test/java/com/codereview/agent/tools/ReadDiffToolTest.java)；BRC-04 已核对，PRS-04 只增加 RANGE 读取来源 |
| BRC-M05 游标与查询绑定 | [ToolCursor](../../../backend/src/main/java/com/codereview/agent/tools/ToolCursor.java)、ToolInvocation.fingerprint | AgentToolCapabilities.CURSOR_DESCRIPTION、分页相关诊断 | [ToolCursorTest](../../../backend/src/test/java/com/codereview/agent/tools/ToolCursorTest.java)；变更指纹字段须检查说明中的查询参数列表，BRC-04 已核对 |
| BRC-M06 工具批次、重复调用及终止处理 | [AgentTaskWorker](../../../backend/src/main/java/com/codereview/agent/execution/AgentTaskWorker.java) | prompts/correction、AgentToolCapabilities 总说明、prompts/agent/tool-usage.txt | [AgentTaskWorkerTest](../../../backend/src/test/java/com/codereview/agent/execution/AgentTaskWorkerTest.java)；数量读取冻结预算，其他控制语义不改为提示词配置，BRC-04 已核对 |
| BRC-M07 提示词版本存在、归属、正文与回退策略 | [PromptVersionResolver](../../../backend/src/main/java/com/codereview/agent/support/PromptVersionResolver.java) | 各入口默认与错误分类：专项／Agent 的 null→当前版本与 CONFIG_INCOMPLETE；报告不选规则、显式选择与 VALIDATION_FAILED | [PromptVersionResolverTest](../../../backend/src/test/java/com/codereview/agent/support/PromptVersionResolverTest.java)、TaskServiceTest、AgentTaskServiceTest、ReportGenerationServiceTest；报告两项均 null 不视为选择，显式空白不回退 |
| BRC-M08 模型快照共同字段、null、默认协议与凭据排除 | [ModelConfigSnapshots](../../../backend/src/main/java/com/codereview/agent/support/ModelConfigSnapshots.java) | 场景布局：专项连接字段及原协议、报告默认协议、Agent 精简字段；默认协议取自 [ModelSnapshot](../../../backend/src/main/java/com/codereview/agent/invocation/ModelSnapshot.java) | [ModelConfigSnapshotsTest](../../../backend/src/test/java/com/codereview/agent/support/ModelConfigSnapshotsTest.java)、TaskServiceTest、AgentTaskServiceTest、ReportGenerationServiceTest；不改历史快照，不写入凭据 |

## 4. 第三档与既有差异

分析方法、调查顺序、对未执行动作和未知事实的措辞、报告组织保留自然语言。演示 JSON 继续用真实解析／校验验证；不把“字段格式正确”当作结论正确。

保留并在改动时核对的既有差异：

- materialId 的工具 Schema 最大长度与运行时材料资格校验不同，不擅自统一。
- 提交字段的非空白检查不等同于 Schema 的 required／maxLength；整数边界也可能在后续业务阶段检查，保持现有拒绝阶段和错误分类。
- 报告提示词及版本均为 null 时允许不选规则；单项缺失或显式空白不等同于该入口。BRC-02 只共用存在／归属／正文基础检查，保留各入口默认、空正文错误分类及报告不选规则。
- 专项／Agent 仅在版本 ID 为 null 时使用当前版本；显式空白不回退。模型快照的字段顺序、字段存在性、null 表达及协议处理按入口保留，不因共用构造入口统一。
- 部分行号默认／边界在 Schema、参数解析、实际读取及业务校验分阶段处理；BRC-04 已确认权威默认：read_code 起始行说明与 Schema 写 1，解析结果保持 null，读取实现取 1。
- changeRelation 的文件审查说明写为 null，既有校验也接受空白文本；当前不收紧。将来改变接受范围须单独说明并核对正式契约。
- 可选数组缺失／null 与数组中的 null 元素不是同一条件；保留既有错误分类，见 SpecialistOutputContractsTest、AgentContractMaintenanceTest。
- 格式示例中占位材料 ID、示例行号和文本均不是真实证据，也不作为一般业务规则声明。

## 5. 验证前置检查

PAS-01 组装职责迁移：调用方仍经 `prompt/PromptAssemblyService` 门面；系统指令及协议片段由 `prompt/PromptSystemRenderer` 消费本表的权威契约，普通材料格式及重试片段由 `prompt/PromptSections` 渲染，Agent 四类纠正事件由 `prompt/AgentCorrectionRenderer` 渲染。场景编排分别在 Specialist／Standard、Report、Agent、ToolCheckPromptStrategy，公共消息计量在 `prompt/PromptMessageFactory`。BRC-M01–M08 的业务权威入口及触发条件不变，迁移后仍需核对固定请求、工具定义及对应契约回归，不在策略内复制字段和限额规则。

固定回归前检查真实探针开关，在测试子进程清除 DEEPSEEK_API_KEY、GITEA_TEST_BASE_URL，并显式传空 deepseek.api-key，不输出密钥。不将环境自动触发的真实探针作为业务验收；此前发生的调用见计划第 7 节。

BRC-03 验收已核对本表路径、源码关联注释、业务正反例和资源；未新增仅验证整段中文相同的第二档测试，也未靠修改构造出来的虚拟业务规则证明同源。交付记录见[计划第 10 节](README.md#10-brc-04-交付记录2026-11-01)及[第 11 节](README.md#11-brc-03-交付记录2026-11-01)。
