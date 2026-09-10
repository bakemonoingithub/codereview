package com.codereview.git;

import java.util.List;

/**
 * Git 宿主客户端抽象（GitHub 与 Gitea 的 REST API 高度兼容，故抽一层适配）。
 * credentialType：1 令牌（Bearer）/ 2 账号密码（Basic，credential 形如 user:pass）。
 */
public interface GitHostClient {

    /** 递归文件树（扁平条目） */
    List<GitTreeEntry> tree(String token, Integer credentialType, String owner, String repo, String branch);

    /** 取某个文件的原始内容 */
    String rawFile(String token, Integer credentialType, String owner, String repo, String branch, String path);

    /** 分支 HEAD 的 commit sha */
    String headCommitSha(String token, Integer credentialType, String owner, String repo, String branch);

    /** 分支名列表 */
    List<String> branches(String token, Integer credentialType, String owner, String repo);

    /** 提交列表 */
    List<CommitInfo> commits(String token, Integer credentialType, String owner, String repo, String branch);

    /** base...head 的变更文件路径列表 */
    List<String> changedFiles(String token, Integer credentialType, String owner, String repo, String base, String head);
}
