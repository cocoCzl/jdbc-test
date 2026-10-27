package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Additive projections, including malformed legacy JSON, on PostgreSQL and H2 fixtures. */
public class V15__query_summaries extends BaseJavaMigration {

    private static final int BATCH_SIZE = 500;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        try (var sql = connection.createStatement()) {
            sql.execute("ALTER TABLE test_run ADD COLUMN execution_summary text");
            sql.execute("ALTER TABLE test_run ADD COLUMN cleanup_succeeded boolean NOT NULL DEFAULT false");
            sql.execute("ALTER TABLE test_run ADD COLUMN execution_issues_empty boolean NOT NULL DEFAULT true");
            sql.execute("ALTER TABLE test_case_result ADD COLUMN contract_conclusion text");
            sql.execute("ALTER TABLE test_case_result ADD COLUMN requirement_scope text");
            sql.execute("ALTER TABLE test_case_result ADD COLUMN contract_invoked boolean NOT NULL DEFAULT false");
        }
        backfillRuns(connection);
        backfillCases(connection);
        try (var sql = connection.createStatement()) {
            sql.execute("CREATE INDEX idx_test_run_status_created_id ON test_run(status, created_at, id)");
            sql.execute("CREATE INDEX idx_test_run_created_id ON test_run(created_at, id)");
            sql.execute("CREATE INDEX idx_selected_run_module ON test_run_selected_scenario(run_id, module_id)");
            sql.execute("CREATE INDEX idx_result_run_contract ON test_case_result(run_id, contract_conclusion, requirement_scope, contract_invoked)");
        }
    }

    private void backfillRuns(Connection connection) throws SQLException {
        try (PreparedStatement read = connection.prepareStatement("SELECT id, cleanup_evidence, execution_issues, execution_input FROM test_run");
            PreparedStatement write = connection.prepareStatement("UPDATE test_run SET cleanup_succeeded=?, execution_issues_empty=?, execution_summary=? WHERE id=?")) {
            read.setFetchSize(BATCH_SIZE);
            try (ResultSet rows = read.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    var cleanup = readEvidence(rows.getString(2));
                    String rawIssues = rows.getString(3);
                    var issues = readEvidence(rawIssues);
                    write.setBoolean(1, "SUCCESS".equals(cleanup.path("status").asString()));
                    write.setBoolean(2, rawIssues == null || (issues.isArray() && issues.isEmpty()));
                    String rawInput = rows.getString(4);
                    write.setString(3, rawInput == null ? null : mapper.writeValueAsString(
                        com.jdbctest.server.run.RunInputSummary.compact(readEvidence(rawInput))));
                    write.setObject(4, rows.getObject(1));
                    write.addBatch();
                    if (++count % BATCH_SIZE == 0) {
                        write.executeBatch();
                    }
                }
                write.executeBatch();
            }
        }
    }

    private void backfillCases(Connection connection) throws SQLException {
        try (PreparedStatement read = connection.prepareStatement("SELECT id, execution_evidence FROM test_case_result");
            PreparedStatement write = connection.prepareStatement("UPDATE test_case_result SET contract_conclusion=?, requirement_scope=?, contract_invoked=? WHERE id=?")) {
            read.setFetchSize(BATCH_SIZE);
            try (ResultSet rows = read.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    var evidence = readEvidence(rows.getString(2));
                    write.setString(1, evidence.has("conclusion") ? evidence.path("conclusion").asString() : null);
                    write.setString(2, evidence.path("requirementScope").asString(null));
                    write.setBoolean(3, evidence.path("contractInvoked").asBoolean(false));
                    write.setObject(4, rows.getObject(1));
                    write.addBatch();
                    if (++count % BATCH_SIZE == 0) {
                        write.executeBatch();
                    }
                }
                write.executeBatch();
            }
        }
    }

    private JsonNode readEvidence(String value) {
        try {
            return value == null ? mapper.nullNode() : mapper.readTree(value);
        } catch (tools.jackson.core.JacksonException exception) {
            return mapper.nullNode();
        }
    }
}
