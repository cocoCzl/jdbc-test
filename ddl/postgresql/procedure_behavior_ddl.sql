-- PostgreSQL procedure calls require the connection-level escapeSyntaxCallMode=callIfNoReturn.
CREATE PROCEDURE {{procedure_inout}}(IN p_value INTEGER, INOUT p_acc INTEGER, OUT p_double INTEGER)
LANGUAGE plpgsql AS $$
BEGIN
    p_acc := p_acc + p_value;
    p_double := p_value * 2;
END;
$$;
