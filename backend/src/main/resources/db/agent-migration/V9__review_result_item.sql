-- 正文继续保存在 review_result；这里只建立逐条反馈的稳定身份。
CREATE TABLE review_result_item (
    id         CHAR(26)    NOT NULL,
    result_id  CHAR(26)    NOT NULL,
    kind       VARCHAR(20) NOT NULL,
    ordinal    INT         NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_result_item (result_id, kind, ordinal)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
