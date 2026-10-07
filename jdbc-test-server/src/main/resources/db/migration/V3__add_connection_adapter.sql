alter table database_connection_profile
    add column adapter_id varchar(100) not null default 'postgresql';

alter table database_connection_profile
    alter column adapter_id drop default;

create index idx_database_connection_profile_adapter_id on database_connection_profile(adapter_id);
