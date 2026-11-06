package com.codereview.git;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用共享裸镜像计算固定提交的净变化。GitHub 与 Gitea 走同一条比较，不调用 PR 文件或 PR diff 接口。
 */
@Component
public class FixedComparisonService {

    private final GitMirror mirror;

    public FixedComparisonService(GitMirror mirror) {
        this.mirror = mirror;
    }

    public FixedComparison.Result between(String cloneUrl, String credential, Integer credentialType,
                                          String targetHead, String sourceHead, int maxBytes) {
        return mirror.using(cloneUrl, credential, credentialType, List.of(targetHead, sourceHead), null,
                dir -> FixedComparison.between(dir, targetHead, sourceHead, FixedComparison.MAX_FILES, maxBytes));
    }

    public FixedComparison.Result trees(String cloneUrl, String credential, Integer credentialType,
                                        String mergeBase, String sourceHead, int maxBytes) {
        return mirror.using(cloneUrl, credential, credentialType, List.of(mergeBase, sourceHead), null,
                dir -> FixedComparison.trees(dir, mergeBase, sourceHead, FixedComparison.MAX_FILES, maxBytes));
    }
}
