INSERT INTO array_test (name, int_array, text_array, varchar_array)
VALUES ('数组测试', ARRAY[1, 2, 3, 4, 5], ARRAY['a', 'b', 'c'], ARRAY['hello', 'world']);

INSERT INTO array_test (name, int_array, text_array, varchar_array)
VALUES ('空数组', ARRAY[]::INTEGER[], ARRAY[]::TEXT[], ARRAY[]::VARCHAR[]);
