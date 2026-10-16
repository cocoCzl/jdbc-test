package com.jdbctest.server.deploy;

import java.io.Console;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Properties;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Offline checks and read-only database probes. Never runs application migrations.
 */
public final class DeploymentTool {

    @Configuration(proxyBeanMethods = false)
    static class CheckConfiguration {

    }

    record Settings(String url, String username, String password, String key) {

    }

    /**
     * Called only by the unified Docker entry after project/path/volume checks.
     */
    static void initializeDocker(String mode, String project, int port, boolean stateChecked)
        throws Exception {
        if (!stateChecked) {
            fail(
                "Use ./jdbc-test docker so actual Docker project/volume state is checked before initialization.");
        }
        if (!List.of("bundled", "external").contains(mode) || !project.matches(
            "[a-z0-9][a-z0-9_-]*")) {
            fail("Invalid Docker mode/project.");
        }
        if (port < 1 || port > 65535) {
            fail("HTTP port must be between 1 and 65535.");
        }
        Path root = Path.of(".").toAbsolutePath().normalize();
        Path target = root.resolve(".docker-launch.lock/platform.env");
        if (Files.exists(root.resolve(".env"), LinkOption.NOFOLLOW_LINKS) || Files.exists(target,
            LinkOption.NOFOLLOW_LINKS)) {
            fail(
                "Configuration already exists and was not changed. Restart uses the original password and Master Key.");
        }
        Path lock = root.resolve(".jdbc-test-init.lock");
        try {
            Files.createDirectory(lock);
        } catch (IOException exception) {
            fail("Initialization lock exists. Confirm no initializer is running before recovery.");
        }
        try {
            assertDataEmpty(root.resolve("data"));
            var values = new LinkedHashMap<String, String>();
            values.put("COMPOSE_PROJECT_NAME", project);
            if (mode.equals("bundled")) {
                values.put("POSTGRES_DB", "jdbc_test");
                values.put("POSTGRES_USER", "jdbc_test");
                values.put("POSTGRES_PASSWORD", HexFormat.of().formatHex(randomBytes()));
            } else {
                Console console = System.console();
                if (console == null) {
                    fail(
                        "Use a terminal for hidden input, or copy .env.example to .env and fill the three platform fields before running the unified Docker command.");
                }
                String url = console.readLine(
                    "Full platform JDBC URL (database name required; no user/password in URL): ");
                String username = console.readLine("Existing platform database username: ");
                char[] secret = console.readPassword("Existing database password (hidden): ");
                if (url == null || username == null || secret == null) {
                    fail("Initialization cancelled; no configuration was written.");
                }
                String password = new String(secret);
                java.util.Arrays.fill(secret, '\0');
                validateConnection(url, username, password);
                try (Connection connection = connect(new Settings(url, username, password, ""))) {
                    assertDatabaseEmpty(connection);
                }
                values.put("SPRING_DATASOURCE_URL", url);
                values.put("SPRING_DATASOURCE_USERNAME", username);
                values.put("SPRING_DATASOURCE_PASSWORD", password);
            }
            values.put("JDBC_TEST_MASTER_KEY", Base64.getEncoder().encodeToString(randomBytes()));
            values.put("SERVER_PORT", Integer.toString(port));
            publish(target, DotenvConfiguration.encode(values));
            System.out.println(
                "Configuration prepared (owner only); database password and independent Master Key are not displayed.");
        } finally {
            Files.deleteIfExists(lock);
        }
    }

