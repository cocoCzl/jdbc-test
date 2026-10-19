CREATE TABLE {{multiple_results_data}} (id INTEGER PRIMARY KEY, value INTEGER);
/
INSERT INTO {{multiple_results_data}} (id, value) VALUES (1, 0);
/
-- Publish native implicit results. JDBC update-count visibility remains a tested observation.
CREATE PROCEDURE {{multiple_results}}() AS
  first_result REFCURSOR;
  second_result REFCURSOR;
BEGIN
  OPEN first_result FOR SELECT 11 AS n;
  dbms_sql.return_result(first_result);
  UPDATE {{multiple_results_data}} SET value = value + 1 WHERE id = 1;
  OPEN second_result FOR SELECT 22 AS n;
  dbms_sql.return_result(second_result);
END;
/
