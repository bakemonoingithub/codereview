package com.codereview.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.codereview.common.CurrentUser;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 审计字段自动填充：created_at/updated_at/created_by/updated_by
 */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
        Long uid = CurrentUser.getId();
        this.strictInsertFill(metaObject, "createdBy", Long.class, uid);
        this.strictInsertFill(metaObject, "updatedBy", Long.class, uid);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
        this.strictUpdateFill(metaObject, "updatedBy", Long.class, CurrentUser.getId());
    }
}
