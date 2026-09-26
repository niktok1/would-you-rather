package io.ntole.wyr.server.push

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.push.PushPlatform
import io.ntole.wyr.server.plugins.ApiFailure

/**
 * [token] as a push token the server keeps, or [ApiFailure.validation]: not blank, at most
 * [WyrApi.Limits.MAX_PUSH_TOKEN_LENGTH], and visible ASCII only, which every token Firebase gives is. The
 * message never holds the token.
 */
internal fun checkedPushToken(token: String): String {
    if (token.isEmpty()) throw ApiFailure.validation("token is blank")
    if (token.length > WyrApi.Limits.MAX_PUSH_TOKEN_LENGTH) {
        throw ApiFailure.validation("token is over ${WyrApi.Limits.MAX_PUSH_TOKEN_LENGTH} characters")
    }
    if (token.any { it !in VISIBLE_ASCII }) throw ApiFailure.validation("token holds a character no push token has")
    return token
}

/** [platform], or [ApiFailure.validation] for [PushPlatform.UNKNOWN]: a client names its own. */
internal fun checkedPushPlatform(platform: PushPlatform): PushPlatform {
    if (platform == PushPlatform.UNKNOWN) throw ApiFailure.validation("platform names no platform")
    return platform
}

private val VISIBLE_ASCII = '!'..'~'
