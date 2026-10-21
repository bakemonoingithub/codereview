# P31.2 有限声明增强与有界继承定位

状态：完成（2026-10-21，Asia/Taipei）。范围为本地固定样本单测；没有真实多源码根仓库或第三方源码可用。

## 1. 交付

| 能力 | 位置 | 说明 |
| --- | --- | --- |
| 同版本声明定位 | `DeclarationResolver` | 文件内类型 → 显式 import → 同包 → 通配 import；读取候选后核对 package 与实际声明 |
| 有界继承遍历 | `DeclarationEnhancementService` | 父类与接口广度优先，访问键含 SHA/路径/限定名，循环与菱形去重 |
| 声明摘要 | `DeclarationSummaryBuilder` | 保留类型头、字段、签名、注释和原始行号；方法体/初始化块用等行数占位替换 |
| 直接类型补充 | 同一增强服务 | 字段、方法参数、返回类型及显式泛型参数的一层声明；不递归展开对象图 |
| 缺口记录 | `SpecialistMaterials.Gap` | `AMBIGUOUS`、`PARSE_FAILED`、`NOT_AVAILABLE`、`LIMIT_REACHED` 分别保留，不写成“类型不存在” |

## 2. 定位与限额

- 限定名、显式 import、同包与通配 import 按 Java 可见性顺序尝试；多个可核对声明给 `AMBIGUOUS`，不取列表第一个。
- 候选文件必须解析成功且包含目标限定名；包名不符、同名类、多个源码根都不会被文件名直接认定。
- 嵌套类型按 `Outer.Inner` 核对；匿名类、复杂泛型边界、静态 import 和编译器级重载绑定仍可能缺口。
- 继承深度默认 6，辅助文件默认 12，辅助正文默认 256 KiB；命中后停止相应分支并记录 `LIMIT_REACHED`，目标文件内已有类型不重复发送。
- 祖先只发送声明摘要，`completeness=DECLARATION_SUMMARY`；摘要行号与原文一致，方法体以 `// [实现省略]` 占位，不能当作完整源码。

## 3. 验证

- `DeclarationResolverTest`：8 项，覆盖显式 import 优先、同包、通配歧义、嵌套 import、同文件、第三方缺失、候选解析失败、包/路径核对。
- `DeclarationEnhancementServiceTest`：7 项，覆盖广度优先顺序、摘要省略方法体、深度限额、文件限额、循环/菱形、缺失父类型、直接引用缺口、目标文件去重。
- 以上均为 Mockito 固定仓库替身；未使用真实 Git 宿主。

## 4. 未验证

- 未在真实多模块仓库验证同名类型、多个源码根和通配 import 的实际分布。
- 未引入 JavaSymbolSolver、完整 classpath、依赖下载或反编译；这些仍不在批准范围。
- 未接入 P31.4 的容量精简与拆分，辅助材料的实际发送量仍需在 P31.9 校准。
