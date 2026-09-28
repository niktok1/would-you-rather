package io.ntole.wyr.share

import io.ntole.wyr.core.domain.analytics.Analytics
import io.ntole.wyr.core.domain.analytics.AnalyticsEvent
import io.ntole.wyr.core.domain.analytics.AnalyticsProperty
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.Tally

/**
 * A question as its shared image shows it (CLAUDE.md §8d, *Sharing*): its two options, in the language
 * shown, and, when the player may share the results, the crowd's split, [tally], and the player's own
 * [pick], if they answered it. No [tally], and the image shows the question alone.
 */
data class SharedQuestion(
    val id: String,
    val categories: Set<String>,
    val optionA: String,
    val optionB: String,
    val tally: Tally? = null,
    val pick: Side? = null,
) {
    /** Whether the image may show the results: a question answered, or one of the player's own served. */
    val hasResults: Boolean get() = tally != null
}

/**
 * [question] was shared, [withResults] or not, as [outcome] says (CLAUDE.md §8g): its id and its
 * categories, never its text. A share that failed sends nothing.
 */
fun Analytics.questionShared(
    question: SharedQuestion,
    withResults: Boolean,
    outcome: ShareOutcome,
) {
    if (outcome == ShareOutcome.FAILED) return
    track(
        AnalyticsEvent.QUESTION_SHARED,
        mapOf(
            AnalyticsProperty.QUESTION_ID to question.id,
            AnalyticsProperty.CATEGORIES to question.categories.toList(),
            AnalyticsProperty.WITH_RESULTS to withResults,
            AnalyticsProperty.OUTCOME to outcome.name.lowercase(),
        ),
    )
}
