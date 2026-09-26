-- Blocking an author (CLAUDE.md §8d, Moderation): a moderator stops a player submitting questions, and
-- the time they did is kept on the player. Every player already here may submit, so the column is added
-- empty.
--
-- Drafted by ./gradlew :server:pendingMigration, then written in lower case and unquoted, as V1 is, so
-- the one script runs on H2 and on PostgreSQL. A nullable column with no default, which both add
-- without rewriting the table.
--
-- A build from before this script still runs on what it leaves (CLAUDE.md §8b, Rollbacks): it never
-- names the column, so a blocked author submits again there until the roll forward.
--
-- Never edit this file once it has shipped: Flyway refuses to boot on a changed checksum.

ALTER TABLE players ADD COLUMN submissions_blocked_at BIGINT;
