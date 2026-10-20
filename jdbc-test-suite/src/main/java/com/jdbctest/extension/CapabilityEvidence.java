package com.jdbctest.extension;

import org.junit.jupiter.api.TestReporter;

/** Boolean evidence only: no exception, SQL or credential text is published here. */
public final class CapabilityEvidence {

    private CapabilityEvidence() {
    }

    public static void record(TestReporter reporter, String key, boolean value) {
        reporter.publishEntry("jdbc.capability." + key, Boolean.toString(value));
        if (key.startsWith("metadata.")) {
            reporter.publishEntry("jdbc.contract.metadataBoolean", "true_or_false_allowed");
            reporter.publishEntry("jdbc.contract.source",
                "https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/DatabaseMetaData.html");
        }
        // Surefire XML preserves stdout; Worker preserves JUnit report entries.
        System.out.println("[JDBC_CAPABILITY] " + key + "=" + value);
    }
}
