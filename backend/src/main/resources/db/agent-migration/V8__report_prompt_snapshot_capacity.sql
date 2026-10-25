-- 报告生成配置快照内含所选提示词正文，需要容纳与 prompt_version 相同级别的正文容量。
ALTER TABLE report
    MODIFY COLUMN instruction_snapshot MEDIUMTEXT NULL;
