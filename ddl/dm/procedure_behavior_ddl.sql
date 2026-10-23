CREATE PROCEDURE {{procedure_inout}}(p_value IN INTEGER, p_acc IN OUT INTEGER, p_double OUT INTEGER) AS
BEGIN
  p_acc := p_acc + p_value;
  p_double := p_value * 2;
END;
/
