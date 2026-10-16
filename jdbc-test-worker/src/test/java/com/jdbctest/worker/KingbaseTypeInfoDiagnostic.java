package com.jdbctest.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.OutputStream;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Properties;

/**
 * Explicit, read-only reproduction tool, never a default CI database test. Reads the existing
 * connection-probe request from stdin; emits timings/counts, never URLs, credentials or type names.
 */
public final class KingbaseTypeInfoDiagnostic {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PrintStream output;
    private final long started = System.nanoTime();

    private KingbaseTypeInfoDiagnostic(PrintStream output) {
        this.output = output;
    }

    public static void main(String[] args) throws Exception {
        PrintStream originalOutput = System.out;
        PrintStream originalError = System.err;
        var diagnostic = new KingbaseTypeInfoDiagnostic(originalOutput);
        int exit = 0;
        try (var discarded = new PrintStream(OutputStream.nullOutputStream())) {
            System.setOut(discarded);
            System.setErr(discarded);
            try {
                var request = diagnostic.mapper.readValue(System.in, ConnectionProbeRequest.class);
                diagnostic.run(request);
            } catch (Exception exception) {
                // Driver exception text can contain connection details. Only publish its type.
                diagnostic.emit("diagnostic", "FAILED",
                    Map.of("errorType", exception.getClass().getName()));
                exit = 1;
            } finally {
                System.setOut(originalOutput);
                System.setErr(originalError);
            }
        }
        System.exit(exit);
    }

    private void run(ConnectionProbeRequest request) throws Exception {
        var loaded = phase("driver.load", () -> DriverLoader.load(request.driver()));
        try {
            var properties = new Properties();
            properties.setProperty("user", request.connection().username());
            properties.setProperty("password", request.connection().password());
            Connection connection = phase("connection.open",
                () -> loaded.driver().connect(request.connection().jdbcUrl(), properties));
            try {
                connection.setReadOnly(true);
                DatabaseMetaData metadata = phase("connection.metadata", connection::getMetaData);
                inspect(metadata, "next_only", false);
                inspect(metadata, "read_columns", true);
            } finally {
                phase("connection.close", () -> {
                    connection.close();
                    return null;
                });
            }
        } finally {
            phase("driver.close", () -> {
                loaded.close();
                return null;
            });
        }
        emit("diagnostic", "COMPLETED", Map.of());
    }

    private void inspect(DatabaseMetaData metadata, String pass, boolean readColumns)
        throws Exception {
        ResultSet rows = phase(pass + ".get_type_info", metadata::getTypeInfo);
        try {
            boolean first = phase(pass + ".first_next", rows::next);
            int count = phase(pass + ".iterate", () -> {
                int total = 0;
                boolean available = first;
                while (available) {
                    if (readColumns) {
                        rows.getString("TYPE_NAME");
                        rows.getInt("DATA_TYPE");
                    }
                    total++;
                    available = rows.next();
                }
                return total;
            });
            phase(pass + ".assertions", () -> {
                if (count == 0) {
                    throw new IllegalStateException("Expected at least one JDBC type");
                }
                return null;
            });
            emit(pass, "ROWS", Map.of("rowCount", count));
        } finally {
            phase(pass + ".result_set_close", () -> {
                rows.close();
                return null;
            });
        }
    }

    private <T> T phase(String name, CheckedSupplier<T> operation) throws Exception {
        emit(name, "STARTED", Map.of());
        long begin = System.nanoTime();
        try {
            T result = operation.get();
            long duration = System.nanoTime() - begin;
            emit(name, "COMPLETED", Map.of("durationNanos", duration));
            return result;
        } catch (Exception exception) {
            emit(name, "FAILED", Map.of("durationNanos", System.nanoTime() - begin,
                "errorType", exception.getClass().getName()));
            throw exception;
        }
    }

    private void emit(String phase, String state, Map<String, ?> details) throws Exception {
        output.println(mapper.writeValueAsString(Map.of("phase", phase, "state", state,
            "elapsedNanos", System.nanoTime() - started, "details", details)));
        output.flush();
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {

        T get() throws Exception;
    }
}
