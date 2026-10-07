
CREATE TABLE {{callablestatement_test}} (
    id INT IDENTITY(1,1) PRIMARY KEY,
    name NVARCHAR(100) NOT NULL,
    value INT DEFAULT 0,
    created_at DATETIME2 DEFAULT GETDATE()
);
GO
CREATE FUNCTION {{add_numbers}}(@a INT, @b INT) RETURNS INT
AS
BEGIN
    RETURN @a + @b;
END;
GO
CREATE FUNCTION {{get_name_by_id}}(@p_id INT) RETURNS NVARCHAR(100)
AS
BEGIN
    DECLARE @result_name NVARCHAR(100);
    SELECT @result_name = name FROM {{callablestatement_test}} WHERE id = @p_id;
    RETURN @result_name;
END;
GO
CREATE FUNCTION {{update_name_by_id}}(@p_id INT, @p_name NVARCHAR(100)) RETURNS NVARCHAR(100)
AS
BEGIN
    UPDATE {{callablestatement_test}} SET name = @p_name WHERE id = @p_id;
    RETURN @p_name;
END;
GO
CREATE FUNCTION {{multiply_numbers}}(@a INT, @b INT) RETURNS INT
AS
BEGIN
    RETURN @a * @b;
END;
GO
CREATE FUNCTION {{get_count}}() RETURNS INT
AS
BEGIN
    DECLARE @cnt INT;
    SELECT @cnt = COUNT(*) FROM {{callablestatement_test}};
    RETURN @cnt;
END;
GO
