package io.ntole.wyr.core.network.trace

import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.utils.unwrapCancellationException
import kotlin.time.TimeSource

internal class HttpTracingConfig {
    var trace: HttpTrace = HttpTrace()
}

/**
 * Records every exchange into [HttpTracingConfig.trace].
 *
 * Install it after `Auth`. Send interceptors run in install order, outermost first, so installed
 * last this sees each request that actually went out, where earlier it would see a refreshed and
 * retried call as one. The response validator is built in and installed before every configured
 * plugin, so an error status arrives here as a response, not as the exception it later becomes.
 */
internal val HttpTracing =
    createClientPlugin("HttpTracing", ::HttpTracingConfig) {
        val trace = pluginConfig.trace

        on(Send) { request ->
            val started = TimeSource.Monotonic.markNow()
            val method = request.method.value
            val pathAndQuery = request.url.build().encodedPathAndQuery

            fun record(outcome: HttpExchange.Outcome) =
                trace.record(HttpExchange(method, pathAndQuery, outcome, started.elapsedNow().inWholeMilliseconds))

            val call =
                try {
                    proceed(request)
                } catch (failure: Throwable) {
                    // Rethrown untouched, cancellation included (CLAUDE.md §5), but recorded as the
                    // caller will see it. A request timeout cancels the request with the
                    // HttpRequestTimeoutException as its cause, and only reaches the caller as that
                    // once Ktor unwraps it, further out; recorded as is, it would read as a
                    // cancellation nobody asked for.
                    val seen = failure.unwrapCancellationException()
                    record(HttpExchange.Outcome.Failed(seen::class.simpleName ?: "Throwable"))
                    throw failure
                }
            record(HttpExchange.Outcome.Answered(call.response.status.value))
            call
        }
    }
