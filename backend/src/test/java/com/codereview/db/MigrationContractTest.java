package com.codereview.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迁移文件的**契约测试**。
 *
 * <p>为什么需要它：本仓的后端测试全是 Mockito 桩、不连数据库，所以迁移语句
 * **不会**被任何测试执行 —— "文件里写了 MEDIUMTEXT"这件事在 CI 里没有任何东西拦着它写错。
 * 真实 MySQL 上的验证是人工做的（临时 schema 跑 V1–V6 + 写 20 万字节，见 backlog 归档），
 * 这里只锁住三条**改了就会出事**的约定：
 *
 * <ol>
 *   <li>V1 不许动：Flyway 校验已应用迁移的 checksum，改一行就让所有已部署环境启动失败；
 *       而"把 V1 的 TEXT 直接改成 MEDIUMTEXT"是最容易顺手做的错误修法。</li>
 *   <li>必须存在一条后续迁移，把 {@code review_record.result_json} 与
 *       {@code report.content_markdown} 双双升到 MEDIUMTEXT —— 漏一个就等于把同一个 bug 留在隔壁表。</li>
 *   <li>{@code error_message} 必须是**有界**列（VARCHAR，不是 TEXT）：
 *       它的用途正是让失败写不再携带无界载荷，用 TEXT 等于把坑换个列再挖一遍。</li>
 * </ol>
 */
class MigrationContractTest {

    private static String migration(String name) throws IOException {
        try (InputStream in = MigrationContractTest.class.getResourceAsStream("/db/migration/" + name)) {
            assertNotNull(in, "找不到迁移文件 " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 去掉 `--` 行注释并压掉空白，避免断言被注释里的同名文本蒙混通过。 */
    private static String effectiveSql(String sql) {
        return List.of(sql.split("\\R")).stream()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .reduce("", (a, b) -> a + " " + b)
                .replaceAll("\\s+", " ")
                .toLowerCase();
    }

    @Test
    void v1KeepsItsOriginalColumnTypesSoFlywayChecksumsStayValid() throws IOException {
        String v1 = effectiveSql(migration("V1__init.sql"));

        assertTrue(v1.contains("result_json text"),
                "V1 里的 result_json 必须保持 TEXT：改已应用的迁移会让 Flyway 校验失败");
        assertTrue(v1.contains("content_markdown text"),
                "V1 里的 content_markdown 必须保持 TEXT");
    }

    @Test
    void aLaterMigrationRaisesBothUnboundedLlmOutputColumns() throws IOException {
        String v6 = effectiveSql(migration("V6__review_result_capacity.sql"));

        assertTrue(v6.contains("alter table review_record modify result_json mediumtext"),
                "result_json 必须被后续迁移升到 MEDIUMTEXT，实际：" + v6);
        assertTrue(v6.contains("alter table report modify content_markdown mediumtext"),
                "content_markdown 必须被同一次迁移升到 MEDIUMTEXT（报告正文同样是无界 LLM 输出）");
    }

    @Test
    void errorMessageColumnIsBoundedAndNotAnotherTextColumn() throws IOException {
        String v6 = effectiveSql(migration("V6__review_result_capacity.sql"));

        assertTrue(v6.contains("add column error_message varchar("),
                "error_message 必须是有界的 VARCHAR 列，实际：" + v6);
        assertTrue(!v6.contains("error_message text"),
                "error_message 不能用 TEXT —— 它存在的意义就是承载有界载荷");
    }
}
