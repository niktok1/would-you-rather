package io.ntole.wyr.core.network.trace

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The latest HTTP exchanges a client made, newest first, for the dev console to show.
 *
 * Holds only what is safe to put on a screen. Headers and bodies are never read, because that is
 * where the tokens are: the bearer in `Authorization`, the refresh token in the refresh body.
 */
public class HttpTrace(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity > 0) { "capacity must be positive: $capacity" }
    }

    private val recorded = MutableStateFlow<List<HttpExchange>>(emptyList())

    public val exchanges: StateFlow<List<HttpExchange>> = recorded.asStateFlow()

    internal fun record(exchange: HttpExchange) {
        recorded.update { (listOf(exchange) + it).take(capacity) }
    }

    public companion object {
        public const val DEFAULT_CAPACITY: Int = 50
    }
}

/**
 * One request as it went over the wire. A call the bearer provider retried after a refresh is
 * three of these: the rejected call, the refresh, and the retry.
 */
public data class HttpExchange(
    public val method: String,
    public val pathAndQuery: String,
    public val outcome: Outcome,
    public val elapsedMillis: Long,
) {
    public sealed interface Outcome {
        /** A response arrived, whatever its status. */
        public data class Answered(
            public val status: Int,
        ) : Outcome

        /** No response arrived. [exceptionClass] names what was thrown instead. */
        public data class Failed(
            public val exceptionClass: String,
        ) : Outcome
    }
}
