-- Deleting an account (CLAUDE.md §8a, Deleting an account) deletes the player's sessions, and deleting
-- their row makes PostgreSQL check that no session names it: both look sessions up by player, which
-- nothing did before, so it gets an index. PostgreSQL does not index a foreign key by itself.
--
-- Drafted by ./gradlew :server:pendingMigration, then written in lower case and unquoted, as V1 is, so
-- the one script runs on H2 and on PostgreSQL.
--
-- A build from before this script still runs on what it leaves (CLAUDE.md §8b, Rollbacks): an index
-- changes no statement's answer.
--
-- Never edit this file once it has shipped: Flyway refuses to boot on a changed checksum.

CREATE INDEX sessions_player_id_id ON sessions (player_id, id);
