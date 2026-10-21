package com.codereview.agent.specialist.protocol;

import java.util.List;

/**
 * 三类专项的模型输出结构。
 *
 * <p>这些对象只承载“模型声明的业务内容”；稳定 ID、任务/目标、版本、完整性和调用记录
 * 由服务端封套补齐，模型不能填写。位置里的 {@code materialId} 必须是实际发送的材料。
 */
public final class SpecialistResults {

    private SpecialistResults() {
    }

    public sealed interface Result permits IssueResult, PatternResult, CouplingResult {
        String summary();

        List<String> limitations();
    }

    public record Location(String materialId, int startLine, int endLine) {
    }

    public record Issue(String category, String severity, String title, List<Location> locations,
                        String reason, String suggestion, List<Location> evidence,
                        String changeRelation) {
        public Issue {
            locations = locations == null ? List.of() : List.copyOf(locations);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    public record IssueResult(String summary, List<Issue> issues, List<String> limitations)
            implements Result {
        public IssueResult {
            issues = issues == null ? List.of() : List.copyOf(issues);
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
        }
    }

    public record PatternCandidate(String name, String family, String role, String explanation,
                                   List<Location> evidence) {
        public PatternCandidate {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    public record PatternResult(String summary, List<PatternCandidate> patterns,
                                List<String> limitations) implements Result {
        public PatternResult {
            patterns = patterns == null ? List.of() : List.copyOf(patterns);
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
        }
    }

    public record CouplingObservation(String title, String explanation,
                                      List<String> dependencyIds, List<Location> evidence,
                                      String suggestion) {
        public CouplingObservation {
            dependencyIds = dependencyIds == null ? List.of() : List.copyOf(dependencyIds);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    public record CouplingResult(String summary, List<CouplingObservation> observations,
                                 List<String> limitations) implements Result {
        public CouplingResult {
            observations = observations == null ? List.of() : List.copyOf(observations);
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
        }
    }
}
