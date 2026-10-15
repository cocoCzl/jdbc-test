package com.jdbctest.server.run;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jdbctest.server.connection.DatabaseConnectionProfileEntity;
import com.jdbctest.server.connection.DatabaseConnectionProfileRepository;
import com.jdbctest.server.driver.DriverSourceType;
import com.jdbctest.server.driver.JdbcDriverPackageEntity;
import com.jdbctest.server.driver.JdbcDriverPackageRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Historical raw evidence in isolated H2, exercised through the real report HTTP branch. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:legacy-evidence;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "jdbc-test.security.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class LegacyEvidenceReportTest {

  @TempDir
  static Path directory;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("jdbc-test.storage.data-directory", () -> directory.toString());
  }

  @Autowired WebApplicationContext context;
  @Autowired JdbcDriverPackageRepository drivers;
  @Autowired DatabaseConnectionProfileRepository connections;
  @Autowired TestRunRepository runs;
  @Autowired TestCaseResultRepository results;
  @Autowired TestRunSelectedScenarioRepository selected;
  @Autowired TestRunService service;
  @MockitoBean TestRunScheduler scheduler;
  MockMvc mvc;
  final ObjectMapper mapper = new ObjectMapper();
  JdbcDriverPackageEntity driver;
  DatabaseConnectionProfileEntity connection;

  @BeforeEach
  void setup() {
    mvc = MockMvcBuilders.webAppContextSetup(context).build();
    driver = drivers.save(new JdbcDriverPackageEntity("fixture", "fixture", "1", "fixture.Driver",
        DriverSourceType.UPLOAD));
    connection = new DatabaseConnectionProfileEntity("fixture", "postgresql", "postgresql",
        "jdbc:fixture:legacy", "fixture");
    connection.setDefaultDriver(driver);
    connection = connections.save(connection);
  }

  private TestRunEntity legacyRun(TestRunStatus status) {
    var run = new TestRunEntity(connection, driver, "CORE", "fixture", "postgresql", "postgresql",
        "jdbc:fixture:legacy", "fixture", "fixture", "1", "fixture.Driver", null);
    run.freezeExecutionInput("""
        {"scope":{"schema":"fixture"},"objectPrefix":"fixture_","dataSources":{},
         "adapterExecution":{},"capabilities":{"transactions":true}}
        """);
    run.setStatus(status);
    run.markFinished();
    return runs.save(run);
  }

  private TestCaseResultEntity result(TestRunEntity run, TestCaseResultStatus status, String raw) {
    run.setTotalCases(1);
    run.recordCase(status);
    runs.save(run);
    var selection = new TestRunSelectedScenarioEntity(run, "connection.fixture", "connection");
    selection.started();
    selection.finished(status.name());
    selected.save(selection);
    var row = new TestCaseResultEntity(run, "connection.fixture", "connection", "fixture", status);
    row.complete(42L, "password=ReviewOnlyLegacyHeadReviewOnlyLegacyTail", null);
    row.setExecutionEvidence(raw);
    return results.save(row);
  }

  private JsonNode report(UUID id) throws Exception {
    assertFalse(Files.exists(directory.resolve("runs").resolve(id.toString())
        .resolve("report/report.json")), "must exercise database reconstruction, not file fallback");
    return mapper.readTree(mvc.perform(get("/api/runs/{id}/report.json", id))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  }

  private static void assertSafe(String text) {
    assertFalse(text.contains("ReviewOnlyLegacyHead"), text);
    assertFalse(text.contains("ReviewOnlyLegacyTail"), text);
  }

  @Test
  void historicalV2ReportProjectsCompleteResponseAndLedgerWithoutChangingStoredEvidence()
      throws Exception {
    var run = legacyRun(TestRunStatus.FAILED);
    String raw = """
        {"type":"CASE_FINISHED","timestamp":"historical","runId":"obsolete",
         "scenarioId":"connection.fixture","status":"FAILED","durationMs":42,
         "message":"password=ReviewOnlyLegacyHeadReviewOnlyLegacyTail",
         "causes":[{"token":"ReviewOnlyLegacyHeadReviewOnlyLegacyTail","optional":null}],
         "passwordRequired":true}
        """;
    var row = result(run, TestCaseResultStatus.FAILED, raw);
    Path workspace = directory.resolve("runs").resolve(run.getId().toString());
    Files.createDirectories(workspace);
    Files.writeString(workspace.resolve("object-ledger.yaml"), """
        objects:
          - message: password=ReviewOnlyLegacyHeadReviewOnlyLegacyTail
            trustStorePassword: ReviewOnlyLegacyHeadReviewOnlyLegacyTail
            attempts: 2
            owned: true
        """);
    var json = report(run.getId());
    assertSafe(json.toString());
    assertEquals("PERSISTED_WORKER_EVENTS", json.path("source").asString());
    assertEquals(run.getId().toString(), json.path("runId").asString());
    var event = json.path("scenario_results").path("connection.fixture");
    assertEquals("FAILED", event.path("status").asString());
    assertEquals(42, event.path("durationMs").asInt());
    assertTrue(event.path("passwordRequired").asBoolean());
    assertTrue(event.path("causes").get(0).path("optional").isNull());
    assertFalse(event.has("type"));
    assertFalse(event.has("timestamp"));
    assertFalse(event.has("runId"));
    var object = json.path("objectLedger").path("objects").get(0);
    assertEquals(2, object.path("attempts").asInt());
    assertTrue(object.path("owned").asBoolean());
    assertSafe(mapper.writeValueAsString(service.evidenceReport(run.getId())));
    for (String suffix : new String[] {"/results", "/report", ""}) {
      String body = mvc.perform(get("/api/runs/" + run.getId() + suffix))
          .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
      assertSafe(body);
    }
    var list = mapper.readTree(mvc.perform(get("/api/runs/{id}/results", run.getId()))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    var listed = (tools.jackson.databind.node.ObjectNode) list.path("items").get(0)
        .path("executionEvidence").deepCopy();
    listed.remove("type");
    listed.remove("timestamp");
    listed.remove("runId");
    assertEquals(event, listed);
    assertEquals(raw, results.findById(row.getId()).orElseThrow().getExecutionEvidence());
    assertTrue(Files.readString(workspace.resolve("object-ledger.yaml")).contains("ReviewOnlyLegacyHead"));
  }
}
