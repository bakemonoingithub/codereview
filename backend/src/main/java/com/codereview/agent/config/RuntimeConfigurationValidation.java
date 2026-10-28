package com.codereview.agent.config;

import com.codereview.agent.db.AgentDataSourceProperties;
import com.codereview.agent.execution.*;
import com.codereview.agent.invocation.ModelInvocationProperties;
import com.codereview.agent.report.ReportGenerationProperties;
import com.codereview.agent.repository.RepositoryProperties;
import com.codereview.agent.specialist.SpecialistProperties;
import com.codereview.agent.tools.*;
import com.codereview.git.*;
import lombok.Data;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Validate before constructing database, HTTP clients or dispatchers. Never report secret values. */
@Configuration
public class RuntimeConfigurationValidation {
    @Bean
    static BeanFactoryPostProcessor runtimeConfigurationPreflight(Environment environment) {
        return factory -> validate(environment);
    }

    public static void validate(Environment environment) {
        // SpringApplication removes this before ConfigData; also cover direct context/validation callers.
        FileConfigurationEnvironmentPostProcessor.removeSystemEnvironment(environment);
        int port;
        try { port = environment.getProperty("server.port", Integer.class, 8080); }
        catch (RuntimeException e) { throw new IllegalStateException("配置无效：server.port，必须为整数"); }
        check(port >= 0 && port <= 65535, "server.port", "范围为 0–65535；0 仅用于动态端口检查");
        Binder binder = Binder.get(environment);
        Agent agent = bind(binder, "agent", Agent.class);
        Git git = bind(binder, "git", Git.class);
        bind(binder, "git.http", GitHttpProperties.class);
        bind(binder, "git.github", GitHubHostProperties.class);
        bind(binder, "git.gitea", GiteaProperties.class);
        bind(binder, "git.cache", Cache.class);
        bind(binder, "github", GitHubProperties.class);
        UiProperties ui = bind(binder, "app.ui", UiProperties.class);
        positiveFields(agent, "agent"); positiveFields(git, "git"); positiveFields(ui, "app.ui");
        check(!agent.datasource.getExpectedDatabase().isBlank(), "agent.datasource.expected-database", "不能为空白；必须指定期望的数据库名称");
        check(agent.datasource.getMinimumIdle() <= agent.datasource.getMaximumPoolSize(), "agent.datasource.minimum-idle", "不得超过 maximum-pool-size");
        check(agent.datasource.getConnectionTimeoutMillis() >= 250, "agent.datasource.connection-timeout-millis", "至少 250 毫秒");
        check(agent.model.getTransportRetries() <= 10, "agent.model.transport-retries", "范围为 0–10");
        check(agent.model.getRetryBaseMillis() <= agent.model.getRetryMaxMillis(),
                "agent.model.retry-base-millis", "不得超过 retry-max-millis");
        check(agent.model.getRetryMultiplier() >= 1 && agent.model.getRetryMultiplier() <= 10,
                "agent.model.retry-multiplier", "范围为 1–10");
        check(agent.model.getRetryJitterRatio() >= 0 && agent.model.getRetryJitterRatio() <= 1,
                "agent.model.retry-jitter-ratio", "范围为 0–1");
        check(agent.model.getPerTaskConcurrency() <= agent.model.getGlobalConcurrency(), "agent.model.per-task-concurrency", "不得超过全局并发");
        check(agent.budget.getNetworkAttempts() <= agent.model.getTaskCallLimit(), "agent.budget.network-attempts", "不得超过 agent.model.task-call-limit");
        validateBudget(agent.budget);
        check(agent.model.getSafetyReserveRatio() > 0 && agent.model.getSafetyReserveRatio() < 1, "agent.model.safety-reserve-ratio", "范围为 (0,1)");
        validateTools(agent.tools.getLimits());
        check(!ui.getTitle().isBlank() && ui.getTitle().length() <= 100, "app.ui.title", "长度为 1–100");
        check(ui.getModelValidationTimeoutMillis() > agent.model.getConnectivityValidationTimeoutSeconds() * 1000L,
                "app.ui.model-validation-timeout-millis", "须大于连通验证总期限，并预留请求和结果保存时间");
        check(ui.getAgentValidationTimeoutMillis() > agent.model.getToolValidationTimeoutSeconds() * 2000L,
                "app.ui.agent-validation-timeout-millis", "须大于两轮工具验证总期限，并预留请求和结果保存时间");
        check(ui.getApiBase().matches("(?:/)?[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*"), "app.ui.api-base", "必须为同源路径，不能含主机、查询参数或相对跳转");
        check(!agent.repository.getAllowedExtensions().isEmpty() && agent.repository.getAllowedExtensions().stream().allMatch(x -> x.matches("[a-z0-9]+")), "agent.repository.allowed-extensions", "必须为非空小写后缀集合，不含点");
        check(!agent.tools.getGitExecutable().isBlank(), "agent.tools.git-executable", "不能为空");
        check(git.github.getHosts().stream().allMatch(RuntimeConfigurationValidation::validHost), "git.github.hosts", "必须为 host 或 host:port");
        check(git.gitea.getHosts().stream().allMatch(RuntimeConfigurationValidation::validHost), "git.gitea.hosts", "必须为 host 或 host:port");
        check(git.github.getHosts().stream().noneMatch(github -> git.gitea.getHosts().stream().anyMatch(github::equalsIgnoreCase)),
                "git.github.hosts", "不得与 git.gitea.hosts 重复");
        check(httpUrl(git.apiBase) && httpUrl(git.rawBase), "git.api-base / git.raw-base", "必须为有效 HTTP(S) 地址，不能包含凭据、查询参数或片段");
        check(git.gitea.getApiBase().isBlank() || httpUrl(git.gitea.getApiBase()), "git.gitea.api-base", "为空或有效 HTTP(S) 地址");
        check(git.http.getConnectTimeoutSeconds() <= Integer.MAX_VALUE / 1000
                        && git.http.getReadTimeoutSeconds() <= Integer.MAX_VALUE / 1000,
                "git.http", "超时转换为毫秒后不得超过整数范围");
        org.slf4j.LoggerFactory.getLogger(RuntimeConfigurationValidation.class).info(
                "运行配置校验通过：模型并发={}/{}, Agent预算={}/{}/{}, 工具={}, 文件上限={}字节, 前端轮询={}/{}/{}毫秒",
                agent.model.getGlobalConcurrency(), agent.model.getPerTaskConcurrency(),
                agent.budget.getModelTurns(), agent.budget.getNetworkAttempts(), agent.budget.getToolAttempts(),
                AgentToolSchema.version(agent.tools.getLimits()), agent.repository.getMaxFileBytes(),
                ui.getAgentPollMillis(), ui.getReportPollMillis(), ui.getSpecialistPollMillis());
    }

