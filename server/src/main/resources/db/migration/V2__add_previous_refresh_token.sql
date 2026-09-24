-- The refresh-token grace window (CLAUDE.md §8a): each player keeps, beside the current refresh
-- token, the one the last rotation displaced, as its hash, the expiry it had while current, and when
-- it was displaced. PlayerStore.rotateRefreshToken still accepts that one within the grace window.
--
-- Drafted by ./gradlew :server:pendingMigration, then written in lower case and unquoted, as V1 is,
-- so the one script runs on H2 and on PostgreSQL. Every column is nullable with no default, so the
-- rows already there get NULL, which reads as no previous token: on PostgreSQL adding such a column
-- rewrites nothing. The unique constraint is the index a refresh looks the previous hash up by, and
-- NULLs never collide in it on either engine.
--
-- A build from before this script still runs on what it leaves: it never names these columns, so
-- it inserts players without them and rotates tokens without touching them (CLAUDE.md §8b,
-- *Rollbacks*).
--
-- Never edit this file once it has shipped: Flyway refuses to boot on a changed checksum.

ALTER TABLE players ADD COLUMN previous_refresh_token_hash VARCHAR(64) NULL;
ALTER TABLE players ADD COLUMN previous_refresh_token_expires_at BIGINT NULL;
ALTER TABLE players ADD COLUMN previous_refresh_token_rotated_at BIGINT NULL;
ALTER TABLE players ADD CONSTRAINT players_previous_refresh_token_hash_unique UNIQUE (previous_refresh_token_hash);
