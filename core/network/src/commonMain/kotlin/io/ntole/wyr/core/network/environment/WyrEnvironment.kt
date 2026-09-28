package io.ntole.wyr.core.network.environment

/**
 * The server a client build talks to, chosen when the build is made (CLAUDE.md §8e), so a production
 * build cannot reach a development server by accident, nor the other way round.
 *
 * Here rather than in `:app:shared`, which is the game's UI, so that any client can name one, the
 * moderation app included: where a client's requests go is this module's business, and each platform
 * source set here knows how that platform reaches the developer's own machine.
 */
public enum class WyrEnvironment(
    /** Root URL of the API, scheme included, with no path: every route is an absolute path. */
    public val apiBaseUrl: String,
    /** How a person reads the environment's name, as the game's Account screen and the moderation app show it. */
    public val displayName: String,
) {
    /**
     * A server on the developer's own machine, `./gradlew :server:run`: `localhost:8080`, except on
     * Android, whose emulator reaches the host through `10.0.2.2`. Nothing else can reach it, a
     * physical phone included.
     */
    LOCAL(apiBaseUrl = localApiBaseUrl, displayName = "Local"),

    /** `wyr-server-dev` on Render: in-memory H2, deployed from every green commit on `main` (CLAUDE.md §8). */
    DEV(apiBaseUrl = "https://wyr-server-dev.onrender.com", displayName = "Dev"),

    /**
     * `wyr-server` on Render, on the production database, deployed only by hand (CLAUDE.md §8), through
     * the custom domain `wyr-api.ntole.com` rather than Render's own name, so the service can move off
     * Render without stranding an installed build.
     */
    PROD(apiBaseUrl = "https://wyr-api.ntole.com", displayName = "Prod"),
    ;

    public companion object {
        /**
         * The environment [name] names: `local`, `dev` or `prod`, in any case, trimmed. No name, or a
         * blank one, is [LOCAL], which is what a build that sets none is for.
         *
         * Anything else throws, naming the value, rather than falling back: a build that meant to name
         * an environment and got it wrong should stop at launch, not talk to a server nobody chose.
         */
        public fun parse(name: String?): WyrEnvironment {
            val trimmed = name?.trim()
            if (trimmed.isNullOrEmpty()) return LOCAL
            return requireNotNull(entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }) {
                "WYR_ENV must be local, dev or prod, but is \"$name\""
            }
        }
    }
}

/** Where [WyrEnvironment.LOCAL] is reached from this platform. */
internal expect val localApiBaseUrl: String
