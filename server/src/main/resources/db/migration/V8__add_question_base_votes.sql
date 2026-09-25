-- Seeds' made-up votes (CLAUDE.md §8d, Seeds): every question gets two counts, one for each side, that
-- every tally the server reports adds to the players' own votes, so a seed's split looks like a crowd's
-- from the first answer. Every question already here gets none, then each seed, found by the id every
-- build has given it, gets the counts Seed gives it in a new database, a different total and split
-- each. A database with no seeds yet, a new one, updates nothing here, and the seed writes them
-- itself once the migration is done. MigrationsTest holds the counts here to Seed's.
--
-- Drafted by ./gradlew :server:pendingMigration, then written in lower case and unquoted, as V1 is, so
-- the one script runs on H2 and on PostgreSQL, one ALTER TABLE per column. NOT NULL columns with a
-- constant default, which PostgreSQL 11 and later adds without rewriting the table.
--
-- A build from before this script still runs on what it leaves (CLAUDE.md §8b, Rollbacks): it never
-- names the columns, so its tallies count the players' votes alone, as before.
--
-- Never edit this file once it has shipped: Flyway refuses to boot on a changed checksum.

ALTER TABLE questions ADD COLUMN base_votes_a INT DEFAULT 0 NOT NULL;
ALTER TABLE questions ADD COLUMN base_votes_b INT DEFAULT 0 NOT NULL;

UPDATE questions SET base_votes_a = 212, base_votes_b = 158 WHERE id = 'seed-1';
UPDATE questions SET base_votes_a = 97, base_votes_b = 143 WHERE id = 'seed-2';
UPDATE questions SET base_votes_a = 188, base_votes_b = 61 WHERE id = 'seed-3';
UPDATE questions SET base_votes_a = 264, base_votes_b = 119 WHERE id = 'seed-4';
UPDATE questions SET base_votes_a = 52, base_votes_b = 301 WHERE id = 'seed-5';
UPDATE questions SET base_votes_a = 77, base_votes_b = 246 WHERE id = 'seed-6';
UPDATE questions SET base_votes_a = 134, base_votes_b = 171 WHERE id = 'seed-7';
UPDATE questions SET base_votes_a = 156, base_votes_b = 139 WHERE id = 'seed-8';
UPDATE questions SET base_votes_a = 283, base_votes_b = 88 WHERE id = 'seed-9';
UPDATE questions SET base_votes_a = 201, base_votes_b = 176 WHERE id = 'seed-10';
UPDATE questions SET base_votes_a = 318, base_votes_b = 205 WHERE id = 'seed-11';
UPDATE questions SET base_votes_a = 143, base_votes_b = 231 WHERE id = 'seed-12';
UPDATE questions SET base_votes_a = 252, base_votes_b = 190 WHERE id = 'seed-13';
UPDATE questions SET base_votes_a = 219, base_votes_b = 97 WHERE id = 'seed-14';
UPDATE questions SET base_votes_a = 167, base_votes_b = 274 WHERE id = 'seed-15';
UPDATE questions SET base_votes_a = 58, base_votes_b = 73 WHERE id = 'seed-16';
UPDATE questions SET base_votes_a = 121, base_votes_b = 94 WHERE id = 'seed-17';
UPDATE questions SET base_votes_a = 239, base_votes_b = 82 WHERE id = 'seed-18';
UPDATE questions SET base_votes_a = 108, base_votes_b = 196 WHERE id = 'seed-19';
UPDATE questions SET base_votes_a = 149, base_votes_b = 131 WHERE id = 'seed-20';
UPDATE questions SET base_votes_a = 176, base_votes_b = 68 WHERE id = 'seed-21';
UPDATE questions SET base_votes_a = 162, base_votes_b = 245 WHERE id = 'seed-22';
UPDATE questions SET base_votes_a = 44, base_votes_b = 61 WHERE id = 'seed-23';
UPDATE questions SET base_votes_a = 186, base_votes_b = 233 WHERE id = 'seed-24';
