package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.network.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ErrorMapperTest {
    @Test
    fun `the server's own code decides the domain error`() =
        runTest {
            val table =
                listOf(
                    ApiException(ErrorCode.QUESTION_NOT_FOUND, status = 404) to DomainError.QUESTION_NOT_FOUND,
                    ApiException(ErrorCode.ALREADY_VOTED, status = 409) to DomainError.ALREADY_VOTED,
                    ApiException(ErrorCode.VALIDATION_FAILED, status = 400) to DomainError.SERVER,
                    ApiException(ErrorCode.INVALID_SUBMISSION, status = 422) to DomainError.INVALID_SUBMISSION,
                    ApiException(ErrorCode.SUBMISSION_LIMIT, status = 409) to DomainError.SUBMISSION_LIMIT,
                    ApiException(ErrorCode.ALREADY_DECIDED, status = 409) to DomainError.ALREADY_DECIDED,
                    ApiException(ErrorCode.WRONG_STATUS, status = 409) to DomainError.WRONG_STATUS,
                    ApiException(ErrorCode.FORBIDDEN, status = 403) to DomainError.FORBIDDEN,
                    ApiException(ErrorCode.UNAUTHORIZED, status = 401) to DomainError.UNAUTHORIZED,
                    ApiException(ErrorCode.INVALID_REFRESH_TOKEN, status = 401) to DomainError.UNAUTHORIZED,
                    // No longer sent, and a 401 too: still never UNAUTHORIZED, which would drop the session.
                    ApiException(ErrorCode.INVALID_RECOVERY_SECRET, status = 401) to DomainError.UNKNOWN,
                    ApiException(ErrorCode.RATE_LIMITED, status = 429) to DomainError.RATE_LIMITED,
                    ApiException(ErrorCode.INVALID_USERNAME, status = 422) to DomainError.INVALID_USERNAME,
                    ApiException(ErrorCode.INVALID_PASSWORD, status = 422) to DomainError.INVALID_PASSWORD,
                    ApiException(ErrorCode.USERNAME_TAKEN, status = 409) to DomainError.USERNAME_TAKEN,
                    ApiException(ErrorCode.ALREADY_REGISTERED, status = 409) to DomainError.ALREADY_REGISTERED,
                    // A 401, and still never UNAUTHORIZED, which would drop the session.
                    ApiException(ErrorCode.INVALID_LOGIN, status = 401) to DomainError.INVALID_LOGIN,
                    ApiException(ErrorCode.INTERNAL, status = 500) to DomainError.SERVER,
                    // The code wins over the status whenever there is one.
                    ApiException(ErrorCode.ALREADY_VOTED, status = 503) to DomainError.ALREADY_VOTED,
                )

            table.forEach { (failure, expected) -> assertMapsTo(expected, failure) }
        }

    @Test
    fun `without a code a server-error status still reads as SERVER`() =
        runTest {
            // What a proxy's HTML 502/503 page, or any body that is not an ErrorDto, arrives as.
            listOf(500, 502, 503, 599).forEach { status ->
                assertMapsTo(DomainError.SERVER, ApiException(ErrorCode.UNKNOWN, status = status))
            }
        }

    @Test
    fun `without a code a 429 still reads as RATE_LIMITED`() =
        runTest {
            assertMapsTo(DomainError.RATE_LIMITED, ApiException(ErrorCode.UNKNOWN, status = 429))
        }

    @Test
    fun `a bare 401 is not taken as proof the session is dead`() =
        runTest {
            // UNAUTHORIZED would reset the session and orphan the guest account (CLAUDE.md §8a).
            assertMapsTo(DomainError.UNKNOWN, ApiException(ErrorCode.UNKNOWN, status = 401))
        }

    @Test
    fun `without a code any other status stays UNKNOWN`() =
        runTest {
            listOf(400, 403, 404, 409, 499, 600).forEach { status ->
                assertMapsTo(DomainError.UNKNOWN, ApiException(ErrorCode.UNKNOWN, status = status))
            }
        }

    @Test
    fun `a failure with no response at all is NETWORK`() =
        runTest {
            val failure = assertFailsWith<WyrException> { runApi { throw IllegalStateException("connection reset") } }

            assertEquals(DomainError.NETWORK, failure.error)
        }

    @Test
    fun `the browser engine's failed fetch is NETWORK`() =
        runTest {
            // Ktor's js and wasmJs engines reject a failed fetch with exactly this: an Error, not
            // an Exception.
            val failure = assertFailsWith<WyrException> { runApi { throw Error("Fail to fetch") } }

            assertEquals(DomainError.NETWORK, failure.error)
        }

    @Test
    fun `a fault in the program is not dressed up as NETWORK`() =
        runTest {
            val bug = NotImplementedError()

            val thrown = assertFailsWith<NotImplementedError> { runApi { throw bug } }

            assertSame(bug, thrown)
        }

    @Test
    fun `cancellation is rethrown and never mapped`() =
        runTest {
            val cancellation = CancellationException("caller went away")

            val thrown = assertFailsWith<CancellationException> { runApi { throw cancellation } }

            assertSame(cancellation, thrown)
        }

    private suspend fun assertMapsTo(
        expected: DomainError,
        failure: ApiException,
    ) {
        val mapped = assertFailsWith<WyrException> { runApi { throw failure } }
        assertEquals(expected, mapped.error, "${failure.code} with status ${failure.status}")
        assertSame(failure, mapped.cause)
    }
}
