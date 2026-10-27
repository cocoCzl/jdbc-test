package com.jdbctest.server.run;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Commit-aware, bounded delivery. Persistent snapshots are the reconnect/recovery protocol. */
@Component
final class RunEventPublisher {

    private static final int QUEUE_CAPACITY = 64;
    private final Map<UUID, CopyOnWriteArrayList<Subscriber>> subscribers = new ConcurrentHashMap<>();
    private final Semaphore capacity = new Semaphore(256);
    private final Supplier<SseEmitter> emitterFactory;
    private volatile boolean closed;

    RunEventPublisher() {
        this(() -> new SseEmitter(30_000L));
    }

    RunEventPublisher(Supplier<SseEmitter> emitterFactory) {
        this.emitterFactory = emitterFactory;
    }

    SseEmitter subscribe(UUID runId, TestRunResponse snapshot) {
        return subscribe(runId, snapshot, Runnable::run);
    }

    SseEmitter subscribe(UUID runId, TestRunResponse snapshot,
        java.util.function.Consumer<Runnable> whenTransportReady) {
        if (closed || !capacity.tryAcquire()) {
            throw new RunException(HttpStatus.SERVICE_UNAVAILABLE, "SSE_BUSY",
                "Event subscriber capacity is full; retry or refresh the run");
        }
        Subscriber subscriber;
        try {
            subscriber = new Subscriber(runId, emitterFactory.get());
        } catch (RuntimeException | Error exception) {
            capacity.release();
            throw exception;
        }
        try {
            subscribers.compute(runId, (id, values) -> {
                var current = values == null ? new CopyOnWriteArrayList<Subscriber>() : values;
                current.add(subscriber);
                return current;
            });
            subscriber.emitter.onCompletion(subscriber::stop);
            subscriber.emitter.onTimeout(subscriber::stop);
            subscriber.emitter.onError(error -> subscriber.stop());
            whenTransportReady.accept(subscriber::transportReady);
            // Assign before start: shutdown/error callbacks must always see the sender.
            subscriber.sender = Thread.ofVirtual().name("run-sse-" + runId).unstarted(subscriber::pump);
            subscriber.sender.start();
            // Registration happens while the snapshot's run lock is held. Buffer concurrent commits;
            // the snapshot sequence discards events already included in that snapshot.
            afterCommit(() -> subscriber.initialize(snapshot), subscriber::stop);
            if (closed) {
                subscriber.stop();
            }
            return subscriber.emitter;
        } catch (RuntimeException | Error exception) {
            subscriber.stop();
            // Once started, the sender owns release, including completion failures.
            if (subscriber.sender == null || subscriber.sender.getState() == Thread.State.NEW) {
                capacity.release();
            }
            throw exception;
        }
    }

    void publish(TestRunEntity run, String name, Map<String, ?> body) {
        long sequence = run.nextEventSequence();
        var payload = new java.util.LinkedHashMap<String, Object>(body);
        payload.put("eventSequence", sequence);
        Event event = new Event(sequence, name, payload, terminal(name, body));
        UUID runId = run.getId();
        afterCommit(() -> {
            var values = subscribers.get(runId);
            if (values != null) {
                values.forEach(subscriber -> subscriber.offer(event));
            }
        }, () -> { });
    }

    // Completion must follow the terminal send, even if afterCommit callbacks interleave.
    void complete(UUID runId) {
        afterCommit(() -> {
            var values = subscribers.get(runId);
            if (values != null) {
                values.forEach(Subscriber::requestCompletion);
            }
        }, () -> { });
    }

    private static boolean terminal(String name, Map<String, ?> body) {
        return name.equals("run.finished") || name.equals("run.failed") || name.equals("run.cancelled")
            || (name.equals("run.status") && java.util.Set.of("SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED")
                .contains(body.get("status")));
    }

    private static void afterCommit(Runnable action, Runnable rollback) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    rollback.run();
                }
            }
        });
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        closed = true;
        subscribers.values().forEach(values -> values.forEach(Subscriber::stop));
    }

    private record Event(long sequence, String name, Object body, boolean terminal) {

    }

    private final class Subscriber {

        private final UUID runId;
        private final SseEmitter emitter;
        private final TreeMap<Long, Event> queue = new TreeMap<>();
        private TestRunResponse snapshot;
        private long next;
        private boolean stopped;
        private boolean transportReady;
        private boolean completionRequested;
        private boolean terminalSent;
        private volatile Thread sender;

        private Subscriber(UUID runId, SseEmitter emitter) {
            this.runId = runId;
            this.emitter = emitter;
        }

        private synchronized void transportReady() {
            transportReady = true;
            notifyAll();
        }

        private synchronized void initialize(TestRunResponse value) {
            snapshot = value;
            next = value.eventSequence() + 1;
            queue.headMap(next).clear();
            notifyAll();
        }

        private synchronized void offer(Event event) {
            if (stopped || (snapshot != null && event.sequence() < next)) {
                return;
            }
            if (queue.size() >= QUEUE_CAPACITY) {
                // No silent terminal loss: disconnect this subscriber; reconnect gets DB truth.
                stop();
                return;
            }
            queue.put(event.sequence(), event);
            notifyAll();
        }

        private synchronized void requestCompletion() {
            completionRequested = true;
            notifyAll();
        }

        private synchronized void stop() {
            stopped = true;
            queue.clear();
            subscribers.computeIfPresent(runId, (id, values) -> {
                values.remove(this);
                return values.isEmpty() ? null : values;
            });
            if (sender != null && sender != Thread.currentThread()) {
                sender.interrupt();
            }
            notifyAll();
        }

        private void pump() {
            try {
                TestRunResponse initial;
                synchronized (this) {
                    while (!stopped && (snapshot == null || !transportReady)) {
                        wait();
                    }
                    if (stopped) {
                        return;
                    }
                    initial = snapshot;
                }
                emitter.send(SseEmitter.event().name("run.snapshot").data(initial));
                terminalSent = TestRunStateTransitions.terminal(initial.status());
                while (true) {
                    Event event;
                    synchronized (this) {
                        while (!stopped && !(terminalSent && completionRequested) && !queue.containsKey(next)) {
                            wait();
                        }
                        if (stopped || (terminalSent && completionRequested)) {
                            return;
                        }
                        event = queue.remove(next++);
                    }
                    emitter.send(SseEmitter.event().id(Long.toString(event.sequence()))
                        .name(event.name()).data(event.body()));
                    terminalSent = event.terminal();
                }
            } catch (IOException | IllegalStateException exception) {
                // Broken clients recover from the persisted snapshot on reconnect.
                stop();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                stop();
                try {
                    emitter.complete();
                } catch (IllegalStateException exception) {
                    org.slf4j.LoggerFactory.getLogger(RunEventPublisher.class)
                        .debug("SSE context already closed for run {}", runId);
                } finally {
                    capacity.release();
                }
            }
        }
    }
}
