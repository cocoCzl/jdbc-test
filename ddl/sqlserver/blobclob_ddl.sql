DROP TABLE IF EXISTS blobclob_test;

CREATE TABLE blobclob_test (
    id INT IDENTITY(1,1) PRIMARY KEY,
    name NVARCHAR(100),
    binary_data VARBINARY(MAX),
    text_data NVARCHAR(MAX),
    created_at DATETIME2 DEFAULT GETDATE()
);
