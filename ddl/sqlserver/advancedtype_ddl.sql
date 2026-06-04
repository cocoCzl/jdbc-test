-- SQL Server 不原生支持数组类型，使用 JSON 列作为替代
DROP TABLE IF EXISTS array_test;

CREATE TABLE array_test (
    id INT IDENTITY(1,1) PRIMARY KEY,
    name NVARCHAR(100),
    int_data NVARCHAR(MAX),
    text_data NVARCHAR(MAX)
);

-- 模拟数组数据存储为 JSON
INSERT INTO array_test (name, int_data, text_data)
VALUES (N'数组测试', '[1,2,3,4,5]', '["a","b","c"]');

INSERT INTO array_test (name, int_data, text_data)
VALUES (N'空数组', '[]', '[]');
