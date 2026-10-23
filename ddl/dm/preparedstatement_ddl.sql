CREATE TABLE {{preparedstatement_test}} (
    id INTEGER IDENTITY(1, 1) PRIMARY KEY,
    name VARCHAR2(100),
    value INTEGER,
    amount NUMBER(12,2),
    active BIT,
    created_at TIMESTAMP,
    updated_at DATE,
    description CLOB,
    data BLOB
);
/
