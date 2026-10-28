package com.codereview.agent.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** Keep process environment variables out of Spring configuration, including ConfigData and JSON. */
public class FileConfigurationEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        removeSystemEnvironment(environment);
    }

    static void removeSystemEnvironment(Environment environment) {
        if (!(environment instanceof ConfigurableEnvironment configurable)) return;
        var sources = configurable.getPropertySources();
        // Also cover custom SystemEnvironmentPropertySource names used by embedding hosts and tests.
        var names = sources.stream()
                .filter(source -> source instanceof SystemEnvironmentPropertySource
                        || source.getName().equals(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
                .map(org.springframework.core.env.PropertySource::getName).toList();
        names.forEach(sources::remove);
    }
}
