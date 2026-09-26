-- Deleting an account (CLAUDE.md §8a, Deleting an account) deletes the player's questions no player was
-- ever served, and deleting a question makes PostgreSQL check that no row names it, in every table whose
-- foreign key does: skips and hidden_questions find a question's rows by nothing, since question_id is
-- their key's second column, so each question deleted would read both tables whole. PostgreSQL does
-- not index a foreign key by itself.
--
-- Drafted by ./gradlew :server:pendingMigration, then written in lower case and unquoted, as V1 is, so
-- the one script runs on H2 and on PostgreSQL.
--
-- A build from before this script still runs on what it leaves (CLAUDE.md §8b, Rollbacks): an index
-- changes no statement's answer.
--
-- Never edit this file once it has shipped: Flyway refuses to boot on a changed checksum.

CREATE INDEX skips_question_id_player_id ON skips (question_id, player_id);
CREATE INDEX hidden_questions_question_id_player_id ON hidden_questions (question_id, player_id);
