package com.jdbctest.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record WorkerRunRequest(
        String runId,
        DriverRequest driver,
        ConnectionRequest connection,
        SelectionRequest selection,
        String workspace,
        Long timeoutMs
) {
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DriverRequest(String driverClass, List<String> artifacts) { }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ConnectionRequest(
            String jdbcUrl,
            String username,
            String password,
            Map<String, String> properties,
            String adapterId
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SelectionRequest(
            String profile,
            List<String> modules,
            List<String> includeScenarios,
            List<String> excludeScenarios
    ) { }
}
