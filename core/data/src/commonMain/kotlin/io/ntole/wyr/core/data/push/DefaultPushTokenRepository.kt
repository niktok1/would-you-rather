package io.ntole.wyr.core.data.push

import io.ntole.wyr.core.data.mapper.runApi
import io.ntole.wyr.core.domain.push.PushPlatform
import io.ntole.wyr.core.domain.push.PushTokenRepository
import io.ntole.wyr.core.network.api.PushApi
import io.ntole.wyr.core.push.PushTokenRequest
import io.ntole.wyr.core.push.PushPlatform as WirePushPlatform

/**
 * Registers the device's push token through [PushApi] (CLAUDE.md §8a, *Push tokens*), under the session
 * the bearer names. Through [runApi] alone, never `withSessionRecovery`: a registration follows the
 * session and must never mint or replace one, so a dead session's 401 is only a failure, and the fresh
 * guest the next call mints registers again.
 */
public class DefaultPushTokenRepository(
    private val api: PushApi,
) : PushTokenRepository {
    override suspend fun register(
        token: String,
        platform: PushPlatform,
    ): Unit = runApi { api.register(PushTokenRequest(token = token, platform = platform.toWire())) }
}

internal fun PushPlatform.toWire(): WirePushPlatform =
    when (this) {
        PushPlatform.ANDROID -> WirePushPlatform.ANDROID
        PushPlatform.IOS -> WirePushPlatform.IOS
        PushPlatform.WEB -> WirePushPlatform.WEB
    }
