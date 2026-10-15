package com.jdbctest.server.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DriverValidationProcessTest {

  @Test
  void companionWarningsAreForwardedAndOlderWorkerResponsesRemainCompatible() throws Exception {
    WorkerProcessRunner worker = mock(WorkerProcessRunner.class);
    var process = new DriverValidationProcess(new DriverWorkerProperties(), worker,
        new ObjectMapper());
    for (String warnings : List.of("", ",\"warnings\":[\"ORACLE_I18N_MISSING\",\"ORACLE_XML_MISSING\"]")) {
      when(worker.run(eq("--validate-driver"), any(), any())).thenReturn(
          new WorkerProcessRunner.Result(0, false, """
              {"type":"DRIVER_VALIDATED","driverClass":"oracle.jdbc.OracleDriver",
               "majorVersion":21,"minorVersion":13,"jdbcCompliant":true%s}
              """.formatted(warnings).replace("\n", ""), ""));
      var response = process.validate("oracle.jdbc.OracleDriver", List.of());
      assertEquals(DriverPackageStatus.VALIDATED, response.status());
      assertEquals(warnings.isEmpty() ? List.of()
          : List.of("ORACLE_I18N_MISSING", "ORACLE_XML_MISSING"), response.warnings());
    }
  }

  @Test
  void failedOrTimedOutWorkerCannotBecomeSuccessfulValidation() throws Exception {
    WorkerProcessRunner worker = mock(WorkerProcessRunner.class);
    var process = new DriverValidationProcess(new DriverWorkerProperties(), worker,
        new ObjectMapper());
    for (var result : List.of(
        new WorkerProcessRunner.Result(3, false, "{\"type\":\"DRIVER_VALIDATED\"}", ""),
        new WorkerProcessRunner.Result(0, true, "{\"type\":\"DRIVER_VALIDATED\"}", ""),
        new WorkerProcessRunner.Result(0, false, "{\"type\":\"DRIVER_VALIDATION_FAILED\"}", ""))) {
      when(worker.run(eq("--validate-driver"), any(), any())).thenReturn(result);
      assertThrows(DriverException.class,
          () -> process.validate("oracle.jdbc.OracleDriver", List.of()));
    }
  }
}
