package com.jdbctest.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class LiteralPasswordEnvironmentPostProcessorTest {

    @Test
    void passwordsRemainLiteralThroughSpringBindingAndEnvironmentWins() {
        String password = "  $ # ' \" \\ = ${NAME} \\${NAME} 中文é🔑  ";
        var environment = new StandardEnvironment() {
            @Override
            public Map<String, Object> getSystemEnvironment() {
                return Map.of("SPRING_DATASOURCE_PASSWORD", password, "JDBC_TEST_MASTER_KEY",
                    "environment-key");
            }
        };
        environment.getPropertySources().replace("systemEnvironment",
            new SystemEnvironmentPropertySource("systemEnvironment",
                environment.getSystemEnvironment()));
        environment.getPropertySources().addLast(new MapPropertySource("application.properties",
            Map.of("spring.datasource.password", "file-password", "jdbc-test.security.master-key",
                "", "NAME", "must-not-expand")));
        new LiteralPasswordEnvironmentPostProcessor().postProcessEnvironment(environment,
            new SpringApplication());
        assertEquals(password,
            Binder.get(environment).bind("spring.datasource.password", String.class).get());
        assertEquals(password, environment.getProperty("spring.datasource.password"));
        assertEquals("environment-key",
            Binder.get(environment).bind("jdbc-test.security.master-key", String.class).get());
    }
}
