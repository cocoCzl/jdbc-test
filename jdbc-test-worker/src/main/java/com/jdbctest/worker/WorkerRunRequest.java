package com.jdbctest.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record WorkerRunRequest(
    Integer protocolVersion,
    String runId,
    DriverRequest driver,
    ConnectionRequest connection,
    SelectionRequest selection,
    String workspace,
    Long timeoutMs,
    com.jdbctest.catalog.ExecutionScope scope,
    String objectPrefix,
    Map<String, Boolean> capabilities,
    Boolean destructiveConsent,
    com.jdbctest.catalog.AdapterExecutionConfig adapterExecution,
    String catalogVersion,
    Long caseTimeoutMs,
    List<com.jdbctest.catalog.TestCaseDescriptor> frozenScenarios
) {

    public WorkerRunRequest(Integer protocolVersion, String runId, DriverRequest driver,
        ConnectionRequest connection, SelectionRequest selection, String workspace, Long timeoutMs,
        com.jdbctest.catalog.ExecutionScope scope, String objectPrefix,
        Map<String, Boolean> capabilities, Boolean destructiveConsent,
        com.jdbctest.catalog.AdapterExecutionConfig adapterExecution) {
        this(protocolVersion, runId, driver, connection, selection, workspace, timeoutMs, scope,
            objectPrefix, capabilities, destructiveConsent, adapterExecution, null, null, null);
    }

    public WorkerRunRequest(Integer protocolVersion, String runId, DriverRequest driver,
        ConnectionRequest connection, SelectionRequest selection, String workspace, Long timeoutMs,
        com.jdbctest.catalog.ExecutionScope scope, String objectPrefix,
        Map<String, Boolean> capabilities, Boolean destructiveConsent) {
        this(protocolVersion, runId, driver, connection, selection, workspace, timeoutMs, scope,
            objectPrefix, capabilities, destructiveConsent, null);
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DriverRequest(String driverClass, List<String> artifacts,
        com.jdbctest.catalog.DataSourceProviders dataSources) {

        public DriverRequest(String driverClass, List<String> artifacts) {
            this(driverClass, artifacts, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ConnectionRequest(
        String jdbcUrl,
        String username,
        String password,
        Map<String, String> properties,
        String adapterId
    ) {

    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SelectionRequest(
        String profile,
        List<String> modules,
        List<String> includeScenarios,
        List<String> excludeScenarios
    ) {

    }
}
