package io.ntole.wyr.core.network

/**
 * Where a phone keeps the player's recovery secret (CLAUDE.md §8a, *Recovery*): a store the platform
 * keeps across the app being deleted and installed again, and hands on to the phone that replaces
 * this one, which the session's [TokenStorage] deliberately is not. Android backs it with Google Play
 * services' Block Store, iOS with an item in the iCloud Keychain. Desktop and web have none, and play
 * as guests only: no binding at all, rather than one that stores nothing, so the data layer knows
 * not to ask the server for a secret it could not keep.
 *
 * Every call suspends, since Block Store answers asynchronously, and throws when the store cannot do
 * what was asked: no Play services, or a Keychain that refuses. Unlike [TokenStorage.read], a read
 * that cannot be made throws rather than answering null. None stored is what makes the data layer ask
 * the server for a new secret, which kills the one before, so a store holding a live secret that it
 * could not read must not look empty. What to do instead is the data layer's choice.
 */
public interface RecoverySecretStorage {
    /** The value stored under [key], or null when none is. */
    public suspend fun read(key: String): String?

    /**
     * Stores [value] under [key], and returns once the store holds it. A write asked for is made
     * whole even if the caller is cancelled meanwhile, as [TokenStorage.write] promises.
     */
    public suspend fun write(
        key: String,
        value: String,
    )

    /** Removes what is stored under [key], if anything is, as surely as [write] stores it. */
    public suspend fun clear(key: String)
}
