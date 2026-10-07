alter table test_run add column adapter_id_snapshot varchar(100) not null default 'postgresql';
alter table test_run alter column adapter_id_snapshot drop default;

create table test_run_selected_scenario (
    id uuid primary key,
    run_id uuid not null references test_run(id),
    scenario_id varchar(200) not null,
    module_id varchar(100) not null,
    constraint uk_test_run_selected_scenario unique(run_id, scenario_id)
);
create index idx_test_run_selected_scenario_run on test_run_selected_scenario(run_id);
create index idx_test_run_selected_scenario_module on test_run_selected_scenario(module_id);

create table test_run_credential (
    run_id uuid primary key references test_run(id),
    encrypted_password varchar(2000) not null,
    created_at timestamp with time zone not null
);

create index idx_test_run_connection on test_run(connection_id);
create index idx_test_run_driver on test_run(driver_id);
