package com.jdbctest.server.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:sql-package-flow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "jdbc-test.security.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class SqlPackageFlowTest {

    @TempDir
    static Path directory;
    @Autowired
    WebApplicationContext context;
    @Autowired
    JdbcTemplate sql;
    @Autowired
    TestRunRepository runs;
    @MockitoBean
    TestRunScheduler scheduler;
    @MockitoBean
    RunCatalogService catalog;
    final ObjectMapper mapper = new ObjectMapper();
    MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jdbc-test.storage.data-directory", () -> directory.toString());
    }

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void importQueueAndNewRevisionKeepFrozenDefinitionAndSeparateCatalog() throws Exception {
        String original = Files.readString(Path.of("../examples/sql/postgresql-basics.json"));
        String first = upload(original);
        assertEquals(first, upload(original));
        UUID driver = UUID.randomUUID();
        sql.update("insert into jdbc_driver_package(id,name,vendor,version,driver_class,source_type,status,package_fingerprint,created_at,updated_at) values (?,'fixture','fixture','A','fixture.Driver','UPLOAD','VALIDATED',?,now(),now())", driver, driver.toString());
        var connection = mapper.readTree(mvc.perform(post("/api/connections")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                "name", "SQL fixture", "databaseType", "postgresql", "adapterId", "postgresql",
                "jdbcUrl", "jdbc:fixture:never-contacted", "username", "fixture",
                "defaultDriverId", driver, "executionDatabase", "owned_database",
                "executionSchema", "owned_schema", "savePassword", false))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        var body = new java.util.LinkedHashMap<String, Object>(Map.of("connectionId", connection.path("id").asString(),
            "profile", "CUSTOM", "modules", List.of("sql_feature"), "includeScenarios", List.of("basic.query"),
            "sqlPackageFingerprint", first, "password", "synthetic", "destructiveConsent", true));
        var run = mapper.readTree(mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.sqlPackage.fingerprint").value(first))
            .andExpect(jsonPath("$.contractSummary").doesNotExist())
            .andReturn().getResponse().getContentAsString());
        UUID runId = UUID.fromString(run.path("id").asString());
        String frozen = runs.findById(runId).orElseThrow().getExecutionInput();
        String second = upload(original.replace("1.0.0", "1.0.1"));
        org.junit.jupiter.api.Assertions.assertNotEquals(first, second);
        assertEquals(frozen, runs.findById(runId).orElseThrow().getExecutionInput());
        mvc.perform(get("/api/sql-packages")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        body.put("profile", "FULL");
        mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
            .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("INVALID_SELECTION"));
        body.put("profile", "CUSTOM");
        body.put("includeScenarios", List.of("missing.case"));
        mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
            .andExpect(status().isUnprocessableContent());
        mvc.perform(multipart("/api/sql-packages").file(new MockMultipartFile("file", "bad.json",
                "application/json", "{\"formatVersion\":99}".getBytes(java.nio.charset.StandardCharsets.UTF_8))))
            .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("formatVersion")));
        verify(scheduler).enqueue(runId);
        verifyNoInteractions(catalog);
    }

    private String upload(String manifest) throws Exception {
        return mapper.readTree(mvc.perform(multipart("/api/sql-packages").file(new MockMultipartFile(
            "file", "sql-package.json", "application/json", manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.origin").value("LOCAL"))
            .andReturn().getResponse().getContentAsString()).path("fingerprint").asString();
    }
}
