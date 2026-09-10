package com.codereview.material;

import java.util.List;

/** 共享代码物料：一次制备、多分析器复用（coupling / design-pattern）。 */
public record Material(
        String projectRootPackage,
        List<ClassInfo> classes,
        List<DepEdge> edges,
        String structureSummary) {
}
