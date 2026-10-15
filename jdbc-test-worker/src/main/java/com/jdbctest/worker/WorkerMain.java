package com.jdbctest.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdbctest.catalog.TestCaseDescriptor;
import com.jdbctest.catalog.TestCatalog;
import com.jdbctest.catalog.TestProfile;
import com.jdbctest.catalog.TestSelection;
import com.jdbctest.extension.JdbcContext;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.PostDiscoveryFilter;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

public final class WorkerMain {

  public static final int SUCCESS = 0;
  public static final int INVALID_REQUEST = 2;
  public static final int DRIVER_FAILURE = 3;
  public static final int EXECUTION_FAILURE = 4;
  public static final int INTERNAL_FAILURE = 5;

  private final ObjectMapper mapper = new ObjectMapper();
  private final PrintStream protocolOutput = System.out;
  private final WorkerEventWriter events = new WorkerEventWriter(mapper, protocolOutput);

  public static void main(String[] args) {
    System.exit(new WorkerMain().run(args));
  }

  int run(String[] args) {
    if (args.length == 1 && "--catalog-smoke".equals(args[0])) {
      return catalogSmoke();
    }
    if (args.length == 1 && "--describe-catalog".equals(args[0])) {
      return describeCatalog();
    }
    if (args.length == 1 && "--validate-driver".equals(args[0])) {
      return validateDriver();
    }
    if (args.length == 1 && "--probe-connection".equals(args[0])) {
      return probeConnection();
    }
    WorkerRunRequest request;
    try {
      request = mapper.readValue(System.in, WorkerRunRequest.class);
      validate(request);
    } catch (Exception exception) {
      System.err.println(
          "[worker] invalid request: " + CredentialSanitizer.sanitize(exception.getMessage()));
      events.write("RUN_FAILED", "",
          Map.of("errorType", "INVALID_REQUEST", "message", "Invalid worker request"));
      return INVALID_REQUEST;
    }

    TestCatalog catalog;
    try {
      catalog = TestCatalog.loadDefault();
    } catch (Exception exception) {
      return fail(request.runId(), INTERNAL_FAILURE, "CATALOG_FAILURE", exception);
    }

    TestSelection selection;
    try {
      selection = toSelection(request.selection());
    } catch (Exception exception) {
      return fail(request.runId(), INVALID_REQUEST, "SELECTION_FAILURE", exception);
    }
    List<TestCaseDescriptor> selected;
    try {
      selected = catalog.select(selection);
    } catch (Exception exception) {
      return fail(request.runId(), INVALID_REQUEST, "SELECTION_FAILURE", exception);
    }

    Path workspace = Path.of(request.workspace()).toAbsolutePath().normalize();
    try {
      Files.createDirectories(workspace);
      Files.createDirectories(workspace.resolve("temp"));
    } catch (Exception exception) {
      return fail(request.runId(), INTERNAL_FAILURE, "WORKSPACE_FAILURE", exception);
    }

    events.write("RUN_STARTED", request.runId(),
        Map.of("scenarioCount", selected.size(), "protocolVersion", 2));
    Path configFile = workspace.resolve("temp").resolve("config.json");
    DriverLoader.LoadedDriver loaded = null;
    boolean timedOut = false;
    PrintStream originalOutput = System.out;
    PrintStream driverOutput = new PrintStream(new BoundedDiagnosticOutputStream(), true,
        StandardCharsets.UTF_8);
    try {
      System.setOut(driverOutput);
      loaded = DriverLoader.load(request.driver());
      JdbcContext.setIsolatedDriver(loaded.driver());
      writeConfig(configFile, request);
      System.setProperty("config.yaml", configFile.toString());
      WorkerExecutionEvidence evidence = execute(request, selected);
      JdbcContext.shutdown();
      WorkerReportWriter.write(workspace, request, selected, evidence);
      boolean failed = !"COMPLETED".equals(evidence.terminationReason());
      events.write("RUN_FINISHED", request.runId(),
          Map.of("status", failed ? "FAILED" : "PASSED", "scenarioCount", selected.size(),
              "terminationReason", evidence.terminationReason()));
      return failed ? EXECUTION_FAILURE : SUCCESS;
    } catch (TimeoutException exception) {
      timedOut = true;
      System.err.println("[worker] run timeout after " + request.timeoutMs() + " ms");
      events.write("RUN_FAILED", request.runId(),
          Map.of("errorType", "TIMEOUT", "message", "Run timeout", "cleanupState",
              "UNKNOWN_PENDING_PROCESS_EXIT"));
      return EXECUTION_FAILURE;
    } catch (Exception exception) {
      int code = loaded == null ? DRIVER_FAILURE : EXECUTION_FAILURE;
      return fail(request.runId(), code, "RUN_FAILURE", exception);
    } finally {
      System.setOut(originalOutput);
      driverOutput.close();
      // An interrupted JDBC call can still be running. Do not race cleanup against that thread.
      // Main exits the process; the persisted ownership journal is the residual evidence.
      if (!timedOut) {
        JdbcContext.shutdown();
      }
      System.clearProperty("config.yaml");
      try {
        Files.deleteIfExists(configFile);
      } catch (IOException exception) {
        System.err.println(
            "[worker] cleanup failed: " + CredentialSanitizer.sanitize(exception.getMessage()));
      }
      if (loaded != null && !timedOut) {
        try {
          loaded.close();
        } catch (Exception exception) {
          System.err.println(
              "[worker] driver classloader cleanup failed: " + CredentialSanitizer.sanitize(
                  exception.getMessage()));
        }
      }
    }
  }

