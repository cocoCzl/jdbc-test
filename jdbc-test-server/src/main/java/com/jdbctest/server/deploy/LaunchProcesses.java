package com.jdbctest.server.deploy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Own only explicitly launched processes and their descendants, never processes found by
 * port/name.
 */
public final class LaunchProcesses implements AutoCloseable {

    private final List<Process> roots = new ArrayList<>();
    private final Map<ProcessHandle, Instant> descendants = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private final Thread tracker;

    public LaunchProcesses() throws Failure {
        try {
            ProcessHandle.current().descendants().count();
        } catch (RuntimeException exception) {
            throw new Failure(
                "Process-tree supervision is unavailable in this restricted environment; no services were started.");
        }
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "jdbc-test-stop"));
        tracker = Thread.ofVirtual().start(() -> {
            while (!closed) {
                synchronized (roots) {
                    roots.forEach(p -> p.descendants().forEach(h -> descendants.putIfAbsent(h,
                        h.info().startInstant().orElse(Instant.EPOCH))));
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        });
    }

    public static Map<String, String> basicEnvironment() {
        var result = new LinkedHashMap<String, String>();
        for (String name : List.of("PATH", "HOME", "USERPROFILE", "SystemRoot", "SYSTEMROOT",
            "ComSpec", "COMSPEC",
            "PATHEXT", "TEMP", "TMP", "TMPDIR", "LANG", "LC_ALL", "TERM", "JAVA_HOME")) {
            String value = System.getenv(name);
            if (value != null) {
                result.put(name, value);
            }
        }
        return result;
    }

    public static List<String> command(String executable, String... arguments) {
        var result = new ArrayList<String>();
        if (System.getProperty("os.name").startsWith("Windows") && executable.endsWith(".cmd")) {
            result.add("cmd.exe");
            result.add("/d");
            result.add("/c");
        }
        result.add(executable);
        result.addAll(List.of(arguments));
        return result;
    }

    public Process start(List<String> command, Path directory, Map<String, String> environment)
        throws Exception {
        return start(command, directory, environment, false);
    }

    public Process start(List<String> command, Path directory, Map<String, String> environment,
        boolean capture) throws Exception {
        var builder = new ProcessBuilder(command).directory(directory.toFile());
        if (capture) {
            builder.redirectErrorStream(true);
        } else {
            builder.inheritIO();
        }
        builder.environment().clear();
        builder.environment().putAll(environment);
        Process process = builder.start();
        synchronized (roots) {
            roots.add(process);
        }
        return process;
    }

    public void run(List<String> command, Path directory, Map<String, String> environment)
        throws Exception {
        int status = start(command, directory, environment).waitFor();
        if (status != 0) {
            throw new Failure("Build/command failed (exit " + status
                + "). Started child processes will be stopped; configuration/data are retained.");
        }
    }

    public static final class Failure extends Exception {

        public Failure(String message) {
            super(message);
        }
    }

    private boolean sameProcess(ProcessHandle handle, Instant start) {
        return handle.isAlive() && handle.info().startInstant().orElse(Instant.EPOCH).equals(start);
    }

    private boolean anyAlive() {
        synchronized (roots) {
            return roots.stream().anyMatch(Process::isAlive)
                || descendants.entrySet().stream()
                .anyMatch(e -> sameProcess(e.getKey(), e.getValue()));
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        tracker.interrupt();
        synchronized (roots) {
            for (Process process : roots) {
                try {
                    process.descendants().forEach(h -> descendants.putIfAbsent(h,
                        h.info().startInstant().orElse(Instant.EPOCH)));
                } catch (
                    RuntimeException ignored) { /* Still stop roots and all previously observed descendants. */ }
            }
        }
        // Leaves first; include tracked grandchildren even if their immediate parent already exited.
        descendants.forEach((h, start) -> {
            if (sameProcess(h, start)) {
                h.destroy();
            }
        });
        synchronized (roots) {
            roots.forEach(p -> {
                if (p.isAlive()) {
                    p.destroy();
                }
            });
        }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline && anyAlive()) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                break;
            }
        }
        descendants.forEach((h, start) -> {
            if (sameProcess(h, start)) {
                h.destroyForcibly();
            }
        });
        synchronized (roots) {
            roots.forEach(p -> {
                if (p.isAlive()) {
                    p.destroyForcibly();
                }
            });
        }
    }
}
