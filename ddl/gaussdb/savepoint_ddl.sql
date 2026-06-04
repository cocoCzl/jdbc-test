DROP TABLE IF EXISTS savepoint_test;

CREATE TABLE savepoint_test (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    value INTEGER DEFAULT 0
);
