package com.jdbctest.server.driver;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
final class DriverValidationProcess {

    private final DriverWorkerProperties properties;
    private final WorkerProcessRunner worker;
    private final ObjectMapper mapper;

    DriverValidationProcess(DriverWorkerProperties properties, WorkerProcessRunner worker,
        ObjectMapper mapper) {
        this.properties = properties;
        this.worker = worker;
        this.mapper = mapper;
    }

    DriverValidationResponse validate(String driverClass, List<Path> artifacts) {
        try {
            WorkerProcessRunner.Result result = worker.run("--validate-driver",
                Map.of("driverClass", driverClass,
                    "artifacts", artifacts.stream().map(Path::toString).toList()),
                properties.getDriverValidationTimeout());
            if (result.timedOut()) {
                throw new DriverException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "DRIVER_VALIDATION_FAILED",
                    "Driver validation timed out");
            }
            var events = result.lines().stream().map(this::read).filter(node -> node != null)
                .filter(node -> "DRIVER_VALIDATED".equals(node.path("type").asString())).toList();
            if (result.exitCode() != 0 || events.isEmpty()) {
                throw new DriverException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "DRIVER_VALIDATION_FAILED",
                    "Driver validation failed");
            }
            if (events.size() != 1) {
                throw invalidMetadata("event count");
            }
            JsonNode event = events.getFirst();
            if (!event.path("driverClass").isString()
                || !driverClass.equals(event.path("driverClass").asString())) {
                throw invalidMetadata("driverClass");
            }
            int major = version(event, "majorVersion");
            int minor = version(event, "minorVersion");
            if (!event.path("jdbcCompliant").isBoolean()) {
                throw invalidMetadata("jdbcCompliant");
            }
            List<String> warnings = new ArrayList<>();
            if (event.has("warnings") && !event.path("warnings").isArray()) {
                throw invalidMetadata("warnings");
            }
            for (JsonNode warning : event.path("warnings")) {
                if (!warning.isString()) {
                    throw invalidMetadata("warnings");
                }
                warnings.add(warning.asString());
            }
            return new DriverValidationResponse(DriverPackageStatus.VALIDATED,
                driverClass, major, minor, event.path("jdbcCompliant").asBoolean(), warnings,
                loadedArtifact(event.path("loadedArtifact")));
        } catch (IOException exception) {
            throw new DriverException(HttpStatus.SERVICE_UNAVAILABLE, "WORKER_UNAVAILABLE",
                "Driver validation worker is unavailable");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DriverException(HttpStatus.UNPROCESSABLE_CONTENT, "DRIVER_VALIDATION_FAILED",
                "Driver validation interrupted");
        }
    }

    private int version(JsonNode event, String key) {
        JsonNode value = event.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 0) {
            throw invalidMetadata(key);
        }
        return value.asInt();
    }

    private DriverValidationResponse.LoadedArtifact loadedArtifact(JsonNode value) {
        // Older Workers have no defining-JAR evidence; absence remains explicitly unknown.
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isObject() || !value.path("fileName").isString()
            || value.path("fileName").asString().isBlank()
            || !value.path("sha256").isString()
            || !value.path("sha256").asString().matches("[a-f0-9]{64}")) {
            throw invalidMetadata("loadedArtifact");
        }
        return new DriverValidationResponse.LoadedArtifact(value.path("fileName").asString(),
            value.path("sha256").asString(), optionalText(value, "implementationVersion"),
            optionalText(value, "bundleVersion"));
    }

    private String optionalText(JsonNode value, String key) {
        JsonNode field = value.path(key);
        if (field.isMissingNode() || field.isNull()) {
            return null;
        }
        if (!field.isString() || field.asString().isBlank()) {
            throw invalidMetadata("loadedArtifact." + key);
        }
        return field.asString();
    }

    private DriverException invalidMetadata(String field) {
        return new DriverException(HttpStatus.UNPROCESSABLE_CONTENT,
            "DRIVER_VALIDATION_PROTOCOL_ERROR", "Invalid driver validation field: " + field);
    }

    private JsonNode read(String line) {
        try {
            return mapper.readTree(line);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
