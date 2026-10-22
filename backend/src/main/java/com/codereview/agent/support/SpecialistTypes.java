package com.codereview.agent.support;

import java.util.List;

/** 三类专项的类型标识；与旧 analyzerType 数字不是一套，不做隐式换算。 */
public final class SpecialistTypes {

    public static final String ISSUE = "ISSUE";
    public static final String PATTERN = "PATTERN";
    public static final String COUPLING = "COUPLING";

    public static final List<String> ALL = List.of(ISSUE, PATTERN, COUPLING);

    private SpecialistTypes() {
    }

    public static boolean isValid(String type) {
        return ALL.contains(type);
    }
}
