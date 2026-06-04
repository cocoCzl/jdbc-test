DROP TABLE IF EXISTS statement_test;

CREATE TABLE statement_test (
    id INT IDENTITY(1,1) PRIMARY KEY,
    name NVARCHAR(100) NOT NULL,
    value INT DEFAULT 0,
    active BIT DEFAULT 1,
    created_at DATETIME2 DEFAULT GETDATE()
);

CREATE INDEX idx_statement_test_name ON statement_test(name);
