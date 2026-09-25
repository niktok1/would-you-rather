package io.ntole.wyr.core.domain.account

import io.ntole.wyr.core.domain.question.QuestionRepository

/**
 * Logs this device in to an account (see [AccountRepository.logIn]), then drops the question queue,
 * which was filled from the player before's feed. A refused login leaves both as they were.
 *
 * Nothing is checked first: the server refuses a username or password no account can have as it
 * refuses a wrong one, and a login the rules would refuse today could be an account's under the
 * rules of a later server.
 */
public class LogIn(
    private val accounts: AccountRepository,
    private val questions: QuestionRepository,
) {
    public suspend operator fun invoke(
        username: String,
        password: String,
    ) {
        accounts.logIn(username, password)
        questions.reset()
    }
}
