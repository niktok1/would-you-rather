package io.ntole.wyr.server.plugins

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.request.receive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Reads the request body as [T], reporting a body the client got wrong as
 * [ApiFailure.validation] instead of letting it reach the catch-all 500.
 *
 * Only Ktor's own parse failures are translated: [BadRequestException] for a body that does not
 * decode, [ContentTransformationException] for one that cannot be read as [T] at all (a content
 * type no converter handles). Anything else is a server fault and propagates.
 *
 * Cancellation needs one extra step. Ktor's content negotiation wraps *every* throwable from the
 * converter in [BadRequestException], a [kotlinx.coroutines.CancellationException] included, so
 * the call's own cancellation is re-raised here rather than reported as the client's mistake.
 */
suspend inline fun <reified T : Any> ApplicationCall.receiveOrReject(description: String): T =
    try {
        receive<T>()
    } catch (undecodable: BadRequestException) {
        currentCoroutineContext().ensureActive()
        throw ApiFailure.validation("malformed $description", undecodable)
    } catch (unreadable: ContentTransformationException) {
        throw ApiFailure.validation("malformed $description", unreadable)
    }
