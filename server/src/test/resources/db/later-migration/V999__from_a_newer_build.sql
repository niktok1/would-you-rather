-- For MigrationConfigurationTest only: a script from a build newer than this one, numbered past
-- every committed script, which the test's migration runs before the server's own finds it has no
-- copy of it.
CREATE TABLE from_a_newer_build (id INT);
