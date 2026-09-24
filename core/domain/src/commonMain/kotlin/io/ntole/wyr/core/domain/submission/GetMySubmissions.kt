package io.ntole.wyr.core.domain.submission

import io.ntole.wyr.core.domain.session.SessionRepository

/**
 * Lists the player's own submissions, guaranteeing a session exists first.
 *
 * The list is the session player's, so reading it needs a session just as the stats do. Ensuring it
 * here, as `GetPlayerStats` does, sends the first read with a bearer instead of having it refused and
 * retried.
 */
public class GetMySubmissions(
    private val submissions: SubmissionRepository,
    private val session: SessionRepository,
) {
    public suspend operator fun invoke(): List<Submission> {
        session.ensure()
        return submissions.mine()
    }
}
