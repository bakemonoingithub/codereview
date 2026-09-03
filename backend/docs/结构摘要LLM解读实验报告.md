# JavaParser 结构摘要 + LLM 解读 实验报告

> 实验分支：`experiment/javaparser`（已 cherry-pick AI 网关探针）
> 日期：2026-09-03 · 执行人：DSH（模型）· 复核：杨思逊

## 1. 实验目的

把前两个实验（JavaParser 静态抽取 + DeepSeek 网关）**串起来**，验证系统方案设计的核心机制：

> **「结构摘要替代原文」**：跨文件分析（耦合、设计模式、架构）只喂结构摘要/依赖图，不喂原始代码，规避上下文超限。
> **「确定性优先」**：结构类结论以 JavaParser 静态抽取为准，LLM 只做解读、排序、修复建议。

要回答的问题：**只给模型「类/接口/字段/方法签名/依赖边」这份结构摘要（不含方法体源码），DS 能否分析出有价值的东西？**

## 2. 实验环境

| 项 | 值 |
|---|---|
| 结构抽取 | `JavaParserProbe`（JavaParser 3.28.2，JAVA_17） |
| LLM 调用 | `AiGatewayProbe`（`https://api.deepseek.com`，`deepseek-chat`→实际 `deepseek-v4-flash`） |
| 输入项目 | `D:\lyrics\back-end\AniSonManage`（19 文件，18 类型） |
| 结构摘要规模 | 125 行紧凑文本（约 1.5k 字符） |
| 输出模式 | `response_format=json_object`，temperature 0 |

## 3. 实验方法

新增两个文件：

| 文件 | 作用 |
|---|---|
| `src/main/java/com/codereview/probe/StructureLlmProbe.java` | 组合探针：`buildStructureSummary()` 把 JavaParser 抽取结果压缩成紧凑摘要 → `analyzeStructure()` 发给 DS |
| `src/test/java/com/codereview/probe/StructureLlmProbeTest.java` | 断言：DS 返回合法 JSON 且含 `architecture_overview`/`design_patterns[]`/`coupling[]`/`suggestions[]` |

结构摘要喂给模型的格式（节选）：

```
[class] org.example.anisonmanage.controller.SongController
  @RestController @RequestMapping
  field SongService songService
  method Result add(SongDTO)
  ...
  internal_deps: SongDTO, Result, SongService, SongVO

[class] org.example.anisonmanage.service.serviceImpl.SongServiceImpl
  @Service @Transactional
  implements SongService
  field SongRepository songRepository
  ...
```

> 注意：**没有喂任何方法体源码**，只有签名 + 注解 + 内部依赖边。

运行：`mvnw test -Dtest=StructureLlmProbeTest -Dprobe.root=...`（注入 DEEPSEEK_API_KEY）。

结果：**Tests run: 1, Failures: 0 → BUILD SUCCESS**。

## 4. 实验结果（DS 的分析质量）

### 4.1 架构分层（准确）

DS 正确把 18 个类归入 8 层：controller / service / repository / entity / dto / vo / exception / 其他（启动类 + Result），并指出「依赖方向基本合理，Controller→Service→Repository 符合分层原则」。

### 4.2 设计模式（合理，6 个）

| 识别模式 | 置信度 | 评价 |
|---|---|---|
| 分层架构模式 | 高 | 正确 |
| 接口-实现分离（SongService/SongServiceImpl） | 高 | 正确 |
| DTO 模式 | 高 | 正确 |
| VO 模式 | 中 | 正确 |
| 全局异常处理（@RestControllerAdvice） | 高 | 正确 |
| 门面 Facade（Controller→Service） | 中 | 基本合理 |

### 4.3 耦合观察（8 条边，但分级偏松）

DS 列出 8 条依赖边并标注级别，但**几乎全部标为「高」**（如 Controller→Service、Service→Repository 这类常规分层依赖也被标「高」）。

