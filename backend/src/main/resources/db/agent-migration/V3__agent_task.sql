-- P30.6 任务幂等键。同一项目内同一 Idempotency-Key 只能对应一条任务；
-- MySQL 唯一索引允许多个 NULL，因此不带键的普通创建不受影响。
ALTER TABLE review_task
    ADD COLUMN idempotency_key VARCHAR(100) NULL,
    ADD COLUMN request_hash CHAR(64) NULL;

CREATE UNIQUE INDEX uk_task_idempotency ON review_task (project_id, idempotency_key);
