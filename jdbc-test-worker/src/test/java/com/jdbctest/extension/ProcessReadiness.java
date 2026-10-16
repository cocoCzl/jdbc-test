package com.jdbctest.extension;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Reads a fixture's readiness line without leaving its process alive on timeout.
 */
final class ProcessReadiness {

    private ProcessReadiness() {
    }

    static String awaitLine(Process process, Duration timeout)
        throws InterruptedException, ExecutionException, TimeoutException {
        try (var reader = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),
            Thread.ofPlatform().daemon(true).name("fixture-readiness").factory(),
            new ThreadPoolExecutor.AbortPolicy())) {
            var line = reader.submit(() -> process.inputReader().readLine());
            try {
                return line.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException exception) {
                // Kill before ExecutorService.close(): interrupt alone need not unblock a pipe read.
                process.destroyForcibly();
                throw exception;
            } finally {
                line.cancel(true);
            }
        }
    }
}
