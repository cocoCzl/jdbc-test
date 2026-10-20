package com.jdbctest.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipInputStream;

/** Shared bounded, portable data archive boundary for adapter and SQL packages. */
public final class DataPackageArchive {

    private static final int MAX_FILE = 1_048_576;
    private static final int MAX_TOTAL = 8 * MAX_FILE;
    private static final int MAX_FILES = 512;

    private DataPackageArchive() {
    }

    public static Map<String, String> read(InputStream input) throws IOException {
        Map<String, String> files = new TreeMap<>();
        int total = 0;
        try (ZipInputStream zip = new ZipInputStream(input, StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            int count = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > MAX_FILES) {
                    throw new IllegalArgumentException("ADAPTER_PACKAGE_TOO_MANY_FILES");
                }
                String name = entry.getName();
                validatePath(entry.isDirectory() ? name.replaceAll("/$", "") : name);
                if (entry.isDirectory()) {
                    continue;
                }
                byte[] bytes = zip.readNBytes(MAX_FILE + 1);
                total += bytes.length;
                if (bytes.length > MAX_FILE || total > MAX_TOTAL) {
                    throw new IllegalArgumentException("ADAPTER_PACKAGE_TOO_LARGE");
                }
                if (files.putIfAbsent(name, Base64.getEncoder().encodeToString(bytes)) != null) {
                    throw new IllegalArgumentException("ADAPTER_RESOURCE_DUPLICATE: " + name);
                }
            }
        }
        return files;
    }

    public static void validatePath(String path) {
        if (!path.matches("[A-Za-z0-9_-]+(?:[./][A-Za-z0-9_-]+)*")
            || path.contains("..")) {
            throw new IllegalArgumentException("ADAPTER_RESOURCE_PATH_INVALID");
        }
    }
}
