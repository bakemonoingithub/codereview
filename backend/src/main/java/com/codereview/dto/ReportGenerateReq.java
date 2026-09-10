package com.codereview.dto;

import java.util.List;

public record ReportGenerateReq(String name, Long modelConfigId, List<Long> recordIds, Long promptId) {
}
