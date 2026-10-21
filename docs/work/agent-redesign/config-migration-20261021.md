# 旧库配置迁移记录

日期：2026-10-21（Asia/Taipei）。负责人明确要求将旧库项目、模型、策略和提示词迁入 Agent 新库。本轮只迁移未删除的配置及其有效提示词版本，不导入旧任务、结果、报告或验证历史；P31.9 保持 active。

## 结果

来源为本机 `code_review`，目标为独立的 `ai_code_review_agent`。计划阶段确认目标五张配置表均为空；23 条记录在一个事务内写入，提交前及提交后逐字段核对通过。

| 目标表 | 导入数量 | 处理 |
| --- | ---: | --- |
| `project` | 2 | `gitea_url` 转为 `repo_url`，保留分支、凭据与凭据类型 |
| `model_config` | 2 | `token` 转为 `credential`，不继承旧验证状态 |
| `review_strategy` | 4 | 问题 2 条、耦合 1 条、设计模式 1 条，重建模型与提示词引用 |
| `prompt` | 6 | 专项用途 5 条、报告用途 1 条；JSON 标签数组转为新格式 |
| `prompt_version` | 9 | 保留版本号、正文和当前版本关联；正文没有改写 |

[ID 映射清单](config-migration-20261021-id-map.json) 保存旧 ID、新 ULID、转换说明与版本正文摘要，不包含凭据。新 ID 按来源表、旧 ID 与来源创建时间确定生成，避免重跑产生第二套关联。时间从来源配置的 `Asia/Shanghai` 转为 UTC，目标 `revision` 初始为 0。

## 兼容处理

- 旧 `analyzer_type=1/5` 转为 `ISSUE`，`2` 转为 `COUPLING`，`3` 转为 `PATTERN`。整文件与差异范围改由发起任务时选择。
- `modelConfigId`、`promptId` 转为明确的外键字段，四条策略的引用均有效。旧 `threshold`、`methodWindowLines` 在新结构中没有对应参数，未写成新策略字段；原值保存在本机来源快照中，清单标注了对应策略与参数键。
- DeepSeek 的声明容量从旧 `capability_profile_json` 提取：上下文 1,048,576、最大输出 393,216；这些是来源声明值，不是本轮容量校准结果。千问原记录没有容量配置，保持为空；执行专项前仍须补齐上下文窗口。
- 两个模型均按旧接口使用 `OPENAI_CHAT`。旧 tokenizer 模式与能力探测历史未冒充新算法或新验证结果，`tokenizer` 保持为空，新调用仍使用现有计量方式。
- 当前提示词没有要求覆盖内置输出协议的内容，正文和历史版本均原样保留。报告提示词标为 `REPORT`，没有关联到专项策略。
- 迁移通过来源连接的只读事务读取，目标使用独立账号及串行化事务；不会改写旧库。不存在删除、覆盖或自动修复旧数据的步骤。

## 复验

| 检查 | 结果 |
| --- | --- |
| 转换自测 | 13 项通过：稳定 ID、跨表区分、UTC 转换、专项映射、无效类型与缺失引用拒绝、标签转换与容量处理 |
| 提交前及提交后核对 | 23 条记录的目标字段全部与计划一致，包含凭据、正文与版本关联；匹配过程不输出凭据 |
| API 列表与详情 | 经 5173 代理读取项目 2、模型 2、策略 4、提示词 6；详情与提示词版本读取通过，项目和模型响应没有凭据值 |
| 关联完整性 | 策略模型／提示词缺失 0；提示词当前版本缺失或错属 0 |
| 模型真实连通验证 | 千问修订 0 通过，返回 JSON 对象；DeepSeek 修订 1／2 验证失败，信息为「模型响应缺少正文」 |
| 迁入后修改保留 | 复验期间项目出现修订 1，DeepSeek 出现修订 2；复核 21 条记录仍与计划完全一致，2 条记录的后续修改保留 |
| 重复导入保护 | 对已发生后续修改的目标再次执行 Apply，被目标快照检查拒绝，未覆盖修改或重复写入 |
| 旧库与服务 | 旧库仍为 17 张表、迁移版本 12；前后端健康接口仍通过 |

DeepSeek 后续修订涉及凭据；本轮没有回写迁入时的值。迁移完成时的字段一致性与后续模型连通结果分别记录，不把连通失败写成迁移成功验证，也不把迁移复验写成专项完整流程或 PC 视觉验收。

## 本机备份与执行入口

包含凭据的来源快照、目标写入前快照、执行计划与验证记录保存在 `H:\myProjects\aiCodeReview\agent\backend\.local-backups\config-migration\20261021-config-import`，已加入 Git 忽略；该目录位于 `target` 之外，不受 Maven 常规清理影响。首次执行目录 `backend/target/config-migration/20261021-config-import` 的文件已复制到该目录，并逐文件核对 SHA-256。

迁移入口为 [migrate-legacy-config.ps1](../../../scripts/migrate-legacy-config.ps1)，转换实现在 [LegacyConfigMigration.java](../../../scripts/migration/LegacyConfigMigration.java)。需要 PowerShell 7、JDK 17 以上，以及本机 Maven 缓存中的 SnakeYAML 2.2、MySQL Connector/J 9.1.0、Jackson 2.17.2。脚本不会安装依赖，不把口令放入命令行；本机配置路径作为参数传入。

```powershell
pwsh -NoProfile -File .\scripts\migrate-legacy-config.ps1 -Mode self-test
```

`plan` 必须显式指定 `SourceConfig`、`TargetConfig`，默认只读生成计划；`apply`、`verify` 还必须指定已有的 `RunDirectory`。Apply 会再次核对来源指纹和目标写入前快照。相同内容已导入时不重复写入；目标有后续改动则拒绝覆盖。Verify 要求目标仍与导入时计划完全一致，因此本轮后续编辑出现后不能再把严格一致性检查当作当前配置检查。

本次已经完成导入，无须再次执行 Apply。新库配置可直接在当前页面使用；千问容量待补、DeepSeek 连通失败仍需单独处理。本轮本地提交只包含工具、忽略规则、无凭据清单和记录；已有 `frontend/components.d.ts` 改动不纳入提交。
