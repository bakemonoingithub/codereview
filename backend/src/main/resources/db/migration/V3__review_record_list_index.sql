-- =====================================================================
-- V3__review_record_list_index.sql  审查记录列表分页索引
--
-- 背景：审查记录列表的查询是
--   WHERE is_deleted=0 AND project_id=? ORDER BY created_at DESC LIMIT ?,?
-- 现有索引只有 idx_project(project_id)，排序要靠 filesort；记录一多，
-- 每翻一页都要把该项目全部记录重排一次。分页解决"传多少"，这个索引解决"查多快"。
--
-- 说明：不动 V1__init.sql（Flyway 会校验已应用迁移的 checksum，改了会让启动直接失败）。
-- =====================================================================

ALTER TABLE review_record
    ADD KEY idx_project_created (project_id, created_at);
