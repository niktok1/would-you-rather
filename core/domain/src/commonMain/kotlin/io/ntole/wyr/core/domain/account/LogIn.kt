package io.ntole.wyr.core.domain.account

import io.ntole.wyr.core.domain.analytics.Analytics
import io.ntole.wyr.core.domain.question.QuestionRepository
import io.ntole.wyr.core.domain.session.SessionRepository

/**
 * Logs this device in to an account (see [AccountRepository.logIn]), then drops the question queue,
 * which was filled from the player before's feed, and tells [analytics] who plays here now (CLAUDE.md
 * §8g), by the player id of the session the login stored. A refused login leaves all three as they
 * were.
 *
 * Nothing is checked first: the server refuses a username or password no account can have as it
 * refuses a wrong one, and a login the rules would refuse today could be an account's under the
 * rules of a later server.
 */
public class LogIn(
    private val accounts: AccountRepository,
    private val questions: QuestionRepository,
    private val session: SessionRepository,
    private val analytics: Analytics,
) {
    public suspend operator fun invoke(
        username: String,
        password: String,
    ) {
        accounts.logIn(username, password)
        questions.reset()
        // The session the login stored: nothing is minted, and nothing is sent.
        analytics.identify(session.ensure())
    }
}
