-- The schema as the server built it before it had migrations (CLAUDE.md §8b): the statements
-- SchemaUtils.createStatements(*appTables) generates for PostgreSQL, in its order, with only
-- whitespace added. A database a server built before migrations was built by exactly these
-- statements, through SchemaUtils.create, which is why such a database is baselined at this version
-- rather than running it (Migrations), while an empty one runs it.
--
-- One script serves both engines. The identifiers are unquoted, so PostgreSQL folds them to lower
-- case and H2 to upper case, which is what Exposed's own statements for each engine produce, and
-- the statements are otherwise the same for both. SchemaDriftTest holds this to the table
-- definitions, names included, on H2 and on PostgreSQL.
--
-- Never edit this file once it has shipped: Flyway refuses to boot on a changed checksum. A change
-- to the schema is a new migration.

CREATE TABLE IF NOT EXISTS players (
    id VARCHAR(36) PRIMARY KEY,
    created_at BIGINT NOT NULL,
    total_points INT DEFAULT 0 NOT NULL,
    answers_given INT DEFAULT 0 NOT NULL,
    refresh_token_hash VARCHAR(64) NULL,
    refresh_token_expires_at BIGINT NULL,
    current_cycle INT DEFAULT 1 NOT NULL
);
ALTER TABLE players ADD CONSTRAINT players_refresh_token_hash_unique UNIQUE (refresh_token_hash);

CREATE TABLE IF NOT EXISTS questions (
    id VARCHAR(36) PRIMARY KEY,
    option_a VARCHAR(200) NOT NULL,
    option_b VARCHAR(200) NOT NULL,
    author_player_id VARCHAR(36) NULL,
    status VARCHAR(16) NOT NULL,
    submitted_at BIGINT NOT NULL,
    reviewed_at BIGINT NULL,
    rejection_reason VARCHAR(200) NULL,
    CONSTRAINT fk_questions_author_player_id__id FOREIGN KEY (author_player_id)
        REFERENCES players(id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
CREATE INDEX questions_author_player_id_status ON questions (author_player_id, status);
CREATE INDEX questions_status_submitted_at_id ON questions (status, submitted_at, id);

CREATE TABLE IF NOT EXISTS question_categories (
    question_id VARCHAR(36),
    category VARCHAR(32),
    CONSTRAINT pk_question_categories PRIMARY KEY (question_id, category),
    CONSTRAINT fk_question_categories_question_id__id FOREIGN KEY (question_id)
        REFERENCES questions(id) ON DELETE RESTRICT ON UPDATE RESTRICT
);

CREATE TABLE IF NOT EXISTS votes (
    player_id VARCHAR(36),
    question_id VARCHAR(36),
    side VARCHAR(1) NOT NULL,
    created_at BIGINT NOT NULL,
    answered_at BIGINT NOT NULL,
    answered_in_cycle INT NOT NULL,
    attempt_id VARCHAR(64) NOT NULL,
    CONSTRAINT pk_votes PRIMARY KEY (player_id, question_id),
    CONSTRAINT fk_votes_player_id__id FOREIGN KEY (player_id)
        REFERENCES players(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_votes_question_id__id FOREIGN KEY (question_id)
        REFERENCES questions(id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
CREATE INDEX votes_question_id_side ON votes (question_id, side);

CREATE TABLE IF NOT EXISTS skips (
    player_id VARCHAR(36),
    question_id VARCHAR(36),
    skipped_in_cycle INT NOT NULL,
    CONSTRAINT pk_skips PRIMARY KEY (player_id, question_id),
    CONSTRAINT fk_skips_player_id__id FOREIGN KEY (player_id)
        REFERENCES players(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_skips_question_id__id FOREIGN KEY (question_id)
        REFERENCES questions(id) ON DELETE RESTRICT ON UPDATE RESTRICT
);

CREATE TABLE IF NOT EXISTS likes (
    player_id VARCHAR(36),
    question_id VARCHAR(36),
    CONSTRAINT pk_likes PRIMARY KEY (player_id, question_id),
    CONSTRAINT fk_likes_player_id__id FOREIGN KEY (player_id)
        REFERENCES players(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_likes_question_id__id FOREIGN KEY (question_id)
        REFERENCES questions(id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
CREATE INDEX likes_question_id_player_id ON likes (question_id, player_id);
