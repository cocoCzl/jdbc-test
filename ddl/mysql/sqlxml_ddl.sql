-- Connector/J exposes SQLXML over character data; a native XML SQL type is not required.
CREATE TABLE {{sqlxml_test}} (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100),
    xml_data LONGTEXT
) CHARACTER SET utf8mb4;
