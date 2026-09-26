package io.ntole.wyr.core.domain.notice

import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.session.CurrentSession
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionRepository
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** The notice that a moderator decided one of the player's questions (CLAUDE.md §8d, *Submitting*). */
class DecisionNoticesTest {
    private val questions = FakeQuestions()
    private val session = FakeSession()
    private val store = FakeStore()
    private val notices = DecisionNotices(questions, session, store)

    /** What was decided before this device ever read the list is not news. */
    @Test
    fun `a first read seeds what is seen and shows no news`() =
        runTest {
            session.player.value = "p1"
            questions.listed = listOf(question("a", SubmissionStatus.APPROVED), question("b", SubmissionStatus.PENDING))

            notices.check()

            assertEquals(emptySet(), notices.unseen.value)
            assertEquals(SeenDecisions("p1", setOf("a")), store.kept)
        }

    @Test
    fun `a decision after the first read is news until the Account screen shows it`() =
        runTest {
            session.player.value = "p1"
            questions.listed = listOf(question("a", SubmissionStatus.PENDING), question("b", SubmissionStatus.PENDING))
            notices.check()

            questions.listed =
                listOf(question("a", SubmissionStatus.APPROVED), question("b", SubmissionStatus.REJECTED))
            notices.check()
            assertEquals(setOf("a", "b"), notices.unseen.value)
            notices.check()
            assertEquals(setOf("a", "b"), notices.unseen.value, "a read alone sees nothing")

            val marked = notices.shown(questions.listed, readFor = "p1")

            assertEquals(setOf("a", "b"), marked, "the rows the screen marks")
            assertEquals(emptySet(), notices.unseen.value)
            assertEquals(SeenDecisions("p1", setOf("a", "b")), store.kept)
            assertEquals(emptySet(), notices.shown(questions.listed, readFor = "p1"), "seen once is seen")
        }

    /** A retired question was approved first, and a restored one is approved again: neither is news. */
    @Test
    fun `a retirement or a restoration is no news`() =
        runTest {
            session.player.value = "p1"
            questions.listed = listOf(question("a", SubmissionStatus.APPROVED))
            notices.check()

            questions.listed = listOf(question("a", SubmissionStatus.RETIRED))
            notices.check()
            assertEquals(emptySet(), notices.unseen.value)
            notices.shown(questions.listed, readFor = "p1")

            questions.listed = listOf(question("a", SubmissionStatus.APPROVED))
            notices.check()
            assertEquals(emptySet(), notices.unseen.value)
        }

    @Test
    fun `a status this build cannot name is no decision`() =
        runTest {
            session.player.value = "p1"
            questions.listed = emptyList()
            notices.check()

            questions.listed = listOf(question("a", SubmissionStatus.OTHER))
            notices.check()

            assertEquals(emptySet(), notices.unseen.value)
        }

    /** Another player here has a list of their own, which a first read of theirs seeds. */
    @Test
    fun `another player's first read seeds their own and drops the news before`() =
        runTest {
            session.player.value = "p1"
            questions.listed = listOf(question("a", SubmissionStatus.PENDING))
            notices.check()
            questions.listed = listOf(question("a", SubmissionStatus.APPROVED))
            notices.check()
            assertEquals(setOf("a"), notices.unseen.value)

            session.player.value = "bob"
            questions.listed = listOf(question("x", SubmissionStatus.APPROVED))
            notices.check()

            assertEquals(emptySet(), notices.unseen.value)
            assertEquals(SeenDecisions("bob", setOf("x")), store.kept)
        }

    @Test
    fun `no session reads nothing and shows no news`() =
        runTest {
            notices.check()

            assertEquals(0, questions.reads)
            assertEquals(emptySet(), notices.unseen.value)
            assertEquals(emptySet(), notices.shown(listOf(question("a", SubmissionStatus.APPROVED)), readFor = "p1"))
            assertEquals(null, store.kept)
        }

