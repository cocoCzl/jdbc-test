-- Only supplies a real OUT parameter for a standard registration API observation.
-- It is never executed as a cursor fixture and cannot establish REF_CURSOR support.
CREATE PROCEDURE {{ref_cursor_probe}}(OUT output_value INTEGER)
BEGIN
    SET output_value = 11;
END;
