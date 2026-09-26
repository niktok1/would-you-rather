-- Answer time (CLAUDE.md §8b, Personalization): how long a player's latest answer to a question took,
-- in milliseconds from the question showing to the tap, as the client measured it, kept on the vote
-- beside the side it follows. A signal for choosing questions to suit a player later; nothing reads it
-- yet. NULL for every vote already here, whose times nobody measured, and for any answer that sends
-- none.
--
-- Drafted by ./gradlew :server:pendingMigration, then written in lower case and unquoted, as V1 is, so
-- the one script runs on H2 and on PostgreSQL. Nullable with no default, so on PostgreSQL adding it
-- rewrites nothing.
--
-- A build from before this script still runs on what it leaves (CLAUDE.md §8b, Rollbacks): it never
-- names the column, so its answers leave it as the answer before left it.
--
-- Never edit this file once it has shipped: Flyway refuses to boot on a changed checksum.

ALTER TABLE votes ADD COLUMN answer_millis BIGINT NULL;
