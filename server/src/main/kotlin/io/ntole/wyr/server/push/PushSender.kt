package io.ntole.wyr.server.push

/** One push to one device: a notification's [title] and [body], and [data] for the app to act on. */
data class PushMessage(
    val title: String,
    val body: String,
    val data: Map<String, String>,
)

/** What became of one push. */
enum class PushOutcome {
    SENT,

    /** No device has the token any more (Firebase's `UNREGISTERED`), so it should be forgotten. */
    UNREGISTERED,

    /** Anything else: the push did not go, and the token may still work. Already logged. */
    FAILED,
}

/** Sends pushes to devices by their tokens (CLAUDE.md §8a, *Push tokens*): Firebase in production ([FcmSender]). */
fun interface PushSender {
    /**
     * Sends [message] to the device [token] names. Never throws but for its caller's cancellation: a
     * failure is [PushOutcome.FAILED], logged without the token.
     */
    suspend fun send(
        token: String,
        message: PushMessage,
    ): PushOutcome
}
