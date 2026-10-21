CREATE TABLE {{multiple_results_data}} (id INTEGER PRIMARY KEY, value INTEGER);
/
INSERT INTO {{multiple_results_data}} (id, value) VALUES (1, 0);
/
-- Native Oracle implicit results. Internal update-count visibility remains an observation.
CREATE PROCEDURE {{multiple_results}} AS
  first_result SYS_REFCURSOR;
  second_result SYS_REFCURSOR;
BEGIN
  OPEN first_result FOR SELECT 11 AS n FROM DUAL;
  DBMS_SQL.RETURN_RESULT(first_result);
  UPDATE {{multiple_results_data}} SET value = value + 1 WHERE id = 1;
  OPEN second_result FOR SELECT 22 AS n FROM DUAL;
  DBMS_SQL.RETURN_RESULT(second_result);
END;
/
