-- =====================================================================
-- V4__report_duration.sql  报告耗时字段
--
-- 背景：验收指标 8 要求"5 个 900 行文件批量分析，记录完整耗时"（报告生成 < 1 小时）。
-- review_record 早就有 started_at/finished_at，但 report 表没有 ——
-- 报告耗时连数据都不存在，系统内无法自证，只能靠现场秒表或查库。
--
-- 说明：不动 V1__init.sql（Flyway 会校验已应用迁移的 checksum）。
-- =====================================================================

ALTER TABLE report
    ADD COLUMN started_at  DATETIME NULL COMMENT '开始生成时间' AFTER progress,
    ADD COLUMN finished_at DATETIME NULL COMMENT '生成结束时间' AFTER started_at;
