package com.codereview.dto;

import java.util.Map;

public record StrategyReq(String name, Integer analyzerType, Map<String, Object> params) {
}
