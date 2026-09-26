-- Reports and hiding (CLAUDE.md §8d, Reports): a player reports a question to the moderator, once per
-- question, a repeat replacing the reason, and hides a question, or every question by one author, from
-- themselves for good. A report hides its question too. Three new tables, empty at first, so every row
-- already here stays as it is.
--
-- Drafted by ./gradlew :server:pendingMigration, then written in lower case and unquoted, as V1 is, so
-- the one script runs on H2 and on PostgreSQL.
--
-- A build from before this script still runs on what it leaves (CLAUDE.md §8b, Rollbacks): it never
-- names these tables, so it serves hidden questions again and takes no report, until the roll forward.
--
-- Never edit this file once it has shipped: Flyway refuses to boot on a changed checksum.

CREATE TABLE IF NOT EXISTS reports (
    player_id VARCHAR(36),
    question_id VARCHAR(36),
    reason VARCHAR(16) NOT NULL,
    reported_at BIGINT NOT NULL,
    CONSTRAINT pk_reports PRIMARY KEY (player_id, question_id),
    CONSTRAINT fk_reports_player_id__id FOREIGN KEY (player_id)
        REFERENCES players(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_reports_question_id__id FOREIGN KEY (question_id)
        REFERENCES questions(id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
CREATE INDEX reports_question_id_reason ON reports (question_id, reason);

CREATE TABLE IF NOT EXISTS hidden_questions (
    player_id VARCHAR(36),
    question_id VARCHAR(36),
    CONSTRAINT pk_hidden_questions PRIMARY KEY (player_id, question_id),
    CONSTRAINT fk_hidden_questions_player_id__id FOREIGN KEY (player_id)
        REFERENCES players(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_hidden_questions_question_id__id FOREIGN KEY (question_id)
        REFERENCES questions(id) ON DELETE RESTRICT ON UPDATE RESTRICT
);

CREATE TABLE IF NOT EXISTS hidden_authors (
    player_id VARCHAR(36),
    author_player_id VARCHAR(36),
    CONSTRAINT pk_hidden_authors PRIMARY KEY (player_id, author_player_id),
    CONSTRAINT fk_hidden_authors_player_id__id FOREIGN KEY (player_id)
        REFERENCES players(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_hidden_authors_author_player_id__id FOREIGN KEY (author_player_id)
        REFERENCES players(id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
CREATE INDEX hidden_authors_author_player_id_player_id ON hidden_authors (author_player_id, player_id);
