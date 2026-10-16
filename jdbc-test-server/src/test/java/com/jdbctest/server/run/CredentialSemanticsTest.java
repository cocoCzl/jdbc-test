package com.jdbctest.server.run;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jdbctest.server.connection.DatabaseConnectionProfileRepository;
import com.jdbctest.server.driver.*;
import com.jdbctest.server.security.CredentialEncryptionService;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * Persistence is real H2; probes are explicitly simulated, never target authentication evidence.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:credential-semantics;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "jdbc-test.security.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class CredentialSemanticsTest {

    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path directory;

    @org.springframework.test.context.DynamicPropertySource
    static void properties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("jdbc-test.storage.data-directory", () -> directory.toString());
    }

    @Autowired
    WebApplicationContext context;
    @Autowired
    JdbcDriverPackageRepository drivers;
    @Autowired
    DatabaseConnectionProfileRepository connections;
    @Autowired
    CredentialEncryptionService encryption;
    @Autowired
    TestRunService service;
    @Autowired
    TestRunCredentialRepository credentials;
    @MockitoBean
    TestRunScheduler scheduler;
    @MockitoBean
    RunCatalogService catalog;
    @MockitoBean
    WorkerProcessRunner worker;
    MockMvc mvc;
    UUID driverId;
    final ObjectMapper json = new ObjectMapper();
    final List<String> observed = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        var driver = new JdbcDriverPackageEntity("fixture", "fixture", "1", "fixture.Driver",
            DriverSourceType.UPLOAD);
        driver.setStatus(DriverPackageStatus.VALIDATED);
        driverId = drivers.save(driver).getId();
        when(catalog.catalog()).thenReturn(new RunCatalogService.Catalog(List.of(),
            List.of(new RunCatalogService.Module("connection", "Connection", "fixture", 1, 1)),
            List.of(new RunCatalogService.Scenario("connection.fixture", "connection", "fixture",
                "CORE")), 1));
        when(worker.run(eq("--probe-connection"), any(), any())).thenAnswer(invocation -> {
            Map<?, ?> request = invocation.getArgument(1);
            observed.add((String) ((Map<?, ?>) request.get("connection")).get("password"));
            return new WorkerProcessRunner.Result(0, false, "{\"type\":\"CONNECTION_PROBED\"}", "");
        });
    }

    @Test
    void literalPasswordContractsSurviveCrudProbesQueueAndConsumption() throws Exception {
        for (String literal : List.of("", "  abc  ", "   ", ";\"中文🔐", "${literal}\\")) {
            Map<String, Object> input = input();
            input.put("password", literal);
            input.put("savePassword", true);
            String body = mvc.perform(
                    post("/api/connections").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(input)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hasSavedPassword").value(true))
                .andReturn().getResponse().getContentAsString();
            UUID id = UUID.fromString(json.readTree(body).path("id").asString());
            String envelope = connections.findById(id).orElseThrow().getEncryptedPassword();
            assertNotNull(envelope);
            assertFalse(envelope.isEmpty());
            assertEquals(literal, encryption.decrypt(envelope));
            mvc.perform(
                    post("/api/connections/{id}/test", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
            assertEquals(literal, observed.getLast());
            mvc.perform(
                    post("/api/connections/{id}/test", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"\"}"))
                .andExpect(status().isOk());
            assertEquals("", observed.getLast());
            assertEquals(envelope, connections.findById(id).orElseThrow().getEncryptedPassword());
            input.put("driverId", driverId);
            mvc.perform(post("/api/connections/test").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input))).andExpect(status().isOk());
            assertEquals(literal, observed.getLast());
            var run = service.create(request(id, null));
            assertEquals(literal, service.consumeCredential(run.id()));
            assertFalse(credentials.existsById(run.id()));
            var emptyOverride = service.create(request(id, ""));
            assertEquals("", service.consumeCredential(emptyOverride.id()));
            input.remove("password");
            input.put("savePassword", false);
            mvc.perform(patch("/api/connections/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input))).andExpect(status().isOk());
            assertEquals(envelope, connections.findById(id).orElseThrow().getEncryptedPassword());
            input.put("clearSavedPassword", true);
            input.put("password", "");
            input.put("savePassword", true);
            mvc.perform(patch("/api/connections/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input))).andExpect(status().isBadRequest());
            input.remove("password");
            input.put("savePassword", false);
            mvc.perform(patch("/api/connections/{id}", id).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(input))).andExpect(status().isOk())
                .andExpect(jsonPath("$.hasSavedPassword").value(false));
            mvc.perform(
                    post("/api/connections/{id}/test", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONNECTION_PASSWORD_REQUIRED"));
            assertEquals("CONNECTION_PASSWORD_REQUIRED", assertThrows(RunException.class,
                () -> service.create(request(id, null))).code());
            var explicitEmpty = service.create(request(id, ""));
            assertEquals("", service.consumeCredential(explicitEmpty.id()));
            var abandoned = service.create(request(id, literal));
            service.cleanupCredential(abandoned.id());
            assertFalse(credentials.existsById(abandoned.id()));
        }
    }

    @Test
    void legacyCredentialUrlIsSafeOnReadAndRejectedAtProbeAndRun() throws Exception {
        var driver = drivers.findById(driverId).orElseThrow();
        var entity = new com.jdbctest.server.connection.DatabaseConnectionProfileEntity("legacy",
            "oracle", "oracle",
            "jdbc:oracle:thin:u/AuditOnly_Secret@fixture.invalid", "fixture");
        entity.setExecutionScope(null, "owned_scope");
        entity.setDefaultDriver(driver);
        entity.setEncryptedPassword(encryption.encrypt("fixture"));
        UUID id = connections.save(entity).getId();
        for (String endpoint : List.of("/api/connections/" + id, "/api/connections")) {
            String body = mvc.perform(get(endpoint)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
            assertFalse(body.contains("AuditOnly"));
        }
        mvc.perform(post("/api/connections/{id}/test", id).contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.code").value("CONNECTION_URL_CONTAINS_CREDENTIALS"));
        assertEquals("CONNECTION_URL_CONTAINS_CREDENTIALS",
            assertThrows(RunException.class, () -> service.create(request(id, null))).code());
        verifyNoInteractions(worker);
    }

    @Test
    void legacyTlsCredentialUrlsAreSafeOnHttpReadAndRejectedAtProbeAndRun() throws Exception {
        for (String url : List.of(
            "jdbc:postgresql://fixture.invalid/test?sslpassword=ReviewOnlyTlsSecret",
            "jdbc:sqlserver://fixture.invalid;trustStorePassword=ReviewOnlyTlsSecret;encrypt=true")) {
            var entity = new com.jdbctest.server.connection.DatabaseConnectionProfileEntity(
                "legacy-tls",
                "postgresql", "postgresql", url, "fixture");
            entity.setExecutionScope(null, "owned_scope");
            entity.setDefaultDriver(drivers.findById(driverId).orElseThrow());
            entity.setEncryptedPassword(encryption.encrypt("fixture"));
            UUID id = connections.save(entity).getId();
            for (String endpoint : List.of("/api/connections/" + id, "/api/connections")) {
                String body = mvc.perform(get(endpoint)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
                assertFalse(body.contains("ReviewOnlyTls"));
            }
            mvc.perform(
                    post("/api/connections/{id}/test", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CONNECTION_URL_CONTAINS_CREDENTIALS"));
            mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(request(id, null))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CONNECTION_URL_CONTAINS_CREDENTIALS"));
            assertEquals(url, connections.findById(id).orElseThrow().getJdbcUrl());
        }
        verifyNoInteractions(worker);
    }

    private CreateTestRunRequest request(UUID id, String password) {
        return new CreateTestRunRequest(id, null, "CUSTOM", List.of("connection"), List.of(),
            List.of(), password, true);
    }

    private Map<String, Object> input() {
        return new LinkedHashMap<>(Map.ofEntries(Map.entry("name", "fixture"),
            Map.entry("databaseType", "postgresql"), Map.entry("adapterId", "postgresql"),
            Map.entry("jdbcUrl", "jdbc:fixture:literal"), Map.entry("username", "fixture"),
            Map.entry("defaultDriverId", driverId), Map.entry("executionDatabase", "fixture"),
            Map.entry("executionSchema", "owned_scope")));
    }
}
