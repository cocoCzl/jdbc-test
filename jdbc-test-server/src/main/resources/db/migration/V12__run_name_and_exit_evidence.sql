-- Additive evidence only: preserve historical statuses and reports.
ALTER TABLE test_run ADD COLUMN name varchar(200);
ALTER TABLE test_run ADD COLUMN worker_exit_code integer;
ALTER TABLE test_run ADD COLUMN worker_final_event text;
ALTER TABLE test_run ADD COLUMN worker_artifact_sha256 varchar(64);
