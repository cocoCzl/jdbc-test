CREATE TABLE {{statement_test}} (
    id INTEGER IDENTITY(1, 1) PRIMARY KEY,
    name VARCHAR2(100) NOT NULL,
    value INTEGER DEFAULT 0,
    active BIT DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
/

CREATE INDEX {{local:idx_statement_test_name}} ON {{statement_test}}(name);
/
