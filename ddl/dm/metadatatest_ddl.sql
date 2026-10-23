CREATE TABLE {{metadata_test}} (
    id INTEGER IDENTITY(1, 1) PRIMARY KEY,
    name VARCHAR2(100) NOT NULL,
    value INTEGER DEFAULT 0
);
/
