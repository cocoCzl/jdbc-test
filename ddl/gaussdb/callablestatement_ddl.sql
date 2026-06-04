DROP TABLE IF EXISTS callablestatement_test;

CREATE TABLE callablestatement_test (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    value INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 加法函数
DROP FUNCTION IF EXISTS add_numbers(INTEGER, INTEGER);
CREATE FUNCTION add_numbers(a INTEGER, b INTEGER) RETURNS INTEGER AS $$
BEGIN
    RETURN a + b;
END;
$$ LANGUAGE plpgsql;

-- 根据 ID 获取名称
DROP FUNCTION IF EXISTS get_name_by_id(INTEGER);
CREATE FUNCTION get_name_by_id(p_id INTEGER) RETURNS VARCHAR AS $$
DECLARE
    result_name VARCHAR(100);
BEGIN
    SELECT name INTO result_name FROM callablestatement_test WHERE id = p_id;
    RETURN result_name;
END;
$$ LANGUAGE plpgsql;

-- 更新名称并返回
DROP FUNCTION IF EXISTS update_name_by_id(INTEGER, VARCHAR);
CREATE FUNCTION update_name_by_id(p_id INTEGER, p_name VARCHAR) RETURNS VARCHAR AS $$
BEGIN
    UPDATE callablestatement_test SET name = p_name WHERE id = p_id;
    RETURN p_name;
END;
$$ LANGUAGE plpgsql;

-- 函数计算乘法（带OUT参数）
DROP FUNCTION IF EXISTS multiply_numbers(INTEGER, INTEGER);
CREATE FUNCTION multiply_numbers(a INTEGER, b INTEGER, OUT result INTEGER) AS $$
BEGIN
    result := a * b;
END;
$$ LANGUAGE plpgsql;

-- 获取记录总数
DROP FUNCTION IF EXISTS get_count();
CREATE FUNCTION get_count() RETURNS INTEGER AS $$
DECLARE
    cnt INTEGER;
BEGIN
    SELECT COUNT(*) INTO cnt FROM callablestatement_test;
    RETURN cnt;
END;
$$ LANGUAGE plpgsql;
