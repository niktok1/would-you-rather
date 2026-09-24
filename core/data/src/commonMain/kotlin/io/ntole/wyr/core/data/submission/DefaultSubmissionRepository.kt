package io.ntole.wyr.core.data.submission

import io.ntole.wyr.core.data.mapper.submitQuestionRequest
import io.ntole.wyr.core.data.mapper.toDomain
import io.ntole.wyr.core.data.session.DefaultSessionRepository
import io.ntole.wyr.core.data.session.withSessionRecovery
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionRepository
import io.ntole.wyr.core.network.api.SubmissionApi

/**
 * Submits and lists the session player's own questions, recovering once from a session the server
 * has stopped accepting (see [withSessionRecovery]). A recovered session is a fresh guest, so what
 * comes back is that guest's: the question is submitted as theirs, and the list is theirs.
 *
 * The retry after a recovered session sends the same submission again, as the new guest. That is the
 * one resend, and it is safe: a 401 stored nothing (CLAUDE.md §8b, *Retrying a submission*). Nothing
 * here sends a submission again after any other failure, since one whose answer was lost may have been
 * stored, and a second would be stored beside it.
 */
public class DefaultSubmissionRepository(
    private val api: SubmissionApi,
    private val session: DefaultSessionRepository,
) : SubmissionRepository {
    override suspend fun submit(
        optionA: String,
        optionB: String,
        categories: Set<Category>,
    ): Submission {
        // Built before anything is sent, so a selection the server would refuse as malformed never
        // leaves the client.
        val request = submitQuestionRequest(optionA, optionB, categories)

        return session.withSessionRecovery { api.submit(request) }.toDomain()
    }

    override suspend fun mine(): List<Submission> =
        session.withSessionRecovery { api.mine() }.submissions.map { it.toDomain() }
}
