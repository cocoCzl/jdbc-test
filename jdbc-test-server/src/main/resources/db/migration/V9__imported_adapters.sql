create table imported_adapter (
    id varchar(32) primary key,
    fingerprint varchar(64) not null,
    snapshot_json text not null,
    archived boolean not null default false,
    lock_version bigint not null default 0
);
