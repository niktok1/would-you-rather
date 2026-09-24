-- Retiring an approved question (CLAUDE.md §8d, Moderation): when a moderator retired it, or NULL
-- while it is not retired. A retired question keeps its status, APPROVED, beside this column, rather
-- than a status of its own.
--
-- Drafted by ./gradlew :server:pendingMigration, then written in lower case and unquoted, as V1 is,
-- so the one script runs on H2 and on PostgreSQL. The column is nullable with no default, so the rows
-- already there get NULL, which reads as not retired: on PostgreSQL adding such a column rewrites
-- nothing.
--
-- A build from before this script still runs on what it leaves (CLAUDE.md §8b, Rollbacks): it never
-- names the column, so it inserts questions without it, and it reads questions.status strictly, which
-- is why retirement is not a status there. Such a build would only serve a retired question again.
--
-- Never edit this file once it has shipped: Flyway refuses to boot on a changed checksum.

ALTER TABLE questions ADD COLUMN retired_at BIGINT NULL;
