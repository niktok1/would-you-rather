package io.ntole.wyr.core.domain.account

import io.ntole.wyr.core.domain.question.QuestionRepository

/**
 * Logs this device out (see [AccountRepository.logOut]), then drops the question queue, which was
 * filled from the account's feed. The next call plays as a fresh guest.
 */
public class LogOut(
    private val accounts: AccountRepository,
    private val questions: QuestionRepository,
) {
    public suspend operator fun invoke() {
        accounts.logOut()
        questions.reset()
    }
}
