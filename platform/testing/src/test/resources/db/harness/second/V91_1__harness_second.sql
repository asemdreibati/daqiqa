-- Test-only migration for PostgresTestDatabaseMigrationTest: a later version than harness/first, as ordering (5.x) is
-- later than inventory (3.x).
CREATE SCHEMA harness_second;
CREATE TABLE harness_second.marker (id int PRIMARY KEY);
