package io.ntole.wyr.server.push

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.push.PushTokenRequest
import io.ntole.wyr.core.push.RemovePushTokenRequest
import io.ntole.wyr.server.auth.JWT_AUTH
import io.ntole.wyr.server.auth.authenticatedPlayerId
import io.ntole.wyr.server.auth.authenticatedSessionId
import io.ntole.wyr.server.db.Db
import io.ntole.wyr.server.plugins.RouteLimit
import io.ntole.wyr.server.plugins.rateLimit
import io.ntole.wyr.server.plugins.receiveOrReject

/**
 * A device's push token (CLAUDE.md §8a, *Push tokens*). Both routes need a session, whose player and
 * session the token is kept under, and share one per-player budget. Registered whether or not the
 * server can send pushes (`FCM_SERVICE_ACCOUNT_JSON`), so the devices are there once it can.
 */
fun Route.pushRoutes(db: Db) {
    authenticate(JWT_AUTH) {
        rateLimit(RouteLimit.PUSH_TOKENS) {
            post(WyrApi.Paths.MY_PUSH_TOKENS) {
                val playerId = call.authenticatedPlayerId()
                val sessionId = call.authenticatedSessionId()
                val body = call.receiveOrReject<PushTokenRequest>("push token")
                val token = checkedPushToken(body.token)
                val platform = checkedPushPlatform(body.platform)

                db.query { PushTokenStore.register(token, playerId, sessionId, platform) }

                call.respond(HttpStatusCode.NoContent)
            }

            post(WyrApi.Paths.MY_PUSH_TOKEN_REMOVALS) {
                val playerId = call.authenticatedPlayerId()
                val body = call.receiveOrReject<RemovePushTokenRequest>("push token removal")
                val token = checkedPushToken(body.token)

                db.query { PushTokenStore.remove(token, playerId) }

                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
