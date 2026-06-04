DROP TABLE IF EXISTS savepoint_test;

CREATE TABLE savepoint_test (
    id INT IDENTITY(1,1) PRIMARY KEY,
    name NVARCHAR(100) NOT NULL,
    value INT DEFAULT 0
);
