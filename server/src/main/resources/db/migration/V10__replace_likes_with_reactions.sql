-- Reactions (CLAUDE.md §8d, Reactions): a player likes a question, dislikes it, or neither. The likes
-- table becomes reactions, one row per player and question while they hold either, the kind in its own
-- column, so the key that held a player to one like per question now holds them to one reaction: a
-- like and a dislike of one question can never both be held. Every like already held moves across as
-- a like, and nothing it paid its author changes.
--
-- Drafted from the statements Exposed generates for Tables.kt's Reactions, then written in lower case
-- and unquoted, as V1 is, so the one script runs on H2 and on PostgreSQL. A new table rather than a
-- column added to likes, so every name is what SchemaUtils.create gives Reactions (SchemaDriftTest).
--
-- A build from before this script does not run on what it leaves: it reads likes, which is gone.
-- Accepted: nothing is live yet, so no build before this one is a rollback target (the user,
-- 2026-09-26).
--
-- Never edit this file once it has shipped: Flyway refuses to boot on a changed checksum.

CREATE TABLE IF NOT EXISTS reactions (
    player_id VARCHAR(36),
    question_id VARCHAR(36),
    reaction VARCHAR(8) NOT NULL,
    CONSTRAINT pk_reactions PRIMARY KEY (player_id, question_id),
    CONSTRAINT fk_reactions_player_id__id FOREIGN KEY (player_id)
        REFERENCES players(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_reactions_question_id__id FOREIGN KEY (question_id)
        REFERENCES questions(id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
CREATE INDEX reactions_question_id_player_id ON reactions (question_id, player_id);

INSERT INTO reactions (player_id, question_id, reaction)
    SELECT player_id, question_id, 'LIKE' FROM likes;

DROP TABLE likes;
