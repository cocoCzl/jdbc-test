CREATE PROCEDURE {{procedure_inout}} @p_value INT, @p_acc INT OUTPUT, @p_double INT OUTPUT AS
BEGIN
  SET NOCOUNT ON;
  SET @p_acc = @p_acc + @p_value;
  SET @p_double = @p_value * 2;
END;
GO
