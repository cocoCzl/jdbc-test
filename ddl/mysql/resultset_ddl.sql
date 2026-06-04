DROP TABLE IF EXISTS resultset_test;

CREATE TABLE resultset_test (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    value INT NOT NULL DEFAULT 0,
    amount DECIMAL(12,2),
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at DATE,
    description TEXT,
    data MEDIUMBLOB,
    rating DOUBLE,
    status SMALLINT DEFAULT 1,
    big_num BIGINT
);
