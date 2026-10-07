package com.jdbctest.server.run;

import java.util.EnumSet;

final class TestRunStateTransitions {
    private TestRunStateTransitions() { }
    static boolean terminal(TestRunStatus status) { return EnumSet.of(TestRunStatus.SUCCEEDED, TestRunStatus.FAILED, TestRunStatus.CANCELLED, TestRunStatus.INTERRUPTED).contains(status); }
    static boolean allows(TestRunStatus from, TestRunStatus to) {
        if (terminal(from)) return false;
        return switch (from) {
            case QUEUED -> EnumSet.of(TestRunStatus.PREPARING, TestRunStatus.CANCELLED, TestRunStatus.FAILED, TestRunStatus.INTERRUPTED).contains(to);
            case PREPARING -> EnumSet.of(TestRunStatus.RUNNING, TestRunStatus.FAILED, TestRunStatus.CANCELLED, TestRunStatus.INTERRUPTED).contains(to);
            case RUNNING -> EnumSet.of(TestRunStatus.GENERATING_REPORT, TestRunStatus.FAILED, TestRunStatus.CANCELLED, TestRunStatus.INTERRUPTED).contains(to);
            case GENERATING_REPORT -> EnumSet.of(TestRunStatus.SUCCEEDED, TestRunStatus.FAILED, TestRunStatus.CANCELLED, TestRunStatus.INTERRUPTED).contains(to);
            default -> false;
        };
    }
}
