DROP TABLE IF EXISTS connection_test;

CREATE TABLE connection_test (
    id INT IDENTITY(1,1) PRIMARY KEY,
    name NVARCHAR(100) NOT NULL,
    value INT DEFAULT 0,
    created_at DATETIME2 DEFAULT GETDATE()
);
