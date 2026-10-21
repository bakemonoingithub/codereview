-- P32.4 报告生成：报告调用可追溯，生成起止时间可读取。
-- 调用表增加可空 report_id：专项调用保持 task_id 归属，报告调用使用 report_id，
-- 不把报告伪装成审查任务，也不在报告里复制完整调用正文。
ALTER TABLE model_call
    ADD COLUMN report_id CHAR(26) NULL,
    ADD KEY idx_model_call_report (report_id, created_at);

ALTER TABLE report
    ADD COLUMN started_at  DATETIME(3) NULL,
    ADD COLUMN finished_at DATETIME(3) NULL,
    ADD KEY idx_report_created (created_at);
