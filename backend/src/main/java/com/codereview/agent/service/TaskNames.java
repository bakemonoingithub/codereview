package com.codereview.agent.service;

/** 任务展示名。库里的空名称与尚未回填的行都显示为「审查任务 」加任务 ID。 */
final class TaskNames {

    private TaskNames() {
    }

    static String display(String name, String id) {
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        return "审查任务 " + id;
    }
}