    static String dotenv(String value) throws SetupException {
        validateText(value);
        // Compose double quotes support all quote/backslash boundaries. $$ suppresses
        // interpolation, including ${NAME}; this is dotenv syntax, never shell syntax.
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "$$") + "\"";
    }

    static void validateText(String value) throws SetupException {
        if (value == null || value.isEmpty() || value.codePoints()
            .anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029)) {
            fail(
                "Empty values, NUL, tabs, newlines and control characters are unsupported. Nothing was trimmed or written. A password file must have no trailing newline.");
        }
    }

    static void validateConnection(String url, String username, String password)
        throws SetupException {
        validateText(url);
        validateText(username);
        validateText(password);
        if (username.contains("${")) {
            fail(
                "Placeholder syntax is unsupported in database usernames. Passwords remain literal and support ${NAME}.");
        }
        if (!url.matches("jdbc:postgresql://[^/?#@]+/[^?/#]+(\\?[^#]*)?")
            || url.matches("(?i).*[?&](user|password)=.*") || url.contains("${")) {
            fail(
                "Use jdbc:postgresql://HOST:PORT/DATABASE (optional SSL parameters). Database name is required; supply user/password separately, never embed credentials in the URL.");
        }
        if (url.contains("?")) {
            for (String parameter : url.split("\\?", 2)[1].split("&")) {
                String name;
                try {
                    name = java.net.URLDecoder.decode(parameter.split("=", 2)[0],
                        StandardCharsets.UTF_8);
                } catch (IllegalArgumentException exception) {
                    fail(
                        "Invalid JDBC URL percent encoding. Provide a complete JDBC URL without embedded credentials.");
                    return;
                }
                if (List.of("user", "password", "passwd", "pwd", "sslpassword", "token", "secret")
                    .contains(name.toLowerCase(java.util.Locale.ROOT))) {
                    fail(
                        "Do not embed usernames, passwords or secret tokens in the JDBC URL. Supply the existing username/password separately.");
                }
            }
        }
    }

    static void validateKey(String key) throws SetupException {
        try {
            if (Base64.getDecoder().decode(key).length == 32) {
                return;
            }
        } catch (IllegalArgumentException ignored) {
        }
        fail(
            "JDBC_TEST_MASTER_KEY / jdbc-test.security.master-key must be Base64 for exactly 32 bytes. Restore the original Key for an existing platform; do not generate a replacement.");
    }

    static byte[] randomBytes() throws Exception {
        byte[] bytes = new byte[32];
        SecureRandom.getInstanceStrong().nextBytes(bytes);
        return bytes;
    }

    static Connection connect(Settings settings) throws SetupException {
        var properties = new Properties();
        properties.setProperty("user", settings.username());
        properties.setProperty("password", settings.password());
        properties.setProperty("connectTimeout", "10");
        properties.setProperty("socketTimeout", "10");
        try {
            Connection connection = DriverManager.getConnection(settings.url(), properties);
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            return connection;
        } catch (SQLException exception) {
            fail(
                "Read-only platform database connection failed. Check the JDBC host/port/database, network/SSL settings and existing username/password. No migration or password reset was performed.");
            return null;
        }
    }

    static void assertDatabaseEmpty(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var tables = statement.executeQuery(
            "SELECT 1 FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
                + "WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema' AND c.relkind IN ('r','p','v','m','S','f') LIMIT 1")) {
            if (tables.next()) {
                fail(
                    "Platform database already contains tables/views/sequences. Restore its original configuration and Master Key, or choose a dedicated empty database. No new Key was generated.");
            }
        }
    }

    static void assertDataEmpty(Path path) throws Exception {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(path) || !Files.isDirectory(path)) {
                fail(
                    "Existing application data path found. Restore the original configuration and Master Key.");
            }
            try (var contents = Files.list(path)) {
                if (contents.findAny().isPresent()) {
                    fail(
                        "Existing application data found. Restore the original configuration and Master Key; initialization will not overwrite them.");
                }
            }
        }
    }

    static void publish(Path target, String content) throws Exception {
        if (Files.isSymbolicLink(target.getParent())) {
            fail(
                "Configuration directory is a symlink. Choose a local deployment directory and preserve the original configuration for recovery.");
        }
        Files.createDirectories(target.getParent());
        Path temporary = privateTemp(target.getParent());
        try {
            assertPrivate(
                temporary); // Fail before secret bytes if the OS retained broad inherited ACLs.
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try (var channel = java.nio.channels.FileChannel.open(temporary,
                java.nio.file.StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            // A same-filesystem hard link publishes the complete file atomically, with
            // CREATE_NEW semantics. Neither concurrent writers nor symlinks are replaced.
            try {
                Files.createLink(target, temporary);
            } catch (java.nio.file.FileAlreadyExistsException exception) {
                fail(
                    "Configuration appeared during initialization and was not overwritten. Use check/verify or restore its original Key.");
            } catch (IOException | UnsupportedOperationException exception) {
                fail(
                    "Atomic configuration publication failed. Use a local filesystem supporting hard links and owner-only permissions; no existing configuration was overwritten.");
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static Path privateTemp(Path directory) throws Exception {
        Path file;
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            file = Files.createTempFile(directory, ".config-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            // Create inside an owner-only directory first, before writing secret bytes.
            Path privateDirectory = Files.createTempDirectory(directory, ".config-private-");
            restrictAcl(privateDirectory);
            file = privateDirectory.resolve("config.tmp");
            Files.createFile(file);
            restrictAcl(file);
            // publish() cleans the temporary file; directory cleanup is done below.
            file.toFile().deleteOnExit();
            privateDirectory.toFile().deleteOnExit();
        }
        return file;
    }

    static void restrictAcl(Path path) throws Exception {
        var view = Files.getFileAttributeView(path, AclFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            fail(
                "Filesystem cannot enforce owner-only configuration permissions. Choose a protected local filesystem.");
        }
        view.setAcl(
            List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(view.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
    }

    static void assertPrivate(Path path) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            fail("Password input must be a regular owner-only file, not a symlink.");
        }
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            if (Files.getPosixFilePermissions(path).stream()
                .anyMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_"))) {
                fail(
                    "Password input file must be owner-only (chmod 600). Its contents were not read.");
            }
        } else {
            var view = Files.getFileAttributeView(path, AclFileAttributeView.class);
            var owner = view == null ? null : view.getOwner();
            if (view == null || view.getAcl().stream().anyMatch(
                entry -> entry.type() == AclEntryType.ALLOW && !entry.principal().equals(owner))) {
                fail(
                    "Password input file ACL must allow only its owner. Its contents were not read.");
            }
        }
    }

    static void checkNative(List<String> arguments) throws Exception {
        if (arguments.stream().anyMatch(
            arg -> arg.startsWith("--spring.datasource.password") || arg.startsWith(
                "--jdbc-test.security.master-key") || arg.startsWith(
                "--spring.application.json"))) {
            fail(
                "Do not pass passwords or Master Keys as command-line arguments. Use a protected file or environment.");
        }
        var app = new SpringApplication(CheckConfiguration.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        app.setLogStartupInfo(false);
        var command = new ArrayList<>(arguments);
        command.add("--logging.level.root=OFF");
        try (var context = app.run(command.toArray(String[]::new))) {
            ConfigurableEnvironment environment = context.getEnvironment();
            var settings = new Settings(required(environment, "spring.datasource.url"),
                required(environment, "spring.datasource.username"),
                required(environment, "spring.datasource.password"),
                required(environment, "jdbc-test.security.master-key"));
            validateConnection(settings.url(), settings.username(), settings.password());
            validateKey(settings.key());
            if (environment.getProperty("deployment.check-port", Boolean.class, false)) {
                try (var socket = new ServerSocket()) {
                    // Match the web server: recent TIME_WAIT connections are compatible
                    // with restart, but an actively listening process still blocks binding.
                    socket.setReuseAddress(true);
                    socket.bind(new InetSocketAddress(
                        environment.getProperty("server.address", "127.0.0.1"),
                        environment.getProperty("server.port", Integer.class, 8080)));
                } catch (IOException exception) {
                    fail(
                        "HTTP listen address/port is unavailable or already in use. Choose another JDBC_TEST_PORT / server.port, or stop the conflicting process.");
                }
            }
            summary(settings);
            for (String name : List.of("spring.datasource.url", "spring.datasource.username",
                "spring.datasource.password", "jdbc-test.security.master-key")) {
                for (var source : environment.getPropertySources()) {
                    if (!source.getName().equals("configurationProperties")
                        && source.containsProperty(name)) {
                        System.out.println(name + " source: " + source.getName());
                        break;
                    }
                }
            }
        }
    }

    private static String required(ConfigurableEnvironment environment, String name)
        throws SetupException {
        try {
            String value = environment.getProperty(name);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        } catch (Exception ignored) {
        }
        fail("Missing/invalid " + name
            + ". Supply a complete file or environment configuration; for recovery restore the original Master Key.");
        return null;
    }

    private static void summary(Settings settings) {
        System.out.println(
            "Static configuration valid. Platform JDBC URL/user configured; database password and 32-byte Master Key configured (redacted). This does not prove connectivity or readiness.");
        System.out.println("Platform database endpoint: " + settings.url().split("\\?", 2)[0]
            + " (query parameters omitted)");
    }

    static void fail(String message) throws SetupException {
        throw new SetupException(message);
    }

    static final class SetupException extends Exception {

        SetupException(String message) {
            super(message);
        }
    }
}
