DROP TABLE IF EXISTS connection_test;

CREATE TABLE connection_test (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    value INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE connection_test IS 'Connection 接口测试表';
COMMENT ON COLUMN connection_test.id IS '主键';
COMMENT ON COLUMN connection_test.name IS '名称';
COMMENT ON COLUMN connection_test.value IS '数值';
