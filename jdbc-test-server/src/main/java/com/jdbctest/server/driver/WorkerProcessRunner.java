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

    public WorkerProcessRunner(DriverWorkerProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public Result run(String mode, Object request, java.time.Duration timeout)
        throws IOException, InterruptedException {
        Path worker = properties.getExecutable();
        if (worker == null || !Files.isRegularFile(worker)) {
            throw new IOException("Worker executable is unavailable");
        }
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-jar", worker.toAbsolutePath().toString(),
            mode).start();
        ByteArrayOutputStream stdout = "--describe-catalog".equals(mode)
            ? new CatalogCapture() : new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread stdoutReader = Thread.ofVirtual()
            .start(() -> drain(process.getInputStream(), stdout));
        Thread stderrReader = Thread.ofVirtual()
            .start(() -> drain(process.getErrorStream(), stderr));
        try {
            if ("--describe-catalog".equals(mode)) {
                process.getOutputStream().close();
            } else {
                mapper.writeValue(process.getOutputStream(), request);
                process.getOutputStream().close();
            }
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                terminate(process);
                return new Result(-1, true, "", "");
            }
            stdoutReader.join(1_000);
            stderrReader.join(1_000);
            return new Result(process.exitValue(), false, stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException exception) {
            terminate(process);
            throw exception;
        }
    }

    /**
     * Metadata-only handshake: no driver, credentials or target request is sent.
     */
    public void requireRunProtocol() throws IOException, InterruptedException {
        Result result = run("--describe-catalog", java.util.Map.of(),
            java.time.Duration.ofSeconds(30));
        if (!result.timedOut() && result.exitCode() == 0) {
            for (String line : result.lines()) {
                try {
                    var event = mapper.readTree(line);
                    if ("CATALOG_DESCRIBED".equals(event.path("type").asString())
                        && event.path("protocolVersion").asInt(0) == 2) {
                        return;
                    }
                } catch (RuntimeException ignored) { /* Not a valid handshake. */ }
            }
        }
        throw new ProtocolMismatchException();
    }

    public static final class ProtocolMismatchException extends IOException {

        public ProtocolMismatchException() {
            super("WORKER_PROTOCOL_MISMATCH: expected protocolVersion 2");
        }
    }

    private void terminate(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(properties.getTerminationGracePeriod().toMillis(),
            TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
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
