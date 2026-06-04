DROP TABLE IF EXISTS resultset_test;

CREATE TABLE resultset_test (
    id INT IDENTITY(1,1) PRIMARY KEY,
    name NVARCHAR(100) NOT NULL,
    value INT NOT NULL DEFAULT 0,
    amount DECIMAL(12,2),
    active BIT DEFAULT 1,
    created_at DATETIME2 DEFAULT GETDATE(),
    updated_at DATE,
    description NVARCHAR(MAX),
    data VARBINARY(MAX),
    rating FLOAT(53),
    status SMALLINT DEFAULT 1,
    big_num BIGINT
);
