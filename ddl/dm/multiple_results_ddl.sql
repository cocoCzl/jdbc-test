CREATE TABLE {{multiple_results_data}} (id INTEGER PRIMARY KEY, value INTEGER);
/
INSERT INTO {{multiple_results_data}} (id, value) VALUES (1, 0);
/
-- Native DM procedure result sequence; do not require Oracle RETURN_RESULT.
CREATE PROCEDURE {{multiple_results}} AS
BEGIN
  SELECT 11 AS n FROM DUAL;
  UPDATE {{multiple_results_data}} SET value = value + 1 WHERE id = 1;
  SELECT 22 AS n FROM DUAL;
END;
/
