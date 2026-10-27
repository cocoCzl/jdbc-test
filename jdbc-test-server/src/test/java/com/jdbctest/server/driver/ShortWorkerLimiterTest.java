package com.jdbctest.server.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ShortWorkerLimiterTest {

    @Test
    void concurrentCapacityIsBoundedAndFailuresReleasePermits() throws Exception {
        var properties = new DriverWorkerProperties();
        properties.setMaxConcurrentShortWorkers(2);
        properties.setMaxPendingShortWorkers(0);
        var limiter = new ShortWorkerLimiter(properties);
        var started = new CountDownLatch(2);
        var finish = new CountDownLatch(1);
        Runnable task = () -> {
            try (var lease = limiter.acquire()) {
                started.countDown();
                assertTrue(finish.await(10, TimeUnit.SECONDS));
                throw new IllegalStateException("simulated failure");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IllegalStateException expected) {
                assertEquals("simulated failure", expected.getMessage());
            }
        };
        Thread first = Thread.ofVirtual().start(task);
        Thread second = Thread.ofVirtual().start(task);
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS));
            assertEquals("WORKER_BUSY", assertThrows(DriverException.class, limiter::acquire).code());
        } finally {
            finish.countDown();
            first.join(10_000);
            second.join(10_000);
        }
        try (var recovered = limiter.acquire(); var another = limiter.acquire()) {
            assertEquals("WORKER_BUSY", assertThrows(DriverException.class, limiter::acquire).code());
        }
        limiter.close();
        assertThrows(DriverException.class, limiter::acquire);
    }

    @Test
    void interruptedAndTimedOutWaitersDoNotLeakQueueSlots() throws Exception {
        var properties = new DriverWorkerProperties();
        properties.setMaxConcurrentShortWorkers(1);
        properties.setMaxPendingShortWorkers(1);
        properties.setShortWorkerWaitTimeout(Duration.ofSeconds(20));
        var limiter = new ShortWorkerLimiter(properties);
        try (var held = limiter.acquire()) {
            var interrupted = new CountDownLatch(1);
            Thread waiter = Thread.ofVirtual().start(() -> {
                try (var lease = limiter.acquire()) {
                    throw new AssertionError("Capacity must remain occupied");
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                }
            });
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (waiter.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                assertEquals(Thread.State.TIMED_WAITING, waiter.getState());
                assertEquals("WORKER_BUSY", assertThrows(DriverException.class, limiter::acquire).code());
            } finally {
                waiter.interrupt();
                waiter.join(10_000);
            }
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        }
        try (var recovered = limiter.acquire()) {
            org.junit.jupiter.api.Assertions.assertNotNull(recovered);
        }
        properties.setShortWorkerWaitTimeout(Duration.ZERO);
        var immediate = new ShortWorkerLimiter(properties);
        try (var held = immediate.acquire()) {
            assertThrows(DriverException.class, immediate::acquire);
            assertThrows(DriverException.class, immediate::acquire);
        }
        try (var recovered = immediate.acquire()) {
            org.junit.jupiter.api.Assertions.assertNotNull(recovered);
        }
    }
}
