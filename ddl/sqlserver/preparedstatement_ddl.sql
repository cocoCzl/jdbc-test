DROP TABLE IF EXISTS preparedstatement_test;

CREATE TABLE preparedstatement_test (
    id INT IDENTITY(1,1) PRIMARY KEY,
    name NVARCHAR(100),
    value INT,
    amount DECIMAL(12,2),
    active BIT,
    created_at DATETIME2,
    updated_at DATE,
    description NVARCHAR(MAX),
    data VARBINARY(MAX)
);
