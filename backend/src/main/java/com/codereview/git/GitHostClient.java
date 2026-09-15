package com.codereview.git;

import java.util.List;
import java.util.Set;

/**
 * Git 宿主客户端抽象（GitHub 与 Gitea 的 REST API 高度兼容，故抽一层适配）。
 * credentialType：1 令牌（Bearer）/ 2 账号密码（Basic，credential 形如 user:pass）。
 * <p>
 * 接口按「最小公共能力」设计：不暴露任何宿主专有字段，{@link ChangedFile#patch()} 允许为空，
 * 重命名信息允许缺失，{@code files} 保证已稳定排序 —— 以便后续接入 Gitea 时无需改动上层。
 * <p>
 * <b>多宿主的选法是显式的</b>：每个实现用 {@link #hosts()} 声明它服务哪些 host，
 * 调用方通过 {@link GitHostClientRegistry#forRepo} 取到对应实现。**没有**"默认实现"这一说 ——
 * 未命中的 host 会显式报 {@code GIT_HOST_UNSUPPORTED}，绝不悄悄去查别的站点
 * （历史上正是"填了内网地址却去查 api.github.com"这种静默回落，把问题藏了很久）。
 */
public interface GitHostClient {

    /**
     * 本实现服务的仓库 host（大小写不敏感、精确匹配，如 {@code github.com}）。
     *
     * @see GitRepoRef#host()
     */
    Set<String> hosts();

    /** 是否服务这个仓库地址；匹配规则集中在这里，避免各实现各写一套。 */
    default boolean supports(GitRepoRef ref) {
        if (ref == null || ref.host() == null || hosts() == null) {
            return false;
        }
        return hosts().stream().anyMatch(host -> host.equalsIgnoreCase(ref.host().trim()));
    }

    /** 递归文件树（扁平条目） */
    List<GitTreeEntry> tree(String token, Integer credentialType, String owner, String repo, String branch);

    /**
     * 取某个文件在指定引用下的原始内容。
     * {@code ref} 既可为分支名，也可为 <b>commit sha</b> —— 只有传 sha 才能保证
     * "审查内容与审查记录里记下的提交一致"。
     */
    String rawFile(String token, Integer credentialType, String owner, String repo, String ref, String path);

    /** 分支 HEAD 的 commit sha */
    String headCommitSha(String token, Integer credentialType, String owner, String repo, String branch);

    /** 分支名列表 */
    List<String> branches(String token, Integer credentialType, String owner, String repo);

    /** 提交列表（沿用旧契约：第一页、裸数组） */
    List<CommitInfo> commits(String token, Integer credentialType, String owner, String repo, String branch);

    /** 提交列表（分页）：返回 {@code hasMore} 供前端滚动加载；不返回 total（宿主不提供，伪造会失真）。 */
    CommitPage commitPage(String token, Integer credentialType, String owner, String repo,
                          String branch, int page, int perPage);

    /** {@code base...head} 的变更文件路径列表（沿用旧契约：只有路径） */
    List<String> changedFiles(String token, Integer credentialType, String owner, String repo, String base, String head);

    /**
     * 单提交详情：含 {@code parents}（用于确定比较基线）与每个变更文件的 patch/状态/统计。
     * 这是「审 diff」的数据来源；{@code patch} 可能为空（二进制、过大，或宿主不返回）。
     */
    CommitDetail commitDetail(String token, Integer credentialType, String owner, String repo, String sha);
}
