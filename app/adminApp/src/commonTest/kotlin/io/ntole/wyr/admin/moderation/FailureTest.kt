package io.ntole.wyr.admin.moderation

import io.ntole.wyr.core.domain.error.DomainError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** What the moderator reads for each way an admin route can say no. */
class FailureTest {
    @Test
    fun `a 403 says the token is wrong`() {
        assertTrue("Wrong admin token (403)" in describe(Failure.Refused(DomainError.FORBIDDEN)))
    }

    @Test
    fun `a bare 404 says moderation is off on that server`() {
        // A server without ADMIN_TOKEN has no admin routes, which the data layer reads as UNKNOWN.
        assertTrue("Moderation is off on this server (404)" in describe(Failure.Refused(DomainError.UNKNOWN)))
    }

    @Test
    fun `a 404 naming the question says there is no such question`() {
        assertEquals(
            "No such question on this server (404).",
            describe(Failure.Refused(DomainError.QUESTION_NOT_FOUND)),
        )
    }

    @Test
    fun `a 409 says another decision came first`() {
        assertTrue("Already decided (409)" in describe(Failure.Refused(DomainError.ALREADY_DECIDED)))
        assertTrue("Its status changed first (409)" in describe(Failure.Refused(DomainError.WRONG_STATUS)))
    }

    @Test
    fun `a 429 says how long to wait when the server named it`() {
        val named = describe(Failure.Refused(DomainError.RATE_LIMITED, retryAfter = 42.seconds))
        val unnamed = describe(Failure.Refused(DomainError.RATE_LIMITED))

        assertTrue("Too many requests (429): try again in 42 s." in named, named)
        assertTrue("Too many requests (429): try again shortly." in unnamed, unnamed)
    }

    @Test
    fun `no answer at all says so`() {
        assertTrue("No answer from the server" in describe(Failure.Refused(DomainError.NETWORK)))
    }

    @Test
    fun `every domain error has words of its own and none of them is blank`() {
        DomainError.entries.forEach { error ->
            assertTrue(describe(Failure.Refused(error)).isNotBlank(), "$error")
        }
    }

    @Test
    fun `a bug names what was thrown`() {
        assertEquals(
            "A bug in this app: IllegalStateException: boom",
            describe(Failure.Bug("IllegalStateException", "boom")),
        )
        assertEquals("A bug in this app: IllegalStateException", describe(Failure.Bug("IllegalStateException", null)))
    }
}
