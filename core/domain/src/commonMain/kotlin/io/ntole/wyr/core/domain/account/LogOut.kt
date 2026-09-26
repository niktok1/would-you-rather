package io.ntole.wyr.core.domain.account

import io.ntole.wyr.core.domain.analytics.Analytics
import io.ntole.wyr.core.domain.question.QuestionRepository

/**
 * Logs this device out (see [AccountRepository.logOut]), then drops the question queue, which was
 * filled from the account's feed, and has [analytics] forget the player (CLAUDE.md §8g): the next call
 * plays as a fresh guest, and so does every event after, joined to nobody before.
 */
public class LogOut(
    private val accounts: AccountRepository,
    private val questions: QuestionRepository,
    private val analytics: Analytics,
) {
    public suspend operator fun invoke() {
        accounts.logOut()
        questions.reset()
        analytics.reset()
    }
}
