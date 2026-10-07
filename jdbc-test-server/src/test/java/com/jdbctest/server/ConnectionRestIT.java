package com.jdbctest.server;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jdbctest.server.connection.DatabaseConnectionProfileEntity;
import com.jdbctest.server.connection.DatabaseConnectionProfileRepository;
import com.jdbctest.server.connection.ConnectionTestStatus;
import com.jdbctest.server.driver.DriverSourceType;
import com.jdbctest.server.driver.JdbcDriverPackageEntity;
import com.jdbctest.server.driver.JdbcDriverPackageRepository;
import com.jdbctest.server.run.TestRunEntity;
import com.jdbctest.server.run.TestRunRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

@Tag("integration")
@EnabledIf("dockerAvailable")
@SpringBootTest
class ConnectionRestIT {
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine").withPassword("server-probe-secret");
    static final Path DATA = dataDirectory();
    static { try { POSTGRES.start(); } catch (RuntimeException exception) { POSTGRES = null; } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl); registry.add("spring.datasource.username", POSTGRES::getUsername); registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("jdbc-test.security.master-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("jdbc-test.storage.data-directory", () -> DATA.toString());
        registry.add("jdbc-test.worker.executable", () -> Path.of("..", "jdbc-test-worker", "target", "jdbc-test-worker-1.2.1-SNAPSHOT-all.jar").toAbsolutePath().toString());
    }
    static boolean dockerAvailable() { return POSTGRES != null; }

    @Autowired WebApplicationContext context;
    @Autowired DatabaseConnectionProfileRepository connections;
    @Autowired JdbcDriverPackageRepository drivers;
    @Autowired TestRunRepository runs;

