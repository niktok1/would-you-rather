-- Sessions and the recovery secret (CLAUDE.md §8a, *Sessions* and *Recovery*). A player's refresh
-- tokens move from their players row to sessions, one refresh-token family per device, each rotating
-- on its own (SessionStore.rotate). The players row keeps its refresh-token columns as the mirror, a
-- copy of the session written last, for a rollback to a build from before sessions, which reads them
-- alone; mirrored_refresh_token_hash tells this build when such a build has moved them since. And a
-- player gets a recovery secret, of which only the SHA-256 is kept, as of a refresh token.
--
-- Numbered 4 because a branch built beside this one took 3 (questions.retired_at). Flyway runs the
-- versions it finds in order, gaps and all, so a database this build migrated without that script is
-- only a throwaway one: the shared databases run main, which gets the two together.
--
-- Drafted by ./gradlew :server:pendingMigration, then written in lower case and unquoted, as V1 is, so
-- the one script runs on H2 and on PostgreSQL. The new columns are nullable with no default, so on
-- PostgreSQL adding them rewrites nothing. Each unique constraint is the index its hash is looked up
-- by, and NULLs never collide in one on either engine.
--
-- Every player who holds a refresh token gets a session holding it, with its previous token and the
-- grace that goes with it (V2), so every client keeps refreshing across the deploy. Such a session
-- takes its player's id, which no other session can have, and its player's creation time. The mirror
-- is then marked with what its session holds, read from the session rather than from the players row:
-- were the build before to rotate a player's token between the two statements, the mark would still
-- name the session's token, and this build would find the mirror moved and fold it back.
--
-- A build from before this script still runs on what it leaves: it never names sessions or the new
-- columns, and it keeps refreshing from the players row (CLAUDE.md §8b, *Rollbacks*).
--
-- Never edit this file once it has shipped: Flyway refuses to boot on a changed checksum.

CREATE TABLE sessions (
    id VARCHAR(36) PRIMARY KEY,
    player_id VARCHAR(36) NOT NULL,
    refresh_token_hash VARCHAR(64) NOT NULL,
    refresh_token_expires_at BIGINT NOT NULL,
    previous_refresh_token_hash VARCHAR(64) NULL,
    previous_refresh_token_expires_at BIGINT NULL,
    previous_refresh_token_rotated_at BIGINT NULL,
    created_at BIGINT NOT NULL,
    CONSTRAINT fk_sessions_player_id__id FOREIGN KEY (player_id)
        REFERENCES players(id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
ALTER TABLE sessions ADD CONSTRAINT sessions_refresh_token_hash_unique UNIQUE (refresh_token_hash);
ALTER TABLE sessions ADD CONSTRAINT sessions_previous_refresh_token_hash_unique UNIQUE (previous_refresh_token_hash);

ALTER TABLE players ADD COLUMN mirrored_refresh_token_hash VARCHAR(64) NULL;
ALTER TABLE players ADD COLUMN recovery_secret_hash VARCHAR(64) NULL;
ALTER TABLE players ADD CONSTRAINT players_recovery_secret_hash_unique UNIQUE (recovery_secret_hash);

INSERT INTO sessions (
    id,
    player_id,
    refresh_token_hash,
    refresh_token_expires_at,
    previous_refresh_token_hash,
    previous_refresh_token_expires_at,
    previous_refresh_token_rotated_at,
    created_at
)
SELECT
    id,
    id,
    refresh_token_hash,
    refresh_token_expires_at,
    previous_refresh_token_hash,
    previous_refresh_token_expires_at,
    previous_refresh_token_rotated_at,
    created_at
FROM players
WHERE refresh_token_hash IS NOT NULL AND refresh_token_expires_at IS NOT NULL;

UPDATE players
SET mirrored_refresh_token_hash = (SELECT sessions.refresh_token_hash FROM sessions WHERE sessions.id = players.id);
