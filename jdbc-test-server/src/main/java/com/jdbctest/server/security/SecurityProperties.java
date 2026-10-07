package com.jdbctest.server.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "jdbc-test.security")
public record SecurityProperties(@NotBlank String masterKey) {
}
