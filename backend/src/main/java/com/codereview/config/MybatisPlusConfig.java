package com.codereview.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.codereview.common.PageLimits;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // MySQL 方言；达梦迁移时切换为 DbType.DM
        //
        // maxLimit 是**全站兜底**：各列表接口已用 PageLimits.page(...) 显式截断，
        // 这里再钳一道，保证以后新增的接口"忘了写也漏不出去"。上限常量只保留 PageLimits 一份，
        // 避免配置与代码各写一个 100 然后慢慢漂移。
        // 插件内部会把超限的 Page 直接 setSize(MAX_PAGE_SIZE)，所以返回体里的 size 是生效值。
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(PageLimits.MAX_PAGE_SIZE);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
