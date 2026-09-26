package io.ntole.wyr.core.network

import io.ntole.wyr.core.api.WyrApi

/**
 * The build of the game a request comes from (CLAUDE.md §8b, *Minimum client version*): its
 * [platform], one of [WyrApi.ClientPlatform]'s names, and its build [number], made from the app's
 * version as every platform makes it (§8g, *The build number*). The game's HTTP client names both on
 * every request, in [WyrApi.Headers.CLIENT_PLATFORM] and [WyrApi.Headers.CLIENT_VERSION]; the
 * moderation app's names neither, being no build the server ever refuses.
 */
public data class ClientBuild(
    public val platform: String,
    public val number: Int,
) {
    init {
        require(platform in PLATFORMS) { "no client platform: \"$platform\"" }
        require(number >= 1) { "a build number is at least 1: $number" }
    }

    public companion object {
        private val PLATFORMS =
            setOf(
                WyrApi.ClientPlatform.ANDROID,
                WyrApi.ClientPlatform.IOS,
                WyrApi.ClientPlatform.WEB,
                WyrApi.ClientPlatform.DESKTOP,
            )

        /**
         * This platform's build [number], as its entry point read it from the build, or null when it
         * read none, or none of at least 1: a desktop app started without its `wyr.app.build` property,
         * say. Such a build names neither header, which the server serves as it serves a build from
         * before the headers.
         */
        public fun of(number: Int?): ClientBuild? =
            number?.takeIf { it >= 1 }?.let { ClientBuild(clientPlatform(), it) }
    }
}

/** This platform's name as [WyrApi.Headers.CLIENT_PLATFORM] carries it. */
internal expect fun clientPlatform(): String
