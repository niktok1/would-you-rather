-- Push tokens (CLAUDE.md §8a, Push tokens): the devices a player's pushes reach, one row per Firebase
-- registration token, kept under the session that registered it. Empty until a client registers one.
--
-- Both foreign keys cascade, as identities' (V18) does, and no other table's: the logout that deletes a session deletes its
-- device's tokens with it, and deleting a player deletes theirs, with no store having to know this
-- table is there.
--
-- Drafted by ./gradlew :server:pendingMigration, then written in lower case and unquoted, as V1 is, so
-- the one script runs on H2 and on PostgreSQL.
--
-- A build from before this script still runs on what it leaves (CLAUDE.md §8b, Rollbacks): it never
-- names the table. A logout there still deletes its session, and the cascade still takes that
-- session's tokens.
--
-- Never edit this file once it has shipped: Flyway refuses to boot on a changed checksum.

CREATE TABLE IF NOT EXISTS push_tokens (
    token VARCHAR(1024) PRIMARY KEY,
    player_id VARCHAR(36) NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    platform VARCHAR(16) NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT fk_push_tokens_player_id__id FOREIGN KEY (player_id)
        REFERENCES players(id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_push_tokens_session_id__id FOREIGN KEY (session_id)
        REFERENCES sessions(id) ON DELETE CASCADE ON UPDATE RESTRICT
);
CREATE INDEX push_tokens_player_id_updated_at ON push_tokens (player_id, updated_at);
CREATE INDEX push_tokens_session_id_token ON push_tokens (session_id, token);
