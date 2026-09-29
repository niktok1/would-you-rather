-- The seed category GROSS is named Бљак (Yuck) from 2026-09-29, the user's: Гадости read harsher than
-- the game's tone. The seed writes only what a database lacks and never renames what it holds (CLAUDE.md
-- §8d, Seeds), so a database seeded before this build keeps the old names without this script. It
-- renames only a category still named as the seed first wrote it, so a name a moderator gave it stays.
--
-- A build from before this script runs on what it leaves: a category's names are plain text it reads.
--
-- Never edit this file once it has shipped: Flyway refuses to boot on a changed checksum.

UPDATE categories SET name_sr = 'Бљак', name_en = 'Yuck' WHERE id = 'GROSS' AND name_sr = 'Гадости';
