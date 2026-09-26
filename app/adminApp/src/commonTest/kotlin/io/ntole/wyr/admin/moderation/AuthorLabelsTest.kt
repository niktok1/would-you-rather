package io.ntole.wyr.admin.moderation

import io.ntole.wyr.core.domain.moderation.AuthorBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How the app names a question's author, and what blocking one does. */
class AuthorLabelsTest {
    @Test
    fun `an author is named by the start of their id and where they stand once the server said`() {
        assertEquals("3f2a9c1e", shortAuthorOf(AUTHOR))
        assertEquals("Author 3f2a9c1e", authorLabelOf(AUTHOR, isSeed = false, blocked = null))
        assertEquals("Author 3f2a9c1e · blocked", authorLabelOf(AUTHOR, isSeed = false, blocked = true))
        assertEquals("Author 3f2a9c1e · not blocked", authorLabelOf(AUTHOR, isSeed = false, blocked = false))
    }

    @Test
    fun `a question with no author is a seed or one whose author is gone`() {
        assertEquals("Seed", authorLabelOf(null, isSeed = true, blocked = null))
        assertEquals("No author known", authorLabelOf(null, isSeed = false, blocked = null))
    }

    @Test
    fun `a block says how many pending questions it rejected`() {
        val said = (0..2).map { rejected -> blockedNoticeOf(AuthorBlock(AUTHOR, isBlocked = true, rejected)) }

        assertEquals(
            listOf(
                "Blocked author 3f2a9c1e: they can submit nothing until unblocked; nothing of theirs was pending.",
                "Blocked author 3f2a9c1e: they can submit nothing until unblocked; 1 pending question of theirs rejected.",
                "Blocked author 3f2a9c1e: they can submit nothing until unblocked; 2 pending questions of theirs rejected.",
            ),
            said,
        )
    }

    @Test
    fun `the block's warning says what it does before it is confirmed`() {
        val warning = blockWarningOf(AUTHOR)

        assertTrue("Author 3f2a9c1e can submit no more questions until unblocked" in warning, warning)
        assertTrue("still pending is rejected with the reason below" in warning, warning)
        assertTrue("approved questions stay served" in warning, warning)
    }

    private companion object {
        const val AUTHOR = "3f2a9c1e-5d4b-4c1a-9e8f-7a6b5c4d3e2f"
    }
}
