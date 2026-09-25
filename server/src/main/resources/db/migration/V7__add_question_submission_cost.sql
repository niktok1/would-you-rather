-- Submitting a question costs its author a point (CLAUDE.md §8c): what each question cost is kept on
-- it, so a rejection pays back what was paid, whatever submitting costs by then. Every question already
-- here cost nothing, a seed or a submission from before this script, so each gets 0, and rejecting one
-- still pending pays back nothing.
--
-- Drafted by ./gradlew :server:pendingMigration, then written in lower case and unquoted, as V1 is, so
-- the one script runs on H2 and on PostgreSQL. A NOT NULL column with a constant default, which
-- PostgreSQL 11 and later adds without rewriting the table.
--
-- A build from before this script still runs on what it leaves (CLAUDE.md §8b, Rollbacks): it never
-- names the column, so a question it stores costs 0 by the default, and a rejection it makes pays back
-- nothing, although this build charged for the question: the author is a point short for each.
--
-- Never edit this file once it has shipped: Flyway refuses to boot on a changed checksum.

ALTER TABLE questions ADD COLUMN submission_cost INT DEFAULT 0 NOT NULL;
