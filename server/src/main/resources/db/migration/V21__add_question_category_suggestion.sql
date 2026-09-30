-- A category suggestion (CLAUDE.md §8d, Categories, Nothing fits): when none of the server's
-- categories fits a question, its author may file it under none and suggest one, which the moderator
-- reads when deciding, and may make a category of. NULL for every question already here, seeds
-- included, and for any submission that suggests none.
--
-- Drafted by ./gradlew :server:pendingMigration, then written in lower case and unquoted, as V1 is, so
-- the one script runs on H2 and on PostgreSQL. Nullable with no default, so on PostgreSQL adding it
-- rewrites nothing.
--
-- A build from before this script still runs on what it leaves (CLAUDE.md §8b, Rollbacks): it never
-- names the column. A question filed under none reads there as filed under none, and its moderator
-- (an older moderation app) approving it with none keeps it so, served under no category until the
-- roll forward, where a moderator can file it.
--
-- Never edit this file once it has shipped: Flyway refuses to boot on a changed checksum.

ALTER TABLE questions ADD COLUMN category_suggestion VARCHAR(40) NULL;
