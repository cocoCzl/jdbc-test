package com.jdbctest.server.run;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jdbctest.server.connection.DatabaseConnectionProfileEntity;
import com.jdbctest.server.connection.DatabaseConnectionProfileRepository;
import com.jdbctest.server.driver.DriverSourceType;
import com.jdbctest.server.driver.DriverStorage;
import com.jdbctest.server.driver.JdbcDriverArtifactRepository;
import com.jdbctest.server.driver.JdbcDriverPackageEntity;
import com.jdbctest.server.driver.JdbcDriverPackageRepository;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RunPostgresIT {

  @Container
  static final PostgreSQLContainer<?> PLATFORM = new PostgreSQLContainer<>(
      "postgres:16-alpine").withPassword("platform-run-secret");
  @Container
  static final PostgreSQLContainer<?> TARGET = new PostgreSQLContainer<>(
      "postgres:16-alpine").withPassword("target-run-secret");
  static final Path DATA = dataDirectory();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", PLATFORM::getJdbcUrl);
    registry.add("spring.datasource.username", PLATFORM::getUsername);
    registry.add("spring.datasource.password", PLATFORM::getPassword);
    registry.add("jdbc-test.security.master-key",
        () -> Base64.getEncoder().encodeToString(new byte[32]));
    registry.add("jdbc-test.storage.data-directory", DATA::toString);
    registry.add("jdbc-test.worker.executable",
        () -> Path.of("..", "jdbc-test-worker", "target", "jdbc-test-worker-1.2.1-SNAPSHOT-all.jar")
            .toAbsolutePath().toString());
    registry.add("jdbc-test.execution.run-timeout", () -> "120s");
  }

  @Autowired
  WebApplicationContext context;
  @Autowired
  TestRunSelectedScenarioRepository selected;
  @Autowired
  TestRunRepository runs;
  @Autowired
  JdbcDriverPackageRepository drivers;
  @Autowired
  DatabaseConnectionProfileRepository connections;
  @Autowired
  TestRunCredentialRepository credentials;
  @Autowired
  JdbcDriverArtifactRepository artifacts;
  @Autowired
  DriverStorage storage;
  @Autowired
  org.springframework.jdbc.core.JdbcTemplate jdbc;
  @MockitoSpyBean
  TestRunExecutor executor;
  @LocalServerPort
  int port;
  final ObjectMapper json = new ObjectMapper();
  CountDownLatch subscribed;
  MockMvc mvc;

  @org.junit.jupiter.api.Test
  void statusFilteringUsesOnlyStatusAndKeepsPaginationTotals() throws Exception {
    long before = runs.count();
    long beforeSucceeded = countRunsWithStatus(TestRunStatus.SUCCEEDED);
    long beforeFailed = countRunsWithStatus(TestRunStatus.FAILED);
    long beforeGeneratingReport = countRunsWithStatus(TestRunStatus.GENERATING_REPORT);
    int succeededTotal = (int) beforeSucceeded + 3;
    int succeededPages = (int) Math.ceil(succeededTotal / 2.0);
    JdbcDriverPackageEntity driver = drivers.save(
        new JdbcDriverPackageEntity("Filter driver", "Test", "1", "com.test.Driver",
            DriverSourceType.UPLOAD));
    DatabaseConnectionProfileEntity connection = connections.save(
        new DatabaseConnectionProfileEntity("Filter connection", "test", "postgresql",
            "jdbc:test:filter", "tester"));
    saveFixture(connection, driver, TestRunStatus.SUCCEEDED, 3, 3);
    saveFixture(connection, driver, TestRunStatus.SUCCEEDED, 2, 2);
    saveFixture(connection, driver, TestRunStatus.SUCCEEDED, 1, 1);
    saveFixture(connection, driver, TestRunStatus.FAILED, 4, 1);
    saveFixture(connection, driver, TestRunStatus.FAILED, 2, 0);
    saveFixture(connection, driver, TestRunStatus.RUNNING, 1, 0);
    saveFixture(connection, driver, TestRunStatus.QUEUED, 1, 0);
    saveFixture(connection, driver, TestRunStatus.CANCELLED, 1, 0);
    saveFixture(connection, driver, TestRunStatus.INTERRUPTED, 1, 0);

    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/runs")
            .param("size", "100").param("sort", "createdAt,asc"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value((int) before + 9));
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/runs")
            .param("status", "SUCCEEDED").param("page", "0").param("size", "2")
            .param("sort", "createdAt,asc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(Math.min(2, succeededTotal)))
        .andExpect(jsonPath("$.totalElements").value(succeededTotal))
        .andExpect(jsonPath("$.totalPages").value(succeededPages));
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/runs")
            .param("status", "SUCCEEDED").param("page", "1").param("size", "2")
            .param("sort", "createdAt,asc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(Math.min(2, Math.max(0, succeededTotal - 2))))
        .andExpect(jsonPath("$.totalElements").value(succeededTotal))
        .andExpect(jsonPath("$.totalPages").value(succeededPages));
    int lastPage = succeededPages - 1;
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/runs")
            .param("status", "SUCCEEDED").param("page", String.valueOf(lastPage)).param("size", "2")
            .param("sort", "createdAt,asc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(succeededTotal - (lastPage * 2)))
        .andExpect(jsonPath("$.totalElements").value(succeededTotal))
        .andExpect(jsonPath("$.totalPages").value(succeededPages));
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/runs")
            .param("status", "SUCCEEDED").param("page", String.valueOf(lastPage + 1)).param("size", "2")
            .param("sort", "createdAt,asc"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.totalElements").value(succeededTotal))
        .andExpect(jsonPath("$.totalPages").value(succeededPages));
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/runs")
            .param("status", "FAILED").param("size", "2").param("sort", "createdAt,asc"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.totalElements").value((int) beforeFailed + 2))
        .andExpect(jsonPath("$.totalPages").value((int) Math.ceil((beforeFailed + 2) / 2.0)));
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/runs")
            .param("status", "GENERATING_REPORT").param("size", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value((int) beforeGeneratingReport))
        .andExpect(jsonPath("$.totalElements").value((int) beforeGeneratingReport))
        .andExpect(jsonPath("$.totalPages").value((int) Math.ceil(beforeGeneratingReport / 2.0)));
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/runs")
            .param("status", "NO_SUCH_STATUS"))
        .andExpect(status().isBadRequest());
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/runs")
            .param("page", "-1"))
        .andExpect(status().isBadRequest());
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/runs")
            .param("size", "0"))
        .andExpect(status().isBadRequest());
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/runs")
            .param("sort", "profile,asc"))
        .andExpect(status().isBadRequest());
  }

  private long countRunsWithStatus(TestRunStatus status) {
    return jdbc.queryForObject("select count(*) from test_run where status = ?", Long.class,
        status.name());
  }

  private void saveFixture(DatabaseConnectionProfileEntity connection,
      JdbcDriverPackageEntity driver,
      TestRunStatus status, int total, int passed) {
    TestRunEntity run = new TestRunEntity(connection, driver, "CORE", connection.getName(),
        connection.getDatabaseType(),
        connection.getJdbcUrl(), "Filter driver", "Test", "1", "com.test.Driver", null);
    run.setTotalCases(total);
    for (int index = 0; index < passed; index++) {
      run.recordCase(TestCaseResultStatus.PASSED);
    }
    run.setStatus(status);
    runs.save(run);
  }

  @BeforeEach
  void setup() {
    mvc = MockMvcBuilders.webAppContextSetup(context).build();
    subscribed = new CountDownLatch(1);
    doAnswer(invocation -> {
      assertTrue(subscribed.await(30, TimeUnit.SECONDS), "Real SSE client did not subscribe");
      return invocation.callRealMethod();
    }).when(executor).execute(any(UUID.class));
  }

  @ParameterizedTest
  @CsvSource({"connection,17", "statement,23"})
  void externalPostgresModulesThroughRestQueueWorkerSsePersistenceAndReports(String module,
      int count) throws Exception {
    try (var admin = java.sql.DriverManager.getConnection(TARGET.getJdbcUrl(), TARGET.getUsername(),
        TARGET.getPassword()); var statement = admin.createStatement()) {
      statement.execute("CREATE SCHEMA qa_" + module);
    }
    Path postgres = Path.of(
        org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    String driver = mvc.perform(multipart("/api/drivers")
            .part(new MockPart("metadata", null, json.writeValueAsBytes(
                java.util.Map.of("name", "External PostgreSQL identity", "vendor", "PostgreSQL",
                    "version", "external", "driverClass",
                    ExternalIdentityPostgresDriver.class.getName())), MediaType.APPLICATION_JSON))
            .file(new MockMultipartFile("files", "postgresql-external.jar", "application/java-archive",
                Files.readAllBytes(postgres)))
            .file(new MockMultipartFile("files", "identity-driver.jar", "application/java-archive",
                identityJar(module))))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    UUID driverId = UUID.fromString(json.readTree(driver).path("id").asString());
    mvc.perform(post("/api/drivers/{id}/validate", driverId)).andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("VALIDATED"));
    String connection = mvc.perform(post("/api/connections").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(
                java.util.Map.of("executionDatabase", TARGET.getDatabaseName(), "executionSchema",
                    "qa_" + module, "name", "Target " + module, "databaseType", "postgresql",
                    "adapterId", "postgresql", "jdbcUrl", TARGET.getJdbcUrl(), "username",
                    TARGET.getUsername(), "password", TARGET.getPassword(), "savePassword", true,
                    "defaultDriverId", driverId))))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    String connectionId = json.readTree(connection).path("id").asString();
    try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .build()) {
      HttpResponse<String> creation = client.send(
          HttpRequest.newBuilder(uri("/api/runs")).header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                  java.util.Map.of("connectionId", connectionId, "profile", "CUSTOM", "modules",
                      List.of(module), "destructiveConsent", true)))).build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(201, creation.statusCode(), creation.body());
      UUID id = UUID.fromString(json.readTree(creation.body()).path("id").asString());
      assertEquals(count, json.readTree(creation.body()).path("totalCases").asInt());
      assertEquals(count,
          jdbc.queryForObject("select count(*) from test_run_selected_scenario where run_id = ?",
              Long.class, id));
      assertTrue(selected.findByRun_IdOrderByScenarioId(id).stream()
          .allMatch(item -> module.equals(item.getModuleId())));
      HttpResponse<java.io.InputStream> stream = client.send(
          HttpRequest.newBuilder(uri("/api/runs/" + id + "/events"))
              .header("Accept", "text/event-stream").build(),
          HttpResponse.BodyHandlers.ofInputStream());
      assertEquals(200, stream.statusCode());
      subscribed.countDown();
      String sse;
      try (var reader = Executors.newSingleThreadExecutor(); var input = stream.body()) {
        sse = reader.submit(() -> new String(input.readAllBytes(), StandardCharsets.UTF_8))
            .get(150, TimeUnit.SECONDS);
      }
      for (String name : List.of("run.snapshot", "run.status", "case.started", "case.finished",
          "run.progress")) {
        assertTrue(sse.contains("event:" + name), name + ": " + sse);
      }
      assertTrue(sse.contains("event:run.finished") || sse.contains("event:run.failed"), sse);
      for (String secret : List.of(TARGET.getPassword(), PLATFORM.getPassword(),
          "encryptedPassword", "storageKey", DATA.toString(), "workspace")) {
        assertFalse(sse.contains(secret));
      }
      var detail = json.readTree(get(client, "/api/runs/" + id));
      assertTrue(List.of("SUCCEEDED", "FAILED").contains(detail.path("status").asString()),
          detail.toString());
      assertEquals(count, detail.path("completedCases").asInt());
      assertFalse(credentials.existsById(id));
      var result = json.readTree(get(client, "/api/runs/" + id + "/results?size=100"));
      assertEquals(count, result.path("items").size());
      for (var row : result.path("items")) {
        assertEquals(module, row.path("moduleId").asString());
      }
      get(client, "/api/runs/" + id + "/report");
      var report = json.readTree(get(client, "/api/runs/" + id + "/report.json"));
      Path evidenceDirectory = Path.of("target", "acceptance-evidence");
      Files.createDirectories(evidenceDirectory);
      Files.writeString(evidenceDirectory.resolve("postgresql-" + module + "-report.json"),
          report.toString());
      Files.writeString(evidenceDirectory.resolve("postgresql-" + module + "-detail.json"),
          detail.toString());
      Files.writeString(evidenceDirectory.resolve("postgresql-" + module + "-results.json"),
          result.toString());
      Files.writeString(evidenceDirectory.resolve("postgresql-" + module + "-sse.txt"), sse);
      assertEquals(count, report.path("summary").path("total").asInt());
      assertEquals(count, report.path("scenario_results").size());
      var artifact = artifacts.findByDriver_Id(driverId).stream()
          .filter(item -> item.getFileName().equals("postgresql-external.jar")).findFirst()
          .orElseThrow();
      Path external = storage.resolve(artifact.getStorageKey());
      Properties identity = new Properties();
      try (var input = Files.newInputStream(
          external.resolveSibling(external.getFileName() + ".identity"))) {
        identity.load(input);
      }
      assertEquals(external.toAbsolutePath().toString(), identity.getProperty("artifact"));
      assertNotEquals(postgres.toString(), identity.getProperty("artifact"));
      assertEquals(artifact.getSha256(), identity.getProperty("sha256"));
      assertEquals("org.postgresql.Driver", identity.getProperty("driverClass"));
      assertEquals("true", identity.getProperty("isolated"));
      assertNotEquals(Long.toString(ProcessHandle.current().pid()), identity.getProperty("pid"));
      System.out.println(
          "PHASE7_REAL_RUN module=" + module + " runId=" + id + " status=" + detail.path("status")
              .asString() + " selected=" + count + " passed=" + detail.path("passed").asInt()
              + " failed=" + detail.path("failed").asInt() + " skipped=" + detail.path("skipped")
              .asInt());
      for (var row : result.path("items")) {
        if (!"PASSED".equals(row.path("status").asString())) {
          System.out.println(
              "PHASE7_REAL_CASE scenario=" + row.path("scenarioId").asString() + " status="
                  + row.path("status").asString() + " message=" + row.path("message").asString());
        }
      }
    } finally {
      subscribed.countDown();
    }
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private String get(HttpClient client, String path) throws Exception {
    var response = client.send(HttpRequest.newBuilder(uri(path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), path + ": " + response.body());
    assertFalse(response.body().contains(TARGET.getPassword()));
    return response.body();
  }

  private static byte[] identityJar(String module) throws java.io.IOException {
    var bytes = new ByteArrayOutputStream();
    try (var jar = new JarOutputStream(
        bytes); var input = ExternalIdentityPostgresDriver.class.getResourceAsStream(
        "ExternalIdentityPostgresDriver.class")) {
      jar.putNextEntry(new JarEntry(
          ExternalIdentityPostgresDriver.class.getName().replace('.', '/') + ".class"));
      jar.write(input.readAllBytes());
      jar.closeEntry();
      jar.putNextEntry(new JarEntry("verified-module.txt"));
      jar.write(module.getBytes(StandardCharsets.UTF_8));
      jar.closeEntry();
    }
    return bytes.toByteArray();
  }

  private static Path dataDirectory() {
    try {
      return Files.createTempDirectory("run-postgres-e2e-");
    } catch (java.io.IOException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }
}
