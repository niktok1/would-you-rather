package io.ntole.wyr.server.plugins

import io.ktor.http.HttpStatusCode
import io.ntole.wyr.core.error.ErrorCode

/**
 * The only exception route code should throw.
 *
 * Pairs an HTTP status with the contract's own [ErrorCode] so `StatusPages` can render a valid
 * `ErrorDto` without every handler formatting its own error body.
 */
class ApiFailure(
    val status: HttpStatusCode,
    val code: ErrorCode,
    override val message: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    companion object {
        fun questionNotFound(id: String) =
            ApiFailure(HttpStatusCode.NotFound, ErrorCode.QUESTION_NOT_FOUND, "no question $id")

        fun validation(
            message: String,
            cause: Throwable? = null,
        ) = ApiFailure(HttpStatusCode.BadRequest, ErrorCode.VALIDATION_FAILED, message, cause)

        /** A submitted question the player can put right, as opposed to a malformed request ([validation]). */
        fun invalidSubmission(message: String) =
            ApiFailure(HttpStatusCode.UnprocessableEntity, ErrorCode.INVALID_SUBMISSION, message)

        fun submissionLimit(limit: Int) =
            ApiFailure(HttpStatusCode.Conflict, ErrorCode.SUBMISSION_LIMIT, "already $limit submissions pending")

        /** A moderator's decision on a question that is no longer, or never was, pending. */
        fun alreadyDecided(id: String) =
            ApiFailure(HttpStatusCode.Conflict, ErrorCode.ALREADY_DECIDED, "question $id is not pending")

        fun unauthorized(message: String = "missing or invalid credentials") =
            ApiFailure(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED, message)

        /**
         * An admin route called without the server's admin token. Deliberately never [unauthorized]:
         * see `requireAdmin`.
         */
        fun forbidden() = ApiFailure(HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN, "admin token missing or wrong")

        fun invalidRefreshToken() =
            ApiFailure(
                HttpStatusCode.Unauthorized,
                ErrorCode.INVALID_REFRESH_TOKEN,
                "refresh token unknown, expired, or already rotated",
            )
    }
}
