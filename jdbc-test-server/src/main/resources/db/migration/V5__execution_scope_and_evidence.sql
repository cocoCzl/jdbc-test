-- Existing Connections remain probeable; new Runs require explicit authorized scope.
alter table database_connection_profile add column execution_database varchar(63);
alter table database_connection_profile add column execution_schema varchar(63);
alter table test_run add column execution_input text;
alter table test_run add column evidence_version integer;
-- NULL evidence_version means legacy accounting; do not infer start evidence for old results.
alter table test_run_selected_scenario add column execution_started_at timestamp with time zone;
alter table test_run_selected_scenario add column execution_finished_at timestamp with time zone;
alter table test_run_selected_scenario add column result_status varchar(16);
alter table test_run add column execution_issues text;
alter table test_case_result add column execution_evidence text;
alter table test_run add column worker_report_validated boolean not null default false;
