DROP TABLE IF EXISTS sqlxml_test;

CREATE TABLE sqlxml_test (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100),
    xml_data XML
);

COMMENT ON TABLE sqlxml_test IS 'SQLXML 接口测试表';
