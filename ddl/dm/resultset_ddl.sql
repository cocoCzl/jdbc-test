CREATE TABLE {{resultset_test}} (
    id INTEGER IDENTITY(1, 1) PRIMARY KEY,
    name VARCHAR2(100) NOT NULL,
    value INTEGER NOT NULL,
    amount NUMBER(12,2),
    active BIT DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at DATE,
    description CLOB,
    data BLOB,
    rating DOUBLE,
    status NUMBER(5) DEFAULT 1,
    big_num NUMBER(19)
);
/
