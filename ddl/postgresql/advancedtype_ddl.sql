DROP TABLE IF EXISTS array_test;

CREATE TABLE array_test (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100),
    int_array INTEGER[],
    text_array TEXT[],
    varchar_array VARCHAR(50)[]
);
