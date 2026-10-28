package com.codereview.agent.support;

import java.util.Set;

/** 提示词资源的适用范围；与实际调用的协议用途分开。 */
public final class PromptApplicability {
    public static final String PURPOSE_PATTERN = "SPECIALIST|REPORT|AGENT|COMMON";
    private static final Set<String> SCENES = Set.of("SPECIALIST", "AGENT", "REPORT");

    private PromptApplicability() {
    }

    public static boolean supports(String purpose, String scene) {
        return scene != null && SCENES.contains(scene)
                && ("COMMON".equals(purpose) || scene.equals(purpose));
    }
}
