DROP TABLE IF EXISTS resultset_test;

CREATE TABLE resultset_test (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    value INTEGER NOT NULL DEFAULT 0,
    amount DECIMAL(12,2),
    active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at DATE,
    description TEXT,
    data BYTEA,
    rating DOUBLE PRECISION,
    status SMALLINT DEFAULT 1,
    big_num BIGINT
);

COMMENT ON TABLE resultset_test IS 'ResultSet 接口测试表';
COMMENT ON COLUMN resultset_test.name IS '名称';
COMMENT ON COLUMN resultset_test.value IS '数值';
COMMENT ON COLUMN resultset_test.amount IS '金额';