  private int validateDriver() {
    DriverValidationRequest request;
    try {
      request = mapper.readValue(System.in, DriverValidationRequest.class);
    } catch (Exception exception) {
      events.write("DRIVER_VALIDATION_FAILED", "",
          Map.of("errorType", "INVALID_REQUEST", "message", "Invalid validation request"));
      return INVALID_REQUEST;
    }
    if (blank(request.driverClass())) {
      events.write("DRIVER_VALIDATION_FAILED", "",
          Map.of("errorType", "INVALID_REQUEST", "message", "driverClass is required"));
      return INVALID_REQUEST;
    }
    PrintStream originalOutput = System.out;
    PrintStream driverOutput = new PrintStream(new BoundedDiagnosticOutputStream(), true,
        StandardCharsets.UTF_8);
    try {
      System.setOut(driverOutput);
      try (DriverLoader.LoadedDriver loaded = DriverLoader.load(
          new WorkerRunRequest.DriverRequest(request.driverClass(), request.artifacts()))) {
        Driver driver = loaded.driver();
        events.write("DRIVER_VALIDATED", "", Map.of(
            "driverClass", request.driverClass(), "majorVersion", driver.getMajorVersion(),
            "minorVersion", driver.getMinorVersion(), "jdbcCompliant", driver.jdbcCompliant()));
        return SUCCESS;
      }
    } catch (Exception exception) {
      events.write("DRIVER_VALIDATION_FAILED", "",
          Map.of("errorType", "DRIVER_FAILURE", "message", "Driver validation failed"));
      return DRIVER_FAILURE;
    } finally {
      System.setOut(originalOutput);
      driverOutput.close();
    }
  }

  private int probeConnection() {
    ConnectionProbeRequest request;
    try {
      request = mapper.readValue(System.in, ConnectionProbeRequest.class);
    } catch (Exception exception) {
      events.write("CONNECTION_PROBE_FAILED", "",
          Map.of("errorType", "INVALID_REQUEST", "message", "Invalid connection probe request"));
      return INVALID_REQUEST;
    }
    if (request.driver() == null || request.connection() == null || blank(
        request.connection().jdbcUrl())) {
      events.write("CONNECTION_PROBE_FAILED", "",
          Map.of("errorType", "INVALID_REQUEST", "message", "Driver and JDBC URL are required"));
      return INVALID_REQUEST;
    }
    PrintStream originalOutput = System.out;
    PrintStream driverOutput = new PrintStream(new BoundedDiagnosticOutputStream(), true,
        StandardCharsets.UTF_8);
    try {
      System.setOut(driverOutput);
      try (DriverLoader.LoadedDriver loaded = DriverLoader.load(request.driver())) {
        Driver driver = loaded.driver();
        java.util.Properties credentials = new java.util.Properties();
        if (request.connection().username() != null) {
          credentials.setProperty("user", request.connection().username());
        }
        if (request.connection().password() != null) {
          credentials.setProperty("password", request.connection().password());
        }
        try (Connection connection = driver.connect(request.connection().jdbcUrl(), credentials)) {
          if (connection == null) {
            events.write("CONNECTION_PROBE_FAILED", "",
                Map.of("errorType", "DRIVER_DOES_NOT_ACCEPT_URL", "message",
                    "Driver does not accept JDBC URL"));
            return DRIVER_FAILURE;
          }
          Map<String, Object> metadata = probeMetadata(connection, driver);
          events.write("CONNECTION_PROBED", "", metadata);
          return SUCCESS;
        }
      }
    } catch (Exception exception) {
      events.write("CONNECTION_PROBE_FAILED", "",
          Map.of("errorType", "CONNECTION_FAILED", "message", "Connection test failed"));
      return DRIVER_FAILURE;
    } finally {
      System.setOut(originalOutput);
      driverOutput.close();
    }
  }

