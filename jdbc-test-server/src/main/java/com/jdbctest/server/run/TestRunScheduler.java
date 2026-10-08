package com.jdbctest.server.run;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Local FIFO dispatcher. Database status remains authoritative when a queued task begins.
 */
@Component
final class TestRunScheduler {

  private final ExecutorService queue;
  private final TestRunExecutor executor;
  private final TestRunRepository runs;
  private final TestRunCredentialRepository credentials;
  private final TransactionTemplate transactions;
  private final RunEventPublisher events;
  private final TestRunSelectedScenarioRepository selected;
  private volatile boolean accepting = true;

  TestRunScheduler(ExecutionProperties properties, TestRunExecutor executor, TestRunRepository runs,
      TestRunCredentialRepository credentials, TransactionTemplate transactions,
      RunEventPublisher events, TestRunSelectedScenarioRepository selected) {
    if (properties.getMaxConcurrentRuns() < 1) {
      throw new IllegalArgumentException("maxConcurrentRuns must be positive");
    }
    this.queue = Executors.newFixedThreadPool(properties.getMaxConcurrentRuns(),
        Thread.ofVirtual().name("jdbc-test-run-", 0).factory());
    this.executor = executor;
    this.runs = runs;
    this.credentials = credentials;
    this.transactions = transactions;
    this.events = events;
    this.selected = selected;
  }

  synchronized void enqueue(UUID runId) {
    if (!accepting) {
      throw new RunException(HttpStatus.SERVICE_UNAVAILABLE, "SERVER_SHUTTING_DOWN",
          "Server is shutting down");
    }
    queue.submit(() -> executor.execute(runId));
  }

  void cancel(UUID runId) {
    transactions.executeWithoutResult(tx -> {
      TestRunEntity run = runs.findForUpdate(runId).orElseThrow(
          () -> new RunException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "Test run was not found"));
      if (TestRunStateTransitions.terminal(run.getStatus())) {
        throw new RunException(HttpStatus.CONFLICT, "RUN_ALREADY_FINISHED",
            "Run has already finished");
      }
      run.setStatus(TestRunStatus.CANCELLED);
      run.markFinished();
      runs.save(run);
      credentials.deleteById(runId);
      events.publish(runId, "run.cancelled", terminalPayload(run));
      events.complete(runId);
    });
    executor.cancel(runId);
  }

  private Map<String, Object> terminalPayload(TestRunEntity run) {
    var payload = new java.util.LinkedHashMap<String, Object>();
    payload.put("runId", run.getId());
    payload.put("status", run.getStatus().name());
    if (run.getFailureReason() != null) {
      payload.put("reason", run.getFailureReason());
    }
    var accounting = ExecutionAccounting.of(run,
        selected.findByRun_IdOrderByScenarioId(run.getId()));
    if (accounting != null) {
      payload.put("accounting", accounting);
    }
    return payload;
  }

  @EventListener(ContextClosedEvent.class)
  synchronized void shutdown() {
    if (!accepting) {
      return;
    }
    accepting = false;
    try {
      List<UUID> interrupted = transactions.execute(
          tx -> runs.findIdsByStatusIn(List.of(TestRunStatus.QUEUED,
                  TestRunStatus.PREPARING, TestRunStatus.PROBING, TestRunStatus.RUNNING,
                  TestRunStatus.GENERATING_REPORT))
              .stream().map(id -> {
                TestRunEntity run = runs.findForUpdate(id).orElseThrow();
                if (TestRunStateTransitions.terminal(run.getStatus())) {
                  return null;
                }
                run.setStatus(TestRunStatus.INTERRUPTED);
                run.markFinished();
                run.setFailureReason("SERVER_SHUTDOWN");
                credentials.deleteById(run.getId());
                return run.getId();
              }).filter(java.util.Objects::nonNull).toList());
      if (interrupted != null) {
        for (UUID id : interrupted) {
          events.publish(id, "run.status",
              transactions.execute(tx -> terminalPayload(runs.findById(id).orElseThrow())));
          events.complete(id);
        }
      }
    } finally {
      executor.interruptAll();
      queue.shutdownNow();
      try {
        if (!queue.awaitTermination(30, TimeUnit.SECONDS)) {
          org.slf4j.LoggerFactory.getLogger(TestRunScheduler.class)
              .warn("Run dispatcher did not stop within shutdown grace period");
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
