-- NULL 保留旧策略在任务创建时读取当前版本的行为。
ALTER TABLE review_strategy
    ADD COLUMN prompt_version_id CHAR(26) NULL AFTER prompt_id;
