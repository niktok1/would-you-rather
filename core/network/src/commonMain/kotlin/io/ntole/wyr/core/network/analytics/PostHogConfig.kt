package io.ntole.wyr.core.network.analytics

/**
 * Where a game build sends its analytics (CLAUDE.md §8g): a PostHog project's key, [apiKey], its
 * ingestion [host], and the version of the app sending, [appVersion], which every event names.
 *
 * Made by [of] from what the build was configured with; a build with no key has none, and sends
 * nothing. The key is a project's public one, which can only send events, never read them, so it is
 * no secret in a build; it is still never committed, since a build made from the repository must not
 * send to the user's project.
 */
public data class PostHogConfig(
    public val apiKey: String,
    public val host: String,
    public val appVersion: String,
) {
    init {
        require(apiKey.isNotBlank()) { "a PostHog key is never blank" }
        require(host.startsWith("https://") || host.startsWith("http://")) { "a PostHog host is a URL: \"$host\"" }
    }

    /** The key is left out: the one place it may be seen is the build's configuration. */
    override fun toString(): String = "PostHogConfig(host=$host, appVersion=$appVersion)"

    public companion object {
        /** PostHog's EU cloud, which is where a project is made unless another host is named. */
        public const val EU_HOST: String = "https://eu.i.posthog.com"

        /**
         * The configuration [apiKey] and [host] name, as a build sets them, trimmed: none for a blank or
         * absent key, which turns analytics off. A blank host is [EU_HOST], and one without a scheme,
         * `eu.i.posthog.com`, is taken as `https://` (an iOS `.xcconfig` cannot hold `//`); a trailing
         * slash goes.
         *
         * Anything that is no host throws, naming it, as an environment name that is none does
         * (CLAUDE.md §8e): a build that meant to send somewhere and got it wrong stops at launch.
         */
        public fun of(
            apiKey: String?,
            host: String?,
            appVersion: String,
        ): PostHogConfig? {
            val key = apiKey?.trim().orEmpty()
            if (key.isEmpty()) return null
            require(
                key.all { it.isLetterOrDigit() || it == '_' || it == '-' },
            ) { "a PostHog key is letters, digits, _ and -" }
            val named = host?.trim().orEmpty()
            val url =
                when {
                    named.isEmpty() -> EU_HOST
                    "://" in named -> named
                    else -> "https://$named"
                }.trimEnd('/')
            val schemed = url.startsWith("https://") || url.startsWith("http://")
            require(schemed && url.substringAfter("://").let { it.isNotEmpty() && it.none(Char::isWhitespace) }) {
                "the PostHog host is no host: \"$host\""
            }
            return PostHogConfig(apiKey = key, host = url, appVersion = appVersion.trim().ifEmpty { UNKNOWN_VERSION })
        }

        /** The version an event names when the build could not say its own. */
        internal const val UNKNOWN_VERSION: String = "unknown"
    }
}
