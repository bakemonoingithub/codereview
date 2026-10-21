# P31.3 三类专项指令、最小协议与位置校验

状态：完成（2026-10-21，Asia/Taipei）。范围为本地固定样本与离线 JSON 单测；没有调用真实模型。

## 1. 交付

| 能力 | 位置 | 说明 |
| --- | --- | --- |
| 版本化协议 | `SpecialistProtocol` | 协议/指令版本 1；问题、模式、耦合 schema 只在此维护，组装服务引用 |
| 严格解析 | `SpecialistResultParser` | 拒绝未知/重复字段、尾随文本、Markdown 围栏、缺失必填和类型错误 |
| 结构/位置校验 | `SpecialistResultValidator` | 枚举、必填、目标/辅助角色、原始行范围、差异前后侧与 changeRelation |
| 耦合事实 | `CouplingFactsBuilder`、`CouplingFacts` | 程序计算继承、字段、签名、泛型和 `new` 边；去重、coverage、unresolved 与关系计数 |
| 格式重试策略 | `SpecialistProtocolPolicy` | 初次 + 最多 2 次；诊断保留协议错误码与有界返回片段 |
| 组装与封套 | `SpecialistPromptBuilder`、`SpecialistResultEnvelope` | 专项用途、目标/辅助、缺失说明、依赖事实；服务端填写稳定 ID/版本/完整性 |

## 2. 校验规则

- `summary`、`limitations` 和对应结果数组必填；`issues`/`patterns`/`observations` 可以为空数组。
- 问题位置必须落在目标材料内，辅助文件只能出现在 `evidence`；模式每个候选至少一处目标依据。
- 差异问题 `changeRelation` 必填；文件审查必须为 null；新增文件不能用 BEFORE，删除文件不能用 AFTER。
- 耦合 `dependencyIds` 只能来自服务端给定的 `dep-*`；未解析类型不自动补成全仓指标。
- 位置校验只做结构、类型与范围，不核验问题真伪、模式是否经典或解释质量。

## 3. 验证

- `SpecialistResultParserTest`：8 项，覆盖合法结果、围栏、未知字段、缺失数组、缺失位置、尾随文本、重复字段、错误类型。
- `SpecialistResultValidatorTest`：11 项，覆盖目标/辅助、越界、未知材料、changeRelation、前后侧、模式依据、耦合依赖、空结果。
- `CouplingFactsBuilderTest`：3 项，覆盖五类关系、未解析与“零依赖”边界、缺失 AST 失败。
- `SpecialistProtocolPolicyTest`：2 项；`SpecialistPromptBuilderTest`：2 项。
- 全量回归与最终数量见 [P31 总览](README.md) 和 STATE。

## 4. 未验证

- 实际“调用—解析—重试”循环在 P31.5 执行器中接入；本批只固定策略和诊断，未发真实请求。
- 未验证真实模型对三类 schema 的遵循率、输出长度和格式重试分布。
- 未接入 P31.4 的容量精简/拆分；事实文本与材料容量仍须在 P31.9 校准。
