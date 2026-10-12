package com.jdbctest.server.security;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Keep datasource passwords literal while using Spring's normal config precedence. */
public final class LiteralPasswordEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
    Object key = environment.getSystemEnvironment().get("JDBC_TEST_MASTER_KEY");
    if (key instanceof String) {
      environment.getPropertySources().addFirst(new MapPropertySource(
          "JDBC_TEST_MASTER_KEY environment", Map.of("jdbc-test.security.master-key", key)));
    }
    for (var source : environment.getPropertySources()) {
      // Boot's aggregate resolves placeholders already; inspect the underlying source.
      if (source.getName().equals("configurationProperties")) {
        continue;
      }
      Object value = source.getProperty("spring.datasource.password");
      if (value instanceof String password) {
        // The classpath default delegates to the environment rather than defining a secret.
        if (source.getName().contains("class path resource [application.yml]")) {
          return;
        }
        environment.getPropertySources().addFirst(new MapPropertySource(
            "literalDatasourcePassword (" + source.getName() + ")",
            Map.of("spring.datasource.password", password.replace("${", "\\${"))));
        return;
      }
    }
  }
}