  private static Map<String, Object> probeMetadata(Connection connection, Driver driver) {
    Map<String, Object> values = new LinkedHashMap<>();
    DatabaseMetaData metadata;
    try {
      metadata = connection.getMetaData();
    } catch (Exception exception) {
      metadata = null;
    }
    if (metadata != null) {
      put(values, "databaseProductName", metadata::getDatabaseProductName);
      put(values, "databaseProductVersion", metadata::getDatabaseProductVersion);
      put(values, "driverName", metadata::getDriverName);
      put(values, "driverVersion", metadata::getDriverVersion);
      put(values, "jdbcMajorVersion", metadata::getJDBCMajorVersion);
      put(values, "jdbcMinorVersion", metadata::getJDBCMinorVersion);
    }
    values.putIfAbsent("driverName", safe(driver::toString));
    values.putIfAbsent("driverVersion", driver.getMajorVersion() + "." + driver.getMinorVersion());
    return values;
  }

  private static void put(Map<String, Object> values, String key, ThrowingSupplier supplier) {
    Object value = safe(supplier);
    if (value != null) {
      values.put(key, value);
    }
  }

  private static Object safe(ThrowingSupplier supplier) {
    try {
      return supplier.get();
    } catch (Exception ignored) {
      return null;
    }
  }

  @FunctionalInterface
  private interface ThrowingSupplier {

    Object get() throws Exception;
  }

  private int catalogSmoke() {
    try {
      TestCatalog catalog = TestCatalog.loadDefault();
      events.write("CATALOG_READY", "catalog-smoke",
          Map.of("scenarioCount", catalog.testCases().size(), "moduleCount",
              catalog.modules().size()));
      return SUCCESS;
    } catch (Exception exception) {
      System.err.println(
          "[worker] catalog smoke failed: " + CredentialSanitizer.sanitize(exception.getMessage()));
      return INTERNAL_FAILURE;
    }
  }

  /**
   * Emits immutable catalog metadata for the Server without loading suite classes there.
   */
  private int describeCatalog() {
    try {
      TestCatalog catalog = TestCatalog.loadDefault();
      List<Map<String, Object>> profiles = List.of(
          Map.of("id", "CORE", "name", "Core", "scenarioCount",
              catalog.select(TestSelection.core()).size()),
          Map.of("id", "FULL", "name", "Full", "scenarioCount",
              catalog.select(TestSelection.full()).size()));
      List<Map<String, Object>> modules = new ArrayList<>();
      for (var module : catalog.modules()) {
        int full = (int) catalog.testCases().stream()
            .filter(item -> item.module().id().equals(module.id())).count();
        int core = (int) catalog.select(TestSelection.core()).stream()
            .filter(item -> item.module().id().equals(module.id())).count();
        modules.add(Map.of("id", module.id(), "name", module.displayName(), "description",
            module.description(),
            "scenarioCount", full, "coreScenarioCount", core));
      }
      List<Map<String, Object>> scenarios = catalog.testCases().stream()
          .map(item -> Map.<String, Object>of(
              "id", item.scenarioId(), "moduleId", item.module().id(), "displayName",
              item.contract(), "profile", item.profile().name())).toList();
      events.write("CATALOG_DESCRIBED", "catalog", Map.of("profiles", profiles, "modules", modules,
          "protocolVersion", 2, "scenarios", scenarios, "totalScenarioCount",
          catalog.testCases().size()));
      return SUCCESS;
    } catch (Exception exception) {
      System.err.println("[worker] catalog describe failed: " + CredentialSanitizer.sanitize(
          exception.getMessage()));
      return INTERNAL_FAILURE;
    }
  }

