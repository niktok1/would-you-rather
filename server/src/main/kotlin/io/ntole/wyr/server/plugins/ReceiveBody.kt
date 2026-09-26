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
 * [ApiFailure.validation] instead of letting it reach the catch-all 500, and one over the cap
 * ([RequestBodyCap]) as [ApiFailure.bodyTooLarge].
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
    } catch (failure: Exception) {
        currentCoroutineContext().ensureActive()
        throw rejectionFor(failure, description)
    }

/**
 * What to throw for a [failure] out of `receive`.
 *
 * A body over the cap is 413 wherever along the causes its [BodyOverCap] is: the channel the route
 * reads rethrows it wrapped, and content negotiation wraps that again or not, as the read that met it
 * was the converter's or its own first look at the body.
 *
 * Ktor 3.5.1's content negotiation wraps any throwable from the converter in a [BadRequestException],
 * so that exception alone does not say whose fault it is. The kotlinx converter reports a body that
 * does not decode as a [ContentConvertException], and a Content-Type header that does not parse
 * arrives as a [BadContentTypeFormatException]; those are the client's mistake. Anything else — an
 * I/O failure or an [Error] while buffering the body — is rethrown as itself, so `StatusPages` logs
 * it and answers 500 instead of blaming the client and leaving no trace. A
 * [ContentTransformationException], a body no converter reads as the type asked for, is the client's
 * mistake too, and any other failure is rethrown as it came.
 */
@PublishedApi
internal fun rejectionFor(
    failure: Throwable,
    description: String,
): Throwable {
    if (generateSequence(failure) { it.cause }.take(MAX_CAUSE_DEPTH).any { it is BodyOverCap }) {
        return ApiFailure.bodyTooLarge()
    }
    return when (failure) {
        is BadRequestException -> {
            val clientMistake = ApiFailure.validation("malformed $description", failure)
            when (val cause = failure.cause) {
                null, is BadContentTypeFormatException -> clientMistake
                is ContentConvertException -> (cause.cause as? Error) ?: clientMistake
                else -> cause
            }
        }

        is ContentTransformationException -> {
            ApiFailure.validation("malformed $description", failure)
        }

        else -> {
            failure
        }
    }
}

/** How far along a failure's causes [rejectionFor] looks, bounded in case of a cycle. */
private const val MAX_CAUSE_DEPTH = 8
