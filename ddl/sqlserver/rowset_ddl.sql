DROP TABLE IF EXISTS rowset_test;

CREATE TABLE rowset_test (
    id INT IDENTITY(1,1) PRIMARY KEY,
    name NVARCHAR(100) NOT NULL,
    value INT DEFAULT 0
);
