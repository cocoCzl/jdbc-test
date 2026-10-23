CREATE TABLE {{blobclob_test}} (
    id INTEGER IDENTITY(1, 1) PRIMARY KEY,
    name VARCHAR2(100),
    binary_data BLOB,
    text_data CLOB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
/
