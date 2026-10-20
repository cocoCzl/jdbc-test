package com.jdbctest.server.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.jdbctest.server.JdbcTestServerApplication;
import com.jdbctest.server.connection.AdapterService;
import com.jdbctest.server.connection.ConnectionException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

/** Retention after retirement, using a disposable H2 store; not PostgreSQL migration evidence. */
class HistoricalPackageRetentionTest {

    @TempDir
    Path directory;

    @Test
    void retiredRawPackageRecordsSurviveRestartWithoutBecomingRuntimeAdapters() {
        String[] args = {
            "--spring.datasource.url=jdbc:h2:file:" + directory.resolve("platform")
                + ";MODE=PostgreSQL;DB_CLOSE_ON_EXIT=FALSE",
            "--spring.datasource.username=sa", "--spring.datasource.password=",
            "--jdbc-test.storage.data-directory=" + directory.resolve("data"),
            "--jdbc-test.security.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        };
        String evidence = "{\"retained\":true,\"fingerprint\":\"old-content\"}";
        try (var first = new SpringApplicationBuilder(JdbcTestServerApplication.class)
            .web(WebApplicationType.NONE).run(args)) {
            var sql = first.getBean(JdbcTemplate.class);
            sql.update("insert into imported_adapter(id,fingerprint,snapshot_json) values (?,?,?)",
                "old-local", "a".repeat(64), evidence);
            sql.update("insert into sql_test_package(fingerprint,snapshot_json) values (?,?)",
                "b".repeat(64), evidence);
        }
        try (var second = new SpringApplicationBuilder(JdbcTestServerApplication.class)
            .web(WebApplicationType.NONE).run(args)) {
            var sql = second.getBean(JdbcTemplate.class);
            assertEquals(evidence, sql.queryForObject(
                "select snapshot_json from imported_adapter where id='old-local'", String.class));
            assertEquals(evidence, sql.queryForObject(
                "select snapshot_json from sql_test_package where fingerprint=?", String.class,
                "b".repeat(64)));
            var adapters = second.getBean(AdapterService.class);
            assertFalse(adapters.list().stream().anyMatch(item -> "old-local".equals(item.id())));
            assertThrows(ConnectionException.class, () -> adapters.required("old-local"));
            assertEquals("postgresql", adapters.required("postgresql").id());
        }
    }
}
