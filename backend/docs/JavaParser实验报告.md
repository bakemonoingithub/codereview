# JavaParser 技术实验报告

> 实验分支：`experiment/javaparser` · 对应里程碑：M0「技术小实验」任务 4
> 日期：2026-09-03 · 执行人：DSH（模型）· 复核：杨思逊

## 1. 实验目的

在进入业务开发前，验证 M0 的两个技术小实验之一：

> **JavaParser 能否解析目标项目的真实 Java 代码，抽出 class / import / 方法签名。**

它要消除的返工风险：若 JavaParser 解析不了真实项目（Spring Boot + JPA + Lombok 形态），则 M1（真实项目解析）、M2（按类/方法边界切分）、M3（依赖图 + 结构摘要）都会受影响。

本报告同时回答三个子问题：

1. 能否解析真实项目的 Java 代码？
2. 能否可靠抽出 **类名 / import 依赖边 / 公共方法签名**？
3. 对 Java 17 新语法与 **Lombok** 的兼容性如何？

## 2. 实验环境

| 项 | 值 |
|---|---|
| JavaParser | `com.github.javaparser:javaparser-core:3.28.2`（Maven Central 最新稳定版） |
| 语言级别 | `ParserConfiguration.LanguageLevel.JAVA_17` |
| JDK | Microsoft OpenJDK 17.0.20.1 |
| 输入样例 | `D:\lyrics\back-end\AniSonManage\src\main\java`（AnisongManage 本地检出，Spring Boot 3.4.3 + Java 17 + JPA + Lombok 的 toy 项目，19 个 `.java` 文件） |
| 符号解析 | **未引入** `javaparser-symbol-solver`（本实验仅做语法解析） |

## 3. 实验方法

在 `code/backend` 新增两个文件，用最小样例完成验证，不进业务链路：

| 文件 | 作用 |
|---|---|
| `src/main/java/com/codereview/probe/JavaParserProbe.java` | 探针：遍历目录 → `StaticJavaParser.parse` → 抽 package/import/类型/字段/方法签名 → 输出结构化 JSON（对齐未来 M3 的 `STRUCT_SUMMARY` / `DEP_GRAPH`） |
| `src/test/java/com/codereview/probe/JavaParserProbeTest.java` | 7 个 JUnit 断言：5 个抽取正确性 + 2 个 Java17 语法 + 1 个真实项目扫描成功率（由 `-Dprobe.root` 触发） |

运行方式：

```powershell
mvnw.cmd test "-Dprobe.root=D:\lyrics\back-end\AniSonManage\src\main\java"
```

结果：**Tests run: 7, Failures: 0, Errors: 0 → BUILD SUCCESS**。

## 4. 实验结果

### 4.1 真实项目解析成功率

| 指标 | 值 |
|---|---|
| 文件总数 | 19 |
| 解析成功 | **19（100%）** |
| 解析失败 | 0 |
| 自动识别的项目包根 | `org.example.anisonmanage` |
| 抽出的类型 | 18 |
| 抽出的方法 | 21 |
| 抽出的字段 | 43 |

> 说明：19 个文件中 18 个各含 1 个顶层类型；`DataLoader.java` 的类整体被注释（仅剩 package + import），探针正确报告为「0 类型、解析成功」，属预期边界行为。

### 4.2 抽取正确性（对已知文件的精确断言，全部通过）

| 文件 | 关键断言结果 |
|---|---|
| `entity/Song.java` | `class`、`Song`；注解 `Entity/Table/Getter/Setter` 可见；6 个字段；**方法 0 个**（Lombok getter/setter 不在 AST） |
| `controller/SongController.java` | `class`；4 个方法签名正确；通配 import `org.springframework.web.bind.annotation` 被标记 `wildcard=true`；依赖分类 internal/external/jdk 正确 |
| `repository/SongRepository.java` | `interface`；`extends JpaRepository<Song,Long>`；方法 `int deleteBatchByIds(List<Long>)` |
| `pojo/Result.java` | `class`；泛型参数 `T`；静态方法 `static Result<E> success(E)` / `static Result success()` / `static Result error(String)` |
| `exception/GlobalExceptionHandler.java` | 3 个 `Result xxxx(Exception)` 方法全部抽出 |

### 4.3 Java 17 新语法（toy 项目缺失，合成 fixture 补测，全部通过）

- `record Point(int x, int y)` → 识别为 `record`，组件 `x`/`y` 当作字段抽出。
- `sealed interface Shape permits Circle, Square` + `final class Circle implements Shape` → 解析成功，`Shape` 识别为 interface，`Circle` 的 `implements Shape` 正确抽出。

