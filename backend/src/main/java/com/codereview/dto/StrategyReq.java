package com.codereview.dto;

public record StrategyReq(String name, Integer analyzerType, Long modelConfigId, Integer threshold) {
}
