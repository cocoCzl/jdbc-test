DROP TABLE IF EXISTS metadata_test;

CREATE TABLE metadata_test (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    value INTEGER DEFAULT 0
);

COMMENT ON TABLE metadata_test IS 'DatabaseMetaData 接口测试表';
