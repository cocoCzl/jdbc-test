package com.jdbctest.extension;

/** A fixture or runtime prerequisite failed before the JDBC contract was invoked. */
public final class ExecutionPrerequisiteException extends org.opentest4j.TestAbortedException {

    private final String prerequisite;
    private final String blockedBy;

    public ExecutionPrerequisiteException(String message, Throwable cause) {
        this(message, cause, null, null);
    }

    public ExecutionPrerequisiteException(String message, Throwable cause, String prerequisite,
        String blockedBy) {
        super(message, cause);
        this.prerequisite = prerequisite;
        this.blockedBy = blockedBy;
    }

    public String prerequisite() {
        return prerequisite;
    }

    public String blockedBy() {
        return blockedBy;
    }
}
