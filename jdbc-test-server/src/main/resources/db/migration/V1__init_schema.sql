create table jdbc_driver_package (
    id uuid primary key,
    name varchar(200) not null,
    vendor varchar(120) not null,
    version varchar(80) not null,
    driver_class varchar(300) not null,
    url_prefix varchar(200),
    source_type varchar(20) not null,
    status varchar(20) not null,
    package_fingerprint varchar(128),
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create table jdbc_driver_artifact (
    id uuid primary key,
    driver_id uuid not null references jdbc_driver_package(id),
    file_name varchar(255) not null,
    storage_key varchar(500) not null,
    size bigint not null,
    sha256 varchar(64) not null,
    created_at timestamp with time zone not null
);

create index idx_jdbc_driver_artifact_driver_id on jdbc_driver_artifact(driver_id);

create table database_connection_profile (
    id uuid primary key,
    name varchar(200) not null,
    database_type varchar(80) not null,
    jdbc_url varchar(2000) not null,
    username varchar(200) not null,
    encrypted_password varchar(2000),
    default_driver_id uuid references jdbc_driver_package(id),
    description varchar(1000),
    last_connection_test_at timestamp with time zone,
    last_connection_test_status varchar(20),
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create index idx_database_connection_profile_default_driver_id on database_connection_profile(default_driver_id);

create table test_run (
    id uuid primary key,
    connection_id uuid not null references database_connection_profile(id),
    driver_id uuid not null references jdbc_driver_package(id),
    status varchar(32) not null,
    profile varchar(16) not null,
    created_at timestamp with time zone not null,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    total_cases integer not null default 0,
    completed_cases integer not null default 0,
    passed integer not null default 0,
    failed integer not null default 0,
    skipped integer not null default 0,
    current_module varchar(100),
    current_test_case varchar(200),
    failure_reason varchar(4000),
    connection_name_snapshot varchar(200) not null,
    database_type_snapshot varchar(80) not null,
    jdbc_url_sanitized_snapshot varchar(2000) not null,
    driver_name_snapshot varchar(200) not null,
    driver_vendor_snapshot varchar(120) not null,
    driver_version_snapshot varchar(80) not null,
    driver_class_snapshot varchar(300) not null,
    driver_fingerprint_snapshot varchar(128)
);

create index idx_test_run_status on test_run(status);
create index idx_test_run_created_at on test_run(created_at);

create table test_run_module (
    id uuid primary key,
    run_id uuid not null references test_run(id),
    module_id varchar(100) not null,
    module_name varchar(200) not null,
    constraint uk_test_run_module_run_module unique(run_id, module_id)
);

create table test_case_result (
    id uuid primary key,
    run_id uuid not null references test_run(id),
    scenario_id varchar(200) not null,
    module_id varchar(100) not null,
    display_name varchar(500) not null,
    test_class varchar(300),
    test_method varchar(300),
    status varchar(16) not null,
    duration_ms bigint,
    message varchar(4000),
    stack_trace varchar(12000),
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    constraint uk_test_case_result_run_scenario unique(run_id, scenario_id)
);

create index idx_test_case_result_run_id on test_case_result(run_id);
create index idx_test_case_result_run_status on test_case_result(run_id, status);
create index idx_test_case_result_run_module on test_case_result(run_id, module_id);
