-- Test-only migration for PostgresTestDatabaseMigrationTest. Majors from 90 up are never used by a module.
CREATE SCHEMA harness_first;
CREATE TABLE harness_first.marker (id int PRIMARY KEY);
