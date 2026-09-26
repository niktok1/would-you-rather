package io.ntole.wyr.core.domain.account

import io.ntole.wyr.core.domain.analytics.Analytics
import io.ntole.wyr.core.domain.analytics.AnalyticsEvent
import io.ntole.wyr.core.domain.question.QuestionRepository

/**
 * Deletes this device's account (see [AccountRepository.deleteAccount]), then drops the question
 * queue, which was filled from the account's feed, and has [analytics] forget the player (CLAUDE.md
 * §8g), once they have heard it went: the next call plays as a fresh guest, joined to nobody before. A
 * deletion that failed leaves all three as they were.
 */
public class DeleteAccount(
    private val accounts: AccountRepository,
    private val questions: QuestionRepository,
    private val analytics: Analytics,
) {
    public suspend operator fun invoke() {
        accounts.deleteAccount()
        questions.reset()
        // The account's last event, before the analytics forget whose it was.
        analytics.track(AnalyticsEvent.ACCOUNT_DELETED)
        analytics.reset()
    }
}
