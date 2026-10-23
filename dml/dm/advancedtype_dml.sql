INSERT INTO {{array_test}} (name, int_array, text_array, varchar_array)
VALUES ('数组测试', {{integer_array_type}}(1, 2, 3, 4, 5),
    {{text_array_type}}('a', 'b', 'c'), {{text_array_type}}('x', 'y'));
/
INSERT INTO {{array_test}} (name, int_array, text_array, varchar_array)
VALUES ('空数组', {{integer_array_type}}(), {{text_array_type}}(), {{text_array_type}}());
/
