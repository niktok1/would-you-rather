-- Play Games sign-in (CLAUDE.md §8a, Play Games sign-in): the players of other services linked to
-- players here, a Google Play Games player for now, each signing in as the player it is linked to.
-- Empty until someone signs in with one. The primary key links a service's player to one player here,
-- and the unique constraint a player here to one of each service's: the two decide every race.
--
-- The foreign key cascades, as push_tokens' do: deleting a player deletes their links, with no store
-- having to know this table is there.
--
-- Drafted by ./gradlew :server:pendingMigration, then written in lower case and unquoted, as V1 is, so
-- the one script runs on H2 and on PostgreSQL.
--
-- A build from before this script still runs on what it leaves (CLAUDE.md §8b, Rollbacks): it never
-- names the table, so a player who signed in with Play Games plays on there through their sessions,
-- as a guest, and cannot submit until the roll forward unless they also have a username.
--
-- Never edit this file once it has shipped: Flyway refuses to boot on a changed checksum.

CREATE TABLE IF NOT EXISTS identities (
    provider VARCHAR(16),
    subject VARCHAR(255),
    player_id VARCHAR(36) NOT NULL,
    created_at BIGINT NOT NULL,
    CONSTRAINT pk_identities PRIMARY KEY (provider, subject),
    CONSTRAINT fk_identities_player_id__id FOREIGN KEY (player_id)
        REFERENCES players(id) ON DELETE CASCADE ON UPDATE RESTRICT
);
ALTER TABLE identities ADD CONSTRAINT identities_player_id_provider_unique UNIQUE (player_id, provider);
