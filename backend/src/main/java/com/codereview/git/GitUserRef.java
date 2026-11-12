package com.codereview.git;

/**
 * 宿主平台用户基础信息引用（用于自动审查审核者白名单配置时解析用户 ID）。
 */
public record GitUserRef(long id, String login, String name, String avatarUrl) {
}
