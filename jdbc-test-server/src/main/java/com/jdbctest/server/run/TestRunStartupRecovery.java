package com.jdbctest.server.run;

import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** A new Server cannot safely attach to an old Worker, so it records interruption explicitly. */
@Component
final class TestRunStartupRecovery implements ApplicationRunner {
    private final TestRunRepository runs; private final TestRunCredentialRepository credentials; private final TransactionTemplate transactions;
    TestRunStartupRecovery(TestRunRepository runs, TestRunCredentialRepository credentials, TransactionTemplate transactions) { this.runs = runs; this.credentials = credentials; this.transactions = transactions; }
    @Override public void run(ApplicationArguments args) {
        transactions.executeWithoutResult(tx -> runs.findByStatusIn(List.of(TestRunStatus.QUEUED, TestRunStatus.PREPARING, TestRunStatus.PROBING, TestRunStatus.RUNNING, TestRunStatus.GENERATING_REPORT)).forEach(run -> {
            run.setStatus(TestRunStatus.INTERRUPTED); run.markFinished(); run.setFailureReason("SERVER_RESTART"); credentials.deleteById(run.getId());
        }));
    }
}
