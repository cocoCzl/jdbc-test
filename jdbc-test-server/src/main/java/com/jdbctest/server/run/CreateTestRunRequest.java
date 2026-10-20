package com.jdbctest.server.run;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record CreateTestRunRequest(@NotNull UUID connectionId, UUID driverId,
    @NotNull String profile,
    List<String> modules, List<String> includeScenarios,
    List<String> excludeScenarios,
    String password, boolean destructiveConsent,
    @jakarta.validation.constraints.Min(60) @jakarta.validation.constraints.Max(7200)
    Integer budgetSeconds,
    String catalogVersion, String expectedAdapterFingerprint, String sqlPackageFingerprint) {

    public CreateTestRunRequest(UUID connectionId, UUID driverId, String profile,
        List<String> modules, List<String> includeScenarios, List<String> excludeScenarios,
        String password, boolean destructiveConsent, Integer budgetSeconds, String catalogVersion,
        String expectedAdapterFingerprint) {
        this(connectionId, driverId, profile, modules, includeScenarios, excludeScenarios,
            password, destructiveConsent, budgetSeconds, catalogVersion, expectedAdapterFingerprint, null);
    }

    public CreateTestRunRequest(UUID connectionId, UUID driverId, String profile,
        List<String> modules, List<String> includeScenarios, List<String> excludeScenarios,
        String password, boolean destructiveConsent, Integer budgetSeconds, String catalogVersion) {
        this(connectionId, driverId, profile, modules, includeScenarios, excludeScenarios,
            password, destructiveConsent, budgetSeconds, catalogVersion, null);
    }

    public CreateTestRunRequest(UUID connectionId, UUID driverId, String profile,
        List<String> modules, List<String> includeScenarios, List<String> excludeScenarios,
        String password, boolean destructiveConsent) {
        this(connectionId, driverId, profile, modules, includeScenarios, excludeScenarios,
            password, destructiveConsent, null, null);
    }

}
