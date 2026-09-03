package com.codereview.git;

/**
 * 树条目（扁平）：type = blob | tree
 */
public record GitTreeEntry(String path, String type) {
}
