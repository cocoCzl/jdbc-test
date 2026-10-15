CREATE TABLE {{batch_behavior}} (
  id SERIAL PRIMARY KEY,
  name VARCHAR(100) NOT NULL UNIQUE,
  value INTEGER
);
