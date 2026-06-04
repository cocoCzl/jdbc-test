DROP TABLE IF EXISTS callablestatement_test;

CREATE TABLE callablestatement_test (
    id INT IDENTITY(1,1) PRIMARY KEY,
    name NVARCHAR(100) NOT NULL,
    value INT DEFAULT 0,
    created_at DATETIME2 DEFAULT GETDATE()
);
GO

DROP FUNCTION IF EXISTS dbo.add_numbers;
GO
CREATE FUNCTION dbo.add_numbers(@a INT, @b INT) RETURNS INT
AS
BEGIN
    RETURN @a + @b;
END;
GO

DROP FUNCTION IF EXISTS dbo.get_name_by_id;
GO
CREATE FUNCTION dbo.get_name_by_id(@p_id INT) RETURNS NVARCHAR(100)
AS
BEGIN
    DECLARE @result_name NVARCHAR(100);
    SELECT @result_name = name FROM callablestatement_test WHERE id = @p_id;
    RETURN @result_name;
END;
GO

DROP FUNCTION IF EXISTS dbo.update_name_by_id;
GO
CREATE FUNCTION dbo.update_name_by_id(@p_id INT, @p_name NVARCHAR(100)) RETURNS NVARCHAR(100)
AS
BEGIN
    UPDATE callablestatement_test SET name = @p_name WHERE id = @p_id;
    RETURN @p_name;
END;
GO

DROP FUNCTION IF EXISTS dbo.multiply_numbers;
GO
CREATE FUNCTION dbo.multiply_numbers(@a INT, @b INT) RETURNS INT
AS
BEGIN
    RETURN @a * @b;
END;
GO

DROP FUNCTION IF EXISTS dbo.get_count;
GO
CREATE FUNCTION dbo.get_count() RETURNS INT
AS
BEGIN
    DECLARE @cnt INT;
    SELECT @cnt = COUNT(*) FROM callablestatement_test;
    RETURN @cnt;
END;
GO
