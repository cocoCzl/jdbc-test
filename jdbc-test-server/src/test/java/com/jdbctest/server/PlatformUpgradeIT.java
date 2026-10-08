package com.jdbctest.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jdbctest.server.connection.DatabaseConnectionProfileRepository;
import com.jdbctest.server.security.CredentialEncryptionService;
import com.jdbctest.server.security.SecurityProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Starts with populated V4, then lets the production Boot/Flyway/JPA path upgrade it.
 */
@Tag("integration")
@EnabledIf("dockerAvailable")
@SpringBootTest
class PlatformUpgradeIT {

  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
  static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
  static final UUID DRIVER = UUID.randomUUID(), CONNECTION = UUID.randomUUID(), RUN = UUID.randomUUID();
  static final String LEGACY_REPORT = "{\"report_schema_version\":\"1.0.0\",\"summary\":{\"failed\":1},\"scenario_results\":{\"connection.auto_commit\":{\"status\":\"FAILED\"}}}";
  static Path data;
  static String envelope;
  static byte[] driverBytes;

  static {
    try {
      postgres.start();
    } catch (RuntimeException unavailable) {
      postgres = null;
    }
    if (postgres != null) {
      try {
        data = Files.createTempDirectory("platform-v4-upgrade-");
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
            postgres.getPassword());
        Flyway.configure().dataSource(source).target("4").load().migrate();
        var sql = new JdbcTemplate(source);
        assertEquals("4",
            sql.queryForObject("select max(version) from flyway_schema_history where success",
                String.class));
        envelope = new CredentialEncryptionService(new SecurityProperties(KEY)).encrypt(
            postgres.getPassword());
        sql.update(
            "insert into jdbc_driver_package(id,name,vendor,version,driver_class,source_type,status,package_fingerprint,created_at,updated_at) values (?,?,?,?,?,'UPLOAD','VALIDATED',?,now(),now())",
            DRIVER, "legacy driver", "PostgreSQL", "42.7.2", "org.postgresql.Driver",
            "a".repeat(64));
        Path artifact = data.resolve("drivers/legacy.jar");
        Files.createDirectories(artifact.getParent());
        driverBytes = Files.readAllBytes(Path.of(
            Class.forName("org.postgresql.Driver").getProtectionDomain().getCodeSource()
                .getLocation().toURI()));
        Files.write(artifact, driverBytes);
        sql.update(
            "insert into jdbc_driver_artifact(id,driver_id,file_name,storage_key,size,sha256,created_at) values (?,?, 'legacy.jar','drivers/legacy.jar',?,?,now())",
            UUID.randomUUID(), DRIVER, driverBytes.length, java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(driverBytes)));
        sql.update(
            "insert into database_connection_profile(id,name,database_type,adapter_id,jdbc_url,username,encrypted_password,default_driver_id,created_at,updated_at) values (?,'legacy connection','postgresql','postgresql',?,?,?, ?,now(),now())",
            CONNECTION, postgres.getJdbcUrl(), postgres.getUsername(), envelope, DRIVER);
        sql.update(
            "insert into test_run(id,connection_id,driver_id,status,profile,created_at,finished_at,total_cases,completed_cases,failed,connection_name_snapshot,database_type_snapshot,jdbc_url_sanitized_snapshot,driver_name_snapshot,driver_vendor_snapshot,driver_version_snapshot,driver_class_snapshot,adapter_id_snapshot) values (?,?,?,'FAILED','CUSTOM',now(),now(),1,1,1,'legacy connection','postgresql','jdbc:postgresql://fixture.invalid/db','legacy driver','PostgreSQL','42.7.2','org.postgresql.Driver','postgresql')",
            RUN, CONNECTION, DRIVER);
        sql.update(
            "insert into test_run_module(id,run_id,module_id,module_name) values (?,?,'connection','Connection')",
            UUID.randomUUID(), RUN);
        sql.update(
            "insert into test_run_selected_scenario(id,run_id,scenario_id,module_id) values (?,?,'connection.auto_commit','connection')",
            UUID.randomUUID(), RUN);
        sql.update(
            "insert into test_case_result(id,run_id,scenario_id,module_id,display_name,status,message) values (?,?,'connection.auto_commit','connection','legacy failure','FAILED','Original V4 failure')",
            UUID.randomUUID(), RUN);
        Path report = data.resolve("runs/" + RUN + "/report/report.json");
        Files.createDirectories(report.getParent());
        Files.writeString(report, LEGACY_REPORT);
      } catch (Exception failure) {
        throw new ExceptionInInitializerError(failure);
      }
    }
  }

  static boolean dockerAvailable() {
    return postgres != null;
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("jdbc-test.security.master-key", () -> KEY);
    registry.add("jdbc-test.storage.data-directory", data::toString);
    registry.add("jdbc-test.worker.executable",
        () -> Path.of("..", "jdbc-test-worker", "target", "jdbc-test-worker-1.2.1-SNAPSHOT-all.jar")
            .toAbsolutePath().toString());
  }

  @Autowired
  WebApplicationContext context;
  @Autowired
  JdbcTemplate sql;
  @Autowired
  DatabaseConnectionProfileRepository connections;
  @Autowired
  CredentialEncryptionService encryption;

  @Test
  void populatedV4PreservesCredentialsArtifactsAndLegacySemantics() throws Exception {
    var mvc = MockMvcBuilders.webAppContextSetup(context).build();
    assertEquals("5",
        sql.queryForObject("select max(version) from flyway_schema_history where success",
            String.class));
    var connection = connections.findById(CONNECTION).orElseThrow();
    assertEquals(envelope, connection.getEncryptedPassword());
    assertEquals(postgres.getPassword(), encryption.decrypt(connection.getEncryptedPassword()));
    assertNull(connection.getExecutionDatabase());
    assertNull(connection.getExecutionSchema());
    assertArrayEquals(driverBytes, Files.readAllBytes(data.resolve("drivers/legacy.jar")));
    mvc.perform(get("/api/drivers/{id}", DRIVER)).andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("legacy driver"));
    mvc.perform(get("/api/connections/{id}", CONNECTION)).andExpect(status().isOk())
        .andExpect(jsonPath("$.hasSavedPassword").value(true));
    mvc.perform(post("/api/drivers/{id}/validate", DRIVER)).andExpect(status().isOk());
    mvc.perform(
            post("/api/connections/{id}/test", CONNECTION).contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    mvc.perform(patch("/api/connections/{id}", CONNECTION).contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"name\":\"edited legacy\",\"databaseType\":\"postgresql\",\"adapterId\":\"postgresql\",\"jdbcUrl\":\"jdbc:postgresql://fixture.invalid/db\",\"username\":\"fixture\",\"defaultDriverId\":\""
                    + DRIVER + "\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.hasSavedPassword").value(true));
    mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON).content(
            "{\"connectionId\":\"" + CONNECTION
                + "\",\"profile\":\"CORE\",\"destructiveConsent\":true}"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("EXECUTION_SCOPE_REQUIRED"));
    mvc.perform(get("/api/runs/{id}", RUN)).andExpect(status().isOk())
        .andExpect(jsonPath("$.failed").value(1))
        .andExpect(jsonPath("$.accounting").doesNotExist());
    mvc.perform(get("/api/runs/{id}/results", RUN)).andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].status").value("FAILED"));
    mvc.perform(get("/api/runs/{id}/report", RUN)).andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.failed").value(1));
    mvc.perform(get("/api/runs/{id}/report.json", RUN)).andExpect(status().isOk())
        .andExpect(jsonPath("$.report_schema_version").value("1.0.0"))
        .andExpect(jsonPath("$.scenario_results['connection.auto_commit'].status").value("FAILED"))
        .andExpect(jsonPath("$.notRun").doesNotExist());
    assertEquals(LEGACY_REPORT,
        Files.readString(data.resolve("runs/" + RUN + "/report/report.json")));
    assertEquals(1, sql.queryForObject("select count(*) from test_run", Integer.class));
  }
}
