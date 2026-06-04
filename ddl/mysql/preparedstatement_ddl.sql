DROP TABLE IF EXISTS preparedstatement_test;

CREATE TABLE preparedstatement_test (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100),
    value INT,
    amount DECIMAL(12,2),
    active BOOLEAN,
    created_at TIMESTAMP NULL,
    updated_at DATE,
    description TEXT,
    data MEDIUMBLOB
);
