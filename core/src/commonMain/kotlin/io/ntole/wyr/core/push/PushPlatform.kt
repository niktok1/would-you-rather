package io.ntole.wyr.core.push

import kotlinx.serialization.Serializable

/**
 * Which platform a device's push token is for ([PushTokenRequest]). Every one is sent through Firebase
 * Cloud Messaging, which takes a token from any of them; the platform is kept beside it so a message
 * can be shaped for it later.
 *
 * A growable wire enum (CLAUDE.md §5), as another platform may come: [UNKNOWN] is the default of every
 * property of this type. Only a client sends it, and the server refuses [UNKNOWN] as it refuses a
 * request naming no platform, so a client must name its own.
 */
@Serializable
public enum class PushPlatform {
    ANDROID,
    IOS,
    WEB,
    UNKNOWN,
}
