-- The caller keeps a transaction open while consuming the native returned cursor.
CREATE PROCEDURE {{cursor_output}}(OUT result REFCURSOR)
LANGUAGE plpgsql AS $$
BEGIN
    OPEN result FOR SELECT n FROM (VALUES (11), (22)) AS values_to_read(n) ORDER BY n;
END;
$$;
