-- 只标记根记录；子记录的可见性随所属任务或报告变化。
ALTER TABLE review_task
    ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0,
    ADD COLUMN deleted_at DATETIME(3) NULL,
    ADD KEY idx_task_deleted (is_deleted, deleted_at, id);

ALTER TABLE report
    ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0,
    ADD COLUMN deleted_at DATETIME(3) NULL,
    ADD KEY idx_report_deleted (is_deleted, deleted_at, id);
