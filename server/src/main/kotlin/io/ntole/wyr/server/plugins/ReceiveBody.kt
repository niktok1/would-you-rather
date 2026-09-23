package io.ntole.wyr.server.plugins

import io.ktor.http.BadContentTypeFormatException
import io.ktor.serialization.ContentConvertException
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
 * Only Ktor's own parse failures are translated: a [BadRequestException] whose cause is a parse
 * failure (see [rejectionFor]), and a [ContentTransformationException] for a body that cannot be
 * read as [T] at all (a content type no converter handles). Anything else is a server fault and
 * propagates.
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
        throw rejectionFor(undecodable, description)
    } catch (unreadable: ContentTransformationException) {
        throw ApiFailure.validation("malformed $description", unreadable)
    }

/**
 * What to throw for a [BadRequestException] out of `receive`.
 *
 * Ktor 3.5.1's content negotiation wraps any throwable from the converter in one, so the
 * exception alone does not say whose fault it is. The kotlinx converter reports a body that does
 * not decode as a [ContentConvertException], and a Content-Type header that does not parse
 * arrives as a [BadContentTypeFormatException]; those are the client's mistake. Anything else —
 * an I/O failure or an [Error] while buffering the body — is rethrown as itself, so `StatusPages`
 * logs it and answers 500 instead of blaming the client and leaving no trace.
 */
@PublishedApi
internal fun rejectionFor(
    undecodable: BadRequestException,
    description: String,
): Throwable {
    val clientMistake = ApiFailure.validation("malformed $description", undecodable)
    return when (val cause = undecodable.cause) {
        null, is BadContentTypeFormatException -> clientMistake
        is ContentConvertException -> (cause.cause as? Error) ?: clientMistake
        else -> cause
    }
}
