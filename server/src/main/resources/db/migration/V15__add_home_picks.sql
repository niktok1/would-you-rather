-- Home picks (CLAUDE.md §8d, Home picks): the Home screen's two Play buttons, one in each card's
-- colour, each count how many times they have been tapped, by every player together. One row per
-- side, written here at 0, and only ever moved by an SQL increment (HomePickStore.pick, CLAUDE.md §4),
-- so every tap counts however many land at once.
--
-- Drafted by ./gradlew :server:pendingMigration, then written in lower case and unquoted, as V1 is, so
-- the one script runs on H2 and on PostgreSQL. V15 rather than V11: V11 to V14 are
-- feat/server-safety's, which merges first, and Flyway runs whatever versions are there in order, a
-- gap included.
--
-- A build from before this script still runs on what it leaves (CLAUDE.md §8b, Rollbacks): it never
-- names the table.
--
-- Never edit this file once it has shipped: Flyway refuses to boot on a changed checksum.

CREATE TABLE IF NOT EXISTS home_picks (
    side VARCHAR(1) PRIMARY KEY,
    picks BIGINT DEFAULT 0 NOT NULL
);

INSERT INTO home_picks (side, picks) VALUES ('A', 0);
INSERT INTO home_picks (side, picks) VALUES ('B', 0);
