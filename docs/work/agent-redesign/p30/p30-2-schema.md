# P30.2 新库结构与访问层

状态：P30.2 完成（2026-10-20，Asia/Taipei）。依据 [P30.1](p30-1-reuse-diff.md) 第 5、6.6 节与[共用设计](../p29/shared-design.md)第 2、7、8 节。本项只建立结构与访问层，不建库、不执行迁移、不改旧库。

## 1. 交付

| 交付 | 位置 |
| --- | --- |
| 新链迁移 V1（18 张表） | `backend/src/main/resources/db/agent-migration/V1__agent_init.sql` |
| 独立数据源与 MyBatis 工厂 | `backend/src/main/java/com/codereview/agent/db/AgentDatabase.java` |
| 库身份检查 | `AgentSchemaGuard.java`、`AgentSchemaMismatchException.java` |
| 条件装配 | `AgentDataSourceConfig.java`、`AgentDataSourceProperties.java` |
| 就绪检查 | `AgentHealthController.java`（`GET /api/v2/health`） |
| 实体与访问层 | `agent/entity/Agent*`、`agent/mapper/Agent*Mapper`，共 17 组 |
| 稳定 ID 与游标 | `agent/support/AgentIds.java`、`AgentCursor.java` |
| 初始化与检查脚本 | `scripts/agent-db-init.ps1`、`scripts/agent-db-check.ps1` |
| 配置样例 | `backend/src/main/resources/application.example.yml` 中注释掉的 `agent.datasource.*` |

## 2. 结构要点

- 18 张表：身份标记、项目、模型配置、模型验证、提示词、提示词版本、策略、任务、目标、单元、调用、材料、调用-材料、结果、问题、反馈、报告、报告来源。
- ID 统一 `CHAR(26)` ULID；时间为 UTC `DATETIME(3)`；业务表带 `revision`；沿用逻辑外键，不建物理外键，不依赖可能不生效的 `CHECK`。
- 无界模型输出固定在 `MEDIUMTEXT`／`LONGTEXT`：`model_call.request_body/response_body`、`review_result.body`、`prompt_version.content`、`report.frozen_input`（LONGTEXT）、`report.body`。
- 索引覆盖分页与回读路径：`review_task(project_id, created_at, id)`、`model_call(task_id, target_id, unit_id, attempt)`、`material(project_id, sha, path)`、`feedback` 主体唯一键。

### 2.1 与 P29 字段草案的两处收敛

| 设计草案 | 实现 | 原因 |
| --- | --- | --- |
| `feedback(issue_id?, result_id?)` | `feedback(subject_kind, subject_id)` + 唯一键 | MySQL 唯一索引对可空列不生效；用主体类型加稳定 ID 能真正保证"一个主体一条当前反馈" |
| `agent_schema_identity(..., checksum)` | `(schema_name, schema_version, installed_at, app_version)` | 迁移内容校验由 Flyway 的 `agent_schema_history.checksum` 负责，再存一份哈希会出现两套真相 |

## 3. 独立数据源与启动顺序

新库不注册 `DataSource` Bean：旧库仍由 Spring Boot 自动配置创建，避免多一个数据源导致旧库自动配置整体退让。`AgentDatabase` 内部持有自己的连接池、Flyway 链、MyBatis 工厂与事务模板，只在 `mapper(...)` 与 `inTransaction(...)` 内可见。

启动顺序（任一步失败直接终止启动）：

1. 建 Hikari 池（`agent.datasource.*`，默认 10 连接、10 秒建连超时）；
2. `AgentSchemaGuard.preMigrate`：库名匹配、无旧链 `flyway_schema_history`、`agent_schema_history` 与 `agent_schema_identity` 不能只存在其一；
3. Flyway `classpath:db/agent-migration`（历史表 `agent_schema_history`，禁止 baseline）；
4. `AgentSchemaGuard.postMigrate`：身份标记版本必须为 `1`；
5. 建立 MyBatis-Plus 工厂（`mapUnderscoreToCamelCase`、`isDeleted` 逻辑删除），注册 17 个映射器。

未配置 `agent.datasource.url` 时整组 Bean 不创建，`/api/v2/*` 也不注册；旧功能不受影响。配置项见 `application.example.yml` 注释块。

## 4. 脚本

- `scripts/agent-db-init.ps1`：拒绝 `code_review`，输出建库与独立账号 SQL；`-Execute -MysqlExe` 才实际执行，口令仍需人工填入。
- `scripts/agent-db-check.ps1`：只读检查库名、旧链历史、18 张表与版本标记；失败退出码 1，缺少 mysql 客户端退出码 2。两项都只面向操作者，不替代应用启动时的身份检查。

## 5. 验证记录

| 项目 | 状态 | 说明 |
| --- | --- | --- |
| 编译 | 通过 | `mvn -o -Dtest='Agent*Test' test`，JDK 21 |
| 新增单元测试 | 16 项通过 | ID 形状与唯一性、游标编解码、身份检查四条规则、V1 契约（18 表、无旧表、无 CHECK/外键、身份插入） |
| 全量回归 | 通过 | `mvn -o test`：351 项，0 失败，9 跳过（含原有 335 项基线） |
| 实际建库与迁移 | 未执行 | 没有可用 MySQL 实例；建库、迁移与身份检查的真实执行留 P30.8，不能记为已通过 |
| 脚本实际运行 | 未执行 | 本机无 mysql 客户端；只做了语法与参数审查 |

## 6. 交接

- P30.3：配置资源的 v2 接口直接使用本项实体与映射器；删除被引用时用 `CONFIG_IN_USE`，更新用 `revision` 条件。
- P30.6：任务、目标、调用表已就绪；幂等键表与调度状态留到该项新增 V2 迁移。
- P30.8：以隔离新库执行 `scripts/agent-db-init.ps1` 与 `-check`，并验证 A22（误指旧库被拒绝）。