### 4.4 建议（7 条，其中一条命中真实缺陷）

DS 给出一条**真实存在的缺陷**：

> 「批量删除方法命名不一致：SongService 接口中为 `batchDeleteSongs`，而 SongRepository 中为 `deleteBatchByIds`，建议统一命名。」

核对源码：`SongServiceImpl` 里 `songRepository.deleteBatchByIds(ids)`，接口方法叫 `batchDeleteSongs`——**确属不一致**。这说明结构摘要本身携带了足够信息，让 LLM 能在不看方法体的情况下发现接口契约层面的问题。

## 5. 关键结论

| 子问题 | 结论 |
|---|---|
| 只喂结构摘要，DS 能分析出东西吗？ | **能**。准确识别分层、设计模式、依赖关系，并命中真实命名不一致缺陷。 |
| 结构摘要替代原文可行吗？ | **可行**。125 行摘要即够模型做架构级解读，验证了上下文压缩路径。 |
| 确定性优先原则被印证吗？ | **被印证**。LLM 的耦合「高/中/低」分级偏主观偏松，量化耦合应回到 JavaParser 确定性计算。 |

### 必须记录在案的 4 条结论

1. **LLM 解读质量高、但量化不可靠**：设计模式/分层/命名这类「语义+结构」判断很准；但「耦合度高低」这种需要量化阈值（扇入/扇出/循环依赖）的结论，LLM 会「都标高」→ M3 的 coupling 分析器必须**先由 JavaParser 确定性计算依赖图指标，LLM 只做解读排序**，与 `系统方案设计.md` 原则 3 完全一致。

2. **空 stub 类会被正确识别为异常**：`LyricsController`/`RubyController` 是空实现（toy 项目里只有 `@RestController`、无字段无方法），DS 主动提示「未显示依赖，可能为空或未抽取完整」——模型对结构摘要的「信息缺口」有感知，这对 M3 的置信度输出是好信号。

3. **提示词 schema 决定输出可机器消费**：固定 JSON schema（architecture_overview/layering/design_patterns/coupling/suggestions）+ `json_object` 模式，让输出既可用于展示、也可落 `result_json`。

4. **组合探针零冲突复用**：`StructureLlmProbe` 仅组合了 `JavaParserProbe`（静态抽取）与 `AiGatewayProbe`（LLM 调用），无新依赖，证明「分析器 = 静态抽取 + LLM 解读」的 SPI 架构可落。

## 6. 与后续里程碑的衔接

- **M3 `design-pattern` 分析器**：结构摘要 + LLM 识别 → 本实验已验证可行，可直接把 `StructureLlmProbe.buildStructureSummary()` 作为 `STRUCT_SUMMARY` 物料的雏形；输出加「参与类深链 Gitea + 判定依据 + 置信度」。
- **M3 `coupling` 分析器**：依赖图**确定性计算**（扇入/扇出/循环依赖/高耦合）+ LLM 解读——本实验确认「LLM 分级偏松」，量化必须由 JavaParser 做。
- **报告耦合度章节（指标 7）**：结构摘要与依赖图即报告数据源。

## 7. 遗留待办与风险

| 项 | 状态 |
|---|---|
| 依赖图确定性计算（扇入/扇出/循环） | M3 实现，本实验确认必要 |
| 结构摘要递归归并（文件→模块→子系统） | M3 大项目超预算时启用 |
| 内部类/匿名类未纳入结构摘要 | 沿用 JavaParser 实验结论，M3 按需补 |
| LLM 输出字段级 JSON Schema 校验 | 本实验手写 has()/isArray() 断言；M1 起评估引入 schema 校验库 |

## 8. 附录：工件

- 结构摘要：`code/backend/target/structure-summary.txt`（125 行，构建自 AniSonManage）
- DS 分析：`code/backend/target/structure-llm-analysis.json`（architecture/layering/design_patterns/coupling/suggestions）
