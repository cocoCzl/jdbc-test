DROP TABLE IF EXISTS blobclob_test;

CREATE TABLE blobclob_test (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100),
    binary_data BYTEA,
    text_data TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
