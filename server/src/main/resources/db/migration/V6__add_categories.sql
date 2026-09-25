-- Categories become server data (CLAUDE.md §8d, Categories): a table of them, each with a stable id
-- and a name in Serbian (Cyrillic) and in English, which a moderator adds to without a build. The
-- first ones are the four the wire's enum named that stay, under the ids that enum sent, so a client
-- built before still reads every one, in its declaration order (created_at, a millisecond apart),
-- then ABSURD. RANDOM is no category any more: asking for no category is every question, which is
-- what RANDOM was taken for, and the absurd questions filed under it move to ABSURD, once each.
-- Seed.CATEGORIES holds the same rows, and MigrationsTest holds the two equal.
--
-- Drafted by ./gradlew :server:pendingMigration, then written in lower case and unquoted, as V1 is, so
-- the one script runs on H2 and on PostgreSQL. The foreign key comes last, once every row of
-- question_categories names a category: the builds that wrote them knew only the enum's five names.
-- A row naming anything else would fail it, and with it the boot, rather than be left behind.
--
-- A build from before this script still runs on what it leaves (CLAUDE.md §8b, Rollbacks): it never
-- names categories, and it reads a name in question_categories it has no enum member for as RANDOM,
-- beside the question's others, so it shows an ABSURD question, or one under a category added later,
-- as RANDOM, and its RANDOM filter, which compares the stored names, finds none of them. It would still
-- file a submission or an approval under RANDOM, which the foreign key refuses, as a 500.
--
-- Never edit this file once it has shipped: Flyway refuses to boot on a changed checksum.

CREATE TABLE categories (
    id VARCHAR(32) PRIMARY KEY,
    name_sr VARCHAR(40) NOT NULL,
    name_en VARCHAR(40) NOT NULL,
    created_at BIGINT NOT NULL
);

INSERT INTO categories (id, name_sr, name_en, created_at) VALUES
    ('FOOD', 'Храна', 'Food', 1790294400000),
    ('LIFESTYLE', 'Начин живота', 'Lifestyle', 1790294400001),
    ('ETHICS', 'Етика', 'Ethics', 1790294400002),
    ('SUPERPOWERS', 'Супермоћи', 'Superpowers', 1790294400003),
    ('ABSURD', 'Апсурдно', 'Absurd', 1790294400004);

-- No build before this one knew ABSURD, so no question is filed under it yet, and the move files each
-- question under it once.
UPDATE question_categories SET category = 'ABSURD' WHERE category = 'RANDOM';

ALTER TABLE question_categories ADD CONSTRAINT fk_question_categories_category__id FOREIGN KEY (category)
    REFERENCES categories(id) ON DELETE RESTRICT ON UPDATE RESTRICT;
