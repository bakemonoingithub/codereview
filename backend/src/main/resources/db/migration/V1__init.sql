-- =====================================================================
-- V1__init.sql  初始化 9 张核心表
-- 约定：主键雪花 BIGINT（应用层 ASSIGN_ID）；审计 5 列；逻辑外键不建物理外键
-- =====================================================================

-- 登录账号
CREATE TABLE sys_user (
    id            BIGINT       NOT NULL COMMENT '主键(雪花)',
    username      VARCHAR(50)  NOT NULL COMMENT '登录名',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 密文',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    created_by    BIGINT       NULL COMMENT '创建人',
    updated_by    BIGINT       NULL COMMENT '更新人',
    is_deleted    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删/1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录账号';

-- 提示词
CREATE TABLE prompt (
    id                 BIGINT       NOT NULL COMMENT '主键(雪花)',
    name               VARCHAR(128) NOT NULL COMMENT '名称',
    description        VARCHAR(512) NULL COMMENT '描述',
    tags               TEXT         NULL COMMENT '标签(JSON 数组)',
    current_version_id BIGINT       NULL COMMENT '当前版本(逻辑外键->prompt_version.id)',
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by         BIGINT       NULL,
    updated_by         BIGINT       NULL,
    is_deleted         TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name),
    KEY idx_current_version (current_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提示词';

-- 提示词版本
CREATE TABLE prompt_version (
    id         BIGINT NOT NULL COMMENT '主键(雪花)',
    prompt_id  BIGINT NOT NULL COMMENT '所属提示词',
    version_no INT    NOT NULL COMMENT '版本号',
    content    TEXT   NOT NULL COMMENT '提示词正文',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    is_deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_prompt_version (prompt_id, version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提示词版本';

-- 模型接入配置
CREATE TABLE model_config (
    id         BIGINT       NOT NULL COMMENT '主键(雪花)',
    name       VARCHAR(128) NOT NULL COMMENT '名称',
    base_url   VARCHAR(512) NULL COMMENT '接口地址',
    token      VARCHAR(512) NULL COMMENT '令牌(AES 密文)',
    model_name VARCHAR(128) NULL COMMENT '模型名',
    status     TINYINT      NOT NULL DEFAULT 0 COMMENT '0未验证/1验证成功/2验证失败',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by BIGINT       NULL,
    updated_by BIGINT       NULL,
    is_deleted TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型接入配置';

-- 审查策略
CREATE TABLE review_strategy (
    id            BIGINT       NOT NULL COMMENT '主键(雪花)',
    name          VARCHAR(128) NOT NULL COMMENT '名称',
    analyzer_type TINYINT      NOT NULL COMMENT '1 llm-review/2 coupling/3 design-pattern/4 api-review',
    params_json   TEXT         NULL COMMENT '按分析器不同的参数',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by    BIGINT       NULL,
    updated_by    BIGINT       NULL,
    is_deleted    TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审查策略';

-- 项目
CREATE TABLE project (
    id              BIGINT       NOT NULL COMMENT '主键(雪花)',
    name            VARCHAR(128) NOT NULL COMMENT '项目名',
    gitea_url       VARCHAR(512) NOT NULL COMMENT 'Gitea 仓库地址',
    credential      VARCHAR(512) NULL COMMENT '凭据(AES 密文)',
    credential_type TINYINT      NOT NULL DEFAULT 1 COMMENT '1 token/2 password',
    current_branch  VARCHAR(128) NULL COMMENT '当前分支',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by      BIGINT       NULL,
    updated_by      BIGINT       NULL,
    is_deleted      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_gitea_url (gitea_url)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目';

-- 审查记录
CREATE TABLE review_record (
    id                     BIGINT       NOT NULL COMMENT '主键(雪花)',
    project_id             BIGINT       NOT NULL COMMENT '所属项目',
    strategy_id            BIGINT       NULL COMMENT '策略(跳转详情)',
    strategy_snapshot_json TEXT         NULL COMMENT '策略快照',
    branch                 VARCHAR(128) NULL COMMENT '分支',
    commit_sha             VARCHAR(64)  NULL COMMENT '提交 hash',
    scope_json             TEXT         NULL COMMENT '审查范围(文件清单)',
    status                 TINYINT      NOT NULL DEFAULT 0 COMMENT '0排队/1执行中/2成功/3失败/4部分成功',
    progress               TINYINT      NOT NULL DEFAULT 0 COMMENT '进度 0-100',
    started_at             DATETIME     NULL COMMENT '开始时间',
    finished_at            DATETIME     NULL COMMENT '结束时间',
    result_json            TEXT         NULL COMMENT '结构化审查结果',
    created_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by             BIGINT       NULL,
    updated_by             BIGINT       NULL,
    is_deleted             TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_project (project_id),
    KEY idx_strategy (strategy_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审查记录';

-- 综合报告
CREATE TABLE report (
    id               BIGINT       NOT NULL COMMENT '主键(雪花)',
    project_id       BIGINT       NOT NULL COMMENT '所属项目',
    name             VARCHAR(128) NOT NULL COMMENT '报告标题',
    content_markdown TEXT         NULL COMMENT '报告全文(Markdown)',
    status           TINYINT      NOT NULL DEFAULT 0 COMMENT '0排队/1生成中/2成功/3失败',
    progress         TINYINT      NOT NULL DEFAULT 0 COMMENT '进度 0-100',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by       BIGINT       NULL,
    updated_by       BIGINT       NULL,
    is_deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='综合报告';

-- 报告 <-> 记录 关联（多对多）
CREATE TABLE report_record (
    id         BIGINT   NOT NULL COMMENT '主键(雪花)',
    report_id  BIGINT   NOT NULL COMMENT '报告',
    record_id  BIGINT   NOT NULL COMMENT '审查记录',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by BIGINT   NULL,
    updated_by BIGINT   NULL,
    is_deleted TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_report_record (report_id, record_id),
    KEY idx_record (record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报告与记录关联';
