package com.jdbctest.server.run;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jdbctest.server.connection.DatabaseConnectionProfileEntity;
import com.jdbctest.server.connection.DatabaseConnectionProfileRepository;
import com.jdbctest.server.driver.DriverPackageStatus;
import com.jdbctest.server.driver.DriverSourceType;
import com.jdbctest.server.driver.DriverWorkerProperties;
import com.jdbctest.server.driver.JdbcDriverPackageEntity;
import com.jdbctest.server.driver.JdbcDriverPackageRepository;
import com.jdbctest.server.security.CredentialEncryptionService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:orchestration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=create-drop", "jdbc-test.execution.max-concurrent-runs=1"})
class DeterministicOrchestrationTest {

  static final Path DATA = dataDirectory();
  static final Path WORKER = workerJar();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("jdbc-test.security.master-key",
        () -> Base64.getEncoder().encodeToString(new byte[32]));
    registry.add("jdbc-test.storage.data-directory", DATA::toString);
    registry.add("jdbc-test.worker.executable", WORKER::toString);
  }

  @MockitoSpyBean
  TestRunRepository runs;
  @Autowired
  TestRunSelectedScenarioRepository selections;
  @Autowired
  TestCaseResultRepository results;
  @Autowired
  TestRunCredentialRepository credentials;
  @Autowired
  DatabaseConnectionProfileRepository connections;
  @Autowired
  JdbcDriverPackageRepository drivers;
  @Autowired
  CredentialEncryptionService encryption;
  @Autowired
  TestRunScheduler scheduler;
  @Autowired
  TestRunExecutor executor;
  @Autowired
  TestRunService service;
  @Autowired
  TestRunStartupRecovery recovery;
  @Autowired
  RunCatalogService catalog;
  @Autowired
  ExecutionProperties execution;
  @Autowired
  DriverWorkerProperties worker;
  @Autowired
  TransactionTemplate transactions;
  @Autowired
  WebApplicationContext context;
  @MockitoSpyBean
  RunEventPublisher events;
  final Map<UUID, CountDownLatch> completed = new ConcurrentHashMap<>();
  final Map<UUID, List<String>> eventNames = new ConcurrentHashMap<>();
  final List<UUID> created = new ArrayList<>();
  MockMvc mvc;

  @BeforeEach
  void setup() {
    execution.setRunTimeout(Duration.ofSeconds(20));
    execution.setTerminationGracePeriod(Duration.ofMillis(100));
    worker.setExecutable(WORKER);
    assertFalse(Files.exists(DATA.resolve("concurrency-violation")),
        "Overlapping Worker processes detected");
    mvc = MockMvcBuilders.webAppContextSetup(context).build();
    doAnswer(invocation -> {
      UUID id = invocation.getArgument(0);
      eventNames.computeIfAbsent(id, ignored -> new CopyOnWriteArrayList<>())
          .add(invocation.getArgument(1));
      return invocation.callRealMethod();
    }).when(events).publish(any(UUID.class), anyString(), any());
    doAnswer(invocation -> {
      Object value = invocation.callRealMethod();
      completed.computeIfAbsent(invocation.getArgument(0), ignored -> new CountDownLatch(1))
          .countDown();
      return value;
    }).when(events).complete(any(UUID.class));
  }

  @AfterEach
  void cleanup() {
    for (UUID id : created) {
      if (!TestRunStateTransitions.terminal(runs.findById(id).orElseThrow().getStatus())) {
        scheduler.cancel(id);
      }
    }
    worker.setExecutable(WORKER);
    assertFalse(Files.exists(DATA.resolve("concurrency-violation")),
        "Overlapping Worker processes detected");
  }

  @Test
  void incompatibleWorkerIsRejectedBeforeExecutionEvenAfterCatalogWasCached() throws Exception {
    catalog.catalog();
    UUID id = run("NORMAL", 1);
    worker.setExecutable(FakeWorkerJar.create(Files.createTempDirectory("legacy-worker-"), 1));
    scheduler.enqueue(id);
    done(id, TestRunStatus.FAILED, "WORKER_PROTOCOL_MISMATCH");
    assertFalse(Files.exists(workspace(id).resolve("started")));
    assertFalse(Files.exists(workspace(id).resolve("input-evidence.json")));
    assertEquals(0, service.get(id).accounting().started());
    assertEquals(1, service.get(id).accounting().notRun());
    var freshCatalog = new RunCatalogService(
        new com.jdbctest.server.driver.WorkerProcessRunner(worker,
            new tools.jackson.databind.ObjectMapper()), worker,
        new tools.jackson.databind.ObjectMapper());
    assertThrows(RunException.class, freshCatalog::catalog);
  }

  @Test
  void setupFailureKeepsNotRunAndOriginalContainerIssue() throws Exception {
    UUID id = run("SETUP_FAILURE", 2);
    scheduler.enqueue(id);
    started(id);
    done(id, TestRunStatus.FAILED, "WORKER_EXECUTION_FAILED");
    var accounting = service.get(id).accounting();
    assertEquals(0, accounting.failed());
    assertEquals(0, accounting.started());
    assertEquals(2, accounting.notRun());
    assertEquals(1, service.get(id).executionIssues().size());
    mvc.perform(get("/api/runs/{id}/report.json", id)).andExpect(status().isOk())
        .andExpect(jsonPath("$.terminationReason").value("CONTAINER_FAILURE"))
        .andExpect(jsonPath("$.notRun.length()").value(2))
        .andExpect(jsonPath("$.summary.failed").value(0));
  }

  @Test
  void partialWorkerExitRetainsRealSuccessAndNotRun() throws Exception {
    UUID id = run("PARTIAL_EXIT", 3);
    scheduler.enqueue(id);
    started(id);
    done(id, TestRunStatus.FAILED, "WORKER_EXITED_UNEXPECTEDLY");
    var accounting = service.get(id).accounting();
    assertEquals(1, accounting.passed());
    assertEquals(1, accounting.completed());
    assertEquals(2, accounting.notRun());
    assertEquals(0, accounting.failed());
    assertEquals(0, accounting.incomplete());
  }

  @Test
  void startedWithoutFinishIsIncompleteRatherThanFailedOrNotRun() throws Exception {
    UUID id = run("START_NO_FINISH", 2);
    scheduler.enqueue(id);
    started(id);
    done(id, TestRunStatus.FAILED, "WORKER_EXITED_UNEXPECTEDLY");
    var accounting = service.get(id).accounting();
    assertEquals(1, accounting.started());
    assertEquals(1, accounting.incomplete());
    assertEquals(1, accounting.notRun());
    assertEquals(0, accounting.completed());
    assertEquals(0, accounting.failed());
  }

  @Test
  void explicitAllSkipDoesNotProduceSuccessfulCompatibilityRun() throws Exception {
    UUID id = run("ALL_SKIP", 2);
    scheduler.enqueue(id);
    started(id);
    done(id, TestRunStatus.FAILED, "WORKER_EXECUTION_FAILED");
    var accounting = service.get(id).accounting();
    assertEquals(2, accounting.skipped());
    assertEquals(0, accounting.started());
    assertEquals(0, accounting.notRun());
  }

  @Test
  void queuedRunUsesFrozenUrlUserAndScopeAfterConnectionEdit() throws Exception {
    UUID id = run("NORMAL", 1);
    transactions.executeWithoutResult(tx -> {
      var connection = runs.findById(id).orElseThrow().getConnection();
      connection.update("changed", "postgresql", "postgresql", "jdbc:postgresql://changed/other",
          "EXIT_NON_ZERO", null, connection.getDefaultDriver());
      connection.setExecutionScope("other", "other_scope");
    });
    scheduler.enqueue(id);
    started(id);
    done(id, TestRunStatus.SUCCEEDED, null);
    var input = new tools.jackson.databind.ObjectMapper().readTree(
        Files.readString(workspace(id).resolve("input-evidence.json")));
    assertEquals("jdbc:postgresql://host/db", input.path("jdbcUrl").asString());
    assertEquals("NORMAL", input.path("username").asString());
    assertEquals("qa_test", input.path("scope").path("schema").asString());
  }

  @Test
  void missingWriteScopeRejectsRunWithoutWorker() {
    var connection = resource("NORMAL");
    transactions.executeWithoutResult(
        tx -> connections.findById(connection.getId()).orElseThrow().setExecutionScope(null, null));
    var failure = assertThrows(RunException.class, () -> service.create(
        new CreateTestRunRequest(connection.getId(), null, "CUSTOM", List.of("connection"),
            List.of(), List.of(), null, true)));
    assertTrue(failure.getMessage().contains("scope") || failure.getMessage().contains("database"));
  }

  @Test
  void fifoAndConcurrencyOne() throws Exception {
    UUID first = run("BLOCK", 1);
    UUID second = run("BLOCK", 1);
    scheduler.enqueue(first);
    scheduler.enqueue(second);
    long firstPid = started(first);
    assertEquals(TestRunStatus.RUNNING, state(first));
    assertEquals(TestRunStatus.QUEUED, state(second));
    assertFalse(Files.exists(workspace(second).resolve("started")));
    release(first);
    done(first, TestRunStatus.SUCCEEDED, null);
    long secondPid = started(second);
    assertFalse(ProcessHandle.of(firstPid).map(ProcessHandle::isAlive).orElse(false));
    assertTrue(ProcessHandle.of(secondPid).orElseThrow().isAlive());
    release(second);
    done(second, TestRunStatus.SUCCEEDED, null);
    assertTrue(Files.getLastModifiedTime(workspace(first).resolve("exited"))
        .compareTo(Files.getLastModifiedTime(workspace(second).resolve("started"))) <= 0);
    assertFalse(ProcessHandle.of(secondPid).map(ProcessHandle::isAlive).orElse(false));
  }

  @Test
  void boundedQueueRejectsCommittedRunAndPreservesFifo() throws Exception {
    var properties = new ExecutionProperties();
    properties.setQueueCapacity(1);
    var dispatcher = new TestRunScheduler(properties, executor, runs, credentials, transactions,
        events, selections);
    UUID running = run("BLOCK", 1);
    UUID waiting = run("NORMAL", 1);
    UUID rejected = run("NORMAL", 1);
    try {
      dispatcher.enqueue(running);
      started(running);
      dispatcher.enqueue(waiting);
      var error = assertThrows(RunException.class, () -> dispatcher.enqueue(rejected));
      assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, error.status());
      assertEquals("RUN_QUEUE_FULL", error.code());
      assertEquals(TestRunStatus.INTERRUPTED, state(rejected));
      assertEquals("RUN_QUEUE_FULL", service.get(rejected).failureReason());
      assertNotNull(service.get(rejected).finishedAt());
      assertFalse(credentials.existsById(rejected));
      assertFalse(Files.exists(workspace(rejected).resolve("started")));
      assertTrue(eventNames.get(rejected).contains("run.status"));
      assertEquals(0, completed.get(rejected).getCount());
      assertEquals(TestRunStatus.QUEUED, state(waiting));
      release(running);
      done(running, TestRunStatus.SUCCEEDED, null);
      started(waiting);
      release(waiting);
      done(waiting, TestRunStatus.SUCCEEDED, null);
    } finally {
      dispatcher.shutdown();
    }
  }

  @Test
  void cancellingQueuedRunImmediatelyFreesBoundedQueueSlot() throws Exception {
    var properties = new ExecutionProperties();
    properties.setQueueCapacity(1);
    var dispatcher = new TestRunScheduler(properties, executor, runs, credentials, transactions,
        events, selections);
    UUID running = run("BLOCK", 1);
    UUID cancelled = run("NORMAL", 1);
    UUID replacement = run("NORMAL", 1);
    try {
      dispatcher.enqueue(running);
      long pid = started(running);
      dispatcher.enqueue(cancelled);
      dispatcher.cancel(cancelled);
      dispatcher.enqueue(replacement);
      assertEquals(TestRunStatus.RUNNING, state(running));
      assertTrue(ProcessHandle.of(pid).orElseThrow().isAlive());
      assertEquals(TestRunStatus.QUEUED, state(replacement));
      assertEquals(TestRunStatus.CANCELLED, state(cancelled));
      assertFalse(credentials.existsById(cancelled));
      release(running);
      done(running, TestRunStatus.SUCCEEDED, null);
      started(replacement);
      release(replacement);
      done(replacement, TestRunStatus.SUCCEEDED, null);
      assertFalse(Files.exists(workspace(cancelled).resolve("started")));
    } finally {
      dispatcher.shutdown();
    }
  }

  @Test
  void enqueueAfterShutdownCleansRunCommittedAfterShutdownScan() {
    var dispatcher = new TestRunScheduler(execution, executor, runs, credentials, transactions,
        events, selections);
    dispatcher.shutdown();
    UUID id = run("NORMAL", 1);
    var error = assertThrows(RunException.class, () -> dispatcher.enqueue(id));
    assertEquals("SERVER_SHUTTING_DOWN", error.code());
    assertEquals(TestRunStatus.INTERRUPTED, state(id));
    assertEquals("SERVER_SHUTTING_DOWN", service.get(id).failureReason());
    assertFalse(credentials.existsById(id));
    assertNotNull(service.get(id).finishedAt());
  }

  @Test
  void queueCapacityMustBePositive() {
    var properties = new ExecutionProperties();
    for (int capacity : new int[] {0, -1}) {
      properties.setQueueCapacity(capacity);
      assertThrows(IllegalArgumentException.class,
          () -> new TestRunScheduler(properties, executor, runs, credentials, transactions,
              events, selections));
    }
  }

  @Test
  void shutdownInterruptsRunningAndQueuedWorkAndReapsWorker() throws Exception {
    var dispatcher = new TestRunScheduler(execution, executor, runs, credentials, transactions,
        events, selections);
    UUID running = run("BLOCK_STUBBORN", 1);
    UUID queued = run("PASS", 1);
    try {
      dispatcher.enqueue(running);
      long pid = started(running);
      dispatcher.enqueue(queued);
      dispatcher.shutdown();
      done(running, TestRunStatus.INTERRUPTED, "SERVER_SHUTDOWN");
      assertEquals(TestRunStatus.INTERRUPTED, state(queued));
      assertEquals("SERVER_SHUTDOWN", service.get(queued).failureReason());
      assertFalse(credentials.existsById(queued));
      assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
      assertFalse(Files.exists(workspace(queued).resolve("started")));
    } finally {
      dispatcher.shutdown();
    }
  }

  @Test
  void completionWinningShutdownRaceRemainsSucceeded() throws Exception {
    var dispatcher = new TestRunScheduler(execution, executor, runs, credentials, transactions,
        events, selections);
    UUID runId = run("BLOCK", 1);
    dispatcher.enqueue(runId);
    started(runId);
    Thread closingThread = Thread.currentThread();
    var repositoryAnswer = org.mockito.Mockito.mockingDetails(runs).getMockCreationSettings()
        .getDefaultAnswer();
    doAnswer(invocation -> {
      if (Thread.currentThread() == closingThread) {
        release(runId);
        awaitComplete(runId);
      }
      return repositoryAnswer.answer(invocation);
    }).when(runs).findForUpdate(runId);
    try {
      dispatcher.shutdown();
      done(runId, TestRunStatus.SUCCEEDED, null);
    } finally {
      dispatcher.shutdown();
    }
  }

  @ParameterizedTest
  @CsvSource({"NORMAL,SUCCEEDED,none", "FAILED_RUN,FAILED,WORKER_EXECUTION_FAILED",
      "BLOCK,CANCELLED,none",
      "TIMEOUT,FAILED,RUN_TIMEOUT", "INVALID_JSON,FAILED,WORKER_PROTOCOL_ERROR",
      "WRONG_RUN_ID,FAILED,WORKER_PROTOCOL_ERROR",
      "EXIT_NON_ZERO,FAILED,WORKER_EXITED_UNEXPECTEDLY",
      "REPORT_MISMATCH,FAILED,REPORT_INCONSISTENT",
      "FINISH_MISMATCH,FAILED,REPORT_INCONSISTENT",
      "CASE_COUNT_MISMATCH,FAILED,REPORT_INCONSISTENT",
      "REPORT_INVALID,FAILED,REPORT_INCONSISTENT", "UNKNOWN_EVENT,FAILED,WORKER_PROTOCOL_ERROR"})
  void slotAndCredentialReleaseMatrix(String mode, TestRunStatus expected, String reason)
      throws Exception {
    if ("TIMEOUT".equals(mode)) {
      execution.setRunTimeout(Duration.ofSeconds(5));
    }
    UUID first = run("TIMEOUT".equals(mode) ? "BLOCK_STUBBORN" : mode, 1);
    UUID second = run("NORMAL", 1);
    scheduler.enqueue(first);
    scheduler.enqueue(second);
    long firstPid = started(first);
    if (expected == TestRunStatus.CANCELLED) {
      scheduler.cancel(first);
    }
    done(first, expected, "none".equals(reason) ? null : reason);
    execution.setRunTimeout(Duration.ofSeconds(20));
    started(second);
    assertFalse(ProcessHandle.of(firstPid).map(ProcessHandle::isAlive).orElse(false));
    done(second, TestRunStatus.SUCCEEDED, null);
    if ("WRONG_RUN_ID".equals(mode)) {
      assertTrue(results.findByRun_Id(first).isEmpty());
      assertFalse(eventNames.get(first).contains("module.started"));
    }
  }

  @Test
  void queuedCancelNeverStartsAndFollowingRunSurvives() throws Exception {
    UUID first = run("BLOCK", 1);
    UUID second = run("NORMAL", 1);
    UUID third = run("NORMAL", 1);
    scheduler.enqueue(first);
    started(first);
    scheduler.enqueue(second);
    assertTrue(credentials.existsById(second));
    MvcResult stream = subscribe(second);
    mvc.perform(post("/api/runs/{id}/cancel", second)).andExpect(status().isNoContent());
    done(second, TestRunStatus.CANCELLED, null);
    assertFalse(Files.exists(workspace(second).resolve("started")));
    assertEquals(TestRunStatus.RUNNING, state(first));
    assertTrue(stream.getResponse().getContentAsString().contains("event:run.cancelled"));
    scheduler.enqueue(third);
    release(first);
    done(first, TestRunStatus.SUCCEEDED, null);
    started(third);
    done(third, TestRunStatus.SUCCEEDED, null);
    assertFalse(Files.exists(workspace(second).resolve("started")));
  }

  @Test
  void runningCancelForciblyTerminatesAndCannotBeOverwritten() throws Exception {
    UUID first = run("BLOCK_STUBBORN", 1);
    UUID second = run("NORMAL", 1);
    MvcResult stream = subscribe(first);
    scheduler.enqueue(first);
    long pid = started(first);
    scheduler.enqueue(second);
    mvc.perform(post("/api/runs/{id}/cancel", first)).andExpect(status().isNoContent());
    assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    done(first, TestRunStatus.CANCELLED, null);
    started(second);
    done(second, TestRunStatus.SUCCEEDED, null);
    assertEquals(TestRunStatus.CANCELLED, state(first));
    String output = stream.getResponse().getContentAsString();
    assertTrue(output.contains("event:run.cancelled"));
    assertFalse(output.contains("event:run.failed"));
    assertFalse(output.contains("event:run.finished"));
  }

  @RepeatedTest(12)
  void cancelVersusCompletionRaceHasExactlyOneTerminalWinner(
      org.junit.jupiter.api.RepetitionInfo repetition) throws Exception {
    UUID id = run("DELAY_FINISH", 1);
    MvcResult stream = subscribe(id);
    scheduler.enqueue(id);
    started(id);
    signal(id, "finish-ready");
    if (repetition.getCurrentRepetition() % 2 == 0) {
      Files.writeString(workspace(id).resolve("finish"), "go");
      signal(id, "finish-emitted");
    }
    CountDownLatch barrier = new CountDownLatch(1);
    try (var contenders = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(2),
        Thread.ofPlatform().name("run-race-test-", 0).factory(),
        new ThreadPoolExecutor.AbortPolicy())) {
      var completion = contenders.submit(() -> {
        barrier.await();
        Files.writeString(workspace(id).resolve("exit"), "go");
        Files.writeString(workspace(id).resolve("finish"), "go");
        return null;
      });
      var cancellation = contenders.submit(() -> {
        barrier.await();
        try {
          scheduler.cancel(id);
        } catch (RunException exception) {
          assertEquals("RUN_ALREADY_FINISHED", exception.code());
        }
        return null;
      });
      barrier.countDown();
      completion.get(10, TimeUnit.SECONDS);
      cancellation.get(10, TimeUnit.SECONDS);
    }
    awaitComplete(id);
    TestRunStatus winner = state(id);
    assertTrue(winner == TestRunStatus.SUCCEEDED || winner == TestRunStatus.CANCELLED);
    UUID next = run("NORMAL", 1);
    scheduler.enqueue(next);
    started(next);
    done(next, TestRunStatus.SUCCEEDED, null);
    assertEquals(winner, state(id));
    assertThrows(RunException.class, () -> scheduler.cancel(id));
    String output = stream.getResponse().getContentAsString();
    assertEquals(1, output.split("event:run\\.(?:finished|cancelled)", -1).length - 1);
    assertFalse(credentials.existsById(id));
  }

  @Test
  void resultsAndSanitizationPersistAllFourStatuses() throws Exception {
    UUID id = run("MIXED", 4);
    MvcResult stream = subscribe(id);
    scheduler.enqueue(id);
    started(id);
    done(id, TestRunStatus.FAILED, "WORKER_EXECUTION_FAILED");
    var stored = results.findByRun_Id(id);
    var selected = selections.findByRun_IdOrderByScenarioId(id);
    assertEquals(4, stored.size());
    for (int index = 0; index < selected.size(); index++) {
      String scenario = selected.get(index).getScenarioId();
      var value = stored.stream().filter(item -> scenario.equals(item.getScenarioId())).findFirst()
          .orElseThrow();
      assertEquals("connection", value.getModuleId());
      assertEquals("case " + scenario, value.getDisplayName());
      assertEquals(TestCaseResultStatus.valueOf(
          List.of("PASSED", "FAILED", "SKIPPED", "ABORTED").get(index)), value.getStatus());
      assertEquals(42L, value.getDurationMs());
      assertEquals(
          "password=[REDACTED] token=[REDACTED] jdbc:postgresql://host/db?password=[REDACTED]",
          value.getMessage());
      assertEquals("trace password=[REDACTED] token=[REDACTED]", value.getStackTrace());
    }
    TestRunResponse snapshot = service.get(id);
    assertEquals(4, snapshot.completedCases());
    assertEquals(1, snapshot.passed());
    assertEquals(1, snapshot.failed());
    assertEquals(1, snapshot.skipped());
    assertEquals(1,
        snapshot.completedCases() - snapshot.passed() - snapshot.failed() - snapshot.skipped());
    for (String endpoint : List.of("", "/results", "/report", "/report.json")) {
      String output = mvc.perform(get("/api/runs/" + id + endpoint)).andExpect(status().isOk())
          .andReturn().getResponse().getContentAsString();
      noSecrets(output);
    }
    noSecrets(stream.getResponse().getContentAsString());
    noSecrets(Files.readString(workspace(id).resolve("logs/worker.log")));
    UUID failure = run("ERROR_SECRET", 1);
    MvcResult failureStream = subscribe(failure);
    scheduler.enqueue(failure);
    started(failure);
    awaitComplete(failure);
    assertEquals(TestRunStatus.FAILED, state(failure));
    noSecrets(service.get(failure).failureReason());
    assertFalse(credentials.existsById(failure));
    noSecrets(failureStream.getResponse().getContentAsString());
    noSecrets(mvc.perform(get("/api/runs/{id}", failure)).andExpect(status().isOk()).andReturn()
        .getResponse().getContentAsString());
  }

  @Test
  void sseSnapshotTwoSubscribersTerminalAndSafePayload() throws Exception {
    UUID id = run("BLOCK", 1);
    MvcResult first = subscribe(id);
    MvcResult second = subscribe(id);
    assertTrue(first.getResponse().getContentAsString().startsWith("event:run.snapshot"));
    scheduler.enqueue(id);
    started(id);
    release(id);
    done(id, TestRunStatus.SUCCEEDED, null);
    for (MvcResult stream : List.of(first, second)) {
      String output = stream.getResponse().getContentAsString();
      for (String name : List.of("run.snapshot", "run.status", "module.started", "case.started",
          "case.finished", "run.progress", "run.finished")) {
        assertTrue(output.contains("event:" + name), name + ": " + output);
      }
      assertNull(stream.getAsyncResult(1000));
      for (String forbidden : List.of("password", "encryptedPassword", "storageKey",
          DATA.toString(), "workspace")) {
        assertFalse(output.contains(forbidden), forbidden);
      }
      noSecrets(output);
    }
  }

  @Test
  void workerStartupFailureReleasesCredentialsAndSlot() throws Exception {
    UUID first = run("NORMAL", 1);
    worker.setExecutable(DATA.resolve("missing-worker.jar"));
    scheduler.enqueue(first);
    done(first, TestRunStatus.FAILED, "WORKER_UNAVAILABLE");
    worker.setExecutable(WORKER);
    UUID second = run("NORMAL", 1);
    scheduler.enqueue(second);
    started(second);
    done(second, TestRunStatus.SUCCEEDED, null);
  }

  @Test
  void disconnectAndSendFailureDoNotAffectOtherSubscriberOrExecution() throws Exception {
    UUID id = run("BLOCK", 1);
    MvcResult disconnected = subscribe(id);
    MvcResult survivor = subscribe(id);
    @SuppressWarnings("unchecked")
    Map<UUID, CopyOnWriteArrayList<org.springframework.web.servlet.mvc.method.annotation.SseEmitter>> emitters =
        (Map<UUID, CopyOnWriteArrayList<org.springframework.web.servlet.mvc.method.annotation.SseEmitter>>) org.springframework.test.util.ReflectionTestUtils.getField(
            events, "emitters");
    assertEquals(2, emitters.get(id).size());
    var async = (org.springframework.mock.web.MockAsyncContext) disconnected.getRequest()
        .getAsyncContext();
    var error = new jakarta.servlet.AsyncEvent(async, disconnected.getRequest(),
        disconnected.getResponse(), new java.io.IOException("client disconnected"));
    for (var listener : async.getListeners()) {
      listener.onError(error);
    }
    assertEquals(1, emitters.get(id).size());
    var broken = org.mockito.Mockito.mock(
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter.class);
    org.mockito.Mockito.doThrow(new java.io.IOException("send failed")).when(broken).send(
        any(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder.class));
    emitters.get(id).add(broken);
    scheduler.enqueue(id);
    started(id);
    release(id);
    done(id, TestRunStatus.SUCCEEDED, null);
    org.mockito.Mockito.verify(broken).complete();
    assertFalse(emitters.containsKey(id));
    String output = survivor.getResponse().getContentAsString();
    assertTrue(output.contains("event:case.finished"));
    assertTrue(output.contains("event:run.finished"));
    assertFalse(disconnected.getResponse().getContentAsString().contains("event:run.finished"));
    UUID noSubscribers = run("NORMAL", 1);
    scheduler.enqueue(noSubscribers);
    started(noSubscribers);
    done(noSubscribers, TestRunStatus.SUCCEEDED, null);
    assertFalse(emitters.containsKey(noSubscribers));
  }

  @Test
  void subscribingToTerminalRunImmediatelyCompletes() throws Exception {
    UUID id = run("NORMAL", 1);
    scheduler.enqueue(id);
    started(id);
    done(id, TestRunStatus.SUCCEEDED, null);
    MvcResult stream = subscribe(id);
    assertTrue(stream.getResponse().getContentAsString().contains("event:run.snapshot"));
    assertNull(stream.getAsyncResult(1000));
    assertEquals("RUN_ALREADY_FINISHED",
        assertThrows(RunException.class, () -> scheduler.cancel(id)).code());
    assertEquals(TestRunStatus.SUCCEEDED, state(id));
  }

  @ParameterizedTest
  @EnumSource(TestRunStatus.class)
  void recoveryInterruptsOnlyActiveStatesAndCleansCredentials(TestRunStatus original)
      throws Exception {
    UUID id = run("NORMAL", 1);
    transactions.executeWithoutResult(tx -> {
      var entity = runs.findForUpdate(id).orElseThrow();
      entity.setStatus(original);
      if (TestRunStateTransitions.terminal(original)) {
        entity.markFinished();
        entity.setFailureReason("unchanged");
      }
    });
    var previous = service.get(id);
    recovery.run(null);
    if (TestRunStateTransitions.terminal(original)) {
      assertEquals(original, state(id));
      assertEquals("unchanged", service.get(id).failureReason());
      assertEquals(previous.finishedAt(), service.get(id).finishedAt());
      assertTrue(credentials.existsById(id));
    } else {
      assertEquals(TestRunStatus.INTERRUPTED, state(id));
      assertEquals("SERVER_RESTART", service.get(id).failureReason());
      assertNotNull(service.get(id).finishedAt());
      assertFalse(credentials.existsById(id));
    }
    mvc.perform(get("/api/runs/{id}", id)).andExpect(status().isOk());
  }

  @ParameterizedTest
  @EnumSource(TestRunStatus.class)
  void detailReturns200WithNullableFieldsInEveryState(TestRunStatus statusValue) throws Exception {
    UUID id = run("NORMAL", 1);
    transactions.executeWithoutResult(
        tx -> runs.findForUpdate(id).orElseThrow().setStatus(statusValue));
    mvc.perform(get("/api/runs/{id}", id)).andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value(statusValue.name()));
  }

  @ParameterizedTest
  @CsvSource({"CORE,all,136", "FULL,all,202", "CUSTOM,connection,17", "CUSTOM,statement,23"})
  void selectionSnapshotUsesActualDatabaseRows(String profile, String module, int expected)
      throws Exception {
    assertEquals(202, catalog.catalog().totalScenarioCount());
    assertEquals(17, catalog.catalog().modules().size());
    UUID blocker = run("BLOCK", 1);
    scheduler.enqueue(blocker);
    started(blocker);
    var resource = resource("NORMAL");
    TestRunResponse response = service.create(
        new CreateTestRunRequest(resource.getId(), null, profile,
            "all".equals(module) ? List.of() : List.of(module), List.of(), List.of(), null, true));
    created.add(response.id());
    assertEquals(expected, response.totalCases());
    Long count = context.getBean(org.springframework.jdbc.core.JdbcTemplate.class)
        .queryForObject("select count(*) from test_run_selected_scenario where run_id = ?",
            Long.class, response.id());
    assertEquals(expected, count);
    assertEquals(expected, selections.findByRun_IdOrderByScenarioId(response.id()).size());
    if (!"all".equals(module)) {
      assertTrue(selections.findByRun_IdOrderByScenarioId(response.id()).stream()
          .allMatch(item -> module.equals(item.getModuleId())));
    }
    scheduler.cancel(response.id());
    release(blocker);
    done(blocker, TestRunStatus.SUCCEEDED, null);
  }

  @Test
  void historicalSnapshotUnaffectedByResourceMetadataUpdates() throws Exception {
    UUID id = run("NORMAL", 1);
    transactions.executeWithoutResult(tx -> {
      var entity = runs.findById(id).orElseThrow();
      entity.getConnection()
          .update("B", "postgresql", "postgresql", "jdbc:postgresql://new/db", "NORMAL", null,
              entity.getDriver());
      entity.getDriver().updateMetadata("D2", "Vendor2", "V2", null, "org.changed.Driver");
      entity.getDriver().setPackageFingerprint("F2");
    });
    mvc.perform(get("/api/runs/{id}", id)).andExpect(status().isOk())
        .andExpect(jsonPath("$.connection.name").value("A"))
        .andExpect(jsonPath("$.driver.name").value("D1"))
        .andExpect(jsonPath("$.driver.version").value("V1"))
        .andExpect(jsonPath("$.driver.packageFingerprint").value("F1"));
  }

  private UUID run(String mode, int count) {
    var connection = resource(mode);
    UUID id = transactions.execute(tx -> {
      var persistent = connections.findById(connection.getId()).orElseThrow();
      var driver = persistent.getDefaultDriver();
      var run = new TestRunEntity(persistent, driver, "CUSTOM", "A", "postgresql", "postgresql",
          "jdbc:postgresql://host/db", "D1", "Vendor", "V1", "org.external.Driver", "F1");
      run.setTotalCases(count);
      run.freezeExecutionInput(new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
          "jdbcUrl", "jdbc:postgresql://host/db", "username", mode,
          "scope", Map.of("database", "db", "schema", "qa_test"),
          "objectPrefix", "j01234567890123456789", "destructiveConsent", true,
          "capabilities",
          com.jdbctest.catalog.AdapterExecutionConfig.load("postgresql").capabilities())));
      runs.save(run);
      catalog.catalog().scenarios().stream().filter(item -> item.moduleId().equals("connection"))
          .sorted(java.util.Comparator.comparing(RunCatalogService.Scenario::id)).limit(count)
          .forEach(item -> selections.save(
              new TestRunSelectedScenarioEntity(run, item.id(), item.moduleId())));
      credentials.save(new TestRunCredentialEntity(run.getId(), encryption.encrypt("fake-secret")));
      return run.getId();
    });
    created.add(id);
    return id;
  }

  private DatabaseConnectionProfileEntity resource(String mode) {
    return transactions.execute(tx -> {
      var driver = new JdbcDriverPackageEntity("D1", "Vendor", "V1", "org.external.Driver",
          DriverSourceType.UPLOAD);
      driver.setStatus(DriverPackageStatus.VALIDATED);
      driver.setPackageFingerprint("F1");
      driver = drivers.save(driver);
      var connection = new DatabaseConnectionProfileEntity("A", "postgresql", "postgresql",
          "jdbc:postgresql://host/db", mode);
      connection.setExecutionScope("db", "qa_test");
      connection.setDefaultDriver(driver);
      connection.setEncryptedPassword(encryption.encrypt("fake-secret"));
      return connections.save(connection);
    });
  }

  private MvcResult subscribe(UUID id) throws Exception {
    return mvc.perform(get("/api/runs/{id}/events", id)).andExpect(status().isOk())
        .andExpect(request().asyncStarted()).andReturn();
  }

  private TestRunStatus state(UUID id) {
    return runs.findById(id).orElseThrow().getStatus();
  }

  private long started(UUID id) throws Exception {
    signal(id, "started");
    assertEquals(TestRunStatus.RUNNING, state(id));
    long pid = Long.parseLong(Files.readString(workspace(id).resolve("started")));
    Files.writeString(workspace(id).resolve("proceed"), "go");
    return pid;
  }

  private void release(UUID id) throws java.io.IOException {
    Files.writeString(workspace(id).resolve("release"), "go");
  }

  private void done(UUID id, TestRunStatus expected, String reason) throws Exception {
    awaitComplete(id);
    assertEquals(expected, state(id));
    assertEquals(reason, service.get(id).failureReason());
    assertNotNull(service.get(id).finishedAt());
    assertFalse(credentials.existsById(id));
  }

  private void awaitComplete(UUID id) throws InterruptedException {
    assertTrue(
        completed.computeIfAbsent(id, ignored -> new CountDownLatch(1)).await(25, TimeUnit.SECONDS),
        "No terminal completion for " + id + ": " + state(id));
  }

  private static Path workspace(UUID id) {
    return DATA.resolve("runs").resolve(id.toString());
  }

  private static void signal(UUID id, String name) throws Exception {
    Path directory = workspace(id);
    Files.createDirectories(directory);
    try (var watcher = directory.getFileSystem().newWatchService()) {
      directory.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
      while (!Files.exists(directory.resolve(name))) {
        var key = watcher.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        assertNotNull(key, "Worker signal missing: " + id + "/" + name);
        key.pollEvents();
        assertTrue(key.reset());
      }
    }
  }

  private static void noSecrets(String output) {
    assertNotNull(output);
    assertFalse(output.contains("fake-secret"), output);
    assertFalse(output.contains("fake-token"), output);
  }

  private static Path dataDirectory() {
    try {
      return Files.createTempDirectory("deterministic-runs-");
    } catch (java.io.IOException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  private static Path workerJar() {
    try {
      return FakeWorkerJar.create(DATA);
    } catch (java.io.IOException | java.net.URISyntaxException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }
}
