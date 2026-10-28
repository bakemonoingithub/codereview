package com.codereview.agent.config;

import com.codereview.agent.api.RuntimeConfigController;
import com.codereview.agent.execution.AgentBudgetProperties;
import com.codereview.agent.invocation.ModelSnapshot;
import com.codereview.agent.tools.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeConfigurationTest {
    @Test void defaultsAndCustomValuesValidateWithoutDatabase() {
        assertDoesNotThrow(() -> RuntimeConfigurationValidation.validate(new MockEnvironment()));
        assertDoesNotThrow(() -> RuntimeConfigurationValidation.validate(new MockEnvironment()
                .withProperty("agent.budget.model-turns", "12")
                .withProperty("agent.tools.limits.read-max-lines", "600")
                .withProperty("app.ui.title", "内网审查")));
    }
    @Test void rejectsUnknownKeysAndInvalidCrossLimits() {
        for (var env : new MockEnvironment[] {
                new MockEnvironment().withProperty("agent.budget.model-truns", "12"),
                new MockEnvironment().withProperty("agent.toolsdirectory", "/not-an-application-setting"),
                new MockEnvironment().withProperty("agent.model.per-task-concurrency", "5"),
                new MockEnvironment().withProperty("agent.tools.limits.read-max-lines", "100"),
                new MockEnvironment().withProperty("agent.model.transport-retries", "11"),
                new MockEnvironment().withProperty("agent.model.retry-jitter-ratio", "1.1"),
                new MockEnvironment().withProperty("agent.model.retry-multiplier", "0.5"),
                new MockEnvironment().withProperty("agent.model.retry-max-millis", "10"),
                new MockEnvironment().withProperty("app.ui.model-validation-timeout-millis", "60000"),
                new MockEnvironment().withProperty("app.ui.agent-validation-timeout-millis", "240000"),
                new MockEnvironment().withProperty("agent.budget.network-attempts", "201"),
                new MockEnvironment().withProperty("server.port", "65536"),
                new MockEnvironment().withProperty("app.ui.api-base", "https://example.test/api") }) {
            assertThrows(IllegalStateException.class, () -> RuntimeConfigurationValidation.validate(env));
        }
    }
    @Test void environmentCannotOverrideFileValuesOrDefaults() {
        MockEnvironment environment = new MockEnvironment();
        environment.withProperty("agent.budget.model-turns", "14").withProperty("github.token", "file-token");
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("systemEnvironment",
                java.util.Map.of("GIT_PAGER", "cat", "AGENT_BUDGET_MODELTURNS", "0",
                        "GITHUB_TOKEN", "environment-token-do-not-print", "SERVER_PORT", "65536")));
        assertDoesNotThrow(() -> RuntimeConfigurationValidation.validate(environment));
        assertEquals(14, org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("agent.budget", org.springframework.boot.context.properties.bind.Bindable.of(AgentBudgetProperties.class))
                .get().getModelTurns());
        assertEquals("file-token", environment.getProperty("github.token"));
        assertNull(environment.getProperty("server.port"));
        assertNull(environment.getProperty("GITHUB_TOKEN"));
    }

    @Test void arbitraryHostEnvironmentDoesNotBecomeApplicationConfiguration() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("customHostEnvironment",
                java.util.Map.of("AGENT_TOOLSDIRECTORY", "/opt/hostedtoolcache",
                        "AGENT_NEW_HOST_SETTING", "secret-do-not-print", "APP_UI_UNKNOWN", "secret-do-not-print",
                        "GIT_HTTP_UNKNOWN", "secret-do-not-print", "GITHUB_RUN_ID", "fixture")));
        assertDoesNotThrow(() -> RuntimeConfigurationValidation.validate(environment));
        assertNull(environment.getPropertySources().get("customHostEnvironment"));
    }

    @Test void applicationRemovesEnvironmentBeforeConfigDataAndJsonProcessing(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var file = directory.resolve("application.yml");
        java.nio.file.Files.writeString(file, "agent:\n  budget:\n    model-turns: 14\ngithub:\n  token: file-token\n");
        var environment = new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().replace("systemEnvironment", new SystemEnvironmentPropertySource("systemEnvironment",
                java.util.Map.of("AGENT_BUDGET_MODELTURNS", "0", "GITHUB_TOKEN", "environment-token-do-not-print",
                        "SPRING_CONFIG_LOCATION", "file:/must-not-load.yml", "SPRING_PROFILES_ACTIVE", "host-profile",
                        "SPRING_APPLICATION_JSON", "{\"agent\":{\"budget\":{\"model-turns\":0}}}")));
        var application = new org.springframework.boot.SpringApplication(RuntimeConfigurationValidation.class,
                BindingProbe.class);
        application.setEnvironment(environment);
        application.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        try (var context = application.run("--spring.config.location=" + file.toUri(), "--spring.main.banner-mode=off")) {
            assertEquals(14, context.getBean(AgentBudgetProperties.class).getModelTurns());
            assertEquals("file-token", context.getEnvironment().getProperty("github.token"));
            assertEquals(14, context.getBean("turnsFromValue"));
            assertEquals(0, context.getEnvironment().getActiveProfiles().length);
            assertNull(context.getEnvironment().getProperty("SPRING_APPLICATION_JSON"));
            assertNull(context.getEnvironment().getProperty("SPRING_CONFIG_LOCATION"));
            assertNull(context.getEnvironment().getPropertySources().get("systemEnvironment"));
        }
    }

    @org.springframework.boot.context.properties.EnableConfigurationProperties(AgentBudgetProperties.class)
    static class BindingProbe {
        @org.springframework.context.annotation.Bean
        Integer turnsFromValue(@org.springframework.beans.factory.annotation.Value("${agent.budget.model-turns}") int turns) {
            return turns;
        }
    }

    @Test void environmentCannotSupplyPlaceholderValues() {
        MockEnvironment environment = new MockEnvironment().withProperty("github.token", "${GITHUB_TOKEN:}");
        environment.getPropertySources().addFirst(githubActionsEnvironment());
        assertDoesNotThrow(() -> RuntimeConfigurationValidation.validate(environment));
        assertEquals("", environment.getProperty("github.token"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "github.tokne", "agent.budget.model-truns", "git.http.read-timeout-second" })
    void githubActionsEnvironmentDoesNotHideUnknownFileConfiguration(String unknownKey) {
        MockEnvironment environment = new MockEnvironment().withProperty(unknownKey, "secret-do-not-print");
        environment.getPropertySources().addFirst(githubActionsEnvironment());
        var failure = assertThrows(IllegalStateException.class,
                () -> RuntimeConfigurationValidation.validate(environment));
        assertTrue(failure.getMessage().contains(unknownKey));
        assertFalse(failure.getMessage().contains("secret-do-not-print"));
        assertFalse(failure.getMessage().contains("fixture-token-do-not-print"));
    }

    @Test void githubActionsEnvironmentDoesNotHideUnknownCommandLineConfiguration() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(githubActionsEnvironment());
        environment.getPropertySources().addFirst(new org.springframework.core.env.SimpleCommandLinePropertySource(
                "--github.tokne=secret-do-not-print"));
        var failure = assertThrows(IllegalStateException.class,
                () -> RuntimeConfigurationValidation.validate(environment));
        assertTrue(failure.getMessage().contains("github.tokne"));
        assertFalse(failure.getMessage().contains("secret-do-not-print"));
        assertFalse(failure.getMessage().contains("fixture-token-do-not-print"));
    }

    private static SystemEnvironmentPropertySource githubActionsEnvironment() {
        return new SystemEnvironmentPropertySource("systemEnvironment", java.util.Map.of(
                "GITHUB_ACTIONS", "true", "GITHUB_ACTION", "__run", "GITHUB_RUN_ID", "40665486775",
                "GITHUB_WORKFLOW_REF", "fixture/repo/.github/workflows/release.yml@refs/tags/fixture",
                "GITHUB_WORKSPACE", "/home/runner/work/fixture/fixture", "GITHUB_API_URL", "https://api.github.com",
                "GITHUB_TOKEN", "fixture-token-do-not-print", "AGENT_TOOLSDIRECTORY", "/opt/hostedtoolcache"));
    }

    @Test void checkOnlyEntryUsesExternalFileAndCommandLineOverridesWithoutDatabase(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var file = directory.resolve("application.yml");
        java.nio.file.Files.writeString(file, "agent:\n  datasource:\n    url: jdbc:mysql://invalid/not-connected\n  budget:\n    model-turns: 0\n");
        String location = "--spring.config.location=" + file.toUri();
        assertDoesNotThrow(() -> com.codereview.CodeReviewApplication.checkConfiguration(new String[] {
                "--check-config", location, "--agent.budget.model-turns=2", "--spring.main.banner-mode=off" }));
        assertThrows(Exception.class, () -> com.codereview.CodeReviewApplication.checkConfiguration(new String[] {
                "--check-config", location, "--spring.main.banner-mode=off" }));
    }

    @Test void documentedExampleBindsWithoutAnyLiveDependencies(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var example = java.nio.file.Path.of("../docs/work/agent-redesign/runtime-configuration/application.yml.example").toAbsolutePath();
        var configFile = java.nio.file.Files.copy(example, directory.resolve("application.yml"));
        assertDoesNotThrow(() -> com.codereview.CodeReviewApplication.checkConfiguration(new String[] {
                "--check-config", "--spring.config.location=" + configFile.toUri(),
                "--spring.main.banner-mode=off" }));
    }
    @Test void renderedIntranetTemplateBindsAllRetryAndValidationSettings(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var template = java.nio.file.Files.readString(java.nio.file.Path.of("../deploy/config/application.yml.example"));
        var rendered = template.replace("__AGENT_DB_NAME__", "ai_code_review_agent")
                .replace("__AGENT_DB_USER__", "fixture").replace("__AGENT_DB_PASSWORD__", "fixture");
        var configFile = directory.resolve("application.yml");
        java.nio.file.Files.writeString(configFile, rendered);
        assertDoesNotThrow(() -> com.codereview.CodeReviewApplication.checkConfiguration(new String[] {
                "--check-config", "--spring.config.location=" + configFile.toUri(), "--spring.main.banner-mode=off" }));
        var yaml = new org.yaml.snakeyaml.Yaml().<java.util.Map<String,Object>>load(rendered);
        var agent = (java.util.Map<?,?>) yaml.get("agent");
        var model = (java.util.Map<?,?>) agent.get("model");
        assertEquals(3, model.get("transport-retries"));
        assertEquals(600, model.get("max-read-timeout-seconds"));
        var budget = (java.util.Map<?,?>) agent.get("budget");
        assertEquals(192, budget.get("network-attempts"));
    }

    @Test void sourceExampleUsesTheSameKeysWithoutLiveConnections(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var file = java.nio.file.Files.copy(java.nio.file.Path.of("src/main/resources/application.example.yml"),
                directory.resolve("application.yml"));
        assertDoesNotThrow(() -> com.codereview.CodeReviewApplication.checkConfiguration(new String[] {
                "--check-config", "--spring.config.location=" + file.toUri(), "--spring.main.banner-mode=off" }));
    }

    @Test void preflightRunsBeforeSingletonConstruction() {
        new ApplicationContextRunner().withUserConfiguration(RuntimeConfigurationValidation.class)
                .withPropertyValues("agent.task.dispatch-concurrency=0")
                .withBean("mustNotStart", Object.class, () -> { fail("invalid config must stop startup first"); return new Object(); })
                .run(context -> assertNotNull(context.getStartupFailure()));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "", "   " })
    void rejectsBlankExpectedDatabaseBeforeSingletonConstruction(String expectedDatabase) {
        new ApplicationContextRunner().withUserConfiguration(RuntimeConfigurationValidation.class)
                .withPropertyValues("agent.datasource.expected-database=" + expectedDatabase)
                .withBean("mustNotConnect", Object.class, () -> {
                    fail("blank database identity must stop startup before constructing components");
                    return new Object();
                })
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(context.getStartupFailure().getMessage().contains("agent.datasource.expected-database"));
                });
    }
    @Test void checkOnlyEntryRejectsBlankExpectedDatabase(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var file = directory.resolve("application.yml");
        java.nio.file.Files.writeString(file, "agent:\n  datasource:\n    url: jdbc:mysql://invalid/not-connected\n    expected-database: ''\n");
        var failure = assertThrows(IllegalStateException.class,
                () -> com.codereview.CodeReviewApplication.checkConfiguration(new String[] {
                        "--check-config", "--spring.config.location=" + file.toUri(),
                        "--spring.main.banner-mode=off" }));
        assertTrue(failure.getMessage().contains("agent.datasource.expected-database"));
    }
    @Test void publicResponseIsAllowlistedAndNotCached() throws Exception {
        UiProperties properties = new UiProperties(); properties.setTitle("内网分析"); properties.setAgentPollMillis(4000);
        var response = new RuntimeConfigController(properties).get();
        var json = new ObjectMapper().valueToTree(response.getBody());
        assertEquals(9, json.size()); assertEquals("内网分析", json.path("title").asText());
        assertEquals(4000, json.path("agentPollMillis").asInt());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertFalse(json.has("datasource")); assertFalse(json.has("credential"));
    }
    @Test void schemaAndParserShareCustomLimits() {
        ToolLimitProperties limits = new ToolLimitProperties(); limits.setTreeDefaultLimit(5); limits.setTreeMaxLimit(6);
        var scope = new AgentToolScope("p", java.util.Map.of("V1", "a".repeat(40)), java.util.Map.of(), limits);
        var parser = new ToolArgumentParser(new ObjectMapper());
        assertEquals(5, parser.parse("tree", "{\"version\":\"V1\"}", scope).limit());
        assertThrows(ToolFailure.class, () -> parser.parse("tree", "{\"version\":\"V1\",\"limit\":7}", scope));
        var tree = new ObjectMapper().valueToTree(AgentToolSchema.definitions(limits)).get(0);
        assertEquals(6, tree.path("function").path("parameters").path("properties").path("limit").path("maximum").asInt());
        assertNotEquals(AgentToolSchema.version(new ToolLimitProperties()), AgentToolSchema.version(limits));
    }
}
