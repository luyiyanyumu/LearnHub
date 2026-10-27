-- This location is explicitly enabled by one failure-path test only, after the production V7 migration.
INSERT INTO learnhub_table_that_does_not_exist (id) VALUES (1);
