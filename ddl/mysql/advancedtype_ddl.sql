-- MySQL 不原生支持数组类型
DROP TABLE IF EXISTS array_test;

CREATE TABLE array_test (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100),
    int_data JSON,
    text_data JSON
);
