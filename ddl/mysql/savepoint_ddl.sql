DROP TABLE IF EXISTS savepoint_test;

CREATE TABLE savepoint_test (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    value INT DEFAULT 0
);
