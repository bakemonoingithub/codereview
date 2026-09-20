package com.codereview.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.codereview.common.PageLimits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分页插件的 `maxLimit` 是**全站兜底**：各列表接口已用 {@code PageLimits.page(...)} 显式截断，
 * 这里保证"以后新增的接口即使忘了写也漏不出去"。
 *
 * <p>为什么值得一条测试：这正是 backlog ① 的成因 —— 约定写着上限 100，插件却没配；
 * 配置一旦被人删掉，行为会静默退回"想查多少查多少"，而且是**没有任何报错**的那种。
 */
class MybatisPlusConfigTest {

    @Test
    void paginationInterceptorCarriesTheDocumentedMaxLimit() {
        MybatisPlusInterceptor interceptor = new MybatisPlusConfig().mybatisPlusInterceptor();

        PaginationInnerInterceptor pagination = interceptor.getInterceptors().stream()
                .filter(PaginationInnerInterceptor.class::isInstance)
                .map(PaginationInnerInterceptor.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有注册分页插件"));

        assertTrue(pagination.getMaxLimit() != null, "maxLimit 不能为空");
        assertEquals(PageLimits.MAX_PAGE_SIZE, pagination.getMaxLimit().longValue(),
                "上限常量必须与 PageLimits 同源，避免两处各写一个数字后漂移");
    }
}
