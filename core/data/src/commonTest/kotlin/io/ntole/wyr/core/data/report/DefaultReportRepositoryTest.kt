package io.ntole.wyr.core.data.report

import io.ktor.http.HttpStatusCode
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.data.BASE_URL
import io.ntole.wyr.core.data.PlayServer
import io.ntole.wyr.core.data.session
import io.ntole.wyr.core.data.session.DefaultSessionRepository
import io.ntole.wyr.core.data.storeHolding
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.report.ReportQuestion
import io.ntole.wyr.core.domain.report.ReportReason
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.network.SessionStore
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.WyrJson
import io.ntole.wyr.core.network.api.AuthApi
import io.ntole.wyr.core.network.api.ReportApi
import io.ntole.wyr.core.report.HideAuthorRequest
import io.ntole.wyr.core.report.HideQuestionRequest
import io.ntole.wyr.core.report.ReportRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import io.ntole.wyr.core.report.ReportReason as WireReportReason

/** Reporting and hiding through the real client, session recovery included (CLAUDE.md §8d, *Reports*). */
class DefaultReportRepositoryTest {
    private val server = PlayServer()

    @Test
    fun `a first launch's report goes out once with the session just minted`() =
        runTest {
            val store = storeHolding(null)
            val client = WyrHttpClient.create(BASE_URL, store, server.engine)
            val sessions = DefaultSessionRepository(AuthApi(client), store)
            val report = ReportQuestion(DefaultReportRepository(ReportApi(client), sessions), sessions)

            report("q1", ReportReason.NOT_A_CHOICE)

            assertEquals(
                listOf(sentAs("guest1", WyrApi.Paths.REPORTS, report("q1", WireReportReason.NOT_A_CHOICE))),
                server.sent,
            )
        }

    @Test
    fun `each goes to its own route with the question it names`() =
        runTest {
            val reports = repositoryOver(storeHolding(null).also { mintInto(it) })

            reports.report("q1", ReportReason.OFFENSIVE)
            reports.hideQuestion("q2")
            reports.hideAuthor("q3")

            assertEquals(
                listOf(
                    sentAs("guest1", WyrApi.Paths.REPORTS, report("q1", WireReportReason.OFFENSIVE)),
                    sentAs("guest1", WyrApi.Paths.HIDDEN_QUESTIONS, WyrJson.encodeToString(HideQuestionRequest("q2"))),
                    sentAs("guest1", WyrApi.Paths.HIDDEN_AUTHORS, WyrJson.encodeToString(HideAuthorRequest("q3"))),
                ),
                server.sent,
            )
        }

    /** Every reason the menu offers is one the wire names, and never the wire's unknown. */
    @Test
    fun `every reason goes as the wire's reason of the same name`() =
        runTest {
            val reports = repositoryOver(storeHolding(null).also { mintInto(it) })

            ReportReason.entries.forEach { reports.report("q1", it) }

            val reasons = server.sent.map { WyrJson.decodeFromString<ReportRequest>(it.third).reason.name }
            assertEquals(ReportReason.entries.map { it.name }, reasons)
        }

    @Test
    fun `a hide on a dead session is sent again as a fresh guest`() =
        runTest {
            // The server has never heard of "a": the state after a dev server restarts.
            val store = storeHolding(session("a"))

            repositoryOver(store).hideAuthor("q1")

            val body = WyrJson.encodeToString(HideAuthorRequest("q1"))
            assertEquals(
                listOf(
                    sentAs("a", WyrApi.Paths.HIDDEN_AUTHORS, body),
                    sentAs("guest1", WyrApi.Paths.HIDDEN_AUTHORS, body),
                ),
                server.sent,
            )
            assertEquals("guest1", store.read()?.playerId)
        }

    @Test
    fun `a report on a question no player is served is QUESTION_NOT_FOUND and leaves the session alone`() =
        runTest {
            server.refuseWith = HttpStatusCode.NotFound to ErrorCode.QUESTION_NOT_FOUND
            val store = storeHolding(session("a"))

            val failure =
                assertFailsWith<WyrException> { repositoryOver(store).report("gone", ReportReason.SPAM) }

            assertEquals(DomainError.QUESTION_NOT_FOUND, failure.error)
            assertEquals(1, server.sent.size, "nothing resends a refused report")
            assertEquals(0, server.guestsMinted)
            assertEquals(session("a"), store.read())
        }

    private suspend fun mintInto(store: SessionStore) {
        val client = WyrHttpClient.create(BASE_URL, store, server.engine)
        DefaultSessionRepository(AuthApi(client), store).ensure()
    }

    private fun report(
        questionId: String,
        reason: WireReportReason,
    ): String = WyrJson.encodeToString(ReportRequest(questionId, reason))

    private fun sentAs(
        player: String,
        path: String,
        body: String,
    ): Triple<String, String?, String> = Triple(path, "Bearer access-$player", body)

    private fun repositoryOver(store: SessionStore): DefaultReportRepository {
        val client = WyrHttpClient.create(BASE_URL, store, server.engine)
        return DefaultReportRepository(ReportApi(client), DefaultSessionRepository(AuthApi(client), store))
    }
}
