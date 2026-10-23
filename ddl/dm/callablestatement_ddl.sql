CREATE TABLE {{callablestatement_test}} (
    id INTEGER IDENTITY(1, 1) PRIMARY KEY,
    name VARCHAR2(100) NOT NULL,
    value NUMBER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
/

CREATE FUNCTION {{add_numbers}}(a IN NUMBER, b IN NUMBER) RETURN NUMBER IS
BEGIN
    RETURN a + b;
END;
/

CREATE FUNCTION {{get_name_by_id}}(p_id IN NUMBER) RETURN VARCHAR2 IS
    v_name VARCHAR2(100);
BEGIN
    SELECT name INTO v_name FROM {{callablestatement_test}} WHERE id = p_id;
    RETURN v_name;
EXCEPTION
    WHEN NO_DATA_FOUND THEN
        RETURN NULL;
END;
/

CREATE FUNCTION {{multiply_numbers}}(a IN NUMBER, b IN NUMBER) RETURN NUMBER IS
BEGIN
    RETURN a * b;
END;
/

CREATE FUNCTION {{get_count}} RETURN NUMBER IS
    v_cnt NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_cnt FROM {{callablestatement_test}};
    RETURN v_cnt;
END;
/
