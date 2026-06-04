DROP TABLE IF EXISTS metadata_test;

CREATE TABLE metadata_test (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    value INT DEFAULT 0
);
