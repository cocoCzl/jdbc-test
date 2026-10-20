package com.jdbctest.catalog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Portable, bounded data-only package. Resource bytes and declaration are frozen together. */
public record AdapterPackageSnapshot(String manifest, Map<String, String> resources,
    String fingerprint) {

    private static final int MAX_FILE = 1_048_576;
    private static final int MAX_TOTAL = 8 * MAX_FILE;
    private static final int MAX_FILES = 512;

    public AdapterPackageSnapshot {
        resources = new TreeMap<>(resources);
        var definition = parse(manifest);
        if (resources.size() + 1 > MAX_FILES) {
            throw new IllegalArgumentException("ADAPTER_PACKAGE_TOO_MANY_FILES");
        }
        int total = manifest.getBytes(StandardCharsets.UTF_8).length;
        for (var entry : resources.entrySet()) {
            validatePath(entry.getKey());
            boolean allowed = entry.getKey().startsWith(definition.assets().ddl() + "/")
                || entry.getKey().startsWith(definition.assets().dml() + "/")
                || entry.getKey().startsWith(definition.assets().pool() + "/")
                || entry.getKey().startsWith("evidence/");
            if (!allowed || entry.getKey().matches("(?i).*\\.(jar|class|exe|so|dll|sh)$")) {
                throw new IllegalArgumentException("ADAPTER_RESOURCE_BOUNDARY: " + entry.getKey());
            }
            String parent = entry.getKey();
            while (parent.contains("/")) {
                parent = parent.substring(0, parent.lastIndexOf('/'));
                if (resources.containsKey(parent)) {
                    throw new IllegalArgumentException("ADAPTER_RESOURCE_PATH_COLLISION: " + parent);
                }
            }
            if (entry.getValue().length() > ((MAX_FILE + 2) / 3) * 4) {
                throw new IllegalArgumentException("ADAPTER_PACKAGE_TOO_LARGE");
            }
            byte[] bytes = Base64.getDecoder().decode(entry.getValue());
            entry.setValue(Base64.getEncoder().encodeToString(bytes));
            total += bytes.length;
            if (bytes.length > MAX_FILE || total > MAX_TOTAL) {
                throw new IllegalArgumentException("ADAPTER_PACKAGE_TOO_LARGE");
            }
        }
        for (String area : definition.assets().areas()) {
            if ("sqlxml".equals(area) && !definition.capabilities().get("sqlxml")) {
                continue;
            }
            required(resources, definition.assets().ddl() + "/" + definition.assets().directory()
                + "/" + area + "_ddl.sql");
            required(resources, definition.assets().dml() + "/" + definition.assets().directory()
                + "/" + area + "_dml.sql");
        }
        required(resources, definition.assets().pool() + "/" + definition.assets().directory()
            + ".yaml");
        if ("official".equals(definition.trust())) {
            for (Object item : (java.util.List<?>) definition.declaration()
                .get("validated_combinations")) {
                required(resources, (String) ((Map<?, ?>) item).get("evidence"));
            }
        }
        resources = Map.copyOf(resources);
        String actual = digest(manifest, resources);
        if (fingerprint != null && !fingerprint.equals(actual)) {
            throw new IllegalArgumentException("ADAPTER_FINGERPRINT_MISMATCH");
        }
        fingerprint = actual;
    }

    public AdapterDefinition definition() {
        return parse(manifest);
    }

    public static AdapterPackageSnapshot readZip(InputStream input) throws IOException {
        Map<String, String> files = DataPackageArchive.read(input);
        required(files, "adapter.json");
        byte[] manifestBytes = Base64.getDecoder().decode(files.remove("adapter.json"));
        // AdapterManifest performs strict UTF-8 validation before conversion to a String.
        AdapterManifest.read(new ByteArrayInputStream(manifestBytes));
        return new AdapterPackageSnapshot(new String(manifestBytes, StandardCharsets.UTF_8), files,
            null);
    }

    public void materialize(Path target) throws IOException {
        Files.createDirectories(target);
        for (var entry : resources.entrySet()) {
            Path path = target.resolve(entry.getKey()).normalize();
            if (!path.startsWith(target.normalize())) {
                throw new IllegalArgumentException("ADAPTER_RESOURCE_PATH_INVALID");
            }
            Files.createDirectories(path.getParent());
            Files.write(path, Base64.getDecoder().decode(entry.getValue()),
                java.nio.file.StandardOpenOption.CREATE_NEW);
        }
    }

    private static AdapterDefinition parse(String manifest) {
        try {
            return AdapterManifest.read(new ByteArrayInputStream(
                manifest.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException exception) {
            throw new IllegalArgumentException("ADAPTER_JSON_INVALID", exception);
        }
    }

    private static void validatePath(String path) {
        DataPackageArchive.validatePath(path);
    }

    private static void required(Map<String, String> files, String path) {
        if (!files.containsKey(path)) {
            throw new IllegalArgumentException("ADAPTER_RESOURCE_MISSING: " + path);
        }
    }

    private static String digest(String manifest, Map<String, String> resources) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(manifest.getBytes(StandardCharsets.UTF_8));
            new TreeMap<>(resources).forEach((name, value) -> {
                digest.update(("\n" + name + "\n" + value + "\n").getBytes(StandardCharsets.UTF_8));
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
