package com.jdbctest.server.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jdbctest.server.connection.DatabaseConnectionProfileRepository;
import com.jdbctest.server.run.TestRunRepository;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

class DriverServiceTest {

    @TempDir
    Path temporaryDirectory;

    private DriverService service;
    private final JdbcDriverPackageRepository packages = mock(JdbcDriverPackageRepository.class);
    private final DriverValidationProcess validation = mock(DriverValidationProcess.class);

    @BeforeEach
    void setUp() {
        DriverStorageProperties properties = new DriverStorageProperties();
        properties.setDataDirectory(temporaryDirectory);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        service = new DriverService(packages, mock(JdbcDriverArtifactRepository.class),
            mock(DatabaseConnectionProfileRepository.class), mock(TestRunRepository.class),
            new FileSystemDriverStorage(properties), validation, transactions);
    }

    @Test
    void savesActualCustomValuesEvenWhenSpiDeclaresAnotherClass() throws Exception {
        var response = service.create(new CreateDriverRequest("Private JDBC", "Private issuer",
            "custom-version", "private.jdbc.Driver", "jdbc:private:"),
            List.of(jar("org.postgresql.Driver\n")));
        assertEquals("Private issuer", response.vendor());
        assertEquals("private.jdbc.Driver", response.driverClass());
        assertEquals("jdbc:private:", response.urlPrefix());
        assertEquals(DriverPackageStatus.PENDING_VALIDATION, response.status());
        verifyNoInteractions(validation);
    }

    @Test
    void discoversSpiClassForOmittedOrClearedClass() throws Exception {
        for (String requested : new String[] {null, "", "  "}) {
            var response = service.create(new CreateDriverRequest("Private JDBC", "Private", "1",
                requested, null), List.of(jar("private.jdbc.Driver\n")));
            assertEquals("private.jdbc.Driver", response.driverClass());
            assertNull(response.urlPrefix());
        }
    }

    @Test
    void discoversKingbaseSpiWithoutSubstitutingPostgresqlOrFilenameVersion() throws Exception {
        var response = service.create(new CreateDriverRequest("KingbaseES JDBC", "Kingbase",
            "V008R006B0001", "", "jdbc:kingbase8:"), List.of(jar("com.kingbase8.Driver\n")));
        assertEquals("com.kingbase8.Driver", response.driverClass());
        assertEquals("jdbc:kingbase8:", response.urlPrefix());
        assertEquals("V008R006B0001", response.version());
    }

    @Test
    void returnsCandidatesAndAcceptsExplicitSelectionOnRetry() throws Exception {
        List<MultipartFile> files = List.of(jar("a.Driver\nb.Driver\n"));
        DriverException error = assertThrows(DriverException.class,
            () -> service.create(new CreateDriverRequest("Private JDBC", "Private", "1", "",
                null), files));
        assertEquals("DRIVER_CLASS_AMBIGUOUS", error.code());
        assertEquals(List.of("a.Driver", "b.Driver"), error.properties().get("candidates"));
        var response = service.create(new CreateDriverRequest("Private JDBC", "Private", "1",
            "b.Driver", null), files);
        assertEquals("b.Driver", response.driverClass());
    }

    @Test
    void absentSpiRequiresExplicitClassWithoutPresetFallback() throws Exception {
        DriverException error = assertThrows(DriverException.class,
            () -> service.create(new CreateDriverRequest("Private JDBC", "Private", "1", null,
                null), List.of(jar(null))));
        assertEquals("DRIVER_INVALID", error.code());
        verifyNoInteractions(packages, validation);
    }

    private static MockMultipartFile jar(String spi) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            if (spi != null) {
                jar.putNextEntry(new JarEntry("META-INF/services/java.sql.Driver"));
                jar.write(spi.getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
        return new MockMultipartFile("files", "driver.jar", "application/java-archive",
            bytes.toByteArray());
    }
}
