# P31.1 逐文件材料准备、结构解析及来源记录

状态：完成（2026-10-21，Asia/Taipei）。范围为本地工程实现与固定样本单测；没有读取真实目标仓库，也没有执行数据库迁移。

## 1. 交付

| 能力 | 位置 | 说明 |
| --- | --- | --- |
| 固定版本材料准备 | `agent.specialist.material.MaterialPreparationService` | 按文件或提交差异读取固定 SHA，返回目标材料、缺口与失败原因；失败不返回空材料 |
| Java 结构解析 | `JavaStructureParser`、`JavaSourceStructure` | 包、导入、类型、继承、字段、方法签名、初始化块、`new`、注释、成员与覆盖行 |
| 材料来源记录 | `MaterialStore`、`AgentMaterialStore` | 同项目/SHA/路径/侧/行范围/正文哈希复用原 ID，内容变化不命中旧记录 |
| 差异两侧 | 同一准备服务 | 保留 base/head、新旧路径、BEFORE/AFTER；新增/删除文件只取存在一侧 |
| 资源参数 | `SpecialistProperties` | 继承深度 6、辅助文件 12、辅助 256 KiB 的 P29 候选起点 |

## 2. 行为与边界

- 目标读取失败、二进制、超过 4 MiB：该文件失败，错误码保留 `FILE_NOT_READABLE` 等语义；不把失败写成空成功。
- 问题与模式：非 Java 或 AST 解析失败仍使用原文，缺口写为 `UNSUPPORTED`／`PARSE_FAILED`；耦合必须取得目标 AST，非 Java 为 `UNSUPPORTED_LANGUAGE`，解析失败为 `STRUCTURE_PARSE_FAILED`。
- 差异：新增文件没有 BEFORE，删除文件没有 AFTER；重命名保留旧路径；根提交记录 `BASE_UNAVAILABLE` 但不失败；一侧不可读时保留另一侧并记录缺口。
- 结构覆盖：`coverage` 与 `uncoveredLines` 暴露类型外非空行、注释和成员范围；A09 的“无漏行”先在这里可见，拆分在 P31.4 继续。
- 材料 ID 在准备时生成并写入来源记录；P31.5 的调用关联使用同一 ID，不用最新源码重建。

## 3. 验证

- `JavaStructureParserTest`：5 项，覆盖包/导入/嵌套类型/record/enum/初始化块/覆盖行/解析失败。
- `MaterialPreparationServiceTest`：13 项，覆盖整文件、读取失败、结构失败降级、耦合失败、非 Java、新增/删除/重命名/根提交、二进制、单侧不可读、非法 SHA、来源 ID 复用。
- 后端全量回归在本批结束时通过；原始输出与最终数量以 [P31 总览](README.md) 和 STATE 的复验记录为准。

## 4. 未验证

- 未对真实目标仓库采样，4 MiB 上限、行数与宿主返回格式仍需 P31.9。
- 未执行 `material` 表迁移或写入真实数据库；`AgentMaterialStore` 只用 Mockito 替身验证。
- 未实现拆分（P31.4）、执行发布（P31.5）和材料阅读接口（P31.8）。
