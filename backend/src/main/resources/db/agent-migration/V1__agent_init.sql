-- 新路线的独立库结构（P30.2）。与旧链 db/migration 的 V1–V7 完全分离：
-- 本文件由 classpath:db/agent-migration 从 V1 起单独初始化，禁止在旧库上 baseline 后继续。
-- 约定：ID 为服务端生成的 26 位 ULID；时间为 UTC 的 DATETIME(3)；逻辑外键，不建物理外键；
--       revision 用于乐观并发；JSON 用于快照，不做查询列。

SET NAMES utf8mb4;

-- 库身份标记：AgentSchemaGuard 在迁移后校验，缺失或版本不符时拒绝启动。
CREATE TABLE agent_schema_identity (
    schema_name   VARCHAR(64)  NOT NULL,
    schema_version VARCHAR(20) NOT NULL,
    installed_at  DATETIME(3)  NOT NULL,
    app_version   VARCHAR(40)  NULL,
    PRIMARY KEY (schema_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO agent_schema_identity (schema_name, schema_version, installed_at, app_version)
VALUES ('ai_code_review_agent', '1', UTC_TIMESTAMP(3), 'p30.2');

-- 项目。credential 为凭据本体，只写不读：沿用既有维护层存于该列，接口、日志与快照都不回传。
CREATE TABLE project (
    id              CHAR(26)     NOT NULL,
    name            VARCHAR(100) NOT NULL,
    repo_url        VARCHAR(500) NOT NULL,
    credential  VARCHAR(1024) NULL,
    credential_type TINYINT      NOT NULL DEFAULT 1,
    current_branch  VARCHAR(200) NOT NULL DEFAULT 'main',
    revision        INT          NOT NULL DEFAULT 0,
    created_at      DATETIME(3)  NOT NULL,
    updated_at      DATETIME(3)  NOT NULL,
    created_by      VARCHAR(64)  NULL,
    updated_by      VARCHAR(64)  NULL,
    is_deleted      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_project_repo (repo_url),
    KEY idx_project_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 模型配置。窗口、输出、tokenizer 与协议是发起任务与容量判断的输入，不能只存在 JSON 档案里。
-- credential 与项目同口径：凭据本体只写不读，不进入任何响应。
CREATE TABLE model_config (
    id            CHAR(26)     NOT NULL,
    name          VARCHAR(100) NOT NULL,
    base_url      VARCHAR(500) NOT NULL,
    model_name    VARCHAR(200) NOT NULL,
    credential VARCHAR(1024) NULL,
    context_window INT         NULL,
    max_output     INT         NULL,
    tokenizer      VARCHAR(100) NULL,
    protocol       VARCHAR(40) NOT NULL DEFAULT 'OPENAI_CHAT',
    revision       INT         NOT NULL DEFAULT 0,
    created_at     DATETIME(3) NOT NULL,
    updated_at     DATETIME(3) NOT NULL,
    created_by     VARCHAR(64) NULL,
    updated_by     VARCHAR(64) NULL,
    is_deleted     TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_model_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 验证记录绑定配置修订：修订一改，旧验证自动失效，历史验证保留可追溯。
CREATE TABLE model_validation (
    id              CHAR(26)    NOT NULL,
    model_config_id CHAR(26)    NOT NULL,
    config_revision INT         NOT NULL,
    scope           VARCHAR(40) NOT NULL,
    result          VARCHAR(16) NOT NULL,
    detail_json     JSON        NULL,
    created_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_validation_config (model_config_id, config_revision)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE prompt (
    id                 CHAR(26)     NOT NULL,
    name               VARCHAR(100) NOT NULL,
    description        VARCHAR(500) NULL,
    tags               VARCHAR(500) NULL,
    purpose            VARCHAR(32)  NOT NULL DEFAULT 'SPECIALIST',
    current_version_id CHAR(26)     NULL,
    revision           INT          NOT NULL DEFAULT 0,
    created_at         DATETIME(3)  NOT NULL,
    updated_at         DATETIME(3)  NOT NULL,
    created_by         VARCHAR(64)  NULL,
    updated_by         VARCHAR(64)  NULL,
    is_deleted         TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_prompt_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 正文只经新版本写入；历史版本不可改，实际调用快照另存。
CREATE TABLE prompt_version (
    id         CHAR(26)    NOT NULL,
    prompt_id  CHAR(26)    NOT NULL,
    version_no INT         NOT NULL,
    content    MEDIUMTEXT  NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_prompt_version (prompt_id, version_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE review_strategy (
    id              CHAR(26)    NOT NULL,
    name            VARCHAR(100) NOT NULL,
    specialist_type VARCHAR(20) NOT NULL,
    model_config_id CHAR(26)    NULL,
    prompt_id       CHAR(26)    NULL,
    revision        INT         NOT NULL DEFAULT 0,
    created_at      DATETIME(3) NOT NULL,
    updated_at      DATETIME(3) NOT NULL,
    created_by      VARCHAR(64) NULL,
    updated_by      VARCHAR(64) NULL,
    is_deleted      TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_strategy_type (specialist_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 任务。route 预留 AGENT；state 只经带 revision 的条件更新写入终态。
CREATE TABLE review_task (
    id                 CHAR(26)    NOT NULL,
    project_id         CHAR(26)    NOT NULL,
    route              VARCHAR(20) NOT NULL DEFAULT 'SPECIALIST',
    specialist_type    VARCHAR(20) NULL,
    state              VARCHAR(20) NOT NULL,
    stop_reason        VARCHAR(40) NULL,
    input_snapshot     JSON        NULL,
    config_snapshot    JSON        NULL,
    revision           INT         NOT NULL DEFAULT 0,
    created_at         DATETIME(3) NOT NULL,
    updated_at         DATETIME(3) NOT NULL,
    started_at         DATETIME(3) NULL,
    finished_at        DATETIME(3) NULL,
    deadline_at        DATETIME(3) NULL,
    cancel_requested_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    KEY idx_task_project (project_id, created_at, id),
    KEY idx_task_state (state)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 一个文件一个目标；差异目标保留比较关系与旧路径，文件目标只取 head_sha。
CREATE TABLE task_target (
    id         CHAR(26)     NOT NULL,
    task_id    CHAR(26)     NOT NULL,
    ordinal    INT          NOT NULL,
    path       VARCHAR(512) NOT NULL,
    old_path   VARCHAR(512) NULL,
    base_sha   CHAR(40)     NULL,
    head_sha   CHAR(40)     NOT NULL,
    note       TEXT         NULL,
    state      VARCHAR(20)  NOT NULL,
    error_code VARCHAR(40)  NULL,
    revision   INT          NOT NULL DEFAULT 0,
    created_at DATETIME(3)  NOT NULL,
    updated_at DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_target_ordinal (task_id, ordinal)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 执行单元。整文件也有单元；重规划产生新 generation，旧代不能发布结果。
CREATE TABLE execution_unit (
    id         CHAR(26)    NOT NULL,
    target_id  CHAR(26)    NOT NULL,
    generation INT         NOT NULL DEFAULT 1,
    ordinal    INT         NOT NULL,
    ranges     JSON        NULL,
    state      VARCHAR(20) NOT NULL,
    revision   INT         NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_unit_target (target_id, generation, ordinal)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 每次真实网络尝试一条调用记录；正文先落盘再发请求，历史不回读模板重建。
CREATE TABLE model_call (
    id             CHAR(26)     NOT NULL,
    task_id        CHAR(26)     NOT NULL,
    target_id      CHAR(26)     NULL,
    unit_id        CHAR(26)     NULL,
    purpose        VARCHAR(32)  NOT NULL,
    attempt        INT          NOT NULL DEFAULT 1,
    state          VARCHAR(20)  NOT NULL,
    request_body   MEDIUMTEXT   NULL,
    response_body  MEDIUMTEXT   NULL,
    error_code     VARCHAR(40)  NULL,
    error_message  VARCHAR(1000) NULL,
    usage_json     JSON         NULL,
    adapter_version VARCHAR(40) NULL,
    estimate_method VARCHAR(40) NULL,
    created_at     DATETIME(3)  NOT NULL,
    started_at     DATETIME(3)  NULL,
    finished_at    DATETIME(3)  NULL,
    PRIMARY KEY (id),
    KEY idx_call_task (task_id, target_id, unit_id, attempt)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 材料按固定版本保存；side=FILE/BEFORE/AFTER 不可互换。
CREATE TABLE material (
    id           CHAR(26)      NOT NULL,
    project_id   CHAR(26)      NOT NULL,
    sha          CHAR(40)      NOT NULL,
    path         VARCHAR(512)  NOT NULL,
    side         VARCHAR(10)   NOT NULL DEFAULT 'FILE',
    start_line   INT           NULL,
    end_line     INT           NULL,
    content      MEDIUMTEXT    NULL,
    content_hash CHAR(64)      NULL,
    completeness VARCHAR(20)   NOT NULL DEFAULT 'COMPLETE',
    diagnostic   VARCHAR(1000) NULL,
    created_at   DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_material_version (project_id, sha, path)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 调用与材料关联：role 区分目标/辅助，disposition 区分实际发送/读取后未发送。
CREATE TABLE call_material (
    call_id     CHAR(26)    NOT NULL,
    material_id CHAR(26)    NOT NULL,
    role        VARCHAR(20) NOT NULL,
    disposition VARCHAR(20) NOT NULL,
    PRIMARY KEY (call_id, material_id, role)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 结果发布后不可变；schema_version 固定该结果的内容协议。
CREATE TABLE review_result (
    id            CHAR(26)     NOT NULL,
    task_id       CHAR(26)     NOT NULL,
    target_id     CHAR(26)     NULL,
    kind          VARCHAR(20)  NOT NULL,
    schema_version VARCHAR(20) NOT NULL,
    body          MEDIUMTEXT   NOT NULL,
    provenance    JSON         NULL,
    completeness  VARCHAR(20)  NOT NULL DEFAULT 'COMPLETE',
    created_at    DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_result_task (task_id, kind)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE issue (
    id           CHAR(26)     NOT NULL,
    result_id    CHAR(26)     NOT NULL,
    ordinal      INT          NOT NULL,
    category     VARCHAR(32)  NOT NULL,
    severity     VARCHAR(16)  NOT NULL,
    title        VARCHAR(500) NOT NULL,
    reason       TEXT         NULL,
    suggestion   TEXT         NULL,
    locations    JSON         NULL,
    source_units JSON         NULL,
    PRIMARY KEY (id),
    KEY idx_issue_result (result_id, ordinal)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 反馈主体为 issue 或 result；subject_id 与 subject_kind 唯一，防覆盖另靠 revision 条件更新。
CREATE TABLE feedback (
    id           CHAR(26)    NOT NULL,
    subject_kind VARCHAR(10) NOT NULL,
    subject_id   CHAR(26)    NOT NULL,
    assessment   VARCHAR(20) NULL,
    comment      TEXT        NULL,
    revision     INT         NOT NULL DEFAULT 0,
    created_at   DATETIME(3) NOT NULL,
    updated_at   DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_feedback_subject (subject_kind, subject_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE report (
    id                   CHAR(26)     NOT NULL,
    title                VARCHAR(200) NOT NULL,
    state                VARCHAR(20)  NOT NULL,
    model_snapshot       JSON         NULL,
    instruction_snapshot TEXT         NULL,
    frozen_input         LONGTEXT     NULL,
    body                 MEDIUMTEXT   NULL,
    error_code           VARCHAR(40)  NULL,
    error_message        VARCHAR(1000) NULL,
    revision             INT          NOT NULL DEFAULT 0,
    created_at           DATETIME(3)  NOT NULL,
    updated_at           DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_report_state (state, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 冻结来源：正文、版本、当时反馈都在 source_snapshot 内，不联查最新数据。
CREATE TABLE report_source (
    report_id       CHAR(26)  NOT NULL,
    result_id       CHAR(26)  NOT NULL,
    source_snapshot LONGTEXT  NOT NULL,
    ordinal         INT       NOT NULL,
    PRIMARY KEY (report_id, ordinal),
    KEY idx_report_source_result (result_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
