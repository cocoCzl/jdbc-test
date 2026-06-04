DROP TABLE IF EXISTS sqlxml_test;

CREATE TABLE sqlxml_test (
    id INT IDENTITY(1,1) PRIMARY KEY,
    name NVARCHAR(100),
    xml_data XML
);
