-- P33.4 Agent 执行循环。仍沿用代次 1 的增量迁移，不改已执行迁移。
--
-- agent_run：每个任务一行执行进度，用于进度读取与重启收敛；不表示支持恢复原会话。
-- agent_tool_call：每次实际工具执行一行；provider_tool_call_id 只在同一响应内唯一，
-- 因此唯一键用 (task_id, turn_no, ordinal)，不假设跨轮唯一。
-- model_call.turn_no：Agent 的每轮调用可追溯；历史专项与报告调用保持 NULL。

CREATE TABLE agent_run (
    task_id           CHAR(26)     NOT NULL,
    turn_no           INT          NOT NULL DEFAULT 0,
    tool_attempts     INT          NOT NULL DEFAULT 0,
    network_attempts  INT          NOT NULL DEFAULT 0,
    current_action    VARCHAR(200) NULL,
    covered_json      JSON         NULL,
    state             VARCHAR(20)  NOT NULL,
    stop_reason       VARCHAR(40)  NULL,
    revision          INT          NOT NULL DEFAULT 0,
    created_at        DATETIME(3)  NOT NULL,
    updated_at        DATETIME(3)  NOT NULL,
    PRIMARY KEY (task_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE agent_tool_call (
    id                    CHAR(26)      NOT NULL,
    task_id               CHAR(26)      NOT NULL,
    model_call_id         CHAR(26)      NULL,
    turn_no               INT           NOT NULL,
    ordinal               INT           NOT NULL,
    provider_tool_call_id VARCHAR(120)  NOT NULL,
    tool_name             VARCHAR(40)   NOT NULL,
    arguments             MEDIUMTEXT    NULL,
    envelope              MEDIUMTEXT    NULL,
    material_ids          JSON          NULL,
    status                VARCHAR(20)   NOT NULL,
    started_at            DATETIME(3)   NULL,
    finished_at           DATETIME(3)   NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_tool_call (task_id, turn_no, ordinal),
    KEY idx_agent_tool_turn (task_id, turn_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

ALTER TABLE model_call
    ADD COLUMN turn_no INT NULL AFTER attempt;

CREATE INDEX idx_model_call_turn ON model_call (task_id, turn_no, attempt);
