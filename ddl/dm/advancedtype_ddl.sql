-- Native DM collection columns; no Oracle nested-table storage clause.
CREATE TYPE {{integer_array_type}} AS VARRAY(100) OF INTEGER;
/
CREATE TYPE {{text_array_type}} AS VARRAY(100) OF VARCHAR(100);
/
CREATE TABLE {{array_test}} (
    id INTEGER IDENTITY(1, 1) PRIMARY KEY,
    name VARCHAR(100),
    int_array {{integer_array_type}},
    text_array {{text_array_type}},
    varchar_array {{text_array_type}}
);
/
