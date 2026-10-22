package com.jdbctest.worker;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded public diagnostics; classify only explicit SQL states, never message guesses. */
final class ConnectionProbeFailure {

    private ConnectionProbeFailure() {
    }

    static Map<String, Object> describe(String stage, Throwable failure) {
        Map<String, Object> result = new LinkedHashMap<>();
        String code = "LOADING_DRIVER".equals(stage) ? "CONNECTION_DRIVER_LOAD_FAILED"
            : "READING_METADATA".equals(stage) ? "CONNECTION_METADATA_FAILED"
            : "CONNECTION_UNKNOWN";
        var details = new ArrayList<Map<String, Object>>();
        var seen = new IdentityHashMap<Throwable, Boolean>();
        Throwable current = failure;
        while (current != null && details.size() < 8 && seen.put(current, true) == null) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("exceptionType", current.getClass().getName());
            String message = CredentialSanitizer.sanitize(current.getMessage());
            detail.put("message", message.substring(0, Math.min(message.length(), 2000)));
            if (current instanceof SQLException sql) {
                if (sql.getSQLState() != null) {
                    String state = CredentialSanitizer.sanitize(sql.getSQLState());
                    detail.put("sqlState", state.substring(0, Math.min(state.length(), 2000)));
                }
                detail.put("vendorCode", sql.getErrorCode());
                if ("CONNECTION_UNKNOWN".equals(code) && "ESTABLISHING_CONNECTION".equals(stage)) {
                    String state = sql.getSQLState();
                    if (state != null && state.matches("28[A-Za-z0-9]{3}")) {
                        code = "CONNECTION_AUTHENTICATION_FAILED";
                    } else if ("3D000".equals(state)) {
                        code = "CONNECTION_DATABASE_NOT_FOUND";
                    } else if ("08004".equals(state)) {
                        code = "CONNECTION_REJECTED";
                    }
                }
                current = sql.getNextException() == null ? sql.getCause() : sql.getNextException();
            } else {
                current = current.getCause();
            }
            details.add(detail);
        }
        result.put("errorType", code);
        result.put("stage", stage);
        result.put("diagnostics", details);
        return result;
    }
}
