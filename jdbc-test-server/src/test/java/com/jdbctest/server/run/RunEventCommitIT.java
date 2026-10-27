package com.jdbctest.server.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.testcontainers.containers.PostgreSQLContainer;

class RunEventCommitIT {

    @Test
    void rowLockedCommitsCanOvertakeCallbacksWithoutReorderingDelivery() throws Exception {
        try (var database = new PostgreSQLContainer<>("postgres:16")) {
            database.start();
            var source = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
            var sql = new JdbcTemplate(source);
            sql.execute("create table event_order_fixture(id integer primary key, event_sequence bigint)");
            sql.update("insert into event_order_fixture values (1,0)");
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
            var emitter = new OrderedEmitter();
            var publisher = new RunEventPublisher(() -> emitter);
            var id = UUID.randomUUID();
            var snapshot = mock(TestRunResponse.class);
            when(snapshot.eventSequence()).thenReturn(0L);
            when(snapshot.status()).thenReturn(TestRunStatus.RUNNING);
            var committed = new CountDownLatch(1);
            var releaseCallback = new CountDownLatch(1);
            Thread first = null;
            try {
                publisher.subscribe(id, snapshot);
                assertTrue(emitter.initial.await(5, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(tx -> {
                    publish(sql, publisher, id, false);
                    throw new IllegalStateException("controlled rollback");
                }));
                assertEquals(0L, sql.queryForObject("select event_sequence from event_order_fixture", Long.class));
                var firstCommit = new FutureTask<Void>(() -> {
                    transactions.executeWithoutResult(tx -> {
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                committed.countDown();
                                try {
                                    assertTrue(releaseCallback.await(10, TimeUnit.SECONDS));
                                } catch (InterruptedException exception) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(exception);
                                }
                            }
                        });
                        publish(sql, publisher, id, false);
                    });
                    return null;
                });
                first = Thread.ofVirtual().start(firstCommit);
                assertTrue(committed.await(5, TimeUnit.SECONDS));
                // PostgreSQL has released the first row lock, but its delivery callback is paused.
                transactions.executeWithoutResult(tx -> publish(sql, publisher, id, true));
                assertEquals(2L, sql.queryForObject("select event_sequence from event_order_fixture", Long.class));
                assertEquals(1L, emitter.completed.getCount(), "terminal must wait for sequence 1");
                releaseCallback.countDown();
                firstCommit.get(5, TimeUnit.SECONDS);
                assertTrue(emitter.completed.await(5, TimeUnit.SECONDS));
                assertEquals(List.of("1", "2", "complete"), emitter.order);
            } finally {
                releaseCallback.countDown();
                publisher.shutdown();
                if (first != null) {
                    first.join(10_000);
                }
            }
        }
    }

    private static void publish(JdbcTemplate sql, RunEventPublisher publisher, UUID id, boolean terminal) {
        long sequence = sql.queryForObject("select event_sequence from event_order_fixture where id=1 for update", Long.class);
        var run = new TestRunEntity(null, null, "FULL", "fixture", "postgresql", "postgresql",
            "jdbc:fixture:none", "fixture", "fixture", "1", "fixture.Driver", null);
        ReflectionTestUtils.setField(run, "id", id);
        ReflectionTestUtils.setField(run, "eventSequence", sequence);
        publisher.publish(run, terminal ? "run.finished" : "run.progress", Map.of("status", terminal ? "SUCCEEDED" : "RUNNING"));
        sql.update("update event_order_fixture set event_sequence=? where id=1", sequence + 1);
        if (terminal) {
            publisher.complete(id);
        }
    }

    private static final class OrderedEmitter extends SseEmitter {

        private final CountDownLatch initial = new CountDownLatch(1);
        private final CountDownLatch completed = new CountDownLatch(1);
        private final List<String> order = new CopyOnWriteArrayList<>();

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            assertTrue(!TransactionSynchronizationManager.isActualTransactionActive());
            builder.build().forEach(item -> {
                var id = java.util.regex.Pattern.compile("id:([0-9]+)").matcher(String.valueOf(item.getData()));
                if (id.find()) {
                    order.add(id.group(1));
                }
            });
            initial.countDown();
        }

        @Override
        public void complete() {
            order.add("complete");
            completed.countDown();
        }
    }
}
