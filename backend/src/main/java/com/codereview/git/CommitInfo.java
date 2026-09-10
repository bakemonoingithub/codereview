package com.codereview.git;

/** 提交信息（提交视图用）。 */
public record CommitInfo(String sha, String message, String author, String date) {
}
