-- For MigrationConfigurationTest only: a script named with one underscore after its version where
-- Flyway needs two. Flyway would skip it with a warning by default, so it would never run anywhere.
CREATE TABLE misnamed (id INT);