    @Test
    fun `a read that fails changes nothing`() =
        runTest {
            session.player.value = "p1"
            questions.listed = listOf(question("a", SubmissionStatus.PENDING))
            notices.check()
            questions.listed = listOf(question("a", SubmissionStatus.APPROVED))
            notices.check()

            questions.failWith = DomainError.NETWORK
            notices.check()

            assertEquals(setOf("a"), notices.unseen.value)
        }

    /** A list read for the player before a login lands after it: it is theirs, not this one's. */
    @Test
    fun `a list read for a player who plays here no more is dropped`() =
        runTest {
            session.player.value = "p1"
            questions.listed = listOf(question("a", SubmissionStatus.PENDING))
            notices.check()
            questions.onRead = { session.player.value = "bob" }

            notices.check()

            assertEquals(SeenDecisions("p1", emptySet()), store.kept, "bob's seen set is not written as p1's")
        }

    /**
     * A returning player on a new phone: the guest minted first is shown their empty list, then the
     * launch's Play Games sign-in makes the device the player, whose first read seeds what they had
     * decided, and the guest's list, still on the Account screen, is shown again as the player plays.
     */
    @Test
    fun `a list read for another player marks nothing seen`() =
        runTest {
            session.player.value = "guest1"
            notices.shown(emptyList(), readFor = "guest1")
            session.player.value = "p1"
            questions.listed =
                listOf(question("a", SubmissionStatus.APPROVED), question("b", SubmissionStatus.REJECTED))
            notices.check()

            assertEquals(emptySet(), notices.shown(emptyList(), readFor = "guest1"))

            assertEquals(SeenDecisions("p1", setOf("a", "b")), store.kept)
            assertEquals(emptySet(), notices.shown(questions.listed, readFor = "p1"), "nothing decided before is news")
        }

    /** A decision stays one, so a list read before the one last seen cannot make it news again. */
    @Test
    fun `a list read before a decision was seen does not unsee it`() =
        runTest {
            session.player.value = "p1"
            questions.listed = listOf(question("a", SubmissionStatus.PENDING), question("b", SubmissionStatus.PENDING))
            notices.check()
            val older = listOf(question("a", SubmissionStatus.APPROVED), question("b", SubmissionStatus.PENDING))
            val newer = listOf(question("a", SubmissionStatus.APPROVED), question("b", SubmissionStatus.APPROVED))
            notices.shown(newer, readFor = "p1")

            notices.shown(older, readFor = "p1")

            assertEquals(SeenDecisions("p1", setOf("a", "b")), store.kept)
            assertEquals(emptySet(), notices.shown(newer, readFor = "p1"))
        }

    private fun question(
        id: String,
        status: SubmissionStatus,
    ) = Submission(
        id = id,
        optionA = "$id-a",
        optionB = "$id-b",
        categories = setOf("FOOD"),
        status = status,
        rejectionReason = null,
        submittedAt = Instant.fromEpochMilliseconds(0),
    )

    private class FakeQuestions : SubmissionRepository {
        var listed = emptyList<Submission>()
        var failWith: DomainError? = null
        var reads = 0
        var onRead: () -> Unit = {}

        override suspend fun submit(
            optionA: String,
            optionB: String,
            categories: Set<String>,
        ): Submission = error("the notice submits nothing")

        override suspend fun mine(): List<Submission> {
            reads++
            onRead()
            failWith?.let { throw WyrException(it) }
            return listed
        }
    }

    private class FakeSession : CurrentSession {
        val player = MutableStateFlow<String?>(null)

        override fun current(): String? = player.value

        override val sessions: Flow<String> = player.filterNotNull()
    }

    private class FakeStore : SeenDecisionsStore {
        var kept: SeenDecisions? = null

        override fun read(): SeenDecisions? = kept

        override suspend fun write(seen: SeenDecisions) {
            kept = seen
        }
    }
}
