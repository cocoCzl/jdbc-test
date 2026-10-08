package com.jdbctest.server.driver;

import java.io.IOException;
import java.nio.file.Path;
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
        throw new DriverException(HttpStatus.UNPROCESSABLE_CONTENT, "DRIVER_VALIDATION_FAILED",
            "Driver validation timed out");
      }
      JsonNode event = result.lines().stream().map(this::read).filter(node -> node != null)
          .filter(node -> "DRIVER_VALIDATED".equals(node.path("type").asString())).findFirst()
          .orElse(null);
      if (result.exitCode() != 0 || event == null) {
        throw new DriverException(HttpStatus.UNPROCESSABLE_CONTENT, "DRIVER_VALIDATION_FAILED",
            "Driver validation failed");
      }
      return new DriverValidationResponse(DriverPackageStatus.VALIDATED,
          event.path("driverClass").asString(),
          event.path("majorVersion").asInt(), event.path("minorVersion").asInt(),
          event.path("jdbcCompliant").asBoolean());
    } catch (IOException exception) {
      throw new DriverException(HttpStatus.SERVICE_UNAVAILABLE, "WORKER_UNAVAILABLE",
          "Driver validation worker is unavailable");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new DriverException(HttpStatus.UNPROCESSABLE_CONTENT, "DRIVER_VALIDATION_FAILED",
          "Driver validation interrupted");
    }
  }

  private JsonNode read(String line) {
    try {
      return mapper.readTree(line);
    } catch (RuntimeException ignored) {
      return null;
    }
  }
}