    public static void validateBudget(AgentBudgetProperties budget) {
        positiveFields(budget, "agent.budget");
        check(budget.getMaxProtocolErrors() <= 2, "agent.budget.max-protocol-errors", "范围为 0–2");
        check(budget.getContextStopRatio() > 0 && budget.getContextStopRatio() <= 1,
                "agent.budget.context-stop-ratio", "范围为 (0,1]");
        check(budget.getNoProgressTurns() >= 2, "agent.budget.no-progress-turns", "至少 2 轮才能比较重复调用");
    }

    public static void validateTools(ToolLimitProperties p) {
        positiveFields(p, "agent.tools.limits");
        check(p.getEnvelopeMaxBytes() >= 4096 && p.getEnvelopeMaxBytes() <= 1048576, "agent.tools.limits.envelope-max-bytes", "范围为 4096–1048576");
        check(p.getMaterialMaxBytes() >= 256 && p.getMaterialMaxBytes() <= p.getEnvelopeMaxBytes() - 2048, "agent.tools.limits.material-max-bytes", "至少 256，并为 envelope 留出 2048 字节");
        check(p.getTreeDefaultDepth() <= p.getTreeMaxDepth(), "agent.tools.limits.tree-default-depth", "不得超过最大值");
        check(p.getTreeDefaultLimit() <= p.getTreeMaxLimit(), "agent.tools.limits.tree-default-limit", "不得超过最大值");
        check(p.getFindDefaultLimit() <= p.getFindMaxLimit(), "agent.tools.limits.find-default-limit", "不得超过最大值");
        check(p.getSearchDefaultLimit() <= p.getSearchMaxLimit(), "agent.tools.limits.search-default-limit", "不得超过最大值");
        check(p.getReadDefaultLines() <= p.getReadMaxLines(), "agent.tools.limits.read-default-lines", "不得超过最大值");
        check(p.getReadMaxLines() <= 1000000, "agent.tools.limits.read-max-lines", "不得超过 1000000");
    }

