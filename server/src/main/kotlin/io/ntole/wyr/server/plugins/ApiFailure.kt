package io.ntole.wyr.server.plugins

import io.ktor.http.HttpStatusCode
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.question.QuestionStatus

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

        /**
         * A request body over [MAX_REQUEST_BODY_BYTES] ([RequestBodyCap]). [ErrorCode.VALIDATION_FAILED], with
         * 413: no correct client sends one, so it is a bug on one side, as a malformed body is.
         */
        fun bodyTooLarge() =
            ApiFailure(
                HttpStatusCode.PayloadTooLarge,
                ErrorCode.VALIDATION_FAILED,
                "request body over $MAX_REQUEST_BODY_BYTES bytes",
            )

        /** A submitted question the player can put right, as opposed to a malformed request ([validation]). */
        fun invalidSubmission(message: String) =
            ApiFailure(HttpStatusCode.UnprocessableEntity, ErrorCode.INVALID_SUBMISSION, message)

        fun submissionLimit(limit: Int) =
            ApiFailure(HttpStatusCode.Conflict, ErrorCode.SUBMISSION_LIMIT, "already $limit submissions pending")

        fun notEnoughPoints(cost: Int) =
            ApiFailure(HttpStatusCode.Conflict, ErrorCode.NOT_ENOUGH_POINTS, "submitting costs $cost points")

        /** A guest's submission: only a registered player may submit (CLAUDE.md §8d, *Submitting*). */
        fun accountRequired() =
            ApiFailure(HttpStatusCode.Forbidden, ErrorCode.ACCOUNT_REQUIRED, "only a registered player may submit")

        /** A submission by an author a moderator has blocked (CLAUDE.md §8d, *Moderation*). */
        fun submissionsBlocked() =
            ApiFailure(HttpStatusCode.Forbidden, ErrorCode.SUBMISSIONS_BLOCKED, "this author may not submit")

        /** A block or an unblock of an author no player is. */
        fun authorNotFound(id: String) =
            ApiFailure(HttpStatusCode.NotFound, ErrorCode.AUTHOR_NOT_FOUND, "no author $id")

        /**
         * An account a moderator asked to delete that no player has. The message names neither the
         * username nor the id asked for.
         */
        fun playerNotFound() = ApiFailure(HttpStatusCode.NotFound, ErrorCode.PLAYER_NOT_FOUND, "no such account")

        /** A moderator's decision on a question that is no longer, or never was, pending. */
        fun alreadyDecided(id: String) =
            ApiFailure(HttpStatusCode.Conflict, ErrorCode.ALREADY_DECIDED, "question $id is not pending")

        /** A retirement of a question that is not approved, or a restoration of one that is not retired. */
        fun wrongStatus(
            id: String,
            expected: QuestionStatus,
        ) = ApiFailure(HttpStatusCode.Conflict, ErrorCode.WRONG_STATUS, "question $id is not ${expected.name}")

        /** A category added under an id a category has already. */
        fun categoryExists(id: String) =
            ApiFailure(HttpStatusCode.Conflict, ErrorCode.CATEGORY_EXISTS, "a category is $id already")

        fun categoryNotFound(id: String) =
            ApiFailure(HttpStatusCode.NotFound, ErrorCode.CATEGORY_NOT_FOUND, "no category $id")

        /** A registration's username the rules refuse, the player's to put right. */
        fun invalidUsername(message: String) =
            ApiFailure(HttpStatusCode.UnprocessableEntity, ErrorCode.INVALID_USERNAME, message)

        /** A registration's password the rules refuse. The message never holds the password. */
        fun invalidPassword(message: String) =
            ApiFailure(HttpStatusCode.UnprocessableEntity, ErrorCode.INVALID_PASSWORD, message)

        fun usernameTaken() = ApiFailure(HttpStatusCode.Conflict, ErrorCode.USERNAME_TAKEN, "username taken")

        fun alreadyRegistered() =
            ApiFailure(HttpStatusCode.Conflict, ErrorCode.ALREADY_REGISTERED, "player already registered")

        /** A login naming no account, whichever half was wrong: the message never says. */
        fun invalidLogin() =
            ApiFailure(
                HttpStatusCode.Unauthorized,
                ErrorCode.INVALID_LOGIN,
                "no account has that username and password",
            )

        /** A Play Games sign-in's server auth code Google refused. Never [unauthorized]: see the code's doc. */
        fun playGamesCodeRefused() =
            ApiFailure(
                HttpStatusCode.UnprocessableEntity,
                ErrorCode.PLAY_GAMES_CODE_REFUSED,
                "Google refused the Play Games code; ask Play Games for a new one",
            )

        /** Google could not be asked who a Play Games sign-in's code names. */
        fun playGamesUnavailable() =
            ApiFailure(HttpStatusCode.BadGateway, ErrorCode.PLAY_GAMES_UNAVAILABLE, "Play Games could not be asked")

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

        /** A build older than its platform's [minimum] ([ClientVersionCheck]). */
        fun upgradeRequired(
            platform: String,
            version: Int,
            minimum: Int,
        ) = ApiFailure(
            HttpStatusCode.UpgradeRequired,
            ErrorCode.UPGRADE_REQUIRED,
            "$platform build $version is older than $minimum, the oldest this server serves",
        )
    }
}
