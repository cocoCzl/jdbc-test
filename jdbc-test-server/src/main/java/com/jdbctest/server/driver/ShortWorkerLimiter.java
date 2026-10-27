package com.jdbctest.server.driver;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;

/** Bounded admission for short Workers, independent of the run dispatcher. */
final class ShortWorkerLimiter {

    private final Semaphore active;
    private final Semaphore pending;
    private final Duration waitTimeout;
    private volatile boolean closed;

    ShortWorkerLimiter(DriverWorkerProperties properties) {
        if (properties.getMaxConcurrentShortWorkers() < 1
            || properties.getMaxPendingShortWorkers() < 0
            || properties.getShortWorkerWaitTimeout().isNegative()) {
            throw new IllegalArgumentException("Invalid short Worker limits");
        }
        active = new Semaphore(properties.getMaxConcurrentShortWorkers(), true);
        pending = new Semaphore(properties.getMaxPendingShortWorkers(), true);
        waitTimeout = properties.getShortWorkerWaitTimeout();
    }

    Lease acquire() throws InterruptedException {
        if (closed) {
            throw busy();
        }
        if (!active.tryAcquire()) {
            if (!pending.tryAcquire()) {
                throw busy();
            }
            try {
                if (!active.tryAcquire(waitTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw busy();
                }
            } finally {
                pending.release();
            }
        }
        if (closed) {
            active.release();
            throw busy();
        }
        return new Lease();
    }

    void close() {
        closed = true;
    }

    private static DriverException busy() {
        return new DriverException(HttpStatus.SERVICE_UNAVAILABLE, "WORKER_BUSY",
            "Short Worker capacity is full or shutting down; retry later");
    }

    final class Lease implements AutoCloseable {

        private boolean released;

        @Override
        public void close() {
            if (!released) {
                released = true;
                active.release();
            }
        }
    }
}
