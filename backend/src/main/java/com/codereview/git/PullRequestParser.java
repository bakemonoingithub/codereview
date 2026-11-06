package com.codereview.git;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 读取 GitHub 与 Gitea 1.16.1 共用的 PR JSON 字段。两端的编号、分支和 SHA 字段名一致；
 * 缺失的可选字段留空，不把某一端的专有字段写成另一端已经提供。
 */
final class PullRequestParser {

    private PullRequestParser() {
    }

    static List<PullRequests.Item> parseList(JsonNode root) {
        List<PullRequests.Item> items = new ArrayList<>();
        if (root == null || !root.isArray()) {
            return items;
        }
        for (JsonNode node : root) {
            PullRequests.Item item = parse(node);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    static PullRequests.Item parse(JsonNode node) {
        if (node == null || node.isNull() || !node.path("number").canConvertToLong()) {
            return null;
        }
        long number = node.path("number").asLong();
        if (number <= 0) {
            return null;
        }
        JsonNode head = node.path("head");
        JsonNode base = node.path("base");
        return new PullRequests.Item(number, text(node, "title"), text(node, "state"),
                node.path("draft").asBoolean(false), node.path("merged").asBoolean(false),
                text(node.path("user"), "login"), text(head, "ref"), text(base, "ref"),
                sha(text(head, "sha")), sha(text(base, "sha")),
                text(head.path("repo"), "full_name"), text(base.path("repo"), "full_name"),
                text(node, "html_url"), text(node, "updated_at"));
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private static String sha(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
