DROP TABLE IF EXISTS preparedstatement_test;

CREATE TABLE preparedstatement_test (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100),
    value INTEGER,
    amount DECIMAL(12,2),
    active BOOLEAN,
    created_at TIMESTAMP,
    updated_at DATE,
    description TEXT,
    data BYTEA
);
