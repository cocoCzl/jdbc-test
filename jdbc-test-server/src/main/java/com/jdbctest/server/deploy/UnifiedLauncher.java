package com.jdbctest.server.deploy;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small orchestration entry: existing Maven/npm/Compose/DeploymentTool remain the implementations.
 */
public final class UnifiedLauncher {

    private static final List<String> BACKEND = List.of("SPRING_DATASOURCE_URL",
        "SPRING_DATASOURCE_USERNAME",
        "SPRING_DATASOURCE_PASSWORD", "JDBC_TEST_MASTER_KEY", "JDBC_TEST_MAX_CONCURRENT_RUNS",
        "JDBC_TEST_RUN_TIMEOUT");
    private final Path root = Path.of(".").toAbsolutePath().normalize();
    private final Map<String, String> options = new LinkedHashMap<>();

    public static void main(String[] args) {
        try {
            new UnifiedLauncher().execute(args);
        } catch (Exception exception) {
            String message = exception instanceof DeploymentTool.SetupException
                || exception instanceof LaunchProcesses.Failure ? exception.getMessage()
                : "Launch stopped. Check dependencies, permissions and the redacted configuration; no fallback or destructive cleanup was performed.";
            System.err.println(message);
            System.exit(1);
        }
    }

    private void execute(String[] args) throws Exception {
        if (Runtime.version().feature() != 21) {
            DeploymentTool.fail("Java 21 is required.");
        }
        if (args.length == 0) {
            DeploymentTool.fail(
                "Use ./jdbc-test dev | build | docker --bundled-pg/--external-pg; bundle: ./jdbc-test start.");
        }
        if (args[0].equals("start") && !Files.exists(root.resolve(".env"),
            LinkOption.NOFOLLOW_LINKS)
            && (Files.isRegularFile(root.resolve("config/application.properties"))
            || System.getenv("SPRING_DATASOURCE_URL") != null)) {
            startLegacy(List.of(args).subList(1, args.length));
            return;
        }
        for (int index = 1; index < args.length; index++) {
            String key = args[index];
            if (!List.of("--debug", "--new-install", "--configure", "--port", "--web-port",
                "--output", "--mode", "--project", "--docker-state-checked", "--project-only",
                "--needs-key", "--compose-output", "--stop").contains(key) || options.containsKey(
                key)) {
                DeploymentTool.fail(
                    "Unknown/duplicate option. Secrets belong only in protected .env or hidden input, never arguments.");
            }
            String value = "true";
            if (List.of("--port", "--web-port", "--output", "--mode", "--project",
                "--compose-output").contains(key)) {
                if (++index >= args.length) {
                    DeploymentTool.fail("Missing option value.");
                }
                value = args[index];
            }
            options.put(key, value);
        }
        List<String> allowed = switch (args[0]) {
            case "dev" ->
                List.of("--debug", "--new-install", "--configure", "--port", "--web-port");
            case "start" -> List.of("--new-install", "--configure", "--port");
            case "build" -> List.of("--output");
            case "docker-info" -> List.of("--mode", "--project", "--project-only", "--needs-key");
            case "init-docker" ->
                List.of("--mode", "--project", "--new-install", "--port", "--docker-state-checked");
            case "prepare-docker" ->
                List.of("--mode", "--project", "--new-install", "--configure", "--port",
                    "--docker-state-checked", "--compose-output", "--stop");
            default -> List.of();
        };
        if (!allowed.containsAll(options.keySet())) {
            DeploymentTool.fail(
                "Option is not supported for this command. No configuration changed.");
        }
        switch (args[0]) {
            case "dev" -> start(true);
            case "start" -> start(false);
            case "build" -> build();
            case "docker-info" -> dockerInfo();
            case "init-docker" -> initializeDocker();
            case "prepare-docker" -> prepareDocker();
            default -> DeploymentTool.fail("Unsupported command.");
        }
    }