    private static <T> T bind(Binder binder, String prefix, Class<T> type) {
        try {
            return binder.bind(prefix, Bindable.of(type), new NoUnboundElementsBindHandler(BindHandler.DEFAULT))
                    .orElseGet(() -> { try { return type.getDeclaredConstructor().newInstance(); } catch (Exception e) { throw new IllegalStateException(e); } });
        } catch (Exception e) {
            String key = e instanceof BindException b ? b.getName().toString() : prefix;
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof UnboundConfigurationPropertiesException unbound) {
                    key = unbound.getUnboundProperties().stream().map(property -> property.getName().toString())
                            .sorted().collect(java.util.stream.Collectors.joining(", "));
                    break;
                }
            }
            throw new IllegalStateException("配置无效：" + key + "（未知键、类型错误或无法绑定；请检查该配置组）");
        }
    }

    private static void positiveFields(Object object, String prefix) {
        for (var field : object.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            String key = prefix + "." + field.getName().replaceAll("([A-Z])", "-$1").toLowerCase(java.util.Locale.ROOT);
            try {
                Object value = field.get(object);
                check(value != null || key.matches("agent.datasource.(url|username|password)"), key, "不能为空");
                if (value instanceof Number n) {
                    boolean zero = key.endsWith("transport-retries") || key.endsWith("retry-jitter-ratio") || key.endsWith("max-protocol-errors") || key.endsWith("minimum-idle") || key.endsWith("search-context-lines");
                    check(Double.isFinite(n.doubleValue()) && (zero ? n.doubleValue() >= 0 : n.doubleValue() > 0), key, zero ? "必须非负" : "必须为正数");
                } else if (value != null && value.getClass().getName().startsWith("com.codereview")) positiveFields(value, key);
            } catch (IllegalAccessException e) { throw new IllegalStateException("配置校验失败：" + key); }
        }
    }
    private static void check(boolean valid, String key, String reason) {
        if (!valid) throw new IllegalStateException("配置无效：" + key + "，" + reason);
    }

    private static boolean validHost(String value) {
        return value != null && value.matches("[a-zA-Z0-9.-]+(?::[0-9]+)?");
    }

    private static boolean httpUrl(String value) {
        try {
            var uri = java.net.URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException e) { return false; }
    }

    @Data public static class Agent {
        private AgentDataSourceProperties datasource = new AgentDataSourceProperties();
        private AgentBudgetProperties budget = new AgentBudgetProperties();
        private ModelInvocationProperties model = new ModelInvocationProperties();
        private TaskProperties task = new TaskProperties();
        private ToolProperties tools = new ToolProperties();
        private RepositoryProperties repository = new RepositoryProperties();
        private SpecialistProperties specialist = new SpecialistProperties();
        private ReportGenerationProperties report = new ReportGenerationProperties();
    }
    @Data public static class Git {
        private String apiBase = "https://api.github.com";
        private String rawBase = "https://raw.githubusercontent.com";
        private GiteaProperties gitea = new GiteaProperties();
        private GitHttpProperties http = new GitHttpProperties();
        private GitHubHostProperties github = new GitHubHostProperties();
        private Cache cache = new Cache();
    }
    @Data public static class Cache {
        private long commitDetailMaxSize = 200;
        private long rawFileMaxSize = 500;
        private long expireMinutes = 30;
    }
}
