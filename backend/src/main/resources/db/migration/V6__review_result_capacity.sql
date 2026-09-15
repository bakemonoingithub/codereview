-- =====================================================================
-- V6__review_result_capacity.sql  审查结果/报告正文的容量上限 + 失败原因列
--
-- 背景（现场实测，MySQL 8.0.39 / utf8mb4 / sql_mode 含 STRICT_TRANS_TABLES）：
--   review_record.result_json 与 report.content_markdown 都是 TEXT = 65535 **字节**
--   （中文 3 字节/字，约 2.1 万汉字就触顶），而写进去的是**未截断的 LLM 原文**：
--   一条记录把所有单元的 raw 拼成一个 JSON（LlmReviewAnalyzer:325、DiffReviewAnalyzer:160），
--   报告则是 N 条记录汇总后的整篇 Markdown。全仓没有任何输出侧长度限制。
--   实测超限写入 **不是静默截断，而是硬失败**：
--       errorCode=1406 / SQLState=22001 / "Data truncation: Data too long for column"
--
-- 后果链（比"结果缺失"严重）：超限发生在 ReviewExecutor.persist 的 updateById，
--   异常被 catch 后调用的 markFailed 会**用同一个仍带着超大 result_json 的实体再写一次**
--   ⇒ 第二次同样失败 ⇒ 异常逃出 @Async 方法（AsyncConfig 未注册 UncaughtExceptionHandler）
--   ⇒ 记录**永久停在 status=1 / progress=0**，接口层面零错误可见。
--   error_message 这一列就是为了让"失败"这件事有地方可写、且失败写不再携带大载荷。
--
-- 说明：不动 V1__init.sql（Flyway 会校验已应用迁移的 checksum）。
-- =====================================================================

-- 结果与报告正文：16MB 相对现实最大载荷（合并审查 50 单元约十几万字节）有约 100 倍余量
ALTER TABLE review_record
    MODIFY result_json MEDIUMTEXT NULL COMMENT '结构化审查结果';

ALTER TABLE report
    MODIFY content_markdown MEDIUMTEXT NULL COMMENT '报告全文(Markdown)';

-- 失败原因：**有界**列，写入前显式截断（见 ReviewExecutor 的 ERROR_MESSAGE_MAX）。
-- 之所以不塞进 result_json：失败路径若回写该列，会把上一次成功的结果覆盖掉。
ALTER TABLE review_record
    ADD COLUMN error_message VARCHAR(1000) NULL COMMENT '失败原因(有界,写入前截断)' AFTER result_json;
