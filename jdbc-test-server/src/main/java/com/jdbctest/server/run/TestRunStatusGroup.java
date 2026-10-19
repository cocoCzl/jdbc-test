package com.jdbctest.server.run;

import java.util.List;
import org.springframework.http.HttpStatus;

/** History filters only; execution and terminal decisions use TestRunStatus. */
public enum TestRunStatusGroup {
    WAITING(TestRunStatus.QUEUED),
    IN_PROGRESS(TestRunStatus.PREPARING, TestRunStatus.PROBING, TestRunStatus.RUNNING,
        TestRunStatus.GENERATING_REPORT),
    COMPLETED(TestRunStatus.SUCCEEDED),
    ABNORMAL(TestRunStatus.FAILED, TestRunStatus.INTERRUPTED),
    CANCELLED(TestRunStatus.CANCELLED);

    private final List<TestRunStatus> statuses;

    TestRunStatusGroup(TestRunStatus... statuses) {
        this.statuses = List.of(statuses);
    }

    public List<TestRunStatus> statuses() {
        return statuses;
    }

    static TestRunStatusGroup parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new RunException(HttpStatus.BAD_REQUEST, "INVALID_STATUS_GROUP",
                "Unknown run status group");
        }
    }
}
