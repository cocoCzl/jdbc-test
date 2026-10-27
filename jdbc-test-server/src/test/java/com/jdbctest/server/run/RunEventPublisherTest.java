package com.jdbctest.server.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class RunEventPublisherTest {

    @Test
    void rollbackDoesNotBroadcastAndTerminalPrecedesCompletion() throws Exception {
        var emitter = new RecordingEmitter(false);
        var publisher = new RunEventPublisher(() -> emitter);
        var run = run();
        try {
            publisher.subscribe(run.getId(), snapshot(0, TestRunStatus.RUNNING));
            assertTrue(emitter.initial.await(5, TimeUnit.SECONDS));
            transaction(false, () -> publisher.publish(run, "run.progress", Map.of("completedCases", 1)));
            // Rollback restores the entity in real JPA; restore this standalone test entity too.
            ReflectionTestUtils.setField(run, "eventSequence", 0L);
            assertEquals(List.of("run.snapshot"), emitter.names);
            transaction(true, () -> {
                publisher.publish(run, "run.finished", Map.of("status", "SUCCEEDED"));
                publisher.complete(run.getId());
                assertEquals(List.of("run.snapshot"), emitter.names);
            });
            assertTrue(emitter.completed.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("run.snapshot", "run.finished", "complete"), emitter.names);
        } finally {
            publisher.shutdown();
        }
    }

    @Test
    void slowSubscriberDoesNotBlockOtherSubscribersOrPublisherAndOverflowDisconnects() throws Exception {
        var slow = new RecordingEmitter(true);
        var fast = new RecordingEmitter(false);
        var emitters = new java.util.concurrent.ConcurrentLinkedQueue<>(List.of(slow, fast));
        var publisher = new RunEventPublisher(emitters::remove);
        var run = run();
        try {
            publisher.subscribe(run.getId(), snapshot(0, TestRunStatus.RUNNING));
            publisher.subscribe(run.getId(), snapshot(0, TestRunStatus.RUNNING));
            assertTrue(slow.initial.await(5, TimeUnit.SECONDS));
            assertTrue(fast.initial.await(5, TimeUnit.SECONDS));
            publisher.publish(run, "run.finished", Map.of("status", "SUCCEEDED"));
            publisher.complete(run.getId());
            assertTrue(fast.completed.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("run.snapshot", "run.finished", "complete"), fast.names);
            for (int index = 0; index < 70; index++) {
                publisher.publish(run, "run.progress", Map.of("completedCases", index));
            }
            assertTrue(slow.completed.await(5, TimeUnit.SECONDS));
        } finally {
            slow.release.countDown();
            publisher.shutdown();
        }
    }

    @Test
    void snapshotDiscardsOlderCallbacksAndReordersCommittedEventsBeforeClosing() throws Exception {
        var emitter = new RecordingEmitter(false);
        var publisher = new RunEventPublisher(() -> emitter);
        var run = run();
        try {
            // Buffer callbacks before initialization, as can happen while snapshot transaction commits.
            TransactionSynchronizationManager.initSynchronization();
            publisher.subscribe(run.getId(), snapshot(1, TestRunStatus.RUNNING));
            var snapshotCallbacks = TransactionSynchronizationManager.getSynchronizations();
            TransactionSynchronizationManager.clearSynchronization();
            publisher.publish(run, "run.progress", Map.of("completedCases", 1));
            TransactionSynchronizationManager.initSynchronization();
            publisher.publish(run, "run.progress", Map.of("completedCases", 2));
            var delayed = TransactionSynchronizationManager.getSynchronizations();
            TransactionSynchronizationManager.clearSynchronization();
            publisher.publish(run, "run.finished", Map.of("status", "SUCCEEDED"));
            publisher.complete(run.getId());
            commit(snapshotCallbacks);
            commit(delayed);
            assertTrue(emitter.completed.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("run.snapshot", "run.progress", "run.finished", "complete"), emitter.names);
            assertEquals(List.of("2", "3"), emitter.ids);
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
            publisher.shutdown();
        }
    }

    @Test
    void reconnectToTerminalSnapshotCompletesWithoutReplayingOldEvents() throws Exception {
        var emitter = new RecordingEmitter(false);
        var publisher = new RunEventPublisher(() -> emitter);
        var id = UUID.randomUUID();
        try {
            publisher.subscribe(id, snapshot(9, TestRunStatus.FAILED));
            publisher.complete(id);
            assertTrue(emitter.completed.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("run.snapshot", "complete"), emitter.names);
        } finally {
            publisher.shutdown();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {2, 65})
    void transportInitializationCannotBypassTheBoundedQueue(int pending) throws Exception {
        var emitter = new RecordingEmitter(false);
        var publisher = new RunEventPublisher(() -> emitter);
        var ready = new java.util.concurrent.atomic.AtomicReference<Runnable>();
        var run = run();
        try {
            publisher.subscribe(run.getId(), snapshot(0, TestRunStatus.RUNNING), ready::set);
            for (int index = 1; index < pending; index++) {
                publisher.publish(run, "run.progress", Map.of("completedCases", index));
            }
            publisher.publish(run, "run.finished", Map.of("status", "SUCCEEDED"));
            publisher.complete(run.getId());
            assertFalse(emitter.names.contains("run.snapshot"), "must not enter MVC's early-send buffer");
            ready.get().run();
            assertTrue(emitter.completed.await(5, TimeUnit.SECONDS));
            if (pending == 2) {
                assertEquals(List.of("run.snapshot", "run.progress", "run.finished", "complete"), emitter.names);
            } else {
                assertEquals(List.of("complete"), emitter.names, "overflow disconnects before any send");
            }
        } finally {
            publisher.shutdown();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"timeout", "error", "completion", "shutdown"})
    void disconnectCallbacksAndShutdownReleaseSubscriberQuota(String cause) throws Exception {
        var emitter = new RecordingEmitter(false);
        var publisher = new RunEventPublisher(() -> emitter);
        try {
            publisher.subscribe(UUID.randomUUID(), snapshot(0, TestRunStatus.RUNNING));
            assertTrue(emitter.initial.await(5, TimeUnit.SECONDS));
            switch (cause) {
                case "timeout" -> emitter.timeoutCallback.run();
                case "error" -> emitter.errorCallback.accept(new IOException("fixture disconnect"));
                case "completion" -> emitter.completionCallback.run();
                case "shutdown" -> publisher.shutdown();
                default -> throw new AssertionError(cause);
            }
            assertTrue(emitter.completed.await(5, TimeUnit.SECONDS));
            var capacity = (java.util.concurrent.Semaphore) ReflectionTestUtils.getField(publisher, "capacity");
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
                capacity.acquire(256);
                capacity.release(256);
            });
        } finally {
            publisher.shutdown();
        }
    }

    @Test
    void setupAndCompletionFailuresReleaseCapacityWithoutAffectingAnotherSubscriber() throws Exception {
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        RecordingEmitter failed = new RecordingEmitter(false) {
            @Override
            public void complete() {
                super.complete();
                throw new IllegalStateException("fixture completion failure");
            }
        };
        var survivor = new RecordingEmitter(false);
        var publisher = new RunEventPublisher(() -> {
            if (attempts.incrementAndGet() <= 256) {
                throw new IllegalStateException("fixture initialization failure");
            }
            return attempts.get() == 257 ? failed : survivor;
        });
        var run = run();
        try {
            for (int index = 0; index < 256; index++) {
                assertThrows(IllegalStateException.class,
                    () -> publisher.subscribe(run.getId(), snapshot(0, TestRunStatus.RUNNING)));
            }
            publisher.subscribe(run.getId(), snapshot(0, TestRunStatus.RUNNING));
            publisher.subscribe(run.getId(), snapshot(0, TestRunStatus.RUNNING));
            assertTrue(failed.initial.await(5, TimeUnit.SECONDS));
            assertTrue(survivor.initial.await(5, TimeUnit.SECONDS));
            publisher.publish(run, "run.finished", Map.of("status", "SUCCEEDED"));
            publisher.complete(run.getId());
            assertTrue(failed.completed.await(5, TimeUnit.SECONDS));
            assertTrue(survivor.completed.await(5, TimeUnit.SECONDS));
            var capacity = (java.util.concurrent.Semaphore) ReflectionTestUtils.getField(publisher, "capacity");
            // Joining is deterministic even when the completion latch fires before finally releases.
            // Both terminated subscriptions must relinquish their slots.
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
                capacity.acquire(256);
                capacity.release(256);
            });
        } finally {
            publisher.shutdown();
        }
    }

    private static TestRunEntity run() {
        var run = new TestRunEntity(null, null, "FULL", "fixture", "postgresql", "postgresql",
            "jdbc:fixture:none", "fixture", "fixture", "1", "fixture.Driver", null);
        ReflectionTestUtils.setField(run, "id", UUID.randomUUID());
        return run;
    }

    private static TestRunResponse snapshot(long sequence, TestRunStatus state) {
        var snapshot = mock(TestRunResponse.class);
        when(snapshot.eventSequence()).thenReturn(sequence);
        when(snapshot.status()).thenReturn(state);
        return snapshot;
    }

    private static void transaction(boolean success, Runnable action) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            action.run();
            var callbacks = TransactionSynchronizationManager.getSynchronizations();
            if (success) {
                commit(callbacks);
            } else {
                callbacks.forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static void commit(List<TransactionSynchronization> callbacks) {
        callbacks.forEach(TransactionSynchronization::afterCommit);
        callbacks.forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
    }

    private static class RecordingEmitter extends SseEmitter {

        private final List<String> names = new CopyOnWriteArrayList<>();
        private final List<String> ids = new CopyOnWriteArrayList<>();
        private final CountDownLatch initial = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch completed = new CountDownLatch(1);
        private final boolean slow;
        private Runnable timeoutCallback;
        private Runnable completionCallback;
        private java.util.function.Consumer<Throwable> errorCallback;

        @Override
        public void onTimeout(Runnable callback) {
            timeoutCallback = callback;
        }

        @Override
        public void onCompletion(Runnable callback) {
            completionCallback = callback;
        }

        @Override
        public void onError(java.util.function.Consumer<Throwable> callback) {
            errorCallback = callback;
        }

        private RecordingEmitter(boolean slow) {
            this.slow = slow;
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            String text = builder.build().stream().map(item -> String.valueOf(item.getData()))
                .collect(java.util.stream.Collectors.joining());
            var name = java.util.regex.Pattern.compile("event:([^\\n]+)").matcher(text);
            assertTrue(name.find());
            names.add(name.group(1));
            var id = java.util.regex.Pattern.compile("id:([^\\n]+)").matcher(text);
            if (id.find()) {
                ids.add(id.group(1));
            }
            initial.countDown();
            if (slow) {
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Disconnected slow fixture", exception);
                }
            }
        }

        @Override
        public void complete() {
            names.add("complete");
            completed.countDown();
        }
    }
}
