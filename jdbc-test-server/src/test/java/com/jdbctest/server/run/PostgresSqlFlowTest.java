package com.jdbctest.server.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jdbctest.server.JdbcTestServerApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** REST + actual external-driver Worker + actual disposable PostgreSQL, including a deliberate failure. */
@EnabledIfEnvironmentVariable(named = "JDBC_TEST_DISPOSABLE_PG_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres")
class PostgresSqlFlowTest {

    @TempDir
    Path directory;
    final ObjectMapper mapper = new ObjectMapper();

    @Test
    void realWorkerPassFailureCleanupTimeoutAndRestartEvidence() throws Exception {
        String token = UUID.randomUUID().toString().replace("-", "");
        String platform = "platform_" + token;
        String target = "target_" + token;
        String role = "role_" + token;
        String base = System.getenv("JDBC_TEST_DISPOSABLE_PG_URL");
        var admin = new JdbcTemplate(new DriverManagerDataSource(base, "postgres", ""));
        admin.execute("CREATE SCHEMA " + platform);
        admin.execute("CREATE ROLE " + role + " LOGIN");
        admin.execute("CREATE SCHEMA " + target + " AUTHORIZATION " + role);
        admin.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
        String[] args = {"--spring.datasource.url=" + base + "?currentSchema=" + platform,
            "--spring.datasource.username=postgres", "--spring.datasource.password=",
            "--spring.flyway.default-schema=" + platform, "--server.port=0",
            "--jdbc-test.storage.data-directory=" + directory,
            "--jdbc-test.security.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "--jdbc-test.worker.executable=" + Path.of("../jdbc-test-worker/target/jdbc-test-worker-1.2.1-SNAPSHOT-all.jar").toAbsolutePath()};
        UUID successful;
        UUID failed;
        try {
            try (var context = new SpringApplicationBuilder(JdbcTestServerApplication.class)
                .web(WebApplicationType.SERVLET).run(args)) {
                var mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context).build();
                byte[] driverBytes = Files.readAllBytes(Path.of(Class.forName("org.postgresql.Driver")
                    .getProtectionDomain().getCodeSource().getLocation().toURI()));
                String metadata = mapper.writeValueAsString(Map.of("name", "disposable pgjdbc", "vendor", "PostgreSQL",
                    "version", "test", "driverClass", "org.postgresql.Driver"));
                var driver = mapper.readTree(mvc.perform(multipart("/api/drivers")
                    .part(new MockPart("metadata", null, metadata.getBytes(java.nio.charset.StandardCharsets.UTF_8), MediaType.APPLICATION_JSON))
                    .file(new MockMultipartFile("files", "postgresql.jar", "application/java-archive", driverBytes)))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
                String driverId = driver.path("id").asString();
                mvc.perform(post("/api/drivers/{id}/validate", driverId)).andExpect(status().isOk());
                String connectionId = mapper.readTree(mvc.perform(post("/api/connections").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("name", "disposable SQL target", "databaseType", "postgresql",
                        "adapterId", "postgresql", "jdbcUrl", base, "username", role, "savePassword", false,
                        "defaultDriverId", driverId, "executionDatabase", "postgres", "executionSchema", target))))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asString();
                String original = Files.readString(Path.of("../examples/sql/postgresql-basics.json"));
                String packageFingerprint = upload(mvc, original);
                successful = create(mvc, connectionId, packageFingerprint,
                    List.of("basic.query", "null.parameter", "join.aggregate", "expected.error"));
                await(context.getBean(TestRunRepository.class), successful);
                var good = context.getBean(TestRunRepository.class).findById(successful).orElseThrow();
                assertEquals(TestRunStatus.SUCCEEDED, good.getStatus(), good.getFailureReason());
                assertEquals(4, good.getPassed());
                assertTrue(good.isWorkerReportValidated());
                mvc.perform(get("/api/runs/{id}/report.json", successful)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.testDomain").value("SQL_FEATURE"))
                    .andExpect(jsonPath("$.cleanupEvidence.status").value("SUCCESS"))
                    .andExpect(jsonPath("$.scenario_results['expected.error'].steps[0].actual.sqlState").value("22012"));
                String badDefinition = original.replace("SELECT 1 AS n", "SELECT 999 AS n");
                failed = create(mvc, connectionId, upload(mvc, badDefinition), List.of("basic.query", "join.aggregate"));
                await(context.getBean(TestRunRepository.class), failed);
                var bad = context.getBean(TestRunRepository.class).findById(failed).orElseThrow();
                assertEquals(TestRunStatus.FAILED, bad.getStatus());
                assertEquals(1, bad.getFailed());
                assertEquals(1, bad.getPassed());
                assertEquals("SQL_ASSERTION_FAILED", bad.getFailureReason());
                assertTrue(bad.isWorkerReportValidated());
                mvc.perform(get("/api/runs/{id}/results", failed)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].executionEvidence.conclusion").value("SQL_ASSERTION_FAILED"));
                String timeoutDefinition = original.replace("\"timeoutSeconds\": 10", "\"timeoutSeconds\": 1")
                    .replace("SELECT 1 AS n", "SELECT 1 AS n FROM pg_sleep(3)");
                UUID timeout = create(mvc, connectionId, upload(mvc, timeoutDefinition), List.of("basic.query", "join.aggregate"));
                await(context.getBean(TestRunRepository.class), timeout);
                assertEquals(TestRunStatus.FAILED, context.getBean(TestRunRepository.class).findById(timeout).orElseThrow().getStatus());
                mvc.perform(get("/api/runs/{id}/results", timeout)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].executionEvidence.conclusion").value("TIMEOUT"));
                assertEquals(0, admin.queryForObject("select count(*) from pg_tables where schemaname=?", Integer.class, target));
                verifyCli(base, role, target, driverBytes, original, badDefinition);
                String cancelDefinition = original.replace("JOIN {{items}} b ON a.id = b.id",
                    "JOIN {{items}} b ON a.id = b.id CROSS JOIN pg_sleep(10)");
                UUID cancelled = create(mvc, connectionId, upload(mvc, cancelDefinition), List.of("join.aggregate"));
                long until = System.nanoTime() + 10_000_000_000L;
                while (admin.queryForObject("select count(*) from pg_tables where schemaname=?", Integer.class, target) == 0) {
                    assertTrue(System.nanoTime() < until, "SQL preparation did not create its owned table");
                    Thread.sleep(50);
                }
                mvc.perform(post("/api/runs/{id}/cancel", cancelled)).andExpect(status().isNoContent());
                var repository = context.getBean(TestRunRepository.class);
                until = System.nanoTime() + 5_000_000_000L;
                while (repository.findById(cancelled).orElseThrow().getCleanupEvidence() == null) {
                    assertTrue(System.nanoTime() < until, "Cooperative SQL cancellation did not report cleanup");
                    Thread.sleep(50);
                }
                var stopped = repository.findById(cancelled).orElseThrow();
                assertEquals(TestRunStatus.CANCELLED, stopped.getStatus());
                assertEquals("SUCCESS", mapper.readTree(stopped.getCleanupEvidence()).path("status").asString());
                assertEquals(0, admin.queryForObject("select count(*) from pg_tables where schemaname=?", Integer.class, target));
                mvc.perform(get("/api/runs/{id}/report.json", cancelled)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.runStatus").value("CANCELLED"))
                    .andExpect(jsonPath("$.terminationReason").value("CANCELLED"))
                    .andExpect(jsonPath("$.workerReportValidated").value(false));
            }
            try (var reopened = new SpringApplicationBuilder(JdbcTestServerApplication.class)
                .web(WebApplicationType.SERVLET).run(args)) {
                var repository = reopened.getBean(TestRunRepository.class);
                assertTrue(repository.findById(successful).orElseThrow().isWorkerReportValidated());
                assertEquals(1, repository.findById(failed).orElseThrow().getFailed());
                assertTrue(repository.findById(successful).orElseThrow().getExecutionInput().contains("sqlPackage"));
                assertEquals(0, admin.queryForObject("select count(*) from pg_tables where schemaname=?", Integer.class, target));
            }
        } finally {
            admin.execute("DROP SCHEMA " + target + " CASCADE");
            admin.execute("DROP SCHEMA " + platform + " CASCADE");
            admin.execute("DROP ROLE " + role);
        }
    }

    private void verifyCli(String url, String role, String schema, byte[] driverBytes,
        String passing, String failing) throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        Path driver = directory.resolve("cli-driver.jar");
        Files.write(driver, driverBytes);
        Path config = directory.resolve("cli-config.json");
        Files.writeString(config, mapper.writeValueAsString(Map.of("adapter", "postgresql",
            "db", Map.of("url", url, "username", role, "password_env", "JDBC_PHASE3_SQL_PASSWORD",
                "connection_mode", "driver_manager", "driver", Map.of("kind", "local", "path", driver.toString())),
            "namespace", Map.of("mode", "existing", "name", schema, "database", "postgres", "destructive_consent", true))));
        for (int expected : List.of(0, 1)) {
            Path input = directory.resolve("cli-package-" + expected + ".json");
            Files.writeString(input, expected == 0 ? passing : failing);
            Path output = directory.resolve("cli-result-" + expected);
            var command = new ProcessBuilder("python3", "-B", "scripts/runner.py", "sql-run",
                "--package", input.toString(), "--config", config.toString(),
                "--case", "basic.query", "--case", "join.aggregate", "--output", output.toString());
            command.directory(root.toFile());
            command.redirectErrorStream(true);
            command.environment().put("JDBC_PHASE3_SQL_PASSWORD", "");
            Path diagnosticFile = directory.resolve("cli-" + expected + ".log");
            command.redirectOutput(diagnosticFile.toFile());
            Process process = command.start();
            try {
                assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "CLI did not terminate");
            } finally {
                if (process.isAlive()) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                    process.waitFor();
                }
            }
            String diagnostic = Files.readString(diagnosticFile);
            assertEquals(expected, process.exitValue(), diagnostic);
            assertEquals("SUCCESS", mapper.readTree(Files.readString(output.resolve("report.json")))
                .at("/cleanupEvidence/status").asString());
        }
    }

    private String upload(MockMvc mvc, String source) throws Exception {
        return mapper.readTree(mvc.perform(multipart("/api/sql-packages").file(new MockMultipartFile(
                "file", "sql-package.json", "application/json", source.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("fingerprint").asString();
    }

    private UUID create(MockMvc mvc, String connectionId, String fingerprint, List<String> ids) throws Exception {
        JsonNode run = mapper.readTree(mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("connectionId", connectionId, "profile", "CUSTOM",
                    "modules", List.of("sql_feature"), "includeScenarios", ids, "sqlPackageFingerprint", fingerprint,
                    "password", "", "destructiveConsent", true, "budgetSeconds", 60))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(run.path("id").asString());
    }

    private void await(TestRunRepository runs, UUID run) throws Exception {
        long deadline = System.nanoTime() + 45_000_000_000L;
        while (!TestRunStateTransitions.terminal(runs.findById(run).orElseThrow().getStatus())) {
            assertTrue(System.nanoTime() < deadline, "Worker did not terminate");
            Thread.sleep(100);
        }
    }
}