### 4.4 依赖边分类（import 三类分法）

探针对每条 import 按「项目内 / JDK / 三方」分类，规则：以自动识别的项目包根 `org.example.anisonmanage` 为界。

| 类别 | 判定 | 实测样例 |
|---|---|---|
| `internal` | 前缀等于项目包根 | `org.example.anisonmanage.dto.SongDTO` |
| `jdk` | 前缀 `java.` | `java.util.List`、`java.time.LocalDateTime` |
| `external` | 其余 | `org.springframework.*`、`jakarta.persistence.*`、`lombok.*` |

实测 19 个文件的 import 均被正确归类，未发现误判。

## 5. 关键结论（回答三个子问题）

| 子问题 | 结论 |
|---|---|
| 能否解析真实 Java 代码？ | **能**。Spring Boot + JPA + Lombok 形态 19/19 全部解析成功，0 失败。 |
| 能否抽出 class/import/方法签名？ | **能**。类名/接口/枚举、继承/实现、注解、字段、方法签名、import 依赖边均可稳定抽取，断言全过。 |
| Java 17 与 Lombok 兼容性？ | record/sealed 可解析；Lombok 注解与字段可见，但**生成的 getter/setter 不在源码 AST**。 |

### 必须记录在案的 5 条边界结论（影响后续 M1–M3 实现）

1. **Lombok**：`@Getter/@Setter/@Data` 类的 getter/setter 是编译期生成，AST 里只有字段 + 注解，**没有方法**。→ 结构摘要只含源码显式方法；M3 若需完整访问器签名，须「字段 + Lombok 注解」额外推断（记入 M3 待办，本实验不实现）。

2. **通配 import**：`jakarta.persistence.*` 等通配导入，无符号解析时**无法精确到具体类**。→ 依赖边只能到「包」粒度，配合第 4.4 节的包前缀启发式分类；若 M3 需要精确到类的依赖边，再评估引入 `javaparser-symbol-solver`（本实验结论：**暂不需要**）。

3. **`asString()` 会规范化空白**：`JpaRepository<Song, Long>` 经 `asString()` 输出为 `JpaRepository<Song,Long>`（去掉类型参数间的空格）。→ 后续做字符串比对/断言前需先归一化，或统一用 `asString()` 的产物做比较。

4. **无类型文件**：文件整体被注释时（如 `DataLoader.java`）解析成功但 `types` 为空，探针需容忍并正确统计，不能误报为失败。

5. **顶层类型之外**：本实验只抽 `CompilationUnit.getTypes()` 的**顶层类型**；内部类/匿名类/局部类未纳入。toy 项目无内部类，若真实项目大量使用内部类，M3 需改用 `findAll(...)` 递归收集（记入风险）。

## 6. 与后续里程碑的衔接

- **M1**：探针能力接入「Gitea 拉取 → 解析 → 单文件 LLM 审查」闭环，验证真实项目解析链路。
- **M2**：复用探针的类/方法边界信息做「按类/方法切分」，单元带固定头。
- **M3**：探针升级为共享代码物料——import 依赖边 + 类名 + 公共方法签名 → 构建依赖图 + 结构摘要（一次制备，coupling 与 design-pattern 复用）；届时处理 Lombok 推断与内部类（见第 5 节待办）。

## 7. 遗留待办与风险

| 项 | 状态 |
|---|---|
| Lombok 生成方法的访问器推断 | M3 待办（需时再做） |
| 内部类/匿名类的递归收集 | 风险：真实项目若大量使用，M3 需 `findAll` 补抽 |
| 精确到类的依赖边（symbol-solver） | 暂不需要，M3 按需评估 |
| 真实 PDM 业务项目复测 | 建议 M1 用真实项目再做一次成功率复核 |

## 8. 附录：测试用例清单

| 测试方法 | 覆盖点 |
|---|---|
| `extractLombokEntity` | Lombok 实体：注解/字段可见、getter/setter 不在 AST |
| `extractController` | 4 方法签名、通配 import、import 三类分类 |
| `extractRepositoryInterface` | interface 识别、泛型 extends |
| `extractGenericClassWithStaticMethods` | 泛型参数、静态/实例方法签名 |
| `parseJava17Record` | record 类型与组件字段 |
| `parseJava17Sealed` | sealed 接口与实现类 |
| `parseRealProject` | 真实项目 100% 解析 + 关键文件识别 + 输出 JSON 工件 |
