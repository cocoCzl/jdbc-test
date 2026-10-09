package com.jdbctest.server;

import static org.junit.jupiter.api.Assertions.*;

import com.jdbctest.server.connection.DatabaseConnectionProfileEntity;
import com.jdbctest.server.connection.DatabaseConnectionProfileRepository;
import com.jdbctest.server.driver.*;
import com.jdbctest.server.run.*;
import com.jdbctest.server.security.CredentialEncryptionService;
import java.util.Base64;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@Tag("integration")
@EnabledIf("dockerAvailable")
@SpringBootTest
class PlatformPersistenceIT {

  static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  static {
    try {
      POSTGRES.start();
    } catch (RuntimeException exception) {
      POSTGRES = null;
    }
  }

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("jdbc-test.security.master-key",
        () -> Base64.getEncoder().encodeToString(new byte[32]));
  }

  static boolean dockerAvailable() {
    return POSTGRES != null;
  }

  @Autowired
  JdbcDriverPackageRepository driverRepository;
  @Autowired
  JdbcDriverArtifactRepository artifactRepository;
  @Autowired
  DatabaseConnectionProfileRepository connectionRepository;
  @Autowired
  TestRunRepository runRepository;
  @Autowired
  TestRunModuleRepository moduleRepository;
  @Autowired
  TestCaseResultRepository resultRepository;
  @Autowired
  CredentialEncryptionService encryptionService;

  @Test
  void flywayJpaAndPersistenceRelationsWork() {
    JdbcDriverPackageEntity driver = driverRepository.save(new JdbcDriverPackageEntity(
        "PostgreSQL", "PostgreSQL", "42", "org.postgresql.Driver", DriverSourceType.BUILTIN));
    JdbcDriverArtifactEntity artifact = artifactRepository.save(
        new JdbcDriverArtifactEntity(driver, "postgresql.jar", "drivers/postgresql.jar", 123,
            "a".repeat(64)));
    DatabaseConnectionProfileEntity connection = connectionRepository.save(
        new DatabaseConnectionProfileEntity(
            "integration", "postgresql", "jdbc:postgresql://host/db?password=[REDACTED]",
            "tester"));
    String encryptedPassword = encryptionService.encrypt("integration-secret");
    connection.setEncryptedPassword(encryptedPassword);
    connection.setDefaultDriver(driver);
    connection = connectionRepository.save(connection);
    assertNotEquals("integration-secret",
        connectionRepository.findById(connection.getId()).orElseThrow().getEncryptedPassword());
    assertEquals("integration-secret",
        encryptionService.decrypt(connection.getEncryptedPassword()));
    TestRunEntity run = runRepository.save(
        new TestRunEntity(connection, driver, "CORE", connection.getName(),
            connection.getDatabaseType(), connection.getJdbcUrl(), "PostgreSQL JDBC", "PostgreSQL",
            "42", driver.getDriverClass(), "a".repeat(64)));
    moduleRepository.save(new TestRunModuleEntity(run, "connection", "Connection"));
    resultRepository.save(
        new TestCaseResultEntity(run, "connection.auto-commit", "connection", "Auto commit",
            TestCaseResultStatus.PASSED));

    assertTrue(artifactRepository.findById(artifact.getId()).isPresent());
    assertEquals(TestRunStatus.QUEUED,
        runRepository.findById(run.getId()).orElseThrow().getStatus());
    assertEquals(1, resultRepository.findAll().size());
  }
}
