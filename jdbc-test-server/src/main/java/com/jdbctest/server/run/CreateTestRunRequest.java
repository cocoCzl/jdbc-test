package com.jdbctest.server.run;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record CreateTestRunRequest(@NotNull UUID connectionId, UUID driverId, @NotNull String profile,
                                   List<String> modules, List<String> includeScenarios, List<String> excludeScenarios,
                                   String password, boolean destructiveConsent) { }
