package com.jdbctest.server.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jdbctest.catalog.AdapterCatalog;
import com.jdbctest.catalog.AdapterManifest;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.ObjectMapper;

class AdapterControllerTest {

    @Test
    void kingbasePublishesOfficialStatusAndOnlyItsObservedCombination() {
        var response = new AdapterService(org.mockito.Mockito.mock(ImportedAdapterRepository.class),
            org.mockito.Mockito.mock(DatabaseConnectionProfileRepository.class), new ObjectMapper()).list().stream()
            .filter(adapter -> "kingbase".equals(adapter.id())).findFirst().orElseThrow();
        assertEquals("OFFICIAL", response.status());
        assertEquals("com.kingbase8.Driver", response.driverClass());
        assertEquals("adapter.kingbaseQualification", response.qualificationNoteKey());
        assertEquals(List.of(new AdapterResponse.ValidatedCombination("12.1", "V008R006B0001")),
            response.validatedCombinations());
    }

    @Test
    void publicHintsExcludePrivateSourcePathsExtensionsAndEvidenceContents() throws Exception {
        String manifest;
        try (InputStream input = getClass().getResourceAsStream(
            "/adapters/postgresql/adapter.json")) {
            manifest = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                .replace("\"kind\": \"maven\"", "\"kind\": \"local\"")
                .replace("\"coordinate\"", "\"path\"")
                .replace("org.postgresql:postgresql:42.7.2", "/private/fixture-only/driver.jar")
                .replaceFirst("\\{", "{\"private_extension\":\"private-fixture-value\",");
        }
        var definition = AdapterManifest.read(new ByteArrayInputStream(
            manifest.getBytes(StandardCharsets.UTF_8)));
        var mapper = new ObjectMapper();
        String body = mapper.writeValueAsString(AdapterResponse.from(definition));
        assertEquals("org.postgresql.Driver", mapper.readTree(body).path("driverClass").asString());
        assertFalse(body.contains("/private/"));
        assertFalse(body.contains("private-fixture-value"));
        assertFalse(body.contains("evidence/"));
        assertTrue(mapper.readTree(body).path("recommendedMavenCoordinate").isNull());
    }

    @Test
    void indexedCustomAdapterLoadsWithoutProductOrDriverClasspathChanges() throws Exception {
        String manifest;
        try (InputStream input = getClass().getResourceAsStream(
            "/adapters/postgresql/adapter.json")) {
            manifest = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                .replace("\"id\": \"postgresql\"", "\"id\": \"private-db\"")
                .replace("\"assets\": {", "\"assets\": {\"directory\":\"postgresql\",");
        }
        String customManifest = manifest;
        URL[] urls = {
            AdapterCatalog.class.getProtectionDomain().getCodeSource().getLocation(),
            AdapterController.class.getProtectionDomain().getCodeSource().getLocation(),
            JsonFactory.class.getProtectionDomain().getCodeSource().getLocation()
        };
        try (URLClassLoader isolated = new URLClassLoader(urls,
            ClassLoader.getPlatformClassLoader()) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if ("adapters/catalog.json".equals(name)) {
                    return new ByteArrayInputStream(
                        "[\"private-db\"]".getBytes(StandardCharsets.UTF_8));
                }
                if ("adapters/private-db/adapter.json".equals(name)) {
                    return new ByteArrayInputStream(
                        customManifest.getBytes(StandardCharsets.UTF_8));
                }
                return super.getResourceAsStream(name);
            }
        }) {
            Class<?> registryType = isolated.loadClass("com.jdbctest.catalog.AdapterRegistry");
            Object registry = registryType.getMethod("bundled").invoke(null);
            List<?> adapters = (List<?>) registryType.getMethod("list").invoke(registry);
            assertEquals(1, adapters.size());
            assertEquals("private-db",
                adapters.getFirst().getClass().getMethod("id").invoke(adapters.getFirst()));
            assertThrows(ClassNotFoundException.class,
                () -> isolated.loadClass("org.postgresql.Driver"));
            assertThrows(ClassNotFoundException.class,
                () -> isolated.loadClass("com.jdbctest.extension.JdbcContext"));
            assertTrue(AdapterCatalog.contains("postgresql"));
        }
    }
}
