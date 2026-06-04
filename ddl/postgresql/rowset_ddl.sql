DROP TABLE IF EXISTS rowset_test;

CREATE TABLE rowset_test (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    value INTEGER DEFAULT 0
);
