package com.jdbctest.server.deploy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * Literal dotenv framing; quoted strings use Jackson's JSON codec, never Shell evaluation.
 */
final class DotenvConfiguration {

    static final String HEADER = "# jdbc-test literal dotenv v1\n";
    private static final ObjectMapper JSON = tools.jackson.databind.json.JsonMapper.builder()
        .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    final Path file;
    final byte[] original;
    final Map<String, String> values;

    private DotenvConfiguration(Path file, byte[] original, Map<String, String> values) {
        this.file = file;
        this.original = original;
        this.values = values;
    }

    static DotenvConfiguration read(Path file) throws Exception {
        DeploymentTool.assertPrivate(file);
        byte[] original = Files.readAllBytes(file);
        String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(original))
            .toString();
        boolean legacyCompose = text.startsWith("# Compose dotenv only;");
        var values = new LinkedHashMap<String, String>();
        int number = 0;
        for (String line : text.split("\\r?\\n", -1)) {
            number++;
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator < 1) {
                throw new DeploymentTool.SetupException(
                    "Invalid .env assignment at line " + number + "; values are not displayed.");
            }
            String name = line.substring(0, separator).strip();
            if (!name.matches("[A-Z][A-Z0-9_]*") || values.containsKey(name)) {
                throw new DeploymentTool.SetupException(
                    "Invalid/duplicate .env name at line " + number
                        + "; values are not displayed.");
            }
            String value = line.substring(separator + 1);
            if (value.startsWith("\"")) {
                try {
                    value = JSON.readValue(value, String.class);
                } catch (Exception exception) {
                    throw new DeploymentTool.SetupException(
                        "Invalid quoted .env value at line " + number
                            + "; use JSON escapes or hidden --configure input. No value was displayed.");
                }
            } else if (value.startsWith("'")) {
                if (value.length() < 2 || !value.endsWith("'")) {
                    throw new DeploymentTool.SetupException(
                        "Unclosed single-quoted .env value at line " + number + ".");
                }
                value = value.substring(1, value.length() - 1);
            }
            if (legacyCompose) {
                value = value.replace("$$", "$");
            }
            if (!value.isEmpty()) {
                DeploymentTool.validateText(value);
            }
            values.put(name, value);
        }
        return new DotenvConfiguration(file, original, values);
    }

    /**
     * A copied example inherits platform defaults; secure the owner's local file before reading
     * secrets.
     */
    static void protectLocalFile(Path file) throws Exception {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            DeploymentTool.fail(".env must be a regular local file, not a symlink.");
        }
        var currentOwner = file.getFileSystem().getUserPrincipalLookupService()
            .lookupPrincipalByName(System.getProperty("user.name"));
        if (!Files.getOwner(file, LinkOption.NOFOLLOW_LINKS).equals(currentOwner)) {
            DeploymentTool.fail(
                ".env must belong to the account running this entry; its content was not read.");
        }
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(file,
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } else {
            DeploymentTool.restrictAcl(file);
        }
        DeploymentTool.assertPrivate(file);
    }

    String get(String name, String fallback) {
        String value = values.get(name);
        return value == null || value.isEmpty() ? fallback : value;
    }

    boolean needsDockerSecrets(boolean bundled) {
        return get("JDBC_TEST_MASTER_KEY", "").isEmpty() || (bundled && get("POSTGRES_PASSWORD",
            "").isEmpty());
    }

    String required(String name) throws Exception {
        String value = get(name, "");
        if (value.isEmpty()) {
            DeploymentTool.fail(
                "Fill " + name + " in .env, then rerun the same command. No secret was changed.");
        }
        return value;
    }

    static String encode(Map<String, String> values) throws Exception {
        StringBuilder content = new StringBuilder(HEADER);
        for (var entry : values.entrySet()) {
            content.append(entry.getKey()).append('=')
                .append(JSON.writeValueAsString(entry.getValue())).append('\n');
        }
        return content.toString();
    }

    /**
     * Called only while the shared initialization directory lock is held.
     */
    void update(Map<String, String> additions) throws Exception {
        DeploymentTool.assertPrivate(file);
        if (!java.util.Arrays.equals(original, Files.readAllBytes(file))) {
            DeploymentTool.fail(
                ".env changed during preparation. No configuration was replaced; rerun after finishing your edit.");
        }
        var merged = new LinkedHashMap<>(values);
        merged.putAll(additions);
        String content = encode(merged);
        Path temporary = DeploymentTool.privateTemp(file.getParent());
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try (var channel = java.nio.channels.FileChannel.open(temporary,
                java.nio.file.StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            if (!java.util.Arrays.equals(original, Files.readAllBytes(file))) {
                DeploymentTool.fail(
                    ".env changed during preparation; no configuration was replaced.");
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    void ensureKey(Path data, boolean confirmedNew) throws Exception {
        if (!get("JDBC_TEST_MASTER_KEY", "").isEmpty()) {
            DeploymentTool.validateKey(get("JDBC_TEST_MASTER_KEY", ""));
            return;
        }
        Path lock = file.getParent().resolve(".jdbc-test-init.lock");
        try {
            Files.createDirectory(lock);
        } catch (Exception exception) {
            DeploymentTool.fail(
                "Initialization lock exists. Confirm no initializer is running before recovery; .env was not changed.");
        }
        try {
            // Re-read after taking the lock: a concurrent initializer's completed Key is never replaced.
            DotenvConfiguration current = read(file);
            if (!current.get("JDBC_TEST_MASTER_KEY", "").isEmpty()) {
                DeploymentTool.validateKey(current.get("JDBC_TEST_MASTER_KEY", ""));
                values.clear();
                values.putAll(current.values);
                return;
            }
            for (String legacy : new String[] {"config/application.properties",
                "config/master-key"}) {
                if (Files.exists(file.getParent().resolve(legacy), LinkOption.NOFOLLOW_LINKS)) {
                    DeploymentTool.fail(
                        "Existing legacy configuration found but .env has no Key. Restore the original Key or use ./jdbc-test start with the original properties/environment; no replacement was generated.");
                }
            }
            DeploymentTool.assertDataEmpty(data);
            DeploymentTool.assertDataEmpty(file.getParent().resolve("data"));
            String url = current.required("SPRING_DATASOURCE_URL");
            String user = current.required("SPRING_DATASOURCE_USERNAME");
            String password = current.required("SPRING_DATASOURCE_PASSWORD");
            DeploymentTool.validateConnection(url, user, password);
            try (var connection = DeploymentTool.connect(
                new DeploymentTool.Settings(url, user, password, ""))) {
                DeploymentTool.assertDatabaseEmpty(connection);
            }
            confirmNew(confirmedNew);
            String key = Base64.getEncoder().encodeToString(DeploymentTool.randomBytes());
            current.update(Map.of("JDBC_TEST_MASTER_KEY", key));
            values.put("JDBC_TEST_MASTER_KEY", key);
            System.out.println(
                "Saved independent Master Key in the existing .env (owner only). Database settings were preserved; secrets are not displayed.");
        } finally {
            Files.deleteIfExists(lock);
        }
    }

    static void confirmNew(boolean confirmed) throws Exception {
        if (!confirmed) {
            var console = System.console();
            if (console == null || !"NEW".equals(console.readLine(
                "For a confirmed NEW empty platform type NEW; for existing data/recovery press Enter: "))) {
                DeploymentTool.fail(
                    "Preparation cancelled. Restore the original Key for recovery, or confirm a new empty platform. .env was not changed.");
            }
        }
    }
}
