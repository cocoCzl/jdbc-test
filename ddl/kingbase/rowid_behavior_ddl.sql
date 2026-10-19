-- Enable the native ROWID on this owned table only; never change default_with_rowid.
CREATE TABLE {{rowid_behavior}} (id INTEGER PRIMARY KEY, value INTEGER) WITH (ROWID = ON);
