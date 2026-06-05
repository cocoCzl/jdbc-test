package com.jdbctest.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    private String originalConfigPath;

    @BeforeEach
    void setUp() {
        originalConfigPath = System.getProperty("config.yaml");
        ConfigLoader.resetForTesting();
    }

    @AfterEach
    void tearDown() {
        if (originalConfigPath == null) {
            System.clearProperty("config.yaml");
        } else {
            System.setProperty("config.yaml", originalConfigPath);
        }
        ConfigLoader.resetForTesting();
    }

    @Test
    void loadsConfigFromSystemPropertyPath() throws Exception {
        Path config = tempDir.resolve("jdbc-test.yaml");
        Files.writeString(config, """
                db:
                  type: mysql
                  url: jdbc:mysql://127.0.0.1:3306/sample
                  username: root
                  password: secret
                """);
        System.setProperty("config.yaml", config.toString());

        Config loaded = ConfigLoader.load();

        assertEquals(Config.DbType.MYSQL, loaded.db.type);
        assertEquals("jdbc:mysql://127.0.0.1:3306/sample", loaded.db.getJdbcUrl());
        assertEquals("ddl", loaded.ddl.basePath);
        assertEquals("dml", loaded.dml.basePath);
        assertEquals("pool", loaded.pool.profileDir);
        assertEquals("profile", loaded.profile.profileDir);
    }

    @Test
    void resolvesEnvironmentPlaceholdersToEmptyStringWhenMissing() throws Exception {
        Path config = tempDir.resolve("jdbc-test.yaml");
        Files.writeString(config, """
                db:
                  type: postgresql
                  url: jdbc:postgresql://localhost:5432/postgres
                  username: develop
                  password: ${JDBC_TEST_PASSWORD_THAT_SHOULD_NOT_EXIST}
                """);
        System.setProperty("config.yaml", config.toString());

        Config loaded = ConfigLoader.load();

        assertEquals(Config.DbType.POSTGRESQL, loaded.db.type);
        assertEquals("", loaded.db.password);
    }

    @Test
    void rejectsHostPortDatabaseStyleConfig() throws Exception {
        Path config = tempDir.resolve("jdbc-test.yaml");
        Files.writeString(config, """
                db:
                  type: mysql
                  host: 127.0.0.1
                  port: 3306
                  database: sample
                  username: root
                  password: secret
                """);
        System.setProperty("config.yaml", config.toString());

        RuntimeException ex = assertThrows(RuntimeException.class, ConfigLoader::load);

        assertTrue(ex.getMessage().contains("加载配置文件失败"));
    }

    @Test
    void cachesConfigByResolvedPath() throws Exception {
        Path mysqlConfig = tempDir.resolve("mysql.yaml");
        Files.writeString(mysqlConfig, """
                db:
                  type: mysql
                  url: jdbc:mysql://127.0.0.1:3306/sample
                  username: root
                  password: secret
                """);
        Path postgresConfig = tempDir.resolve("postgres.yaml");
        Files.writeString(postgresConfig, """
                db:
                  type: postgresql
                  url: jdbc:postgresql://localhost:5432/postgres
                  username: develop
                  password: secret
                """);

        System.setProperty("config.yaml", mysqlConfig.toString());
        Config mysql = ConfigLoader.load();

        System.setProperty("config.yaml", postgresConfig.toString());
        Config postgres = ConfigLoader.load();

        assertEquals(Config.DbType.MYSQL, mysql.db.type);
        assertEquals(Config.DbType.POSTGRESQL, postgres.db.type);
    }
}
