package com.jdbctest.server.driver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Starts a short-lived worker and owns only its process and protocol IO lifecycle.
 */
@Component
public final class WorkerProcessRunner {

    private static final int DIAGNOSTIC_LIMIT = 64 * 1024;
    private static final int CATALOG_LIMIT = 1024 * 1024;
    private final DriverWorkerProperties properties;
    private final ObjectMapper mapper;
    private final ShortWorkerLimiter limiter;
    private final java.util.Set<Process> processes = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private boolean closed;

    public WorkerProcessRunner(DriverWorkerProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.limiter = new ShortWorkerLimiter(properties);
    }

    public Result run(String mode, Object request, java.time.Duration timeout)
        throws IOException, InterruptedException {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Short Workers must run outside database transactions");
        }
        try (var lease = limiter.acquire()) {
            return execute(mode, request, timeout);
        }
    }

    private Result execute(String mode, Object request, java.time.Duration timeout)
        throws IOException, InterruptedException {
        Path worker = properties.getExecutable();
        if (worker == null || !Files.isRegularFile(worker)) {
            throw new IOException("Worker executable is unavailable");
        }
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process;
        synchronized (this) {
            if (closed) {
                throw new DriverException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "WORKER_BUSY", "Worker runner is shutting down");
            }
            process = new ProcessBuilder(java, "-jar", worker.toAbsolutePath().toString(), mode).start();
            processes.add(process);
        }
        ByteArrayOutputStream stdout = "--describe-catalog".equals(mode)
            ? new CatalogCapture() : new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread stdoutReader = Thread.ofVirtual().start(() -> drain(process.getInputStream(), stdout));
        Thread stderrReader = Thread.ofVirtual().start(() -> drain(process.getErrorStream(), stderr));
        var writeFailure = new java.util.concurrent.atomic.AtomicReference<IOException>();
        Thread writer = Thread.ofVirtual().start(() -> {
            try (var output = process.getOutputStream()) {
                if (!"--describe-catalog".equals(mode)) {
                    mapper.writeValue(output, request);
                }
            } catch (IOException exception) {
                writeFailure.set(exception);
                process.destroy();
            }
        });
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return new Result(-1, true, "", "");
            }
            writer.join(1_000);
            stdoutReader.join(1_000);
            stderrReader.join(1_000);
            if (writeFailure.get() != null) {
                throw writeFailure.get();
            }
            return new Result(process.exitValue(), false, stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
        } finally {
            terminate(process);
            processes.remove(process);
            writer.interrupt();
            stdoutReader.interrupt();
            stderrReader.interrupt();
        }
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        synchronized (this) {
            closed = true;
            limiter.close();
        }
        processes.forEach(this::terminate);
    }

    /**
     * Metadata-only handshake: no driver, credentials or target request is sent.
     */
    public void requireRunProtocol() throws IOException, InterruptedException {
        requireRunProtocol(false);
    }

    public void requireRunProtocol(boolean requireAdapterSnapshots)
        throws IOException, InterruptedException {
        Result result = run("--describe-catalog", java.util.Map.of(),
            java.time.Duration.ofSeconds(30));
        if (!result.timedOut() && result.exitCode() == 0) {
            for (String line : result.lines()) {
                try {
                    var event = mapper.readTree(line);
                    if ("CATALOG_DESCRIBED".equals(event.path("type").asString())
                        && event.path("protocolVersion").asInt(0) == 2
                        && (!requireAdapterSnapshots
                        || event.path("adapterSnapshotVersion").asInt(0) == 1)) {
                        return;
                    }
                } catch (RuntimeException ignored) {
                    // A diagnostic line is not a handshake; inspect the next line.
                    continue;
                }
            }
        }
        throw new ProtocolMismatchException();
    }

    public static final class ProtocolMismatchException extends IOException {

        public ProtocolMismatchException() {
            super("WORKER_PROTOCOL_MISMATCH: expected protocolVersion 2");
        }
    }

    private void terminate(Process process) {
        // Snapshot descendants before destroying the parent, otherwise they can be reparented.
        var descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        boolean interrupted = Thread.interrupted();
        try {
            if (!process.waitFor(properties.getTerminationGracePeriod().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
            for (var child : descendants) {
                if (child.isAlive()) {
                    child.destroyForcibly();
                }
            }
            process.waitFor();
        } catch (InterruptedException exception) {
            interrupted = true;
            process.destroyForcibly();
            descendants.forEach(ProcessHandle::destroyForcibly);
            while (process.isAlive()) {
                try {
                    process.waitFor();
                } catch (InterruptedException again) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void drain(java.io.InputStream input, ByteArrayOutputStream captured) {
        int limit = captured instanceof CatalogCapture ? CATALOG_LIMIT : DIAGNOSTIC_LIMIT;
        try (input) {
            byte[] bytes = new byte[4096];
            int count;
            while ((count = input.read(bytes)) >= 0) {
                int remaining = limit - captured.size();
                if (remaining > 0) {
                    captured.write(bytes, 0, Math.min(count, remaining));
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static final class CatalogCapture extends ByteArrayOutputStream {

    }

    public record Result(int exitCode, boolean timedOut, String stdout, String stderr) {

        public List<String> lines() {
            return stdout.lines().filter(line -> !line.isBlank()).toList();
        }
    }
}
