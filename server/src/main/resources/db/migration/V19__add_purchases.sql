-- The shop (CLAUDE.md §8d, The shop): what each player bought, one row per player and item, and the
-- points they paid for it, which their stats count as spent. Empty until someone buys. The primary key
-- holds a player to one purchase of each item, and leads with the player, so it also finds what they
-- own and serves the foreign key.
--
-- The foreign key cascades, as push_tokens' (V17) and identities' (V18) do: deleting a player deletes
-- their purchases, with no store having to know this table is there.
--
-- Drafted by ./gradlew :server:pendingMigration, then written in lower case and unquoted, as V1 is, so
-- the one script runs on H2 and on PostgreSQL.
--
-- A build from before this script still runs on what it leaves (CLAUDE.md §8b, Rollbacks): it never
-- names the table, so it sells nothing, and it counts none of a purchase's price as spent, so a buyer's
-- stats there no longer add up to their total until the roll forward.
--
-- Never edit this file once it has shipped: Flyway refuses to boot on a changed checksum.

CREATE TABLE IF NOT EXISTS purchases (
    player_id VARCHAR(36),
    item_id VARCHAR(32),
    price INT NOT NULL,
    purchased_at BIGINT NOT NULL,
    CONSTRAINT pk_purchases PRIMARY KEY (player_id, item_id),
    CONSTRAINT fk_purchases_player_id__id FOREIGN KEY (player_id)
        REFERENCES players(id) ON DELETE CASCADE ON UPDATE RESTRICT
);
