package com.codereview.git;

import java.util.List;

/**
 * Git 宿主客户端抽象（GitHub 与 Gitea 的 REST API 高度兼容，故抽一层适配）
 */
public interface GitHostClient {

    /** 递归文件树（扁平条目） */
    List<GitTreeEntry> tree(String token, String owner, String repo, String branch);

    /** 取某个文件的原始内容 */
    String rawFile(String token, String owner, String repo, String branch, String path);

    /** 分支名列表 */
    List<String> branches(String token, String owner, String repo);
}
