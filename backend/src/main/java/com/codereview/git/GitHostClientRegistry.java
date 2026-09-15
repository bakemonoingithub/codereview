package com.codereview.git;

import com.codereview.common.BusinessException;
import com.codereview.common.ResultCode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 按仓库 host 选实现（策略注册表）。
 *
 * <p>写法刻意与本仓已有的 {@code Analyzer} 注册表一致（{@code ReviewExecutor} 里
 * {@code Collectors.toMap(Analyzer::type, identity())} + 取不到就抛"不支持的类型"）：
 * 注入所有实现，按 host 取匹配的那个，**取不到就显式报错**。
 *
 * <p>为什么不能"默认用 GitHub"：内网 Gitea 项目一旦被拿去查 {@code api.github.com}，
 * 表现是"仓库读不到/文件树为空"，而错误信息里没有任何线索指向 host —— 这正是 E1 被藏了很久的原因。
 * 现在同样的场景会直接告诉用户"未接入的仓库宿主：xxx"。
 */
@Component
public class GitHostClientRegistry {

    private final List<GitHostClient> clients;

    public GitHostClientRegistry(List<GitHostClient> clients) {
        this.clients = clients;
    }

    /**
     * 取服务该仓库的实现。
     *
     * @throws BusinessException {@code GIT_HOST_UNSUPPORTED}：没有任何实现声明服务该 host
     * @throws IllegalStateException 多于一个实现声明服务同一 host（配置错误，启动后即暴露）
     */
    public GitHostClient forRepo(GitRepoRef ref) {
        List<GitHostClient> matches = clients.stream().filter(client -> client.supports(ref)).toList();
        if (matches.isEmpty()) {
            throw new BusinessException(ResultCode.GIT_HOST_UNSUPPORTED.getCode(),
                    "未接入的仓库宿主：" + hostOf(ref) + "（当前已接入：" + supportedHosts() + "）");
        }
        if (matches.size() > 1) {
            throw new IllegalStateException("多个 GitHostClient 都声明服务 host=" + hostOf(ref) + "："
                    + matches.stream().map(c -> c.getClass().getSimpleName()).collect(Collectors.joining(", ")));
        }
        return matches.get(0);
    }

    /** 已接入的 host 清单（供错误信息与自检展示）。 */
    public Set<String> supportedHosts() {
        return clients.stream()
                .flatMap(client -> client.hosts() == null ? Set.<String>of().stream() : client.hosts().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String hostOf(GitRepoRef ref) {
        return ref == null || ref.host() == null || ref.host().isBlank() ? "<空地址>" : ref.host();
    }
}
