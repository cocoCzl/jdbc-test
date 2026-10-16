package com.jdbctest.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.json.JsonFactory;

class AdapterManifestTest {

    private String fixture(String name) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/adapter-contract/" + name)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private AdapterDefinition parse(String text) throws Exception {
        return AdapterManifest.read(
            new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void sharedJavaPythonMetadataContract() throws Exception {
        List<?> cases = (List<?>) new Yaml().load(fixture("cases.json"));
        for (Object item : cases) {
            Map<?, ?> test = (Map<?, ?>) item;
            Map<String, Object> manifest = AdapterManifest.object(
                new Yaml().load(fixture("base.json")), "fixture");
            if (test.get("updates") instanceof List<?> updates) {
                for (Object update : updates) {
                    Map<?, ?> entry = (Map<?, ?>) update;
                    List<?> path = (List<?>) entry.get("path");
                    mutableParent(manifest, path).put(path.getLast().toString(),
                        entry.get("value"));
                }
            }
            if (test.get("remove") instanceof List<?> removals) {
                for (Object removal : removals) {
                    List<?> path = (List<?>) removal;
                    mutableParent(manifest, path).remove(path.getLast().toString());
                }
            }
            String json = test.containsKey("raw") ? test.get("raw").toString() : json(manifest);
            if (Boolean.TRUE.equals(test.get("valid"))) {
                assertNotNull(parse(json), test.get("name").toString());
            } else {
                assertThrows(IllegalArgumentException.class, () -> parse(json),
                    test.get("name").toString());
            }
        }
    }

    private Map<String, Object> mutableParent(Map<String, Object> root, List<?> path) {
        Map<String, Object> result = root;
        for (int index = 0; index < path.size() - 1; index++) {
            String key = path.get(index).toString();
            Map<String, Object> child = AdapterManifest.object(result.get(key), "fixture");
            result.put(key, child);
            result = child;
        }
        return result;
    }

    private String json(Object value) {
        StringWriter writer = new StringWriter();
        try (JsonGenerator generator = new JsonFactory().createGenerator(ObjectWriteContext.empty(),
            writer)) {
            writeJson(generator, value);
        }
        return writer.toString();
    }

    private void writeJson(JsonGenerator generator, Object value) {
        if (value instanceof Map<?, ?> fields) {
            generator.writeStartObject();
            fields.forEach((key, item) -> {
                generator.writeName(key.toString());
                writeJson(generator, item);
            });
            generator.writeEndObject();
        } else if (value instanceof List<?> list) {
            generator.writeStartArray();
            list.forEach(item -> writeJson(generator, item));
            generator.writeEndArray();
        } else if (value instanceof Boolean flag) {
            generator.writeBoolean(flag);
        } else if (value instanceof Number number) {
            generator.writeNumber(number.toString());
        } else if (value == null) {
            generator.writeNull();
        } else {
            generator.writeString(value.toString());
        }
    }

    @Test
    void registryUsesOneImmutableDefinitionAndPreservesClassification() throws Exception {
        AdapterRegistry registry = AdapterRegistry.bundled();
        assertSame(registry, AdapterRegistry.bundled());
        assertSame(registry.required("mysql"), registry.required("mysql"));
        for (DbType type : DbType.values()) {
            assertEquals(type, DbType.fromCode(type.code()).orElseThrow());
            assertEquals(type, registry.required(type.code()).dbType().orElseThrow());
        }
        assertTrue(DbType.fromCode("private-db").isEmpty());
        AdapterDefinition gauss = registry.required("gaussdb");
        assertEquals(SqlDialect.POSTGRESQL, gauss.dialect());
        assertEquals("gaussdb", gauss.assets().directory());
        assertTrue(
            gauss.metadataConstraints().get("expected_database_product_regex").contains("GaussDB"));
        assertThrows(UnsupportedOperationException.class, () -> registry.list().clear());
        AdapterDefinition definition = parse(fixture("base.json"));
        Map<?, ?> assets = (Map<?, ?>) definition.declaration().get("assets");
        assertThrows(UnsupportedOperationException.class, assets::clear);
        assertThrows(UnsupportedOperationException.class,
            () -> ((List<?>) assets.get("areas")).clear());
        assertThrows(UnsupportedOperationException.class, () -> definition.declaration().clear());
        assertThrows(UnsupportedOperationException.class, () -> definition.capabilities().clear());
    }

    @Test
    void badIndexesAndUnregisteredCustomProduct() throws Exception {
        String base = fixture("base.json");
        for (String index : List.of("null", "[]", "{}", "[\"../outside\"]", "[\"/absolute\"]",
            "[\"postgresql\",\"postgresql\"]", "[\"missing\"]")) {
            assertThrows(IllegalArgumentException.class,
                () -> registry(index, Map.of("postgresql", base)), index);
        }
        assertThrows(IllegalArgumentException.class, () -> registry(null, Map.of()));
        assertThrows(IllegalArgumentException.class,
            () -> registry("[\"custom-db\"]", Map.of("custom-db", base)));
        assertThrows(IllegalArgumentException.class, () -> registry("[\"postgresql\"]",
            Map.of("postgresql", base.replace("\"ddl\": \"ddl\"", "\"ddl\": \"other/ddl\""))));
        String custom = base.replace("\"id\": \"postgresql\"", "\"id\": \"custom-db\"");
        AdapterRegistry registry = registry("[\"custom-db\"]", Map.of("custom-db", custom));
        assertTrue(registry.contains("custom-db"));
        assertFalse(registry.contains("postgresql"));
        assertTrue(registry.required("custom-db").dbType().isEmpty());
        assertEquals(SqlDialect.POSTGRESQL, registry.required("custom-db").dialect());
        assertThrows(IllegalArgumentException.class, () -> registry.required("unknown"));
    }

    @Test
    void inputLimitsAndImmutableExtensions() throws Exception {
        for (String text : List.of("{\"x\":" + "9".repeat(129) + "}",
            "[".repeat(65) + "0" + "]".repeat(65), "\"" + "x".repeat(1_048_576) + "\"",
            "{\"x\":1e309}", "{\"x\":true,\"x\":false}", "{\"x\":true,}",
            "{/* comment */\"x\":1}", "{'x':1}", "{} {}", "\uFEFF{}")) {
            assertThrows(IllegalArgumentException.class,
                () -> AdapterJson.read(
                    new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))));
        }
        assertThrows(IllegalArgumentException.class,
            () -> AdapterManifest.read(
                new ByteArrayInputStream(new byte[] {(byte) 255, '{', '}'})));
        AdapterDefinition definition = parse(fixture("base.json").replace("\"id\": \"postgresql\"",
            "\"vendor_options\":{\"items\":[null,{\"value\":12.5}]},\"id\":\"postgresql\""));
        Map<?, ?> extension = (Map<?, ?>) definition.declaration().get("vendor_options");
        List<?> items = (List<?>) extension.get("items");
        assertEquals(null, items.getFirst());
        assertThrows(UnsupportedOperationException.class, extension::clear);
        assertThrows(UnsupportedOperationException.class, items::clear);
        assertThrows(UnsupportedOperationException.class, ((Map<?, ?>) items.getLast())::clear);
    }

    private AdapterRegistry registry(String index, Map<String, String> manifests) {
        Map<String, String> resources = new HashMap<>();
        if (index != null) {
            resources.put("adapters/catalog.json", index);
        }
        manifests.forEach(
            (id, manifest) -> resources.put("adapters/" + id + "/adapter.json", manifest));
        return AdapterRegistry.load(new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                String value = resources.get(name);
                return value == null ? null
                    : new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
            }
        });
    }
}
