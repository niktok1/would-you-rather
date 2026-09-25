package io.ntole.wyr.core.auth

/**
 * How a recovery secret shows in a `toString`: whether there is one, never what it is. It is the one
 * credential that never rotates, so a copy in a log or a test's failure message would recover its
 * player for as long as the player keeps it (CLAUDE.md §8a, *Recovery*).
 */
internal fun redacted(secret: String?): String = if (secret == null) "null" else "<redacted>"
