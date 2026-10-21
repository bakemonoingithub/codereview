-- 审查任务名称。历史任务回填为「审查任务 」加任务 ID；新任务在创建时写入。
ALTER TABLE review_task
    ADD COLUMN name VARCHAR(200) NULL AFTER project_id;

UPDATE review_task
SET name = CONCAT('审查任务 ', id)
WHERE name IS NULL OR name = '';

ALTER TABLE review_task
    MODIFY COLUMN name VARCHAR(200) NOT NULL;
