CREATE TABLE {{multiple_results_data}} (id INTEGER PRIMARY KEY, value INTEGER);
INSERT INTO {{multiple_results_data}} (id, value) VALUES (1, 0);
CREATE PROCEDURE {{multiple_results}}()
BEGIN
  SELECT 11 AS n;
  UPDATE {{multiple_results_data}} SET value = value + 1 WHERE id = 1;
  SELECT 22 AS n;
END;
