DROP TABLE IF EXISTS metadata_test;

CREATE TABLE metadata_test (
    id INT IDENTITY(1,1) PRIMARY KEY,
    name NVARCHAR(100) NOT NULL,
    value INT DEFAULT 0
);
