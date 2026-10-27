-- Validation tokens advance on driver-class edits and every validation attempt.
ALTER TABLE jdbc_driver_package ADD COLUMN validation_revision bigint NOT NULL DEFAULT 0;
