package io.ntole.wyr.server.plugins

import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ReceiveRequestBytes
import io.ktor.server.request.contentLength
import io.ktor.utils.io.copyTo
import io.ktor.utils.io.writer
import java.io.IOException

/**
 * The largest request body the server takes, 64 KiB (CLAUDE.md §8b, *Request bodies*). The largest a
 * correct client sends is a submission: two options of at most
 * [io.ntole.wyr.core.api.WyrApi.Limits.MAX_OPTION_LENGTH] characters, under 1.5 KiB in the widest UTF-8,
 * and the ids of the categories picked, about 34 bytes each, so some 10 KiB with 300 of them picked.
 * Anything past the cap is no client's: it is refused before it costs the server more than the cap in
 * memory.
 */
const val MAX_REQUEST_BODY_BYTES: Long = 64L * 1024L

/**
 * Refuses a request body over [MAX_REQUEST_BODY_BYTES] with 413, on every route, without a
 * dependency of its own (Ktor's body-limit plugin is one): Ktor reads a whole body into memory to
 * decode it, so without a cap one request could hold as much as it liked.
 *
 * Twice over. A request whose `Content-Length` says more is refused before anything else of it is
 * read, the route's rate limit and authentication included, whether or not the route reads a body.
 * One that says nothing, a chunked body, is counted as it is read instead ([ReceiveRequestBytes]):
 * past the cap the read stops with [BodyOverCap], which [receiveOrReject] answers 413. A route that
 * never reads its body never reads past the cap either.
 */
val RequestBodyCap =
    createApplicationPlugin("RequestBodyCap") {
        onCall { call ->
            val declared = call.request.contentLength()
            if (declared != null && declared > MAX_REQUEST_BODY_BYTES) throw ApiFailure.bodyTooLarge()
        }

        on(ReceiveRequestBytes) { call, body ->
            // Copied through at most one byte past the cap, so a longer body is known to be one without
            // holding more of it. The copy's own failure closes the channel the route reads, with it as
            // the cause, which is how it reaches receiveOrReject.
            call
                .writer {
                    val copied = body.copyTo(channel, limit = MAX_REQUEST_BODY_BYTES + 1)
                    if (copied > MAX_REQUEST_BODY_BYTES) throw BodyOverCap()
                }.channel
        }
    }

/**
 * A request body went past [MAX_REQUEST_BODY_BYTES] as it was read. Ktor hands it on wrapped, in a
 * closed channel's failure and then in content negotiation's, so [rejectionFor] looks for it along
 * the causes.
 */
class BodyOverCap : IOException("request body over $MAX_REQUEST_BODY_BYTES bytes")
