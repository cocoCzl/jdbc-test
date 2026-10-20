package com.jdbctest.server.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jdbctest.catalog.AdapterPackageSnapshot;
import com.jdbctest.catalog.AdapterRegistry;
import com.jdbctest.server.connection.AdapterService;
import com.jdbctest.server.driver.WorkerProcessRunner;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/** Real REST/JPA/storage with synthetic assets; no uploaded code or target database is executed. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:imported-adapter-flow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "jdbc-test.security.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class ImportedAdapterFlowTest {

    @TempDir
    static Path directory;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jdbc-test.storage.data-directory", () -> directory.toString());
    }

    @Autowired
    WebApplicationContext context;
    @Autowired
    AdapterService adapters;
    @Autowired
    TestRunRepository runs;
    @MockitoBean
    TestRunScheduler scheduler;
    @MockitoBean
    RunCatalogService catalog;
    @MockitoBean
    WorkerProcessRunner worker;
    final ObjectMapper mapper = new ObjectMapper();
    MockMvc mvc;

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        when(catalog.catalog()).thenReturn(new RunCatalogService.Catalog(List.of(),
            List.of(new RunCatalogService.Module("connection", "Connection", "fixture", 1, 1)),
            List.of(new RunCatalogService.Scenario("connection.is_valid", "connection",
                "isValid", "CORE")), 1, "synthetic-catalog"));
        when(worker.run(eq("--validate-driver"), any(), any())).thenReturn(
            new WorkerProcessRunner.Result(0, false,
                "{\"type\":\"DRIVER_VALIDATED\",\"driverClass\":\"fixture.Driver\"}", ""));
    }

    @Test
    void importUploadConnectQueueUpdateAndArchivePreserveIndependentSnapshots() throws Exception {
        String id = "private-flow";
        String imported = mvc.perform(multipart("/api/adapters").file(bundle(id, "1", false)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LOCAL"))
            .andExpect(jsonPath("$.origin").value("IMPORTED")).andReturn()
            .getResponse().getContentAsString();
        String firstFingerprint = mapper.readTree(imported).path("fingerprint").asString();
        mvc.perform(multipart("/api/adapters").file(bundle(id, "1", false)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.fingerprint").value(firstFingerprint));
        mvc.perform(multipart("/api/adapters").file(bundle(id, "2", false)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ADAPTER_UPDATE_REQUIRES_FINGERPRINT"));

        String metadata = "{\"name\":\"fixture\",\"vendor\":\"fixture\",\"version\":\"A\","
            + "\"driverClass\":\"fixture.Driver\"}";
        var driver = mapper.readTree(mvc.perform(multipart("/api/drivers")
                .part(new MockPart("metadata", null, metadata.getBytes(StandardCharsets.UTF_8),
                    MediaType.APPLICATION_JSON))
                .file(new MockMultipartFile("files", "driver.jar", "application/java-archive", jar())))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String driverId = driver.path("id").asString();
        mvc.perform(post("/api/drivers/{id}/validate", driverId)).andExpect(status().isOk());
        var connection = mapper.readTree(mvc.perform(post("/api/connections")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                    "name", "fixture", "databaseType", "Private product", "adapterId", id,
                    "jdbcUrl", "jdbc:fixture:never-contacted", "username", "fixture",
                    "defaultDriverId", driverId, "savePassword", false, "executionDatabase", "owned_database",
                    "executionSchema", "owned_schema"))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.adapterId").value(id))
            .andReturn().getResponse().getContentAsString());
        String connectionId = connection.path("id").asString();
        UUID oldRun = createRun(connectionId);
        String frozenBefore = runs.findById(oldRun).orElseThrow().getExecutionInput();
        var frozenPackage = mapper.treeToValue(mapper.readTree(frozenBefore).path("adapterPackage"),
            AdapterPackageSnapshot.class);
        assertEquals(firstFingerprint, frozenPackage.fingerprint());
        assertTrue(mapper.readTree(frozenBefore).path("destructiveConsent").asBoolean());
        assertEquals("owned_schema", mapper.readTree(frozenBefore).at("/scope/schema").asString());
        assertEquals(driverId, mvc.perform(get("/api/runs/{id}", oldRun)).andExpect(status().isOk())
            .andExpect(jsonPath("$.adapterSnapshot.version").value("1"))
            .andExpect(jsonPath("$.adapterSnapshot.fingerprint").value(firstFingerprint))
            .andReturn().getResponse().getContentAsString().transform(
                value -> mapper.readTree(value).at("/driver/id").asString()));
        mvc.perform(multipart("/api/adapters").file(bundle(id, "2", false))
                .param("expectedFingerprint", firstFingerprint)).andExpect(status().isOk());
        mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON).content(
                mapper.writeValueAsString(Map.of("connectionId", connectionId, "profile", "CUSTOM",
                    "includeScenarios", List.of("connection.is_valid"), "password", "synthetic-only",
                    "destructiveConsent", true, "expectedAdapterFingerprint", firstFingerprint))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ADAPTER_CHANGED"));
        UUID newRun = createRun(connectionId);
        var newFrozen = mapper.readTree(runs.findById(newRun).orElseThrow().getExecutionInput());
        assertEquals("2", newFrozen.at("/adapterSnapshot/version").asString());
        assertNotEquals(firstFingerprint, newFrozen.at("/adapterSnapshot/fingerprint").asString());
        assertEquals(frozenBefore, runs.findById(oldRun).orElseThrow().getExecutionInput());
        mvc.perform(delete("/api/adapters/{id}", id)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ADAPTER_IN_USE"));
        // Deleting a connection with task history is already blocked by the existing service.
        mvc.perform(delete("/api/connections/{id}", connectionId)).andExpect(status().isConflict());
        mvc.perform(get("/api/runs/{id}", oldRun)).andExpect(status().isOk())
            .andExpect(jsonPath("$.adapterSnapshot.version").value("1"));
        // A queued task still cannot be downloaded as a completed report.
        mvc.perform(get("/api/runs/{id}/report.json", oldRun)).andExpect(status().isConflict());
        Path oldAssets = directory.resolve("old-run-resources");
        frozenPackage.materialize(oldAssets);
        assertTrue(java.nio.file.Files.readString(
            oldAssets.resolve("ddl/postgresql/connection_ddl.sql")).contains("synthetic 1"));
        verify(scheduler).enqueue(oldRun);
        verify(scheduler).enqueue(newRun);
    }

    @Test
    void importedOfficialClaimStaysLocalAndBuiltinIdsCannotBeReplaced() throws Exception {
        mvc.perform(multipart("/api/adapters").file(bundle("forged-official", "1", true)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LOCAL"))
            .andExpect(jsonPath("$.origin").value("IMPORTED"));
        mvc.perform(multipart("/api/adapters").file(bundle("postgresql", "1", false)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ADAPTER_BUILTIN_IMMUTABLE"));
        mvc.perform(delete("/api/adapters/postgresql")).andExpect(status().isConflict());
        mvc.perform(get("/api/adapters")).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id=='postgresql')].origin").value("BUILTIN"));
        mvc.perform(delete("/api/adapters/forged-official")).andExpect(status().isNoContent());
        mvc.perform(get("/api/adapters")).andExpect(jsonPath("$[?(@.id=='forged-official')]").isEmpty());
    }

    @Test
    void invalidPackageHasConcreteValidationDetail() throws Exception {
        mvc.perform(multipart("/api/adapters").file(new MockMultipartFile("file", "bad.zip",
                "application/zip", "invalid".getBytes(StandardCharsets.UTF_8))))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.code").value("ADAPTER_PACKAGE_INVALID"))
            .andExpect(jsonPath("$.detail").value("ADAPTER_RESOURCE_MISSING: adapter.json"));
    }

    private UUID createRun(String connectionId) throws Exception {
        var node = mapper.readTree(mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("connectionId", connectionId,
                    "profile", "CUSTOM", "includeScenarios", List.of("connection.is_valid"),
                    "password", "synthetic-only", "destructiveConsent", true))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(node.path("id").asString());
    }

    static MockMultipartFile bundle(String id, String version, boolean official) throws Exception {
        var mapper = new ObjectMapper();
        var definition = AdapterRegistry.bundled().required("postgresql");
        var tree = mapper.valueToTree(definition.declaration()).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) tree).put("id", id).put("version", version)
            .put("trust", official ? "official" : "local");
        ((tools.jackson.databind.node.ObjectNode) tree.path("assets")).put("directory", "postgresql");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            entry(zip, "adapter.json", mapper.writeValueAsString(tree));
            for (String area : definition.assets().areas()) {
                entry(zip, "ddl/postgresql/" + area + "_ddl.sql", "-- synthetic " + version);
                entry(zip, "dml/postgresql/" + area + "_dml.sql", "-- synthetic " + version);
            }
            entry(zip, "pool/postgresql.yaml", "{}");
            entry(zip, "evidence/postgresql-15.2-driver-42.7.2.json", "{\"fixtureOnly\":true}");
        }
        return new MockMultipartFile("file", "adapter.zip", "application/zip", output.toByteArray());
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private byte[] jar() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output)) {
            jar.putNextEntry(new JarEntry("META-INF/services/java.sql.Driver"));
            jar.write("fixture.Driver".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return output.toByteArray();
    }
}
