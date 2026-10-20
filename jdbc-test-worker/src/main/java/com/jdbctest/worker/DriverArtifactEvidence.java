package com.jdbctest.worker;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.sql.Driver;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

/** Identifies the actual defining JAR, independently of upload labels or dependency manifests. */
final class DriverArtifactEvidence {

    private DriverArtifactEvidence() {
    }

    static Map<String, Object> read(Driver driver, List<String> artifacts) throws Exception {
        var source = driver.getClass().getProtectionDomain().getCodeSource();
        if (source == null || !"file".equals(source.getLocation().getProtocol())) {
            throw new IllegalArgumentException("Driver defining artifact is unavailable");
        }
        Path actual = Path.of(source.getLocation().toURI()).toRealPath();
        boolean selected = false;
        for (String artifact : artifacts) {
            if (actual.equals(Path.of(artifact).toRealPath())) {
                selected = true;
            }
        }
        if (!selected || !Files.isRegularFile(actual)) {
            throw new IllegalArgumentException("Driver was not loaded from a selected artifact");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = new DigestInputStream(Files.newInputStream(actual), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("fileName", actual.getFileName().toString());
        evidence.put("sha256", HexFormat.of().formatHex(digest.digest()));
        try (var jar = new JarFile(actual.toFile())) {
            var manifest = jar.getManifest();
            Attributes main = manifest == null ? null : manifest.getMainAttributes();
            Attributes section = manifest == null ? null : manifest.getAttributes(
                driver.getClass().getPackageName().replace('.', '/') + "/");
            String implementation = value(section, "Implementation-Version");
            evidence.put("implementationVersion", implementation == null
                ? value(main, "Implementation-Version") : implementation);
            evidence.put("bundleVersion", value(main, "Bundle-Version"));
        }
        return evidence;
    }

    private static String value(Attributes attributes, String name) {
        String value = attributes == null ? null : attributes.getValue(name);
        return value == null || value.isBlank() ? null : value;
    }
}
