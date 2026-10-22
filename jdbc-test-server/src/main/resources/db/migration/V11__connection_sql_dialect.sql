-- Nullable additions only: existing connections and frozen task inputs are unchanged.
alter table jdbc_driver_package add column default_sql_dialect varchar(20);
alter table database_connection_profile add column sql_dialect varchar(20);
