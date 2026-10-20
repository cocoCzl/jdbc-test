package com.jdbctest.server.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jdbctest.server.JdbcTestServerApplication;
import com.jdbctest.server.connection.AdapterService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Two closed/reopened application contexts and a disposable file-backed platform store. */
class AdapterRestartTest {

    @TempDir
    Path directory;

    @Test
    void packageAndResourcesSurviveApplicationRestart() throws Exception {
        String[] arguments = {
            "--spring.datasource.url=jdbc:h2:file:" + directory.resolve("platform")
                + ";MODE=PostgreSQL;DB_CLOSE_ON_EXIT=FALSE",
            "--spring.datasource.username=sa", "--spring.datasource.password=",
            "--jdbc-test.storage.data-directory=" + directory.resolve("data"),
            "--jdbc-test.security.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        };
        String fingerprint;
        try (var first = new SpringApplicationBuilder(JdbcTestServerApplication.class)
            .web(WebApplicationType.NONE).run(arguments)) {
            fingerprint = first.getBean(AdapterService.class)
                .importPackage(ImportedAdapterFlowTest.bundle("restart-fixture", "1", false), null)
                .fingerprint();
        }
        try (var second = new SpringApplicationBuilder(JdbcTestServerApplication.class)
            .web(WebApplicationType.NONE).run(arguments)) {
            var service = second.getBean(AdapterService.class);
            var snapshot = service.packageSnapshot("restart-fixture");
            assertEquals(fingerprint, snapshot.fingerprint());
            assertEquals("1", service.required("restart-fixture").version());
            Path assets = directory.resolve("reopened-assets");
            snapshot.materialize(assets);
            assertTrue(Files.readString(assets.resolve("ddl/postgresql/connection_ddl.sql"))
                .contains("synthetic 1"));
        }
    }
}
