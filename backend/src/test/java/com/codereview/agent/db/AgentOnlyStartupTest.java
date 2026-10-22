package com.codereview.agent.db;

import com.codereview.CodeReviewApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class AgentOnlyStartupTest {
    @Test
    void missingAgentConfigurationFailsInsteadOfStartingLegacyApplication() {
        new ApplicationContextRunner().withUserConfiguration(CodeReviewApplication.class)
                .withPropertyValues("spring.datasource.url=jdbc:mysql://invalid/code_review")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseInstanceOf(AgentSchemaMismatchException.class));
    }
}
