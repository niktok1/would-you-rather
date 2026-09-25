-- The seeds in Serbian (CLAUDE.md §8d, Seeds): each seed a database seeded before holds, found by the id
-- every build has given it, gets the Serbian Cyrillic options Seed writes into a new database, written
-- for Serbian rather than put word for word from the English. Nothing else about a seed changes: its
-- categories, its votes, made-up and real, and its likes stay, and so does a retirement. A database
-- with no seeds yet, a new one, updates nothing here, and the seed writes the Serbian itself once the
-- migration is done. MigrationsTest holds the texts here to Seed's. A player's own questions are left
-- exactly as typed.
--
-- Written by hand, as data: no table definition changed, so ./gradlew :server:pendingMigration drafts
-- nothing for it. In lower case and unquoted identifiers, as V1 is, so the one script runs on H2 and
-- on PostgreSQL; Flyway reads it as UTF-8.
--
-- A build from before this script still runs on what it leaves (CLAUDE.md §8b, Rollbacks): the
-- columns are the ones it reads, and it serves the Serbian as it would any text.
--
-- Never edit this file once it has shipped: Flyway refuses to boot on a changed checksum.

UPDATE questions SET option_a = 'До краја живота јести само пицу', option_b = 'До краја живота јести само суши' WHERE id = 'seed-1';
UPDATE questions SET option_a = 'Заувек се одрећи кафе', option_b = 'Заувек се одрећи чоколаде' WHERE id = 'seed-2';
UPDATE questions SET option_a = 'Да ти храна увек буде мало пресољена', option_b = 'Да ти храна увек буде мало бљутава' WHERE id = 'seed-3';
UPDATE questions SET option_a = 'Радити четири дуга дана у недељи', option_b = 'Радити пет кратких дана у недељи' WHERE id = 'seed-4';
UPDATE questions SET option_a = 'Живети без музике', option_b = 'Живети без филмова' WHERE id = 'seed-5';
UPDATE questions SET option_a = 'Никад више не закаснити', option_b = 'Никад више не осетити умор' WHERE id = 'seed-6';
UPDATE questions SET option_a = 'Сваке године се селити у нови град', option_b = 'Никад не напустити родни град' WHERE id = 'seed-7';
UPDATE questions SET option_a = 'Увек говорити истину', option_b = 'Да ти сви увек говоре истину' WHERE id = 'seed-8';
UPDATE questions SET option_a = 'Увек знати кад те неко лаже', option_b = 'Да ти свако поверује у сваку лаж' WHERE id = 'seed-9';
UPDATE questions SET option_a = 'Спасти једног пријатеља', option_b = 'Спасти пет непознатих људи' WHERE id = 'seed-10';
UPDATE questions SET option_a = 'Имати моћ летења', option_b = 'Имати моћ невидљивости' WHERE id = 'seed-11';
UPDATE questions SET option_a = 'Читати туђе мисли', option_b = 'Видети недељу дана унапред' WHERE id = 'seed-12';
UPDATE questions SET option_a = 'Телепортовати се било где у трену', option_b = 'Сваког дана зауставити време на сат' WHERE id = 'seed-13';
UPDATE questions SET option_a = 'Никад више не морати да спаваш', option_b = 'Никад више не морати да једеш' WHERE id = 'seed-14';
UPDATE questions SET option_a = 'Борити се са једном патком величине коња', option_b = 'Борити се са сто коња величине патке' WHERE id = 'seed-15';
UPDATE questions SET option_a = 'Имати прсте дугачке као ноге', option_b = 'Имати ноге кратке као прсти' WHERE id = 'seed-16';
UPDATE questions SET option_a = 'Увек говорити у стиховима', option_b = 'Увек само шапутати' WHERE id = 'seed-17';
UPDATE questions SET option_a = 'Живети у вечном лету', option_b = 'Живети у вечној зими' WHERE id = 'seed-18';
UPDATE questions SET option_a = 'Изгубити све своје фотографије', option_b = 'Изгубити све своје поруке' WHERE id = 'seed-19';
UPDATE questions SET option_a = 'Да те после смрти сви забораве', option_b = 'Да те памте по погрешном' WHERE id = 'seed-20';
UPDATE questions SET option_a = 'Јести само топлу храну', option_b = 'Јести само хладну храну' WHERE id = 'seed-21';
UPDATE questions SET option_a = 'Разговарати са животињама', option_b = 'Говорити све људске језике' WHERE id = 'seed-22';
UPDATE questions SET option_a = 'Заувек шепати без икаквог разлога', option_b = 'Заувек кашљати без икаквог разлога' WHERE id = 'seed-23';
UPDATE questions SET option_a = 'Сутра добити на лутрији', option_b = 'Живети двадесет година дуже' WHERE id = 'seed-24';
