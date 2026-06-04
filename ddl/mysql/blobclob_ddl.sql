DROP TABLE IF EXISTS blobclob_test;

CREATE TABLE blobclob_test (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100),
    binary_data MEDIUMBLOB,
    text_data MEDIUMTEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
