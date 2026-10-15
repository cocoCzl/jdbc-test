CREATE PROCEDURE {{procedure_inout}}(IN p_value INT, INOUT p_acc INT, OUT p_double INT)
BEGIN
  SET p_acc = p_acc + p_value;
  SET p_double = p_value * 2;
END;