    @Test
    void connectionResourcesUseValidatedExternalDriverAndWorkerProbe() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(get("/api/adapters")).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == 'postgresql')]").exists());
        String driverId = createAndValidatePostgresDriver(mvc);
        String password = POSTGRES.getPassword();
        String create = "{\"name\":\" PostgreSQL dev \",\"databaseType\":\"postgresql\",\"adapterId\":\"postgresql\",\"jdbcUrl\":\"" + POSTGRES.getJdbcUrl() + "\",\"username\":\"" + POSTGRES.getUsername() + "\",\"password\":\"" + password + "\",\"savePassword\":true,\"defaultDriverId\":\"" + driverId + "\",\"description\":\"test\"}";
        String body = mvc.perform(post("/api/connections").contentType(MediaType.APPLICATION_JSON).content(create))
                .andExpect(status().isCreated()).andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern("/api/connections/[0-9a-f-]+")))
                .andExpect(jsonPath("$.hasSavedPassword").value(true)).andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.encryptedPassword").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String connectionId = id(body);
        DatabaseConnectionProfileEntity persisted = connections.findById(java.util.UUID.fromString(connectionId)).orElseThrow();
        org.junit.jupiter.api.Assertions.assertFalse(persisted.getEncryptedPassword().contains(password));
        mvc.perform(post("/api/connections/{id}/test", connectionId).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.databaseProductName").value("PostgreSQL"));
        mvc.perform(post("/api/connections/test").contentType(MediaType.APPLICATION_JSON).content("{\"databaseType\":\"postgresql\",\"adapterId\":\"postgresql\",\"jdbcUrl\":\"" + POSTGRES.getJdbcUrl() + "\",\"username\":\"" + POSTGRES.getUsername() + "\",\"password\":\"" + password + "\",\"driverId\":\"" + driverId + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        String encrypted = persisted.getEncryptedPassword();
        mvc.perform(post("/api/connections/{id}/test", connectionId).contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("CONNECTION_FAILED"));
        org.junit.jupiter.api.Assertions.assertEquals(encrypted, connections.findById(java.util.UUID.fromString(connectionId)).orElseThrow().getEncryptedPassword());
        org.junit.jupiter.api.Assertions.assertEquals(ConnectionTestStatus.FAILED, connections.findById(java.util.UUID.fromString(connectionId)).orElseThrow().getLastConnectionTestStatus());
        mvc.perform(get("/api/connections?page=0&size=20&sort=createdAt,desc")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
        mvc.perform(patch("/api/connections/{id}", connectionId).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"PostgreSQL updated\",\"databaseType\":\"postgresql\",\"adapterId\":\"postgresql\",\"jdbcUrl\":\"" + POSTGRES.getJdbcUrl() + "\",\"username\":\"" + POSTGRES.getUsername() + "\",\"clearSavedPassword\":true,\"defaultDriverId\":\"" + driverId + "\",\"description\":\"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasSavedPassword").value(false));
        mvc.perform(post("/api/connections/{id}/test", connectionId).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONNECTION_PASSWORD_REQUIRED"));
        mvc.perform(delete("/api/connections/{id}", connectionId)).andExpect(status().isNoContent());
    }

    @Test
    void rejectsUrlCredentialsUnknownAdapterAndUnvalidatedDriver() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        String unknown = java.util.UUID.randomUUID().toString();
        String base = "{\"name\":\"x\",\"databaseType\":\"postgresql\",\"adapterId\":\"%s\",\"jdbcUrl\":\"%s\",\"username\":\"u\",\"savePassword\":false,\"defaultDriverId\":\"%s\"}";
        mvc.perform(post("/api/connections").contentType(MediaType.APPLICATION_JSON).content(base.formatted("postgresql", "jdbc:postgresql://host/db?password=secret", unknown)))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("CONNECTION_URL_CONTAINS_CREDENTIALS"));
        mvc.perform(post("/api/connections").contentType(MediaType.APPLICATION_JSON).content(base.formatted("typo", "jdbc:postgresql://host/db", unknown)))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("ADAPTER_NOT_FOUND"));
    }

    @Test
    void referencedConnectionCannotBeDeleted() throws Exception {
        JdbcDriverPackageEntity driver = drivers.save(new JdbcDriverPackageEntity("test", "Acme", "1", "com.acme.Driver", DriverSourceType.UPLOAD));
        DatabaseConnectionProfileEntity connection = new DatabaseConnectionProfileEntity("used", "acme", "postgresql", "jdbc:acme:test", "tester"); connection.setDefaultDriver(driver); connection = connections.save(connection);
        runs.save(new TestRunEntity(connection, driver, "CORE", "used", "acme", "jdbc:acme:test", "Acme", "Acme", "1", "com.acme.Driver", null));
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(delete("/api/connections/{id}", connection.getId())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONNECTION_IN_USE"));
    }

    @Test
    void customRunUsesWorkerExternalDriverPersistsResultAndReport() throws Exception {
        try (var admin = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); var statement = admin.createStatement()) {
            statement.execute("CREATE SCHEMA qa_single");
        }
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        String driverId = createAndValidatePostgresDriver(mvc);
        String connection = mvc.perform(post("/api/connections").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"run target\",\"executionDatabase\":\"" + POSTGRES.getDatabaseName() + "\",\"executionSchema\":\"qa_single\",\"databaseType\":\"postgresql\",\"adapterId\":\"postgresql\",\"jdbcUrl\":\"" + POSTGRES.getJdbcUrl() + "\",\"username\":\"" + POSTGRES.getUsername() + "\",\"password\":\"" + POSTGRES.getPassword() + "\",\"savePassword\":true,\"defaultDriverId\":\"" + driverId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String connectionId = id(connection);
        String body = mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON).content("{\"connectionId\":\"" + connectionId + "\",\"profile\":\"CUSTOM\",\"modules\":[\"connection\"],\"includeScenarios\":[\"connection.get_auto_commit\"],\"destructiveConsent\":true}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.totalCases").value(1)).andReturn().getResponse().getContentAsString();
        String runId = id(body);
        for (int i = 0; i < 300; i++) {
            String run = mvc.perform(get("/api/runs/{id}", runId)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String state = new tools.jackson.databind.ObjectMapper().readTree(run).path("status").asString();
            if ("SUCCEEDED".equals(state)) break;
            if ("FAILED".equals(state)) org.junit.jupiter.api.Assertions.fail(run);
            Thread.sleep(100);
        }
        mvc.perform(get("/api/runs/{id}", runId)).andExpect(jsonPath("$.status").value("SUCCEEDED")).andExpect(jsonPath("$.totalCases").value(1));
        mvc.perform(get("/api/runs/{id}/results", runId)).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].scenarioId").value("connection.get_auto_commit"));
        mvc.perform(get("/api/runs/{id}/report.json", runId)).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    private String createAndValidatePostgresDriver(MockMvc mvc) throws Exception {
        var existing = drivers.findAll().stream().filter(item -> item.getStatus() == com.jdbctest.server.driver.DriverPackageStatus.VALIDATED).findFirst();
        if (existing.isPresent()) return existing.get().getId().toString();
        Path artifact = Path.of(org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String response = mvc.perform(multipart("/api/drivers").part(new MockPart("metadata", null, "{\"name\":\"External PostgreSQL\",\"vendor\":\"PostgreSQL\",\"version\":\"external\",\"driverClass\":\"org.postgresql.Driver\"}".getBytes(), MediaType.APPLICATION_JSON))
                        .file(new MockMultipartFile("files", "postgresql-external.jar", "application/java-archive", Files.readAllBytes(artifact))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = id(response);
        mvc.perform(post("/api/drivers/{id}/validate", id)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VALIDATED"));
        return id;
    }
    private static String id(String json) { return new tools.jackson.databind.ObjectMapper().readTree(json).path("id").asString(); }
    private static Path dataDirectory() { try { return Files.createTempDirectory("jdbc-connection-rest-"); } catch (Exception exception) { throw new ExceptionInInitializerError(exception); } }
}