    private DotenvConfiguration configuration() throws Exception {
        Path file = root.resolve(".env");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            DeploymentTool.fail(
                "Missing .env. Copy .env.example to .env without replacing an existing file, fill its platform URL/username/password and rerun this command. No configuration was generated.");
        }
        if (!options.containsKey("--mode")) {
            DotenvConfiguration.protectLocalFile(file);
        }
        DotenvConfiguration config = DotenvConfiguration.read(file);
        if (options.containsKey("--configure")) {
            var console = System.console();
            if (console == null) {
                DeploymentTool.fail("Hidden --configure input requires a terminal.");
            }
            if (!config.get("SPRING_DATASOURCE_URL", "").isEmpty() || !config.get(
                "SPRING_DATASOURCE_USERNAME", "").isEmpty() || !config.get(
                "SPRING_DATASOURCE_PASSWORD", "").isEmpty()) {
                DeploymentTool.fail(
                    "--configure fills only an empty template. Edit existing .env locally; existing database settings are never overwritten automatically.");
            }
            String url = console.readLine(
                "Full platform JDBC URL (database name required; no user/password in URL): ");
            String user = console.readLine("Existing platform database username: ");
            char[] secret = console.readPassword("Existing database password (hidden): ");
            if (url == null || user == null || secret == null) {
                DeploymentTool.fail("Configuration cancelled.");
            }
            String password = new String(secret);
            java.util.Arrays.fill(secret, '\0');
            DeploymentTool.validateConnection(url, user, password);
            Path lock = root.resolve(".jdbc-test-init.lock");
            Files.createDirectory(lock);
            try {
                config.update(
                    Map.of("SPRING_DATASOURCE_URL", url, "SPRING_DATASOURCE_USERNAME", user,
                        "SPRING_DATASOURCE_PASSWORD", password));
            } finally {
                Files.deleteIfExists(lock);
            }
            config = DotenvConfiguration.read(file);
        }
        return config;
    }

    private int port(String value) throws Exception {
        try {
            int port = Integer.parseInt(value);
            if (port > 0 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException ignored) {
        }
        DeploymentTool.fail("Port must be an integer between 1 and 65535.");
        return 0;
    }

    private void checkPort(String host, int port) throws Exception {
        try (var socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(host, port));
        } catch (Exception exception) {
            DeploymentTool.fail("Port " + port
                + " is occupied/unavailable. Stop only your own conflicting service or choose another port in .env; no process was killed.");
        }
    }

    private Path data(DotenvConfiguration config) {
        return root.resolve(config.get("JDBC_TEST_DATA_DIRECTORY", "data")).normalize();
    }

    private void privateDirectory(Path path) throws Exception {
        for (Path ancestor = path; ancestor != null; ancestor = ancestor.getParent()) {
            if (Files.isSymbolicLink(ancestor)) {
                DeploymentTool.fail(
                    "Data directory must not use symlink paths; choose a protected local path.");
            }
        }
        Files.createDirectories(path);
        if (Files.isSymbolicLink(path)) {
            DeploymentTool.fail("Use a protected local data directory, not a symlink.");
        }
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        } else {
            DeploymentTool.restrictAcl(path);
        }
    }

    private void authenticate(DotenvConfiguration config) throws Exception {
        String url = config.required("SPRING_DATASOURCE_URL");
        String user = config.required("SPRING_DATASOURCE_USERNAME");
        String password = config.required("SPRING_DATASOURCE_PASSWORD");
        DeploymentTool.validateConnection(url, user, password);
        try (var connection = DeploymentTool.connect(
            new DeploymentTool.Settings(url, user, password, ""));
            var statement = connection.createStatement()) {
            statement.executeQuery("SELECT 1").close();
        }
    }

    private void start(boolean dev) throws Exception {
        DotenvConfiguration config = configuration();
        if (config.values.containsKey("POSTGRES_PASSWORD")) {
            DeploymentTool.fail(
                "This is bundled-PG configuration. Choose docker --bundled-pg; dev/start use an existing platform PG and never switch modes silently.");
        }
        int serverPort = port(options.getOrDefault("--port",
            config.get("SERVER_PORT", config.get("JDBC_TEST_PORT", "8080"))));
        int webPort = port(
            options.getOrDefault("--web-port", config.get("JDBC_TEST_WEB_PORT", "5173")));
        String address = config.get("SERVER_ADDRESS",
            config.get("JDBC_TEST_BIND_ADDRESS", "127.0.0.1"));
        checkPort(address, serverPort);
        if (dev) {
            checkPort("127.0.0.1", webPort);
        }
        if (options.containsKey("--debug")) {
            checkPort("127.0.0.1", 5005);
        }
        authenticate(config);
        config.ensureKey(data(config));
        privateDirectory(data(config));
        var backend = LaunchProcesses.basicEnvironment();
        for (String key : BACKEND) {
            if (config.values.containsKey(key)) {
                backend.put(key, config.values.get(key));
            }
        }
        backend.put("SERVER_ADDRESS", address);
        backend.put("SERVER_PORT", Integer.toString(serverPort));
        backend.put("JDBC_TEST_DATA_DIRECTORY", data(config).toString());
        Path worker = dev ? root.resolve(
            "jdbc-test-worker/target/jdbc-test-worker-" + System.getProperty("jdbc.test.version")
                + "-all.jar") : root.resolve("worker/jdbc-test-worker.jar");
        if (!Files.isRegularFile(worker)) {
            DeploymentTool.fail(
                "Current-version Worker is missing. Rebuild with ./jdbc-test dev/build; stale glob matches are never used.");
        }
        backend.put("JDBC_TEST_WORKER_EXECUTABLE", worker.toString());
        backend.put("SPRING_CONFIG_LOCATION", "classpath:/application.yml");
        if (!dev) {
            for (String artifact : List.of("server/jdbc-test-server.jar", "web/index.html")) {
                if (!Files.isRegularFile(root.resolve(artifact))) {
                    DeploymentTool.fail(
                        "Extract the complete deployment bundle: missing " + artifact);
                }
            }
            backend.put("SPRING_PROFILES_ACTIVE", "native");
            backend.put("JDBC_TEST_WEB_DIRECTORY", root.resolve("web").toString());
        }
        try (var processes = new LaunchProcesses()) {
            if (dev) {
                dependencies(processes);
            }
            Process server;
            if (dev) {
                String wrapper = root.resolve(
                        System.getProperty("os.name").startsWith("Windows") ? "mvnw.cmd" : "mvnw")
                    .toString();
                var command = new ArrayList<>(
                    LaunchProcesses.command(wrapper, "-pl", "jdbc-test-server", "spring-boot:run",
                        "-Dspring-boot.run.workingDirectory=" + root));
                if (options.containsKey("--debug")) {
                    command.add(
                        "-Dspring-boot.run.jvmArguments=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005");
                }
                server = processes.start(command, root, backend);
            } else {
                String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
                server = processes.start(
                    List.of(java, "-jar", root.resolve("server/jdbc-test-server.jar").toString()),
                    root, backend);
            }
            String connectHost =
                List.of("0.0.0.0", "::", "127.0.0.1", "localhost").contains(address) ? "127.0.0.1"
                    : address;
            String backendUrl =
                "http://" + (connectHost.contains(":") ? "[" + connectHost + "]" : connectHost)
                    + ":" + serverPort;
            ready(server, backendUrl + "/actuator/health", true);
            ready(server, backendUrl + "/api/test-catalog", false);
            Process frontend = null;
            if (dev) {
                var frontendEnvironment = LaunchProcesses.basicEnvironment();
                frontendEnvironment.put("JDBC_TEST_MANAGED_FRONTEND", "1");
                frontendEnvironment.put("JDBC_TEST_DEV_API_URL", backendUrl);
                frontend = processes.start(
                    LaunchProcesses.command(npm(), "run", "dev", "--", "--host", "127.0.0.1",
                        "--port", Integer.toString(webPort)), root.resolve("jdbc-test-web"),
                    frontendEnvironment);
                ready(frontend, "http://127.0.0.1:" + webPort + "/", false);
                ready(frontend, "http://127.0.0.1:" + webPort + "/api/test-catalog", false);
            }
            System.out.println(
                "Ready. Backend: " + backendUrl + " | Website: " + (dev ? "http://127.0.0.1:"
                    + webPort : backendUrl));
            System.out.println(
                "Ctrl+C stops only this launch's process tree. .env, Key, PG and data are retained.");
            while (server.isAlive() && (frontend == null || frontend.isAlive())) {
                Thread.sleep(100);
            }
            DeploymentTool.fail(
                "A started service exited. Both sides of this launch were stopped; configuration/data were retained.");
        }
    }

    private void startLegacy(List<String> arguments) throws Exception {
        for (String artifact : List.of("server/jdbc-test-server.jar", "worker/jdbc-test-worker.jar",
            "web/index.html")) {
            if (!Files.isRegularFile(root.resolve(artifact))) {
                DeploymentTool.fail("Extract the complete deployment bundle; missing " + artifact);
            }
        }
        var settings = new ArrayList<String>(List.of("--spring.profiles.active=native",
            "--spring.config.additional-location=optional:file:./config/application.properties"));
        settings.addAll(arguments);
        var checks = new ArrayList<>(settings);
        checks.add("--deployment.check-port=true");
        DeploymentTool.checkNative(checks);
        try (var processes = new LaunchProcesses()) {
            var command = new ArrayList<>(
                List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar",
                    root.resolve("server/jdbc-test-server.jar").toString()));
            command.addAll(settings);
            processes.run(command, root, new LinkedHashMap<>(System.getenv()));
        }
    }

    private void initializeDocker() throws Exception {
        DeploymentTool.initializeDocker(mode(), options.getOrDefault("--project", "jdbc-test"),
            port(options.getOrDefault("--port", "8080")),
            options.containsKey("--docker-state-checked"));
    }

    private void ready(Process process, String url, boolean health) throws Exception {
        var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2)).build();
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (System.nanoTime() < deadline && process.isAlive()) {
            try {
                var response = client.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET()
                        .build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && (!health || response.body()
                    .contains("\"UP\""))) {
                    return;
                }
            } catch (Exception ignored) {
            }
            Thread.sleep(250);
        }
        DeploymentTool.fail("Readiness failed at " + url
            + ". Check service messages/ports; this launch's children are stopped, data retained.");
    }

    private String npm() {
        return System.getProperty("os.name").startsWith("Windows") ? "npm.cmd" : "npm";
    }

    private void dependencies(LaunchProcesses processes) throws Exception {
        Path web = root.resolve("jdbc-test-web");
        Path marker = web.resolve("node_modules/.jdbc-test-dependencies");
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(web.resolve("package.json")));
        digest.update(Files.readAllBytes(web.resolve("package-lock.json")));
        digest.update(System.getProperty("jdbc.test.node", "")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String hash = HexFormat.of().formatHex(digest.digest());
        if (!Files.isRegularFile(web.resolve("node_modules/vite/bin/vite.js"))
            || !Files.isRegularFile(marker) || !hash.equals(Files.readString(marker))) {
            var env = LaunchProcesses.basicEnvironment();
            env.put("JDBC_TEST_MANAGED_FRONTEND", "1");
            processes.run(LaunchProcesses.command(npm(), "ci"), web, env);
            Files.writeString(marker, hash);
        }
    }

    private void build() throws Exception {
        try (var processes = new LaunchProcesses()) {
            dependencies(processes);
            var environment = LaunchProcesses.basicEnvironment();
            environment.put("JDBC_TEST_MANAGED_FRONTEND", "1");
            processes.run(LaunchProcesses.command(npm(), "run", "build"),
                root.resolve("jdbc-test-web"), environment);
            String python =
                System.getProperty("os.name").startsWith("Windows") ? "python" : "python3";
            processes.run(List.of(python, root.resolve("scripts/build_distribution.py").toString(),
                "--output", options.getOrDefault("--output", "dist")), root, environment);
        }
    }

    private String mode() throws Exception {
        String mode = options.get("--mode");
        if (!List.of("bundled", "external").contains(mode)) {
            DeploymentTool.fail("Select exactly one Docker mode: --bundled-pg or --external-pg.");
        }
        return mode;
    }

    private String project(DotenvConfiguration config) throws Exception {
        String project = options.getOrDefault("--project",
            config == null ? "jdbc-test" : config.get("COMPOSE_PROJECT_NAME", "jdbc-test"));
        if (!project.matches("[a-z0-9][a-z0-9_-]*")) {
            DeploymentTool.fail("Invalid Compose project name.");
        }
        if (config != null && options.containsKey("--project") && !project.equals(
            config.get("COMPOSE_PROJECT_NAME", project))) {
            DeploymentTool.fail(
                "Project differs from saved .env. Keep the original project for existing data; do not silently switch.");
        }
        return project;
    }

    private void checkMode(DotenvConfiguration config) throws Exception {
        boolean bundled =
            config.values.containsKey("POSTGRES_PASSWORD") || config.values.containsKey(
                "POSTGRES_DB");
        if (bundled != mode().equals("bundled")) {
            DeploymentTool.fail(
                "Docker mode conflicts with .env. Preserve it and choose its original mode; configuration was not converted.");
        }
    }

    private void dockerInfo() throws Exception {
        mode();
        DotenvConfiguration config = Files.exists(root.resolve(".env"), LinkOption.NOFOLLOW_LINKS)
            ? DotenvConfiguration.read(root.resolve(".env")) : null;
        if (config != null) {
            checkMode(config);
        }
        if (options.containsKey("--needs-key")) {
            System.out.println(
                config == null || config.needsDockerSecrets(mode().equals("bundled")) ? "yes"
                    : "no");
        } else {
            System.out.println(project(config));
        }
    }

    private void prepareDocker() throws Exception {
        String mode = mode();
        DotenvConfiguration config = configuration();
        checkMode(config);
        String project = project(config);
        if (mode.equals("external")) {
            if (!options.containsKey("--stop")) {
                authenticate(config);
            }
            if (config.get("JDBC_TEST_MASTER_KEY", "").isEmpty() && !options.containsKey(
                "--docker-state-checked")) {
                DeploymentTool.fail(
                    "Use the root Docker entry so actual project/path/volume state is checked first.");
            }
            if (!options.containsKey("--stop")) {
                config.ensureKey(data(config));
            }
        } else {
            if (config.get("JDBC_TEST_MASTER_KEY", "").isEmpty() || config.get("POSTGRES_PASSWORD",
                "").isEmpty()) {
                if (options.containsKey("--stop") || !options.containsKey(
                    "--docker-state-checked")) {
                    DeploymentTool.fail(
                        "Incomplete bundled configuration: restore original .env for an existing project.");
                }
                DeploymentTool.assertDataEmpty(data(config));
                DeploymentTool.assertDataEmpty(root.resolve("data"));
                Path lock = root.resolve(".jdbc-test-init.lock");
                Files.createDirectory(lock);
                try {
                    var additions = new LinkedHashMap<String, String>();
                    if (config.get("JDBC_TEST_MASTER_KEY", "").isEmpty()) {
                        additions.put("JDBC_TEST_MASTER_KEY",
                            Base64.getEncoder().encodeToString(DeploymentTool.randomBytes()));
                    }
                    if (config.get("POSTGRES_PASSWORD", "").isEmpty()) {
                        additions.put("POSTGRES_PASSWORD",
                            HexFormat.of().formatHex(DeploymentTool.randomBytes()));
                    }
                    config.update(additions);
                } finally {
                    Files.deleteIfExists(lock);
                }
                config = DotenvConfiguration.read(root.resolve(".env"));
            }
            DeploymentTool.validateKey(config.required("JDBC_TEST_MASTER_KEY"));
            config.required("POSTGRES_PASSWORD");
        }
        config = DotenvConfiguration.read(root.resolve(".env"));
        if (!config.values.containsKey("COMPOSE_PROJECT_NAME")) {
            Path lock = root.resolve(".jdbc-test-init.lock");
            Files.createDirectory(lock);
            try {
                config.update(Map.of("COMPOSE_PROJECT_NAME", project));
            } finally {
                Files.deleteIfExists(lock);
            }
            config = DotenvConfiguration.read(root.resolve(".env"));
        }
        DeploymentTool.validateKey(config.required("JDBC_TEST_MASTER_KEY"));
        var values = new LinkedHashMap<>(config.values);
        values.put("COMPOSE_PROJECT_NAME", project);
        if (options.containsKey("--port")) {
            values.put("JDBC_TEST_PORT", Integer.toString(port(options.get("--port"))));
        } else if (values.containsKey("SERVER_PORT")) {
            values.put("JDBC_TEST_PORT", Integer.toString(port(values.get("SERVER_PORT"))));
        }
        StringBuilder content = new StringBuilder(
            "# Temporary Compose encoding; source .env remains literal.\n");
        for (var entry : values.entrySet()) {
            content.append(entry.getKey()).append('=')
                .append(DeploymentTool.dotenv(entry.getValue())).append('\n');
        }
        Path destination = root.resolve(
                options.getOrDefault("--compose-output", ".docker-launch.lock/compose.env"))
            .normalize();
        if (!destination.startsWith(root.resolve(".docker-launch.lock"))) {
            DeploymentTool.fail("Use the protected Docker launch directory.");
        }
        DeploymentTool.publish(destination, content.toString());
        System.out.println(
            "Configuration checked (redacted). Compose receives the same decoded credentials; no URL rewriting.");
    }
}
