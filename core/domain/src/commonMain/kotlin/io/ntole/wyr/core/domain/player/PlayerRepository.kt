package io.ntole.wyr.core.domain.player

/** The session player, as the server knows them. Implemented in `:core:data`. */
public interface PlayerRepository {
    /**
     * The session player's stats, read from the server every time.
     *
     * @throws io.ntole.wyr.core.domain.error.WyrException on any failure.
     */
    public suspend fun stats(): PlayerStats
}
