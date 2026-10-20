-- P30.5 公共调用基础。沿用代次 1 的增量迁移；身份标记的版本号仍表示整体结构代次，
-- Flyway 历史表负责确切迁移进度。
--
-- 1) 验证、报告与后续 Agent 调用不一定依附专项任务，task_id 因此允许为空；
--    实际任务调用仍由业务层强制写入 taskId。
-- 2) 每次真实尝试都计入同一任务额度。reserved 在发请求前原子加一，硬上限由 SQL 条件保证，
--    并发调用不会因为"先读后写"越过 200 次。
ALTER TABLE model_call
    MODIFY COLUMN task_id CHAR(26) NULL;

CREATE TABLE task_quota (
    task_id    CHAR(26)    NOT NULL,
    hard_limit INT         NOT NULL,
    reserved   INT         NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (task_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
