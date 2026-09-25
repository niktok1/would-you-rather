-- Accounts (CLAUDE.md §8b, Accounts): a player may register a username, stored lower-cased, and a
-- password, of which only a salted hash is kept (Passwords). A guest has neither, so both are NULL for
-- every player already here, who all stay guests until they register. The unique constraint is what
-- decides two registrations racing for one name, and the index a login looks the name up by; NULLs
-- never collide in it on either engine.
--
-- Drafted by ./gradlew :server:pendingMigration, then written in lower case and unquoted, as V1 is, so
-- the one script runs on H2 and on PostgreSQL. The columns are nullable with no default, so on
-- PostgreSQL adding them rewrites nothing.
--
-- A build from before this script still runs on what it leaves (CLAUDE.md §8b, Rollbacks): it never
-- names the new columns, so a registered player plays on there, through their sessions, as a guest
-- would.
--
-- Never edit this file once it has shipped: Flyway refuses to boot on a changed checksum.

ALTER TABLE players ADD COLUMN username VARCHAR(20) NULL;
ALTER TABLE players ADD COLUMN password_hash VARCHAR(255) NULL;
ALTER TABLE players ADD CONSTRAINT players_username_unique UNIQUE (username);
