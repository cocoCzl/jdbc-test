DROP TABLE IF EXISTS metadata_test;

CREATE TABLE metadata_test (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    value INTEGER DEFAULT 0
);
