package com.codereview.agent.repository;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;

import java.util.Map;
import java.util.regex.Pattern;

/** 读取入口的校验：固定 SHA、仓库内相对路径。 */
public final class RepositoryPaths {

    private static final Pattern FULL_SHA = Pattern.compile("^[0-9a-fA-F]{40}$");

    private RepositoryPaths() {
    }

    /**
     * 固定版本只接受完整 40 位 SHA。
     *
     * <p>分支名与短 SHA 都会"看起来能读到东西"，但无法证明读到的就是任务发起时那个版本；
     * 宁可在入口拒绝，也不把移动的引用当成固定版本。
     */
    public static String requireFixedSha(String sha) {
        if (sha == null || !FULL_SHA.matcher(sha.trim()).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "固定版本必须使用完整 40 位提交 SHA", Map.of("field", "sha"));
        }
        return sha.trim().toLowerCase();
    }

    /** 仓库内相对路径：挡掉绝对路径、上跳段、反斜杠与超长路径。 */
    public static String requireSafePath(String path) {
        if (path == null || path.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "缺少文件路径", Map.of("field", "path"));
        }
        String trimmed = path.trim();
        if (trimmed.startsWith("/") || trimmed.startsWith("\\") || trimmed.contains("\\")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "只接受仓库内的相对路径：" + trimmed,
                    Map.of("field", "path"));
        }
        for (String segment : trimmed.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "路径不能含 . 或 .. 段：" + trimmed,
                        Map.of("field", "path"));
            }
        }
        if (trimmed.length() > 512) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "路径过长", Map.of("field", "path"));
        }
        return trimmed;
    }
}
