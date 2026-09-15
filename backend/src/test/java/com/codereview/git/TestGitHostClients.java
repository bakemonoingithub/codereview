package com.codereview.git;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 测试专用：构造"总是命中"的宿主注册表。
 *
 * <p>引入 host 路由后，被测服务不再直接依赖 `GitHostClient`，而是先经
 * {@link GitHostClientRegistry#forRepo} 选实现。既有的一批服务/分析器测试只想让调用落到自己的
 * mock 上，不关心路由本身，于是统一用这里的包装：把 mock 的 {@code supports} 打桩成恒真。
 *
 * <p>注意 `supports` 是接口默认方法，Mockito 的 mock **不会**执行默认实现（默认返回 false），
 * 所以必须显式打桩——否则测试会以"未接入的仓库宿主"这种看似莫名其妙的业务异常失败。
 */
public final class TestGitHostClients {

    private TestGitHostClients() {
    }

    /** 所有仓库都路由到给定的 mock 客户端。 */
    public static GitHostClientRegistry routingTo(GitHostClient client) {
        when(client.supports(any())).thenReturn(true);
        return new GitHostClientRegistry(List.of(client));
    }

    /** 只当依赖占位时用：内部创建一个不会被断言的 mock。 */
    public static GitHostClientRegistry withMockClient() {
        return routingTo(mock(GitHostClient.class));
    }
}
