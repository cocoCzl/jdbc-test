package com.jdbctest.server.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explicit Flyway wiring for Spring Boot 4, where Flyway auto-configuration is not supplied by the
 * starter set used by this application.
 */
@Configuration
public class FlywayConfiguration {

    @Bean(initMethod = "migrate")
    Flyway flyway(DataSource dataSource,
        @Value("${spring.flyway.connect-retries:0}") int connectRetries) {
        return Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .connectRetries(connectRetries)
            .load();
    }

    @Bean
    static BeanFactoryPostProcessor hibernateDependsOnFlyway() {
        return beanFactory -> {
            if (beanFactory.containsBeanDefinition("entityManagerFactory")) {
                BeanDefinition definition = beanFactory.getBeanDefinition("entityManagerFactory");
                definition.setDependsOn(append(definition.getDependsOn(), "flyway"));
            }
        };
    }

    private static String[] append(String[] existing, String dependency) {
        if (existing == null || existing.length == 0) {
            return new String[] {dependency};
        }
        String[] result = new String[existing.length + 1];
        System.arraycopy(existing, 0, result, 0, existing.length);
        result[existing.length] = dependency;
        return result;
    }
}
