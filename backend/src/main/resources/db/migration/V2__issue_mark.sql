-- =====================================================================
-- V2__issue_mark.sql  issue 标记表（准确率复核用，服务验收指标 5）
-- 约定：主键雪花 BIGINT（应用层 ASSIGN_ID）；审计 5 列；逻辑外键不建物理外键
-- mark_value：0 未标记（撤销后的状态）/ 1 误报 / 2 已采纳
-- 说明：撤销标记写回 0 而不是删行——逻辑删除会让唯一键仍被占用，
--       导致同一条 issue 无法被重新标记。
-- =====================================================================

CREATE TABLE issue_mark (
    id          BIGINT       NOT NULL COMMENT '主键(雪花)',
    record_id   BIGINT       NOT NULL COMMENT '审查记录(逻辑外键->review_record.id)',
    unit_path   VARCHAR(512) NOT NULL COMMENT '单元所在文件路径',
    issue_index INT          NOT NULL COMMENT 'issue 在该单元 issues 数组中的下标',
    unit_name   VARCHAR(255) NULL COMMENT '单元名(便于展示)',
    mark_value  TINYINT      NOT NULL DEFAULT 0 COMMENT '0未标记/1误报/2已采纳',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    created_by  BIGINT       NULL COMMENT '创建人',
    updated_by  BIGINT       NULL COMMENT '更新人',
    is_deleted  TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删/1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_record_unit_issue (record_id, unit_path, issue_index),
    KEY idx_record (record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='issue 标记(误报/已采纳)';