  private WorkerExecutionEvidence execute(WorkerRunRequest request,
      List<TestCaseDescriptor> selected) throws Exception {
    Map<String, TestCaseDescriptor> descriptors = new HashMap<>();
    for (TestCaseDescriptor descriptor : selected) {
      descriptors.put(key(descriptor.sourceClass(), descriptor.sourceMethod()), descriptor);
    }
    LauncherDiscoveryRequestBuilder builder = LauncherDiscoveryRequestBuilder.request();
    Set<String> selectedClasses = new java.util.LinkedHashSet<>();
    Set<String> selectedMethods = new java.util.LinkedHashSet<>();
    for (TestCaseDescriptor item : selected) {
      try {
        Class<?> testClass = Class.forName(item.sourceClass(), false,
            Thread.currentThread().getContextClassLoader());
        var method = java.util.Arrays.stream(testClass.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(item.sourceMethod()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "Scenario method does not exist: " + item.scenarioId()));
        selectedClasses.add(item.sourceClass());
        selectedMethods.add(key(item.sourceClass(), item.sourceMethod()));
      } catch (ReflectiveOperationException exception) {
        throw new IllegalArgumentException("Scenario class is unavailable: " + item.sourceClass(),
            exception);
      }
    }
    for (String className : selectedClasses) {
      try {
        builder.selectors(DiscoverySelectors.selectClass(
            Class.forName(className, false, Thread.currentThread().getContextClassLoader())));
      } catch (ClassNotFoundException exception) {
        throw new IllegalArgumentException("Scenario class is unavailable: " + className,
            exception);
      }
    }
    builder.filters((PostDiscoveryFilter) descriptor -> {
      if (descriptor.getSource().isEmpty()) {
        return FilterResult.included("container");
      }
      if (descriptor.getSource()
          .get() instanceof org.junit.platform.engine.support.descriptor.MethodSource source) {
        return selectedMethods.contains(key(source.getClassName(), source.getMethodName()))
            ? FilterResult.included("selected scenario") : FilterResult.excluded("not selected");
      }
      return FilterResult.included("container");
    });
    builder.configurationParameter("junit.jupiter.execution.parallel.enabled", "false");
    LauncherDiscoveryRequest discovery = builder.build();
    WorkerExecutionEvidence listener = new WorkerExecutionEvidence(request.runId(), selected,
        events);
    var executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(1),
        Thread.ofPlatform().daemon(true).name("jdbc-test-worker").factory(),
        new ThreadPoolExecutor.AbortPolicy());
    try {
      Future<?> future = executor.submit(() -> {
        Launcher launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(discovery);
      });
      try {
        future.get(timeoutMillis(request), TimeUnit.MILLISECONDS);
      } catch (TimeoutException timeout) {
        future.cancel(true);
        throw timeout;
      }
    } finally {
      executor.shutdownNow();
    }
    return listener;
  }

  private Map<String, Object> caseFields(TestCaseDescriptor descriptor) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("scenarioId", descriptor.scenarioId());
    fields.put("moduleId", descriptor.module().id());
    fields.put("module", descriptor.module().id());
    fields.put("displayName", descriptor.contract());
    return fields;
  }

  private static String key(String className, String methodName) {
    return className + "#" + methodName;
  }

  private void writeConfig(Path path, WorkerRunRequest request) throws IOException {
    Map<String, Object> db = new LinkedHashMap<>();
    String adapterId =
        blank(request.connection().adapterId()) ? "worker" : request.connection().adapterId();
    db.put("type", adapterId);
    db.put("adapter_id", adapterId);
    db.put("asset_id", adapterId);
    db.put("dialect", adapterId);
    db.put("url", request.connection().jdbcUrl());
    db.put("username", request.connection().username());
    db.put("password", request.connection().password());
    db.put("driver_class", request.driver().driverClass());
    db.put("connection_mode", "driver_manager");
    db.put("data_sources", request.driver().dataSources() == null ? Map.of()
        : request.driver().dataSources().toMap());
    db.put("properties",
        request.connection().properties() == null ? Map.of() : request.connection().properties());
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("db", db);
    config.put("ddl", Map.of("base_path", "classpath:ddl"));
    config.put("dml", Map.of("base_path", "classpath:dml"));
    config.put("pool", Map.of("profile_dir", "classpath:pool"));
    var adapter = com.jdbctest.catalog.AdapterExecutionConfig.load(adapterId);
    db.put("identifier_quote", adapter.identifierQuote());
    Map<String, Object> namespace = new LinkedHashMap<>();
    namespace.put("mode", "existing");
    namespace.put("selection", "mysql".equals(adapterId) ? "catalog" : "schema");
    namespace.put("name", request.scope().namespace(adapterId));
    if (!"oracle".equals(adapterId)) {
      namespace.put("database", request.scope().database());
    }
    namespace.put("object_prefix", request.objectPrefix());
    namespace.put("destructive_consent", request.destructiveConsent());
    namespace.put("ledger_path",
        Path.of(request.workspace()).resolve("object-ledger.yaml").toString());
    config.put("namespace", namespace);
    config.put("adapter", Map.of("id", adapterId, "capabilities",
        com.jdbctest.catalog.AdapterExecutionConfig.validateCapabilities(request.capabilities())));
    config.put("test_filter", Map.of("timeout_ms", timeoutMillis(request)));
    Files.writeString(path, mapper.writeValueAsString(config), StandardCharsets.UTF_8);
    try {
      Files.setPosixFilePermissions(path,
          Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    } catch (UnsupportedOperationException ignored) {
    }
  }

  private static TestSelection toSelection(WorkerRunRequest.SelectionRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("selection is required");
    }
    TestProfile profile = TestProfile.valueOf(
        request.profile() == null ? "FULL" : request.profile().toUpperCase());
    return TestSelection.custom(profile,
        Set.copyOf(request.modules() == null ? List.of() : request.modules()),
        Set.copyOf(request.includeScenarios() == null ? List.of() : request.includeScenarios()),
        Set.copyOf(request.excludeScenarios() == null ? List.of() : request.excludeScenarios()));
  }

  private static void validate(WorkerRunRequest request) {
    if (request == null || !Integer.valueOf(2).equals(request.protocolVersion())) {
      throw new IllegalArgumentException("WORKER_PROTOCOL_MISMATCH: expected protocolVersion 2");
    }
    if (blank(request.runId()) || request.driver() == null || request.connection() == null || blank(
        request.workspace())) {
      throw new IllegalArgumentException("runId, driver, connection and workspace are required");
    }
    if (request.scope() == null || !Boolean.TRUE.equals(request.destructiveConsent())
        || request.objectPrefix() == null
        || !request.objectPrefix().matches("j[0-9a-f]{20}")) {
      throw new IllegalArgumentException("EXECUTION_SCOPE_REQUIRED");
    }
    request.scope().validate(request.connection().adapterId());
    com.jdbctest.catalog.AdapterExecutionConfig.validateCapabilities(request.capabilities());
    var providers = request.driver().dataSources() == null
        ? com.jdbctest.catalog.DataSourceProviders.empty() : request.driver().dataSources();
    if (!providers.capabilities(request.capabilities()).equals(request.capabilities())) {
      throw new IllegalArgumentException("DataSource capabilities must match configured providers");
    }
    if (blank(request.connection().jdbcUrl())) {
      throw new IllegalArgumentException("connection.jdbcUrl is required");
    }
    if (request.timeoutMs() != null && request.timeoutMs() <= 0) {
      throw new IllegalArgumentException("timeoutMs must be positive");
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static long timeoutMillis(WorkerRunRequest request) {
    return request.timeoutMs() == null ? 60_000L : request.timeoutMs();
  }

  private int fail(String runId, int code, String type, Exception exception) {
    String message = CredentialSanitizer.sanitize(exception.getMessage());
    System.err.println("[worker] " + type + ": " + message);
    Throwable cause = exception.getCause();
    if (cause != null && cause.getMessage() != null) {
      System.err.println("[worker] cause: " + CredentialSanitizer.sanitize(cause.getMessage()));
    }
    if (exception.getCause() instanceof org.junit.platform.commons.JUnitException junitException
        && junitException.getCause() != null) {
      System.err.println("[worker] root cause: " + CredentialSanitizer.sanitize(
          junitException.getCause().toString()));
    }
    events.write("RUN_FAILED", runId,
        Map.of("errorType", type, "message", message.isBlank() ? "Worker failure" : message));
    return code;
  }

  /**
   * Bounded sink keeps arbitrary driver output off the NDJSON protocol stream.
   */
  private static final class BoundedDiagnosticOutputStream extends OutputStream {

    private static final int LIMIT = 64 * 1024;
    private int count;

    @Override
    public void write(int value) {
      if (count < LIMIT) {
        count++;
      }
    }

    @Override
    public void write(byte[] bytes, int offset, int length) {
      count = Math.min(LIMIT, count + Math.max(0, length));
    }
  }
}
