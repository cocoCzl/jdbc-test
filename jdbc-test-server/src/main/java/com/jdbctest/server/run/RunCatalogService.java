package com.jdbctest.server.run;

import com.jdbctest.server.driver.DriverWorkerProperties;
import com.jdbctest.server.driver.WorkerProcessRunner;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Caches metadata described by the Worker; Server never loads suite classes.
 */
@Service
final class RunCatalogService {

    private final WorkerProcessRunner worker;
    private final DriverWorkerProperties properties;
    private final ObjectMapper mapper;
    private volatile Catalog catalog;

    RunCatalogService(WorkerProcessRunner worker, DriverWorkerProperties properties,
        ObjectMapper mapper) {
        this.worker = worker;
        this.properties = properties;
        this.mapper = mapper;
    }

    Catalog catalog() {
        Catalog value = catalog;
        if (value != null) {
            return value;
        }
        synchronized (this) {
            if (catalog == null) {
                catalog = load();
            }
            return catalog;
        }
    }

    private Catalog load() {
        try {
            var response = worker.run("--describe-catalog", Map.of(), Duration.ofSeconds(30));
            if (response.timedOut() || response.exitCode() != 0) {
                throw unavailable();
            }
            JsonNode event = response.lines().stream().map(this::read)
                .filter(node -> node != null && "CATALOG_DESCRIBED".equals(
                    node.path("type").asString()))
                .findFirst().orElseThrow(this::unavailable);
            if (event.path("protocolVersion").asInt(0) != 2) {
                throw new RunException(HttpStatus.SERVICE_UNAVAILABLE,
                    "WORKER_PROTOCOL_MISMATCH", "Worker must support execution protocol v2");
            }
            List<Profile> profiles = new ArrayList<>();
            for (JsonNode node : event.path("profiles")) {
                profiles.add(new Profile(node.path("id").asString(), node.path("name").asString(),
                    node.path("scenarioCount").asInt()));
            }
            List<Module> modules = new ArrayList<>();
            for (JsonNode node : event.path("modules")) {
                modules.add(new Module(node.path("id").asString(), node.path("name").asString(),
                    node.path("description").asString(null), node.path("scenarioCount").asInt(),
                    node.path("coreScenarioCount").asInt()));
            }
            List<Scenario> scenarios = new ArrayList<>();
            for (JsonNode node : event.path("scenarios")) {
                scenarios.add(
                    new Scenario(node.path("id").asString(), node.path("moduleId").asString(),
                        node.path("displayName").asString(), node.path("profile").asString(),
                        node.path("category").asString("STANDARD_JDBC"),
                        strings(node.path("applicableAdapters")),
                        node.path("jdbcVersion").asString("UNKNOWN"),
                        strings(node.path("executionPrerequisites")),
                        strings(node.path("requiredCapabilities")),
                        node.path("sourceClass").asString("fixture.Unknown"),
                        node.path("sourceMethod").asString("unknown"),
                        node.path("unsupportedPolicy").asString("REQUIRED")));
            }
            return new Catalog(List.copyOf(profiles), List.copyOf(modules), List.copyOf(scenarios),
                event.path("totalScenarioCount").asInt(), event.path("catalogVersion").asString("legacy"));
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw unavailable();
        }
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(item.asString()));
        return List.copyOf(values);
    }

    private JsonNode read(String line) {
        try {
            return mapper.readTree(line);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private RunException unavailable() {
        return new RunException(HttpStatus.SERVICE_UNAVAILABLE, "WORKER_UNAVAILABLE",
            "Test catalog worker is unavailable");
    }

    record Catalog(List<Profile> profiles, List<Module> modules, List<Scenario> scenarios,
        int totalScenarioCount, String catalogVersion) {

        Catalog(List<Profile> profiles, List<Module> modules, List<Scenario> scenarios,
            int totalScenarioCount) {
            this(profiles, modules, scenarios, totalScenarioCount, "legacy");
        }
    }

    record Profile(String id, String name, int scenarioCount) {

    }

    record Module(String id, String name, String description, int scenarioCount,
        int coreScenarioCount) {

    }

    record Scenario(String id, String moduleId, String displayName, String profile,
        String category, List<String> applicableAdapters, String jdbcVersion,
        List<String> executionPrerequisites, List<String> requiredCapabilities,
        String sourceClass, String sourceMethod, String unsupportedPolicy) {

        Scenario(String id, String moduleId, String displayName, String profile) {
            this(id, moduleId, displayName, profile, "STANDARD_JDBC", List.of(), "UNKNOWN",
                List.of(), List.of(), "fixture.Unknown", "unknown", "REQUIRED");
        }

        com.jdbctest.catalog.TestCaseDescriptor descriptor(Module module) {
            return new com.jdbctest.catalog.TestCaseDescriptor(id,
                new com.jdbctest.catalog.TestModule(module.id(), module.name(),
                    module.description() == null ? module.name() : module.description()),
                com.jdbctest.catalog.TestProfile.valueOf(profile), displayName, sourceClass,
                sourceMethod, requiredCapabilities, com.jdbctest.catalog.TestCategory.valueOf(category),
                applicableAdapters, jdbcVersion, executionPrerequisites, unsupportedPolicy);
        }

        com.jdbctest.catalog.ScenarioSelection.Entry selectionEntry() {
            return new com.jdbctest.catalog.ScenarioSelection.Entry(id, moduleId, profile,
                com.jdbctest.catalog.TestCategory.valueOf(category), applicableAdapters);
        }

    }
}
